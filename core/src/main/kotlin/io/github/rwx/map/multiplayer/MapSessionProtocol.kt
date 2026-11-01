package io.github.rwx.map.multiplayer

import kotlinx.serialization.Serializable

@Serializable
data class MapWorldId(val sessionId: String, val mapId: String, val generation: Int = 1)

@Serializable
data class MapSessionPlayer(
    val playerId: String,
    val peerId: String?,
    val teamId: Int,
    val allyId: Int,
    val name: String,
    val ai: Boolean = false,
    val spectator: Boolean = false,
    val startingUnits: Int? = null,
    val aiDifficulty: Int? = null,
)

@Serializable
data class MapSessionWorld(
    val mapId: String,
    val contentHash: String,
    val ownerPeerId: String,
    val generation: Int = 1,
)

@Serializable
data class MapSessionSpec(
    val sessionId: String,
    val hostPeerId: String,
    val rootMapId: String,
    val rules: MapRoomRules,
    val players: List<MapSessionPlayer>,
    val worlds: List<MapSessionWorld>,
    val protocol: Int = 1,
)
