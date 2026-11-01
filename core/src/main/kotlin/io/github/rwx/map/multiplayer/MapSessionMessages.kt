package io.github.rwx.map.multiplayer

import kotlinx.serialization.Serializable

/** Control messages are accepted only from the authenticated room peer, never by claimed sender. */
@Serializable
data class MapSessionMessage(
    val kind: String,
    val sessionId: String,
    val spec: MapSessionSpec? = null,
    val world: MapWorldId? = null,
    val contentHash: String? = null,
    val playerId: String? = null,
    val text: String? = null,
    val teamOnly: Boolean = false,
    val transfer: PortalTransferMessage? = null,
    val presence: MapWorldPresence? = null,
    val candidate: String? = null,
    val outcome: MapGlobalOutcome? = null,
)
