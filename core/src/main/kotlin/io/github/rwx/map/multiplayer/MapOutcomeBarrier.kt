package io.github.rwx.map.multiplayer

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class MapWorldPresence(
    val world: MapWorldId, val revision: Long, val tick: Int,
    val active: Map<Int, Int>, val technical: Map<Int, Int>, val unresolved: List<String> = emptyList(),
    val fence: String? = null, val surrendered: List<Int> = emptyList()
)

@Serializable
data class MapGlobalOutcome(
    val candidate: String, val defeated: List<Int>, val winners: List<Int>,
    val technicalDefeated: List<Int> = emptyList(), val revisions: Map<String, Long> = emptyMap()
)

/** A first report proposes only; a stable cut from every paused world authorizes the outcome. */
class MapOutcomeBarrier(private val spec: MapSessionSpec) {
    private val reports = linkedMapOf<MapWorldId, MapWorldPresence>()
    private val confirmations = linkedMapOf<MapWorldId, MapWorldPresence>()
    private var fence: String? = null
    private var invalid = false
    private var lastOutcome: MapGlobalOutcome? = null
    private fun authorized(peer: String, report: MapWorldPresence): Boolean =
        report.world.sessionId == spec.sessionId &&
                spec.worlds.any { it.mapId == report.world.mapId && it.generation == report.world.generation && it.ownerPeerId == peer } &&
                report.active.all { it.key in spec.players.map { p -> p.teamId } && it.value >= 0 } &&
                report.technical.all { it.key in spec.players.map { p -> p.teamId } && it.value >= 0 } &&
                report.surrendered.all { it in spec.players.map { p -> p.teamId } }

    fun report(peer: String, report: MapWorldPresence) {
        if (!authorized(peer, report) || report.revision < (reports[report.world]?.revision ?: -1)) return
        reports[report.world] = report
    }

    fun candidate(): String? {
        if (fence != null) return fence
        if (reports.size != spec.worlds.size || reports.values.any { it.unresolved.isNotEmpty() }) return null
        val proposed = calculate("", reports.values)
        if (proposed.defeated.isEmpty() && proposed.technicalDefeated.isEmpty()) return null
        if (lastOutcome?.let { it.defeated == proposed.defeated && it.technicalDefeated == proposed.technicalDefeated } == true) return null
        return UUID.randomUUID().toString().also { fence = it; invalid = false; confirmations.clear() }
    }

    fun confirm(peer: String, report: MapWorldPresence): MapGlobalOutcome? {
        if (!authorized(peer, report) || report.fence != fence || fence == null) return null
        if (report.revision < (reports[report.world]?.revision ?: -1) || report.unresolved.isNotEmpty()) invalid = true
        confirmations[report.world] = report
        if (invalid || confirmations.size != spec.worlds.size) return null
        return calculate(fence!!, confirmations.values).also { lastOutcome = it }
    }

    fun complete(): Boolean = fence != null && confirmations.size == spec.worlds.size
    fun release() {
        fence = null; confirmations.clear(); invalid = false
    }

    private fun calculate(candidate: String, values: Collection<MapWorldPresence>): MapGlobalOutcome {
        val participants = spec.players.filterNot { it.spectator }
        val surrendered = values.flatMap { it.surrendered }.toSet()
        fun count(team: Int, technical: Boolean) =
            values.sumOf { (if (technical) it.technical else it.active)[team] ?: 0 }

        val defeated = participants.filter { player ->
            player.teamId in surrendered ||
                    count(player.teamId, false) == 0 && (!spec.rules.sharedControl || participants.none {
                it.allyId == player.allyId && it.teamId !in surrendered && count(it.teamId, false) > 0
            })
        }.map { it.teamId }
        val technical = participants.filter { it.teamId in defeated || count(it.teamId, true) == 0 }.map { it.teamId }
        val surviving = participants.filter { it.teamId !in defeated && it.teamId !in technical }
        val winners = if (surviving.isNotEmpty() && surviving.map { it.allyId }.distinct().size == 1)
            participants.filter { it.allyId == surviving.first().allyId && it.teamId !in defeated }
                .map { it.teamId } else emptyList()
        return MapGlobalOutcome(
            candidate,
            defeated,
            winners,
            technical,
            values.associate { it.world.mapId to it.revision })
    }
}
