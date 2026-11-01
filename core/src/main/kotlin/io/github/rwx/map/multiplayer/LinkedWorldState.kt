package io.github.rwx.map.multiplayer

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
enum class LinkedWorldAction { PREPARE_SOURCE, ACCEPT_TARGET, RESTORE_SOURCE, REMOVE_SOURCE, ACTIVATE_TARGET, SOURCE_DONE, FENCE_WORLD, RELEASE_WORLD, OUTCOME }

@Serializable
data class LinkedWorldOperation(
    val id: String, val action: LinkedWorldAction, val world: MapWorldId,
    val transactionId: String = "", val target: MapWorldId? = null, val portal: String? = null,
    val sourceIds: List<Long> = emptyList(), val x: Float = 0f, val y: Float = 0f,
    val envelope: PortalTransferEnvelope? = null, val outcome: MapGlobalOutcome? = null,
) {
    fun applyTo(state: LinkedWorldState) = state.apply(this)
    fun encode(): ByteArray =
        json.encodeToString(this).toByteArray().also { require(it.size <= MapWorkerWire.MAX_FRAME_BYTES) }

    companion object {
        private val json = Json { encodeDefaults = true }

        @JvmStatic
        fun decode(bytes: ByteArray): LinkedWorldOperation {
            require(bytes.size in 1..MapWorkerWire.MAX_FRAME_BYTES)
            return json.decodeFromString<LinkedWorldOperation>(bytes.toString(Charsets.UTF_8)).also {
                require(it.id.length in 1..256 && it.transactionId.length <= 128 && it.sourceIds.size <= UnitTransferCodec.MAX_UNITS)
                require(it.x.isFinite() && it.y.isFinite()); it.envelope?.validate()
            }
        }
    }
}

@Serializable
data class PortalArrival(val portal: String, val ids: List<Long>, val rootIds: List<Long>)

@Serializable
private data class LinkedWorldSnapshot(
    val version: Int = 1,
    val world: MapWorldId,
    val players: List<MapSessionPlayer>,
    val applied: Map<String, String>,
    val pending: Map<String, List<Long>>,
    val pendingTimes: Map<String, Int> = emptyMap(),
    val unresolved: Set<String>,
    val arrivals: Map<String, PortalArrival>,
    val revision: Long,
    val deferred: List<String> = emptyList(),
    val outcome: MapGlobalOutcome? = null,
    val fence: String? = null,
    val surrendered: Set<Int> = emptySet()
)

/** Deterministic state owned by a map simulator, saved before unit shells and rebound after loading. */
class LinkedWorldState(val world: MapWorldId, val players: List<MapSessionPlayer>) {
    val surrendered = linkedSetOf<Int>()
    fun surrender(teamId: Int) {
        if (surrendered.add(teamId)) revision++
    }

    val pending = linkedMapOf<String, List<Long>>()
    private val pendingTimes = linkedMapOf<String, Int>()
    val unresolved = linkedSetOf<String>()
    val arrivals = linkedMapOf<String, PortalArrival>()
    private val deferred = arrayListOf<String>()
    private val applied = linkedMapOf<String, String>()
    var revision = 0L; private set
    var outcome: MapGlobalOutcome? = null; private set
    var authority: PortalTransferWorker? = null
    var outcomeFence: String? = null; private set
    fun isOutcomeFenced() = outcomeFence != null
    private val codec get() = UnitTransferCodec(players.map { it.teamId }.toSet())

    fun deferCommand(command: com.corrodinggames.rts.gameFramework.Command) {
        check(deferred.size < 128) { "Outcome fence command capacity exceeded" }
        val output = com.corrodinggames.rts.gameFramework.network.GameOutputStream()
        command.serializeCommand(output)
        val bytes = output.toByteArray(); output.close()
        require(bytes.size <= 64 * 1024)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        require(deferred.sumOf { it.length } + encoded.length <= 4 * 1024 * 1024)
        deferred += encoded
    }

