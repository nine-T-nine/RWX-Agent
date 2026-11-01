package io.github.rwx.map.multiplayer

import java.util.Collections

/** Room authority. Viewer selection never changes the immutable world ownership assignment. */
class MapSessionCoordinator(spec: MapSessionSpec) : AutoCloseable {
    val spec: MapSessionSpec = spec.copy(
        worlds = Collections.unmodifiableList(ArrayList(spec.worlds)),
        players = Collections.unmodifiableList(ArrayList(spec.players)),
    )
    private val worlds: Map<String, MapSessionWorld>
    private val players: Map<String, MapSessionPlayer>
    private val readyMaps = mutableSetOf<String>()
    private val readyPeers = mutableSetOf<String>()
    private var closed = false

    init {
        val spec = this.spec
        require(spec.protocol == 1) { "Unsupported map session protocol" }
        require(validId(spec.sessionId) && validId(spec.hostPeerId)) { "Missing session identity" }
        require(spec.worlds.isNotEmpty() && spec.worlds.size <= 100) { "Invalid map count" }
        require(spec.players.isNotEmpty() && spec.players.size <= 100) { "Invalid player count" }
        require(spec.players.all { validId(it.playerId) && it.teamId in 0..99 && it.name.isNotBlank() }) { "Invalid player" }
        require(spec.players.map { it.playerId }.distinct().size == spec.players.size) { "Duplicate player identity" }
        require(spec.players.map { it.teamId }.distinct().size == spec.players.size) { "Duplicate team slot" }
        require(spec.players.all { if (it.ai) it.peerId == null else it.peerId?.let(::validId) == true }) { "Invalid peer binding" }
        val humanPlayers = spec.players.filterNot { it.ai }
        require(humanPlayers.map { it.peerId }.distinct().size == humanPlayers.size) { "Duplicate peer identity" }
        players = humanPlayers.associateBy { it.peerId!! }
        require(spec.hostPeerId in players) { "Host is not in room roster" }
        require(spec.worlds.all { validId(it.mapId) && it.generation > 0 && HASH.matches(it.contentHash) }) { "Invalid map identity or content hash" }
        require(spec.worlds.map { it.mapId }.distinct().size == spec.worlds.size) { "Duplicate map identity" }
        require(spec.worlds.map { it.ownerPeerId }
            .distinct().size == spec.worlds.size) { "Each peer can simulate one map" }
        require(spec.worlds.all { players[it.ownerPeerId]?.spectator == false }) { "Map owner is not an active human player" }
        worlds = this.spec.worlds.associateBy { it.mapId }
        require(spec.rootMapId in worlds) { "Root map is absent" }
        require(spec.rules.fogMode in 0..2 && spec.rules.incomeMultiplier.isFinite() && spec.rules.incomeMultiplier >= 0f) { "Invalid room rules" }
    }

    @Synchronized
    fun ready(peerId: String, world: MapWorldId, contentHash: String): Boolean {
        val assigned = resolve(world) ?: return false
        if (assigned.ownerPeerId != peerId || assigned.contentHash != contentHash) return false
        readyMaps += world.mapId
        return true
    }

    @Synchronized
    fun canStart(): Boolean = !closed && readyMaps.size == worlds.size && readyPeers.size == players.size

    @Synchronized
    fun peerReady(peerId: String, sessionId: String): Boolean {
        if (closed || sessionId != spec.sessionId || peerId !in players) return false
        readyPeers += peerId
        return true
    }

    @Synchronized
    fun authorizeView(peerId: String, world: MapWorldId): MapSessionPlayer? =
        if (resolve(world) != null) players[peerId] else null

    @Synchronized
    override fun close() {
        closed = true
        readyMaps.clear()
        readyPeers.clear()
    }

    private fun resolve(world: MapWorldId): MapSessionWorld? =
        if (closed || world.sessionId != spec.sessionId) null
        else worlds[world.mapId]?.takeIf { it.generation == world.generation }

    companion object {
        private val HASH = Regex("[a-f0-9]{64}")
        private fun validId(value: String): Boolean =
            value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() }
    }
}
