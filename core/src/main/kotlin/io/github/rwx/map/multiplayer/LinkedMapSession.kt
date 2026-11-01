package io.github.rwx.map.multiplayer

import java.io.IOException
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

interface MapSessionView : AutoCloseable {
    fun connect(world: MapWorldId, address: String, token: String)
    fun clearPendingInput()
    fun currentWorld(): MapWorldId?
    fun drive() = Unit
    fun failure(): Throwable? = null
    fun cancelPendingConnection() = Unit
}

interface MapSessionWorker : AutoCloseable {
    fun open(player: MapSessionPlayer): MapWorkerStream
    fun start()
    fun isAlive(): Boolean
    fun pollTransfers(): List<PortalTransferMessage> = emptyList()
    fun deliverTransfer(message: PortalTransferMessage) = Unit
    fun acknowledgeTransferResponse(transaction: String) = Unit
    fun presence(): MapWorldPresence? = null
    fun fence(candidate: String) = Unit
    fun release(candidate: String, outcome: MapGlobalOutcome?) = Unit
}

fun MapWorkerLauncher.sessionWorker(config: MapWorkerLaunch): MapSessionWorker {
    val process = launch(config)
    val client = MapWorkerClient(process.endpoint, config.world, config.controlToken)
    return object : MapSessionWorker {
        override fun open(player: MapSessionPlayer): MapWorkerStream {
            val token = client.authorizeView(player.playerId)
            return MapWorkerStream(Socket("127.0.0.1", process.endpoint.gamePort), token)
        }

        override fun start() = client.resume()
        override fun isAlive() = process.isAlive()
        override fun pollTransfers() = client.pollTransfers()
        override fun deliverTransfer(message: PortalTransferMessage) = client.deliverTransfer(message)
        override fun acknowledgeTransferResponse(transaction: String) = client.acknowledgeTransferResponse(transaction)
        override fun presence() = client.status().presence
        override fun fence(candidate: String) {
            client.fence(candidate)
        }

        override fun release(candidate: String, outcome: MapGlobalOutcome?) = client.release(candidate, outcome)
        override fun close() = process.close()
    }
}