    private fun releaseCommands() {
        val commands = deferred.toList(); deferred.clear()
        commands.forEach { encoded ->
            val input =
                com.corrodinggames.rts.gameFramework.network.GameInputStream(Base64.getDecoder().decode(encoded))
            GameEngine.getInstance().commandController.createCommand()
                .apply { deserializeCommand(input); executeCommand() }
        }
    }

    fun apply(op: LinkedWorldOperation) {
        require(op.world == world)
        val digest = transferDigest(op.encode())
        applied[op.id]?.let { require(it == digest) { "World operation ID reused" }; return }
        require(applied.size < 16384) { "Linked world operation capacity exceeded" }
        when (op.action) {
            LinkedWorldAction.PREPARE_SOURCE -> {
                require(op.transactionId !in pending && op.target != null)
                val captured = try {
                    codec.capture(units(op.sourceIds), op.x, op.y).also { group ->
                        require(group.sourceIds.none(::isArrivalProtected)) { "Group has not left its arrival portal" }
                    }
                } catch (failure: Exception) {
                    if (failure !is IllegalArgumentException && failure !is java.io.IOException) throw failure
                    authority?.captureRejected(op, failure.message ?: "Unit state cannot be transferred")
                    applied[op.id] = digest; revision++; return
                }
                val envelope = PortalTransferEnvelope(
                    op.transactionId, world, op.target, op.portal,
                    Base64.getEncoder().encodeToString(captured.bytes)
                )
                // A durable preparation must precede the first visible frozen state.
                authority?.prepared(op, envelope, captured.sourceIds)
                units(captured.sourceIds).forEach { it.freezeForTransfer(op.transactionId) }
                pending[op.transactionId] = captured.sourceIds
                pendingTimes[op.transactionId] = GameEngine.getInstance().gameTimeMillis
                unresolved += op.transactionId
            }

            LinkedWorldAction.ACCEPT_TARGET -> {
                val envelope = requireNotNull(op.envelope)
                require(envelope.target == world && op.portal != null)
                codec.stage(
                    Base64.getDecoder().decode(envelope.payload),
                    op.x,
                    op.y,
                    GameEngine.getInstance().gameTimeMillis
                )
                authority?.accepted(op, envelope)
                unresolved += op.transactionId
            }

            LinkedWorldAction.RESTORE_SOURCE -> {
                pending.remove(op.transactionId)?.let { ids ->
                    val preparedAt = requireNotNull(pendingTimes.remove(op.transactionId))
                    val waited = Math.subtractExact(GameEngine.getInstance().gameTimeMillis, preparedAt)
                    units(ids).forEach { unit ->
                        unit.rebaseTransferClock(waited)
                        unit.restoreAfterTransferRejection(op.transactionId)
                    }
                }
                unresolved -= op.transactionId
            }

            LinkedWorldAction.REMOVE_SOURCE -> {
                val ids = requireNotNull(pending.remove(op.transactionId)) { "Source has no prepared group" }
                pendingTimes.remove(op.transactionId)
                units(ids).forEach { it.discardForTransfer() }
                authority?.sourceRemoved(op)
            }

            LinkedWorldAction.ACTIVATE_TARGET -> {
                val envelope = requireNotNull(op.envelope)
                require(envelope.target == world && op.portal != null && op.transactionId in unresolved)
                val staged = codec.stage(
                    Base64.getDecoder().decode(envelope.payload),
                    op.x,
                    op.y,
                    GameEngine.getInstance().gameTimeMillis
                )
                val ids = staged.activate()
                val roots =
                    staged.units.filter { it.transportContainer == null && it.parentEntity == null }.map { it.objectId }
                arrivals[op.transactionId] = PortalArrival(op.portal, ids.values.toList(), roots)
                unresolved -= op.transactionId
                authority?.targetActivated(op, ids)
            }

            LinkedWorldAction.SOURCE_DONE -> {
                unresolved -= op.transactionId
            }

            LinkedWorldAction.FENCE_WORLD -> {
                check(outcomeFence == null || outcomeFence == op.transactionId); outcomeFence = op.transactionId
            }

            LinkedWorldAction.RELEASE_WORLD -> {
                if (outcomeFence == op.transactionId) {
                    outcomeFence = null; releaseCommands()
                }
            }

            LinkedWorldAction.OUTCOME -> {
                check(outcomeFence == op.outcome?.candidate) { "Outcome does not match world fence" }
                outcomeFence = null
                check(unresolved.isEmpty()) { "Cannot finalize with pending transfers" }
                outcome = requireNotNull(op.outcome)
                surrendered.addAll(outcome!!.defeated)
                outcome!!.defeated.forEach { PlayerTeam.k(it)?.apply { isTeamWipedOut = true; resetFogOfWar() } }
                BaseUnit.bE.filter { it.team?.teamId in outcome!!.defeated && !it.u() }.forEach { it.markForDeath() }
                outcome!!.technicalDefeated.forEach { PlayerTeam.k(it)?.apply { isTeamDefeatedTech = true } }
                outcome!!.winners.forEach { PlayerTeam.k(it)?.apply { isTeamAlliedVictory = true } }
                val engine = GameEngine.getInstance()
                releaseCommands()
                if (engine.playerTeam?.isSpectatorTeamColor == false) {
                    if (engine.playerTeam.teamId in outcome!!.winners) engine.gameUI.startGameEndSequence()
                    else if (engine.playerTeam.teamId in outcome!!.defeated) engine.gameUI.endGameSequence()
                }
            }
        }
        applied[op.id] = digest; revision++
    }

