package io.github.rwx.map.multiplayer

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.GameMode
import com.corrodinggames.rts.gameFramework.network.GameModeType
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** One real engine per OS process. Platform initialization supplies assets, graphics, audio and view. */
class MapWorkerRuntime(
    config: MapWorkerLaunch, private val stateDirectory: File,
    private val initializeEngine: () -> GameLogic
) : AutoCloseable {
    private val config = config.copy(
        players = config.players.toList(),
        worlds = config.worlds.toList(),
        worldPaths = config.worldPaths.toMap(),
    )
    private val commands = ArrayBlockingQueue<Runnable>(128)
    private val pendingSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val lifecycle = Any()
    private var closed = false
    private val ready = CompletableFuture<MapWorkerEndpoint>()
    private val tokens = MapWorkerViewTokens(this.config.players)
    private var paused = config.startPaused
    private var transfers: PortalTransferWorker? = null
    private var presenceRevision = 0L
    private var stateLock: FileLock? = null
    private var lockFile: RandomAccessFile? = null
    private var transactionDirectory: File? = null
    private lateinit var engine: GameLogic
    private var server: MapWorkerServer? = null
    private val simulation = Thread(::simulate, "map-worker-simulation")

    init {
        require(config.world.sessionId.isNotBlank() && config.world.mapId.isNotBlank() && config.world.generation > 0)
        require(config.mapPath.isNotBlank() && config.controlToken.isNotBlank())
        require(config.contentHash == null || Regex("[a-f0-9]{64}").matches(config.contentHash))
        require(config.players.isNotEmpty() && config.players.size <= 100)
        require(config.players.all { it.teamId in 0..99 && it.playerId.isNotBlank() && it.name.isNotBlank() })
        require(config.players.map { it.teamId }.distinct().size == config.players.size)
        require(config.players.map { it.playerId }.distinct().size == config.players.size)
        require(config.rules.fogMode in 0..2 && config.rules.incomeMultiplier.isFinite() && config.rules.incomeMultiplier >= 0)
    }

    fun start(): MapWorkerEndpoint {
        synchronized(lifecycle) {
            check(!closed) { "Map worker owner has closed" }
            check(started.compareAndSet(false, true)) { "Map worker has already been started" }
            running.set(true)
            simulation.start()
        }
        return try {
            ready.get(120, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            close()
            throw IOException("Map worker failed to start", failure)
        }
    }

    fun awaitTermination() {
        check(started.get()) { "Map worker has not been started" }
        check(Thread.currentThread() !== simulation) { "Simulation thread cannot wait for itself" }
        simulation.join()
    }

    private fun simulate() {
        try {
            check(stateDirectory.isDirectory || stateDirectory.mkdirs()) { "Cannot create worker state directory" }
            if (config.worlds.isNotEmpty()) {
                // Platform launchers may use fresh process/log directories. Journal identity must not.
                val identity = transferDigest(Json.encodeToString(config.world).toByteArray())
                val directory = File(stateDirectory.parentFile, "world-state/$identity")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create world transaction directory" }
                lockFile = RandomAccessFile(File(directory, "owner.lock"), "rw")
                stateLock = lockFile!!.channel.tryLock()
                check(stateLock != null) { "This world generation already has an active owner" }
                check(directory.listFiles()?.none { it.extension == "record" } == true) {
                    "World generation has persisted transfers without a verified checkpoint; refusing fresh-world recovery"
                }
                transactionDirectory = directory
            }
            engine = initializeEngine()
            engine.stopGameThreadIfNotInGameThread()
            verifyMapContent()
            initializeWorld()
            if (config.worlds.isNotEmpty()) {
                engine.missionEngine.validateLinkedEliminationMode()
                val state = LinkedWorldState(config.world, config.players)
                engine.missionEngine.linkedWorldState = state
                transfers = PortalTransferWorker(
                    state, requireNotNull(transactionDirectory), config.worlds,
                    ::scheduleWorldOperation,
                    { engine.missionEngine.linkedPortalMode?.resolveDestinationPortal(it) },
                    { engine.missionEngine.linkedPortalMode?.getPortalCenter(it) },
                    config.worldPaths
                )
                engine.networkEngine.linkedTransferAuthority = transfers
            }
            // Publish readiness only after the loaded world observes the room's start barrier.
            engine.networkEngine.gamePaused = paused
            val listener = MapWorkerServer(config, ::execute) { socket ->
                pendingSockets.add(socket)
                enqueue(Runnable {
                    pendingSockets.remove(socket)
                    if (!running.get()) {
                        socket.close(); return@Runnable
                    }
                    try {
                        engine.networkEngine.acceptMapWorkerSocket(socket)
                    } catch (failure: IOException) {
                        socket.close()
                    }
                }).also { if (!it) pendingSockets.remove(socket) }
            }
            server = listener
            listener.start()
            ready.complete(listener.endpoint)
            val frameNanos = 1_000_000_000L / 60
            while (running.get()) {
                val frameStart = System.nanoTime()
                repeat(128) { commands.poll()?.run() }
                if (!running.get()) break
                engine.networkEngine.updateMapWorkerMaintenance()
                if (paused) {
                    engine.networkEngine.a(0f)
                    engine.networkEngine.b(0f)
                } else {
                    engine.shouldSkipNextDraw = true
                    engine.gameLoop(1f, 16)
                }
                LockSupport.parkNanos((frameNanos - (System.nanoTime() - frameStart)).coerceAtLeast(0))
            }
        } catch (failure: Throwable) {
            ready.completeExceptionally(failure)
            if (ready.isDone && !ready.isCompletedExceptionally) failure.printStackTrace()
        } finally {
            running.set(false)
            transfers?.close()
            server?.close()
            pendingSockets.forEach { runCatching { it.close() } }
            pendingSockets.clear()
            stateLock?.release(); stateLock = null
            lockFile?.close(); lockFile = null
            if (::engine.isInitialized) {
                engine.networkEngine.disconnectNetworking("Map worker stopped")
                engine.networkEngine.mapWorkerAdmission = null
                engine.networkEngine.linkedTransferAuthority = null
                engine.isStopped = true
                engine.stopGameThreadIfNotInGameThread()
            }
        }
    }

    private fun initializeWorld() {
        val network = engine.networkEngine
        network.r()
        PlayerTeam.resetTeamRegistry()
        PlayerTeam.setMaxTeamId(maxOf(10, config.players.maxOf { it.teamId } + 1), false)
        config.players.forEach { player ->
            val team = if (player.ai) AIController(player.teamId) else GameTeam(player.teamId)
            team.teamName = player.name
            // In the legacy engine isTeamSpectator actually identifies AI instances.
            // A spectator is represented by teamColorId -3.
            team.teamColorId = if (player.spectator) -3 else player.allyId
            team.teamAIDifficultyOverride = player.aiDifficulty
            if (player.ai) team.teamPingTime = player.aiDifficulty ?: config.rules.aiDifficulty
            team.startingUnitsOverride = player.startingUnits
            team.isTeamReady = true
        }
        // The worker is an unregistered observer, not an additional playable team slot.
        val observer = GameTeam(-3, false).apply {
            teamName = "Map simulation"
            teamColorId = -3
            isTeamReady = true
        }
        engine.playerTeam = observer
        network.localPlayerTeam = observer
        network.isServer = true
        network.networkGameActive = true
        network.singleplayerServer = false
        network.p2pSession = true
        network.useMasterServer = false
        network.publishToMasterServer = false
        network.allowJoinInProgress = true
        network.playerName = observer.teamName
        network.selectedMapPath = config.mapPath
        network.roomSettings = config.rules.toEngineSettings(
            config.mapPath,
            if (config.mapPath.replace('\\', '/')
                    .startsWith("maps/")
            ) GameModeType.skirmishMap else GameModeType.customMap
        )
        network.regenerateOwnServerId()
        network.mapWorkerAdmission = object : NetworkEngine.MapWorkerAdmission {
            override fun admitView(token: String?): GameTeam? {
                val player = tokens.consume(token) ?: return null
                return PlayerTeam.k(player.teamId) as? GameTeam
            }

            override fun isSimulationThread() = Thread.currentThread() === simulation
            override fun enqueue(work: Runnable) = this@MapWorkerRuntime.enqueue(work)
        }
        engine.currentMapPath = config.mapPath
        engine.isStopped = false
        engine.isPaused = false
        network.gameHasBeenStarted = true
        network.allPlayersReadyCountdown = 0f
        engine.loadGame(true, GameMode.normal)
        check(engine.hasLoadedLevel && engine.tileMap?.isCursorActive == true) { "Worker map did not load" }
        check(engine.pendingGameView?.isActive == true) { "Worker platform did not supply an active game view" }
    }

    private fun verifyMapContent() {
        val expected = config.contentHash ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        val input = TileMap.openMapInputStreamWithMovedFallback(config.mapPath)
            ?: throw IOException("Cannot read worker map: ${config.world.mapId}")
        input.use {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) throw IOException("Worker map content differs: ${config.world.mapId}")
    }

    private fun enqueue(work: Runnable): Boolean = running.get() && commands.offer(work).also {
        if (it) LockSupport.unpark(simulation)
    }

    private fun execute(request: MapWorkerRequest): MapWorkerResponse {
        val result = CompletableFuture<MapWorkerResponse>()
        if (!enqueue(Runnable {
                try {
                    MapWorkerWire.validate(request, config)
                    result.complete(
                        when (request.command) {
                            MapWorkerCommand.STATUS -> MapWorkerResponse(status = status())
                            MapWorkerCommand.AUTHORIZE_VIEW -> MapWorkerResponse(
                                viewToken = tokens.authorize(
                                    requireNotNull(request.playerId)
                                )
                            )

                            MapWorkerCommand.SNAPSHOT -> MapWorkerResponse(snapshot = snapshot())
                            MapWorkerCommand.PAUSE -> {
                                paused = true; engine.networkEngine.gamePaused = true; MapWorkerResponse()
                            }

                            MapWorkerCommand.RESUME -> {
                                paused = false; engine.networkEngine.gamePaused = false; MapWorkerResponse()
                            }

                            MapWorkerCommand.STOP -> {
                                running.set(false); MapWorkerResponse()
                            }

                            MapWorkerCommand.TRANSFER_POLL -> MapWorkerResponse(transfers = transfers?.poll().orEmpty())
                            MapWorkerCommand.TRANSFER_DELIVER -> {
                                require(engine.missionEngine.linkedWorldState?.outcomeFence == null) { "World is fenced for aggregate outcome" }
                                requireNotNull(transfers).receive(requireNotNull(request.transfer)); MapWorkerResponse()
                            }

                            MapWorkerCommand.TRANSFER_ACK -> {
                                transfers?.acknowledgeResponse(requireNotNull(request.transaction)); MapWorkerResponse()
                            }

                            MapWorkerCommand.OUTCOME_FENCE -> {
                                val candidate = requireNotNull(request.candidate); require(candidate.length in 1..128)
                                scheduleWorldOperation(
                                    LinkedWorldOperation(
                                        "fence-$candidate",
                                        LinkedWorldAction.FENCE_WORLD,
                                        config.world,
                                        candidate
                                    )
                                )
                                MapWorkerResponse(presence = presence())
                            }

                            MapWorkerCommand.OUTCOME_RELEASE -> {
                                val candidate = requireNotNull(request.candidate)
                                val queuedFence = engine.commandController.pendingCommands.any { command ->
                                    command.linkedWorldOperation?.let { it.action == LinkedWorldAction.FENCE_WORLD && it.transactionId == candidate } == true
                                }
                                // Cancellation follows the matching queued fence even when simulation is paused.
                                if (engine.missionEngine.linkedWorldState?.outcomeFence == candidate || request.outcome == null && queuedFence) {
                                    val outcome = request.outcome
                                    if (outcome != null) require(outcome.candidate == candidate && transfers?.hasPending() == false)
                                    scheduleWorldOperation(
                                        LinkedWorldOperation(
                                            "release-$candidate",
                                            if (outcome == null) LinkedWorldAction.RELEASE_WORLD else LinkedWorldAction.OUTCOME,
                                            config.world, candidate, outcome = outcome
                                        )
                                    )
                                }
                                MapWorkerResponse()
                            }
                        })
                } catch (failure: Exception) {
                    if (request.command == MapWorkerCommand.TRANSFER_DELIVER && failure is IOException) running.set(
                        false
                    )
                    result.complete(MapWorkerResponse(error = failure.message ?: "Worker command failed"))
                }
            })) return MapWorkerResponse(error = "Map worker is stopped or busy")
        return try {
            result.get(30, TimeUnit.SECONDS)
        } catch (_: Exception) {
            MapWorkerResponse(error = "Map worker command timed out")
        }
    }

    private fun status() = MapWorkerStatus(
        engine.currentTick, BaseUnit.getGlobalUnitList().size,
        config.mapPath, MapRoomRules.fromEngine(engine.networkEngine.roomSettings), paused, presence(),
        // Status carries summaries, while immutable payload bytes stay on the dedicated bounded channel.
        transfers?.journal?.all()?.takeLast(32)?.map {
            TransferSummary(
                it.envelope.transactionId,
                it.envelope.source,
                it.envelope.target,
                it.state,
                it.sourceIds,
                it.destinationIds,
                it.destinationConfirmed
            )
        }.orEmpty(),
        engine.networkEngine.lastSyncedTick, engine.networkEngine.stateChecksum.totalChecksum
    )

    private fun scheduleWorldOperation(operation: LinkedWorldOperation) {
        val command = engine.commandController.createCommand()
        command.team = PlayerTeam.k(config.players.first().teamId)
        command.isSystemAction = true
        command.linkedWorldOperation = operation
        engine.networkEngine.a(command)
    }

    private fun presence(): MapWorldPresence? {
        val state = engine.missionEngine?.linkedWorldState ?: return null
        val units = BaseUnit.bE.filter {
            !it.isDead && !it.isTransferUnavailable && !it.isExcludedFromDefeatCheck() && it.team?.teamId in config.players.filterNot { it.spectator }
                .map { it.teamId }
        }
        val pending = state.unresolved.toMutableList()
        if (transfers?.hasPending() == true && pending.isEmpty()) pending += "pending-operation"
        if (engine.commandController.pendingCommands.any { it.linkedWorldOperation != null }) pending += "scheduled-operation"
        return MapWorldPresence(
            config.world,
            ++presenceRevision,
            engine.currentTick,
            units.groupingBy { it.team.teamId }.eachCount(),
            units.filter { it.bJ() || it.canMove() }.groupingBy { it.team.teamId }.eachCount(),
            pending,
            state.outcomeFence,
            state.surrendered.toList()
        )
    }

    private fun snapshot(): MapWorkerSnapshot {
        val output = GameOutputStream()
        engine.gameSaver.writeSaveToStream(output)
        val bytes = output.toByteArray()
        output.close()
        val file = File.createTempFile("map-snapshot-", ".save", stateDirectory)
        file.outputStream().use { it.write(bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return MapWorkerSnapshot(file.absolutePath, hash, bytes.size.toLong(), engine.currentTick)
    }

    override fun close() {
        synchronized(lifecycle) {
            closed = true
            running.set(false)
            LockSupport.unpark(simulation)
        }
        if (started.get() && Thread.currentThread() !== simulation) {
            simulation.join(10_000)
            check(!simulation.isAlive) { "Map worker did not stop within 10 seconds" }
        }
    }
}
