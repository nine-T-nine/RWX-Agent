package io.github.rwx.map.multiplayer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class MapWorkerLaunch(
    val world: MapWorldId, val mapPath: String, val rules: MapRoomRules,
    val players: List<MapSessionPlayer>, val controlToken: String,
    val contentHash: String? = null, val startPaused: Boolean = false, val worlds: List<MapWorldId> = emptyList(),
    /** World id to locally resolved map path; portal links store paths, not world ids. */
    val worldPaths: Map<String, String> = emptyMap()
)

@Serializable
data class MapWorkerEndpoint(val controlPort: Int, val gamePort: Int)

interface MapWorkerProcess : AutoCloseable {
    val endpoint: MapWorkerEndpoint
    fun isAlive(): Boolean
}

fun interface MapWorkerLauncher {
    fun launch(config: MapWorkerLaunch): MapWorkerProcess
}

@Serializable
data class MapWorkerStatus(
    val tick: Int, val unitCount: Int, val mapPath: String, val rules: MapRoomRules,
    val paused: Boolean = false, val presence: MapWorldPresence? = null,
    val transferStates: List<TransferSummary> = emptyList(), val checksumFrame: Int = -1, val checksum: Long = 0
)

@Serializable
data class TransferSummary(
    val transaction: String, val source: MapWorldId, val target: MapWorldId, val state: TransferState,
    val sourceIds: List<Long>, val destinationIds: Map<Long, Long>, val confirmed: Boolean
)

@Serializable
data class MapWorkerSnapshot(val filePath: String, val contentHash: String, val size: Long, val tick: Int)

@Serializable
enum class MapWorkerCommand { STATUS, AUTHORIZE_VIEW, SNAPSHOT, PAUSE, RESUME, STOP, TRANSFER_POLL, TRANSFER_DELIVER, TRANSFER_ACK, OUTCOME_FENCE, OUTCOME_RELEASE }

@Serializable
data class MapWorkerRequest(
    val world: MapWorldId, val controlToken: String, val command: MapWorkerCommand,
    val playerId: String? = null, val transfer: PortalTransferMessage? = null, val transaction: String? = null,
    val candidate: String? = null, val outcome: MapGlobalOutcome? = null
)

@Serializable
data class MapWorkerResponse(
    val status: MapWorkerStatus? = null, val viewToken: String? = null,
    val snapshot: MapWorkerSnapshot? = null, val error: String? = null,
    val transfers: List<PortalTransferMessage> = emptyList(), val presence: MapWorldPresence? = null
)

/** Private worker pipes and loopback control use the same bounded framing. */
object MapWorkerWire {
    const val MAX_FRAME_BYTES = 1024 * 1024
    private val json = Json { encodeDefaults = true }

    fun writeLaunch(output: OutputStream, launch: MapWorkerLaunch) = writeFrame(output, json.encodeToString(launch))
    fun readLaunch(input: InputStream): MapWorkerLaunch = decode { json.decodeFromString(readFrame(input)) }
    fun writeEndpoint(output: OutputStream, endpoint: MapWorkerEndpoint) =
        writeFrame(output, json.encodeToString(endpoint))

    fun readEndpoint(input: InputStream): MapWorkerEndpoint = decode { json.decodeFromString(readFrame(input)) }
    fun writeRequest(output: OutputStream, request: MapWorkerRequest) = writeFrame(output, json.encodeToString(request))
    fun readRequest(input: InputStream): MapWorkerRequest = decode { json.decodeFromString(readFrame(input)) }
    fun writeResponse(output: OutputStream, response: MapWorkerResponse) =
        writeFrame(output, json.encodeToString(response))

    fun readResponse(input: InputStream): MapWorkerResponse = decode { json.decodeFromString(readFrame(input)) }

    fun validate(request: MapWorkerRequest, launch: MapWorkerLaunch) {
        if (request.world != launch.world || !MessageDigest.isEqual(
                request.controlToken.toByteArray(Charsets.UTF_8), launch.controlToken.toByteArray(Charsets.UTF_8)
            )
        ) {
            throw IOException("Unauthorized worker request")
        }
    }

    private fun writeFrame(output: OutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) throw IOException("Invalid worker frame length")
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }

    private fun readFrame(input: InputStream): String {
        val data = DataInputStream(input)
        val length = data.readInt()
        if (length !in 1..MAX_FRAME_BYTES) throw IOException("Invalid worker frame length")
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun <T> decode(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw IOException("Invalid worker frame", e)
    }
}

/** At most one pending token per manifest player; tokens never become public team identities. */
class MapWorkerViewTokens(
    players: List<MapSessionPlayer>, private val lifetimeMillis: Long = 30_000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val players = players.filterNot { it.ai }.associateBy { it.playerId }
    private val pending = mutableMapOf<String, Grant>()
    private val random = SecureRandom()

    private data class Grant(val player: MapSessionPlayer, val expiresAt: Long)

    init {
        require(lifetimeMillis > 0)
    }

    @Synchronized
    fun authorize(playerId: String): String {
        val player = requireNotNull(players[playerId]) { "Player is absent from worker roster" }
        val now = nowMillis()
        pending.entries.removeAll { it.value.expiresAt <= now || it.value.player.playerId == playerId }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        pending[token] = Grant(player, now + lifetimeMillis)
        return token
    }

    @Synchronized
    fun consume(token: String?): MapSessionPlayer? {
        val grant = pending.remove(token) ?: return null
        return grant.player.takeIf { grant.expiresAt > nowMillis() }
    }
}

class MapWorkerClient(
    private val endpoint: MapWorkerEndpoint, private val world: MapWorldId,
    private val controlToken: String
) {
    fun status(): MapWorkerStatus = requireNotNull(request(MapWorkerCommand.STATUS).status)
    fun authorizeView(playerId: String): String =
        requireNotNull(request(MapWorkerCommand.AUTHORIZE_VIEW, playerId).viewToken)

    fun snapshot(): MapWorkerSnapshot = requireNotNull(request(MapWorkerCommand.SNAPSHOT).snapshot)
    fun pause() {
        request(MapWorkerCommand.PAUSE)
    }

    fun resume() {
        request(MapWorkerCommand.RESUME)
    }

    fun stop() {
        request(MapWorkerCommand.STOP)
    }

    fun pollTransfers(): List<PortalTransferMessage> = request(MapWorkerCommand.TRANSFER_POLL).transfers
    fun deliverTransfer(message: PortalTransferMessage) {
        request(MapWorkerCommand.TRANSFER_DELIVER, transfer = message)
    }

    fun acknowledgeTransferResponse(transaction: String) {
        request(MapWorkerCommand.TRANSFER_ACK, transaction = transaction)
    }

    fun fence(candidate: String): MapWorldPresence =
        requireNotNull(request(MapWorkerCommand.OUTCOME_FENCE, candidate = candidate).presence)

    fun release(candidate: String, outcome: MapGlobalOutcome?) {
        request(MapWorkerCommand.OUTCOME_RELEASE, candidate = candidate, outcome = outcome)
    }

    private fun request(
        command: MapWorkerCommand, playerId: String? = null, transfer: PortalTransferMessage? = null,
        transaction: String? = null, candidate: String? = null, outcome: MapGlobalOutcome? = null
    ): MapWorkerResponse = Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.controlPort), 5_000)
        socket.soTimeout = 30_000
        MapWorkerWire.writeRequest(
            socket.getOutputStream(),
            MapWorkerRequest(world, controlToken, command, playerId, transfer, transaction, candidate, outcome)
        )
        MapWorkerWire.readResponse(socket.getInputStream()).also { if (it.error != null) throw IOException(it.error) }
    }
}