    fun updateArrivals(contains: (String, Float, Float) -> Boolean) {
        val released = arrivals.filterValues { arrival ->
            arrival.ids.all { id ->
                val member = GameObject.a(id, true)
                if (member == null || member.isDead || member.isDestroyed) true
                else {
                    // Cargo can outlive or change carriers. Its current physical root determines exit.
                    var root = member
                    val visited = hashSetOf<BaseUnit>()
                    while (visited.add(root)) {
                        val parent = root.parentEntity ?: root.transportContainer
                        if (parent == null || parent.isDead || parent.isDestroyed) break
                        root = parent
                    }
                    !contains(arrival.portal, root.posX, root.posY)
                }
            }
        }.keys
        if (released.isNotEmpty()) {
            released.forEach(arrivals::remove); revision++
        }
    }

    fun isArrivalProtected(id: Long) = arrivals.values.any { id in it.ids }
    fun reconcileUnits() {
        pending.forEach { (transaction, ids) -> units(ids).forEach { it.freezeForTransfer(transaction) } }
    }

    fun snapshot(): ByteArray = Json.encodeToString(
        LinkedWorldSnapshot(
            world = world,
            players = players,
            applied = applied,
            pending = pending,
            pendingTimes = pendingTimes,
            unresolved = unresolved,
            arrivals = arrivals,
            revision = revision,
            deferred = deferred,
            outcome = outcome,
            fence = outcomeFence,
            surrendered = surrendered
        )
    ).toByteArray()

    companion object {
        @JvmStatic
        fun restore(bytes: ByteArray): LinkedWorldState {
            require(bytes.size <= 8 * 1024 * 1024)
            val state = Json.decodeFromString<LinkedWorldSnapshot>(bytes.toString(Charsets.UTF_8))
            require(state.version == 1 && state.applied.size <= 16384 && state.pending.size <= 4096 && state.arrivals.size <= 4096)
            return LinkedWorldState(state.world, state.players).also {
                it.applied.putAll(state.applied); it.pending.putAll(state.pending); it.pendingTimes.putAll(state.pendingTimes); it.unresolved.addAll(
                state.unresolved
            )
                it.deferred.addAll(state.deferred); it.outcomeFence =
                state.fence; it.surrendered.addAll(state.surrendered); it.arrivals.putAll(state.arrivals); it.revision =
                state.revision; it.outcome = state.outcome
            }
        }

        private fun units(ids: List<Long>): List<BaseUnit> {
            require(ids.isNotEmpty() && ids.distinct().size == ids.size)
            return ids.map { requireNotNull(GameObject.a(it, true)) { "Missing transfer member: $it" } }
        }
    }
}