/** One room session. All lifecycle transitions run on drive's caller; blocking work uses io. */
class LinkedMapSession(
    val localPeerId: String,
    private val view: MapSessionView,
    private val resolveMaps: (MapSessionSpec) -> Map<String, String>,
    private val launchWorker: (MapWorkerLaunch) -> MapSessionWorker,
    private val send: (String, MapSessionMessage) -> Boolean,
    private val sendStream: MapStreamSender,
    private val onStarted: () -> Unit = {},
    private val onFailure: (String) -> Unit = {},
) : AutoCloseable {
    enum class Phase { NEW, PREPARING, READY, STARTING, RUNNING, CLOSED }

    @Volatile
    var phase = Phase.NEW
        private set
    var spec: MapSessionSpec? = null
        private set
    var paths: Map<String, String> = emptyMap()
        private set
    var transitioning = false
        private set
    private var coordinator: MapSessionCoordinator? = null

    @Volatile
    private var tunnel: MapStreamTunnel? = null

    @Volatile
    private var worker: MapSessionWorker? = null
    private val events = ConcurrentLinkedQueue<() -> Unit>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "map-session-prepare").apply { isDaemon = true } }
    private var startReceived = false
    private var target: MapWorldId? = null
    private var source: MapWorldId? = null
    private var targetStream: String? = null
    private var activeStream: String? = null
    private var switchTicket = 0L
    private var recovering = false
    private var deadline = 0L
    private var readyDeadline = 0L
    private var initialViewShown = false
    private var pollingWorker = false
    private var nextPoll = 0L
    private var outcomeBarrier: MapOutcomeBarrier? = null
    private var activeCandidate: String? = null
    private var candidateStarted = 0L
    private val pendingDeliveries = hashSetOf<String>()

    fun prepare(value: MapSessionSpec) {
        check(phase == Phase.NEW)
        val authority = MapSessionCoordinator(value)
        require(authority.spec.players.any { it.peerId == localPeerId })
        coordinator = authority
        spec = authority.spec
        outcomeBarrier = MapOutcomeBarrier(authority.spec)
        phase = Phase.PREPARING
        readyDeadline = System.nanoTime() + 120_000_000_000L
        tunnel = MapStreamTunnel(authority, localPeerId, sendStream, { _, player -> worker?.open(player) })
        io.execute {
            var created: MapSessionWorker? = null
            try {
                val resolved = resolveMaps(authority.spec)
                val owned = value.worlds.singleOrNull { it.ownerPeerId == localPeerId }
                if (owned != null) created = launchWorker(
                    MapWorkerLaunch(
                        MapWorldId(value.sessionId, owned.mapId, owned.generation), resolved.getValue(owned.mapId),
                        value.rules, value.players, UUID.randomUUID().toString(), owned.contentHash, startPaused = true,
                        worlds = value.worlds.map { MapWorldId(value.sessionId, it.mapId, it.generation) },
                        worldPaths = resolved
                    )
                )
                synchronized(this) {
                    if (phase == Phase.CLOSED) {
                        created?.close(); return@execute
                    }
                    worker = created
                    events.add {
                        paths = resolved.toMap()
                        phase = Phase.READY
                        relay(MapSessionMessage("peer-ready", value.sessionId))
                        if (owned != null) relay(
                            MapSessionMessage(
                                "world-ready",
                                value.sessionId,
                                world = MapWorldId(value.sessionId, owned.mapId, owned.generation),
                                contentHash = owned.contentHash
                            )
                        )
                    }
                }
            } catch (error: Throwable) {
                created?.close()
                events.add { failSession(error.message ?: "Map preparation failed") }
            }
        }
    }

    fun handleMessage(authenticatedPeerId: String, message: MapSessionMessage) {
        if (events.size >= 512) return
        events.add {
            val authority = coordinator ?: return@add
            val value = authority.spec
            if (message.sessionId != value.sessionId || phase == Phase.CLOSED) return@add
            if (localPeerId == value.hostPeerId) when (message.kind) {
                "peer-ready" -> authority.peerReady(authenticatedPeerId, message.sessionId)
                "world-ready" -> message.world?.let {
                    authority.ready(
                        authenticatedPeerId,
                        it,
                        message.contentHash.orEmpty()
                    )
                }

                "failed" -> if (value.players.any { it.peerId == authenticatedPeerId }) failSession(
                    message.text ?: "A participant failed"
                )
            }
            if (message.kind == "transfer") routeTransfer(authenticatedPeerId, message)
            if (localPeerId == value.hostPeerId && message.kind == "presence") {
                message.presence?.let { report ->
                    outcomeBarrier?.report(authenticatedPeerId, report)
                    if (activeCandidate != null && report.fence == activeCandidate) {
                        val outcome = outcomeBarrier?.confirm(authenticatedPeerId, report)
                        if (outcomeBarrier?.complete() == true) releaseOutcome(outcome)
                    }
                }
            }
            if (authenticatedPeerId == value.hostPeerId) when (message.kind) {
                "outcome-fence" -> message.candidate?.let { candidate ->
                    io.execute {
                        try {
                            worker?.fence(candidate)
                        } catch (failure: Throwable) {
                            events.add { failSession(failure.message ?: "World fence failed") }
                        }
                    }
                }

                "outcome-release" -> message.candidate?.let { candidate ->
                    io.execute {
                        try {
                            worker?.release(candidate, message.outcome)
                        } catch (failure: Throwable) {
                            events.add { failSession(failure.message ?: "World outcome release failed") }
                        }
                    }
                }

                "start" -> startReceived = true
                "stop" -> {
                    onFailure(message.text ?: "Map session ended"); close()
                }
            }
        }
    }

    fun handleStream(authenticatedPeerId: String, frame: MapStreamFrame): Boolean =
        tunnel?.handleFrame(authenticatedPeerId, frame) == true

    fun requestView(mapId: String, portalId: String? = null) {
        if (phase != Phase.RUNNING || transitioning) return
        val value = spec ?: return
        val world = value.worlds.firstOrNull { it.mapId == mapId } ?: return
        val identity = MapWorldId(value.sessionId, mapId, world.generation)
        if (view.currentWorld() == identity) return
        source = view.currentWorld()
        recovering = false
        open(identity)
    }

    private fun open(world: MapWorldId) {
        val ticket = ++switchTicket
        target = world
        transitioning = true
        deadline = System.nanoTime() + 45_000_000_000L
        targetStream = tunnel?.openView(world, { stream ->
            events.add {
                if (ticket != switchTicket || phase == Phase.CLOSED) tunnel?.closeStream(stream.streamId)
                else {
                    try {
                        view.clearPendingInput(); view.connect(world, stream.address, stream.token)
                    } catch (error: Throwable) {
                        failedView(error)
                    }
                }
            }
        }, { error -> events.add { if (ticket == switchTicket && transitioning) failedView(error) } })
        if (targetStream == null) failedView(IOException("Map owner is unavailable"))
    }

    fun drive() {
        if (phase == Phase.CLOSED) return
        repeat(512) { events.poll()?.invoke() ?: return@repeat }
        if (phase == Phase.CLOSED) return
        val value = spec ?: return
        if (phase == Phase.READY && localPeerId == value.hostPeerId && coordinator?.canStart() == true) {
            if (!broadcast(MapSessionMessage("start", value.sessionId))) {
                failSession("Cannot deliver session start"); return
            }
            startReceived = true
        }
        if (phase == Phase.READY && startReceived) {
            phase = Phase.STARTING
            io.execute {
                try {
                    worker?.start(); events.add {
                        phase = Phase.RUNNING
                        requestView(value.rootMapId)
                    }
                } catch (error: Throwable) {
                    events.add { failSession(error.message ?: "Cannot start map") }
                }
            }
        }
        if (phase in listOf(Phase.PREPARING, Phase.READY, Phase.STARTING) && System.nanoTime() > readyDeadline) {
            failSession("Timed out waiting for all maps and players"); return
        }
        view.drive()
        if (!transitioning && phase == Phase.RUNNING) view.failure()?.let {
            failSession(it.message ?: "Map view disconnected"); return
        }
        if (transitioning) {
            val error = view.failure()
            when {
                error != null -> failedView(error)
                view.currentWorld() == target -> {
                    activeStream?.let { tunnel?.closeStream(it) }
                    activeStream = targetStream
                    targetStream = null
                    transitioning = false
                    source = null
                    if (!initialViewShown) {
                        initialViewShown = true; onStarted()
                    }
                }

                System.nanoTime() > deadline -> failedView(IOException("Map snapshot did not become ready"))
            }
        }
        if (phase == Phase.RUNNING && worker?.isAlive() == false) failSession("A map simulation stopped")
        if (phase == Phase.RUNNING) {
            pollWorker()
            if (localPeerId == value.hostPeerId && activeCandidate == null) outcomeBarrier?.candidate()
                ?.let { candidate ->
                    activeCandidate = candidate; candidateStarted = System.nanoTime()
                    val message = MapSessionMessage("outcome-fence", value.sessionId, candidate = candidate)
                    if (!broadcast(message)) {
                        failSession("Cannot deliver aggregate outcome fence"); return
                    }
                    handleMessage(localPeerId, message)
                }
            if (activeCandidate != null && System.nanoTime() - candidateStarted > 15_000_000_000L) {
                // No timeout finalization: release the tentative cut and collect fresh reports.
                releaseOutcome(null)
            }
        }
    }

    private fun routeTransfer(peer: String, message: MapSessionMessage) {
        val value = spec ?: return
        val transfer = message.transfer ?: return
        if (runCatching { transfer.envelope.validate() }.isFailure) return
        fun owner(world: MapWorldId): String? = value.worlds.firstOrNull {
            world.sessionId == value.sessionId && it.mapId == world.mapId && it.generation == world.generation
        }?.ownerPeerId

        val from = owner(transfer.from) ?: return
        val to = owner(transfer.to) ?: return
        if (localPeerId == value.hostPeerId) {
            if (peer != from) return
            if (to != localPeerId) {
                send(to, message); return
            }
        } else if (peer != value.hostPeerId || to != localPeerId) return
        val key = "${transfer.kind}:${transfer.envelope.source.mapId}:${transfer.envelope.transactionId}"
        if (pendingDeliveries.size >= 128 || !pendingDeliveries.add(key)) return
        io.execute {
            try {
                worker?.deliverTransfer(transfer)
            } catch (_: java.io.IOException) { /* A fenced or transiently busy worker will receive the same durable retry. */
            } catch (failure: Throwable) {
                events.add { failSession(failure.message ?: "Transfer delivery failed") }
            } finally {
                events.add { pendingDeliveries.remove(key) }
            }
        }
    }

    private fun pollWorker() {
        if (worker == null || pollingWorker || System.nanoTime() < nextPoll) return
        pollingWorker = true; nextPoll = System.nanoTime() + 250_000_000L
        io.execute {
            try {
                val transfers = worker?.pollTransfers().orEmpty()
                val presence = worker?.presence()
                events.add {
                    if (phase == Phase.CLOSED) return@add
                    transfers.forEach { transfer ->
                        relay(MapSessionMessage("transfer", spec!!.sessionId, transfer = transfer))
                        // Only response cache entries acknowledge send progress; source decisions stay retryable.
                        if (transfer.from == transfer.envelope.target) io.execute {
                            runCatching { worker?.acknowledgeTransferResponse(transfer.envelope.transactionId) }
                        }
                    }
                    presence?.let { relay(MapSessionMessage("presence", spec!!.sessionId, presence = it)) }
                    pollingWorker = false
                }
            } catch (failure: Throwable) {
                events.add { pollingWorker = false; failSession(failure.message ?: "Worker status failed") }
            }
        }
    }

    private fun releaseOutcome(outcome: MapGlobalOutcome?) {
        val candidate = activeCandidate ?: return
        val message = MapSessionMessage("outcome-release", spec!!.sessionId, candidate = candidate, outcome = outcome)
        if (!broadcast(message)) {
            failSession("Cannot release aggregate outcome fence"); return
        }
        handleMessage(localPeerId, message)
        activeCandidate = null; outcomeBarrier?.release()
    }

    private fun failedView(error: Throwable) {
        targetStream?.let { tunnel?.closeStream(it) }
        targetStream = null
        switchTicket++
        transitioning = false
        val previous = source
        view.cancelPendingConnection()
        if (!recovering && previous != null && view.currentWorld() != previous) {
            recovering = true
            open(previous)
        } else if (previous == null || recovering) {
            failSession(error.message ?: "Unable to restore map view"); return
        } else source = null
        onFailure(error.message ?: "Unable to switch map")
    }

    private fun relay(message: MapSessionMessage) {
        val host = spec!!.hostPeerId
        if (host == localPeerId) handleMessage(localPeerId, message)
        else if (!send(host, message)) failSession("Cannot reach room host")
    }

    private fun broadcast(message: MapSessionMessage): Boolean =
        spec!!.players.filterNot { it.ai || it.peerId == localPeerId }
            .map { send(it.peerId!!, message) }.all { it }

    private fun failSession(reason: String) {
        if (phase == Phase.CLOSED) return
        spec?.let {
            if (it.hostPeerId == localPeerId) broadcast(MapSessionMessage("stop", it.sessionId, text = reason))
            else send(it.hostPeerId, MapSessionMessage("failed", it.sessionId, text = reason))
        }
        try {
            close()
        } finally {
            onFailure(reason)
        }
    }

    @Synchronized
    override fun close() {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        switchTicket++
        transitioning = false
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        cleanup { tunnel?.close() }; tunnel = null
        cleanup { coordinator?.close() }
        cleanup { view.clearPendingInput() }
        cleanup { view.close() }
        cleanup { worker?.close() }; worker = null
        events.clear(); paths = emptyMap()
        io.shutdownNow()
        failure?.let { throw it }
    }
}
