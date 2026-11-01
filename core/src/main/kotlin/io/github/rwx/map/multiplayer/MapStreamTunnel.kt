package io.github.rwx.map.multiplayer

import kotlinx.serialization.Serializable
import java.net.Socket
import java.net.InetAddress
import java.net.ServerSocket
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class MapStreamFrame(
    val world: MapWorldId,
    val streamId: String,
    val kind: String,
    val sequence: Long = 0,
    val payload: String? = null,
    val playerId: String? = null
)

fun interface MapStreamSender {
    fun send(toPeerId: String, frame: MapStreamFrame): Boolean
}

data class MapWorkerStream(val socket: Socket, val token: String)
data class MapStreamView(val world: MapWorldId, val streamId: String, val address: String, val token: String)
data class MapStreamLimits(
    val maxStreams: Int = 32,
    val maxBufferedBytesPerStream: Int = 256 * 1024,
    val maxBufferedBytes: Int = 4 * 1024 * 1024
)

class MapStreamTunnel(
    private val coordinator: MapSessionCoordinator,
    private val localPeerId: String,
    private val sender: MapStreamSender,
    private val openWorker: (MapWorldId, MapSessionPlayer) -> MapWorkerStream?,
    private val limits: MapStreamLimits = MapStreamLimits(),
) : AutoCloseable {
    private val host = coordinator.spec.hostPeerId
    private val routes = ConcurrentHashMap<String, Route>()
    private val views = ConcurrentHashMap<String, Pipe>()
    private val workers = ConcurrentHashMap<String, Pipe>()
    private val closed = AtomicBoolean()
    private val buffered = AtomicLong()
    private val admissionLock = Any()
    private val io = Executors.newCachedThreadPool { Thread(it, "map-stream-io").apply { isDaemon = true } }
    private val timer =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "map-stream-timeout").apply { isDaemon = true } }

    init {
        require(limits.maxStreams > 0 && limits.maxBufferedBytesPerStream >= CHUNK_SIZE && limits.maxBufferedBytes >= CHUNK_SIZE)
        require(coordinator.spec.players.any { it.peerId == localPeerId })
    }

    private class Route(val world: MapWorldId, val viewer: String, val owner: String) {
        var viewSequence = 0L
        var workerSequence = 0L
        var opened = false
    }

    private inner class Pipe(
        val world: MapWorldId,
        val id: String,
        val viewer: Boolean,
        val onReady: ((MapStreamView) -> Unit)? = null,
        val onFailure: (Throwable) -> Unit = {},
    ) {
        val ended = AtomicBoolean()
        val ready = AtomicBoolean()

        @Volatile
        var socket: Socket? = null

        @Volatile
        var listener: ServerSocket? = null
        var receiveSequence = 0L
        private var sendSequence = 0L
        private var queuedBytes = 0L
        private val inbound = LinkedBlockingQueue<ByteArray>()

        @Volatile
        var writer: Future<*>? = null

        @Volatile
        var reader: Future<*>? = null

        fun attach(connected: Socket) {
            synchronized(this) {
                if (ended.get()) {
                    connected.close(); return
                }
                socket = connected
            }
            connected.tcpNoDelay = true
            writer = io.submit {
                try {
                    while (!ended.get()) {
                        val data = inbound.take()
                        try {
                            connected.getOutputStream().write(data)
                        } finally {
                            release(data.size)
                        }
                    }
                } catch (_: Exception) {
                    fail(this, "Map stream writer closed")
                }
            }
            reader = io.submit {
                try {
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (!ended.get()) {
                        val count = connected.getInputStream().read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val frame = MapStreamFrame(
                            world, id, if (viewer) "view-data" else "worker-data", sendSequence++,
                            Base64.getEncoder().encodeToString(buffer.copyOf(count))
                        )
                        if (!upstream(frame)) break
                    }
                } catch (_: Exception) { /* Closing the pipe interrupts blocking reads. */
                } finally {
                    fail(this, "Map stream reader closed")
                }
            }
        }

        @Synchronized
        fun enqueue(sequence: Long, data: ByteArray): Boolean {
            if (ended.get() || sequence != receiveSequence || queuedBytes + data.size > limits.maxBufferedBytesPerStream) return false
            while (true) {
                val previous = buffered.get()
                if (previous + data.size > limits.maxBufferedBytes) return false
                if (buffered.compareAndSet(previous, previous + data.size)) break
            }
            receiveSequence++
            queuedBytes += data.size
            inbound.add(data)
            return true
        }

        @Synchronized
        private fun release(bytes: Int) {
            queuedBytes -= bytes
            buffered.addAndGet(-bytes.toLong())
        }

        fun stop(report: Boolean, reason: String) {
            if (!ended.compareAndSet(false, true)) return
            runCatching { socket?.close() }
            runCatching { listener?.close() }
            writer?.cancel(true)
            reader?.cancel(true)
            synchronized(this) {
                while (true) {
                    val data = inbound.poll() ?: break
                    release(data.size)
                }
            }
            if (report) runCatching { onFailure(IOException(reason)) }
        }
    }

    fun openView(world: MapWorldId, onReady: (MapStreamView) -> Unit, onFailure: (Throwable) -> Unit = {}): String? {
        if (closed.get() || coordinator.authorizeView(localPeerId, world) == null) return null
        val id = UUID.randomUUID().toString()
        val pipe = Pipe(world, id, true, onReady, onFailure)
        synchronized(admissionLock) {
            if (closed.get() || views.size >= limits.maxStreams) return null
            pipe.listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            views[id] = pipe
        }
        io.execute {
            try {
                val socket = pipe.listener!!.accept()
                pipe.listener?.close()
                pipe.attach(socket)
            } catch (_: Exception) {
                if (!pipe.ended.get()) fail(pipe, "Map viewer did not connect")
            }
        }
        timer.schedule({ if (!pipe.ready.get()) fail(pipe, "Map view open timed out") }, 30, TimeUnit.SECONDS)
        if (!upstream(MapStreamFrame(world, id, "open"))) {
            fail(pipe, "Cannot send map view request")
            return null
        }
        return id
    }

    /** The caller supplies transport-authenticated identity, independent of the frame payload. */
    fun handleFrame(authenticatedPeerId: String, frame: MapStreamFrame): Boolean {
        if (closed.get() || !validFrame(frame)) return false
        return if (localPeerId == host) atHost(authenticatedPeerId, frame)
        else authenticatedPeerId == host && atEndpoint(frame)
    }

    private fun validFrame(frame: MapStreamFrame) = frame.streamId.isNotBlank() && frame.streamId.length <= 128 &&
            frame.world.sessionId == coordinator.spec.sessionId && coordinator.spec.worlds.any {
        it.mapId == frame.world.mapId && it.generation == frame.world.generation
    }

    private fun atHost(peer: String, frame: MapStreamFrame): Boolean {
        if (frame.kind == "open") {
            val player = coordinator.authorizeView(peer, frame.world) ?: return false
            val owner = coordinator.spec.worlds.first { it.mapId == frame.world.mapId }.ownerPeerId
            val route = Route(frame.world, peer, owner)
            synchronized(admissionLock) {
                if (routes.size >= limits.maxStreams || routes.putIfAbsent(frame.streamId, route) != null) return false
            }
            if (!forward(owner, frame.copy(playerId = player.playerId))) {
                closeRoute(frame.streamId, "Map owner unavailable")
                return false
            }
            return true
        }
        val route = routes[frame.streamId] ?: return false
        if (route.world != frame.world) return false
        when (frame.kind) {
            "opened" -> {
                if (peer != route.owner || frame.payload.isNullOrBlank() || frame.payload.length > 2048) return false
                synchronized(route) {
                    if (route.opened) return false
                    route.opened = true
                }
                if (!forward(route.viewer, frame)) {
                    closeRoute(frame.streamId, "Viewer unavailable"); return false
                }
                return true
            }

            "view-data", "worker-data" -> {
                val fromViewer = frame.kind == "view-data"
                if (peer != if (fromViewer) route.viewer else route.owner) return false
                val valid = synchronized(route) {
                    val expected = if (fromViewer) route.viewSequence else route.workerSequence
                    if (!route.opened || frame.sequence != expected || decode(frame.payload) == null) false
                    else {
                        if (fromViewer) route.viewSequence++ else route.workerSequence++; true
                    }
                }
                if (!valid) {
                    closeRoute(frame.streamId, "Invalid map stream sequence or payload"); return false
                }
                if (!forward(if (fromViewer) route.owner else route.viewer, frame)) {
                    closeRoute(frame.streamId, "Map stream delivery failed")
                    return false
                }
                return true
            }

            "view-close", "worker-close" -> {
                if (peer != if (frame.kind == "view-close") route.viewer else route.owner) return false
                closeRoute(frame.streamId, frame.payload?.take(512) ?: "Map stream closed")
                return true
            }

            else -> return false
        }
    }

    private fun atEndpoint(frame: MapStreamFrame): Boolean = when (frame.kind) {
        "open" -> {
            val assignment = coordinator.spec.worlds.firstOrNull { it.mapId == frame.world.mapId }
            val player = coordinator.spec.players.firstOrNull { it.playerId == frame.playerId && !it.ai }
            if (assignment?.ownerPeerId != localPeerId || player == null) false else {
                val pipe = Pipe(frame.world, frame.streamId, false)
                val accepted = synchronized(admissionLock) {
                    workers.size < limits.maxStreams && workers.putIfAbsent(frame.streamId, pipe) == null
                }
                if (accepted) io.execute {
                    try {
                        val worker = openWorker(frame.world, player) ?: throw IOException("Map worker unavailable")
                        if (pipe.ended.get()) worker.socket.close()
                        else if (!upstream(frame.copy(kind = "opened", payload = worker.token, playerId = null))) {
                            worker.socket.close()
                            fail(pipe, "Cannot confirm map view")
                        } else pipe.attach(worker.socket)
                    } catch (error: Exception) {
                        fail(pipe, error.message ?: "Cannot open map worker")
                    }
                }
                accepted
            }
        }

        "opened" -> {
            val pipe = views[frame.streamId]
            if (pipe == null || pipe.world != frame.world || pipe.ended.get() || frame.payload.isNullOrBlank()) false
            else if (!pipe.ready.compareAndSet(false, true)) false
            else {
                val port = pipe.listener!!.localPort
                runCatching {
                    pipe.onReady?.invoke(
                        MapStreamView(
                            pipe.world,
                            pipe.id,
                            "127.0.0.1:$port",
                            frame.payload
                        )
                    )
                }
                    .onFailure { fail(pipe, "Map view callback failed") }
                true
            }
        }

        "view-data", "worker-data" -> {
            val pipe = (if (frame.kind == "worker-data") views else workers)[frame.streamId]
            if (pipe == null || pipe.world != frame.world) false else {
                val data = decode(frame.payload)
                if (data == null || !pipe.enqueue(frame.sequence, data)) {
                    fail(pipe, "Map stream input exceeded its bounds")
                    false
                } else true
            }
        }

        "view-close", "worker-close" -> {
            val table = if (frame.kind == "worker-close") views else workers
            val pipe = table[frame.streamId]
            if (pipe?.world != frame.world) false else {
                table.remove(frame.streamId, pipe)
                pipe.stop(true, frame.payload?.take(512) ?: "Map stream closed")
                true
            }
        }

        else -> false
    }

    private fun upstream(frame: MapStreamFrame): Boolean = if (closed.get()) false else
        if (localPeerId == host) atHost(localPeerId, frame) else runCatching { sender.send(host, frame) }.getOrDefault(
            false
        )

    private fun forward(peer: String, frame: MapStreamFrame): Boolean = if (peer == localPeerId) atEndpoint(frame)
    else runCatching { sender.send(peer, frame) }.getOrDefault(false)

    private fun fail(pipe: Pipe, reason: String) {
        val removed = (if (pipe.viewer) views else workers).remove(pipe.id, pipe)
        if (!removed) return
        pipe.stop(true, reason)
        upstream(
            MapStreamFrame(
                pipe.world,
                pipe.id,
                if (pipe.viewer) "view-close" else "worker-close",
                payload = reason.take(512)
            )
        )
    }

    private fun closeRoute(id: String, reason: String) {
        val route = routes.remove(id) ?: return
        forward(route.viewer, MapStreamFrame(route.world, id, "worker-close", payload = reason))
        forward(route.owner, MapStreamFrame(route.world, id, "view-close", payload = reason))
    }

    fun closeStream(streamId: String) {
        val view = views.remove(streamId)
        if (view != null) {
            view.stop(false, "View closed")
            upstream(MapStreamFrame(view.world, streamId, "view-close"))
        } else if (localPeerId == host) closeRoute(streamId, "View closed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        views.values.forEach { it.stop(false, "Session closed") }
        workers.values.forEach { it.stop(false, "Session closed") }
        views.clear(); workers.clear(); routes.clear()
        timer.shutdownNow(); io.shutdownNow()
    }

    companion object {
        private const val CHUNK_SIZE = 16 * 1024
        private fun decode(payload: String?): ByteArray? {
            if (payload == null || payload.length > ((CHUNK_SIZE + 2) / 3) * 4) return null
            return runCatching { Base64.getDecoder().decode(payload) }.getOrNull()?.takeIf { it.size in 1..CHUNK_SIZE }
        }
    }
}
