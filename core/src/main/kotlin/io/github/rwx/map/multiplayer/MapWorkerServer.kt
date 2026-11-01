package io.github.rwx.map.multiplayer

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Listeners only frame requests and accept streams; the runtime owns all engine work. */
internal class MapWorkerServer(
    private val launch: MapWorkerLaunch,
    private val control: (MapWorkerRequest) -> MapWorkerResponse,
    private val acceptGame: (Socket) -> Boolean
) : AutoCloseable {
    private val controlSocket = bind()
    private val gameSocket = try {
        bind()
    } catch (failure: Throwable) {
        controlSocket.close(); throw failure
    }
    val endpoint = MapWorkerEndpoint(controlSocket.localPort, gameSocket.localPort)
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val handlers = ThreadPoolExecutor(
        2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "map-worker-control").apply { isDaemon = true } })

    fun start() {
        listener("map-worker-control-accept", controlSocket) { socket ->
            clients.add(socket)
            try {
                handlers.execute {
                    try {
                        socket.use {
                            it.soTimeout = 5_000
                            val request = MapWorkerWire.readRequest(it.getInputStream())
                            MapWorkerWire.validate(request, launch)
                            val response = control(request)
                            MapWorkerWire.writeResponse(it.getOutputStream(), response)
                        }
                    } catch (_: IOException) {
                        // Reject malformed or unauthorized frames without touching the world.
                    } finally {
                        clients.remove(socket)
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                clients.remove(socket); socket.close()
            }
        }
        listener("map-worker-game-accept", gameSocket) { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            if (!acceptGame(socket)) socket.close()
        }
    }

    private fun listener(name: String, server: ServerSocket, accept: (Socket) -> Unit) {
        Thread({
            while (!server.isClosed) {
                try {
                    accept(server.accept())
                } catch (_: IOException) {
                    if (server.isClosed) break
                }
            }
        }, name).apply { isDaemon = true; start() }
    }

    override fun close() {
        controlSocket.close()
        gameSocket.close()
        // Let STOP's in-flight reply reach the caller before closing its stream.
        handlers.shutdown()
        handlers.awaitTermination(1, TimeUnit.SECONDS)
        clients.forEach { runCatching { it.close() } }
        handlers.shutdownNow()
    }

    private fun bind() = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32) }
}
