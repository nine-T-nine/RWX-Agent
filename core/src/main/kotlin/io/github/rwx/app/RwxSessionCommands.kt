package io.github.rwx.app

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxManagedRoom
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalBattleRoomCommands
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.*
import io.github.rwx.net.CoreUiNetworkCallbacks
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.ScreenNavigator
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Session commands wrap the original room connector and never implement a second wire protocol. */
internal class RwxSessionCommands(
    private val session: GameSession,
    private val room: BattleRoomController,
    private val launch: BattleRoomLaunchController,
    private val navigator: ScreenNavigator,
    private val quit: () -> Unit,
) {
    private var address: String? = null
    private var roomQuery: String? = null
    private var password: String? = null
    private var deadline = 0L
    private var nextPoll = 0L
    private var autoStartPlayers = 0
    private var autoStartIssued = false
    private var managedRoom: RwxManagedRoom? = null
    @Volatile private var phase = "disconnected"
    @Volatile private var failure: String? = null
    @Volatile private var generation = 0

    fun execute(command: Command, args: List<String>): String = when (command) {
        Command.CONNECT -> connect(args)
        Command.HOST -> host(args)
        Command.CONNECTION_STATUS -> { require(args.isEmpty()); status() }
        Command.DISCONNECT -> { require(args.isEmpty()); disconnect(); "Game connection closed; RWX process and CLI remain running" }
        Command.SERVER_LIST -> servers(args)
        Command.EXIT -> {
            require(args.isEmpty())
            CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS).execute {
                FrontendScope.launch { quit() }
            }
            "RWX process exit requested"
        }
        else -> error("Unsupported session command: ${command.commandName()}")
    }

    /** UI owner polls only while a timed connection is pending. Network I/O stays on native workers. */
    fun drive() {
        managedRoom?.let { policy ->
            session.executeSessionCommand { policy.tick() }
            while (true) {
                val request = policy.poll() ?: break
                if (!session.executeSessionCommand { policy.mayExecute(request) }) continue
                val result = runCatching {
                    RwxLocalBattleRoomCommands(session, room, launch, navigator).execute(request.command, request.arguments)
                }.getOrElse { "error: ${it.message}" }
                session.executeSessionCommand { it.networkEngine.a(result, request.connection) }
            }
        }
        if (phase == "hosting" && autoStartPlayers > 0 && !autoStartIssued) {
            val count = session.executeSessionCommand { engine ->
                if (engine.networkEngine.gameHasBeenStarted) 0 else
                    com.corrodinggames.rts.game.PlayerTeam.getTeams().count {
                        it.teamId >= 0 && !it.isTeamSpectator && !it.isSpectatorTeamColor()
                    }
            }
            if (count >= autoStartPlayers) {
                autoStartIssued = true
                launch.startBattleRoomGame()
            }
        }
        if (deadline == 0L || System.currentTimeMillis() < nextPoll) return
        nextPoll = System.currentTimeMillis() + 250
        status()
    }

    private fun options(args: List<String>, allowed: Set<String>): Map<String, String> {
        require(args.size % 2 == 0) { "Every option requires a value" }
        val out = linkedMapOf<String, String>()
        args.chunked(2).forEach { (key, value) ->
            require(key in allowed) { "Unknown option: $key" }
            require(out.put(key, value) == null) { "Duplicate option: $key" }
        }
        return out
    }

    private fun secret(opts: Map<String, String>): String? {
        require(!(opts.containsKey("--password") && opts.containsKey("--password-env"))) {
            "Use --password or --password-env, not both"
        }
        return opts["--password-env"]?.let { System.getenv(it) ?: error("Password environment variable is not set") }
            ?: opts["--password"]
    }

    private fun connect(args: List<String>): String {
        val supplied = args.firstOrNull()?.takeUnless { it.startsWith("--") }
        val opts = options(if (supplied == null) args else args.drop(1), setOf(
            "--name", "--password", "--password-env", "--room", "--timeout-ms", "--reconnect"))
        require(opts["--reconnect"] == null || opts["--reconnect"] == "true") { "--reconnect accepts true" }
        val destination = supplied ?: address?.takeIf { opts["--reconnect"] == "true" }
            ?: error("Usage: rwx-connect <host[:port]|RELAY-CN|connect-descriptor> [options]")
        val timeout = opts["--timeout-ms"]?.let { it.toLongOrNull() ?: error("--timeout-ms must be an integer") } ?: 20000L
        require(timeout in 1000..120000) { "--timeout-ms must be 1000..120000" }
        require(!destination.any { it == '\n' || it == '\r' }) { "Invalid server address" }
        val reconnect = opts["--reconnect"] == "true"
        val query = opts["--room"] ?: roomQuery.takeIf { reconnect && destination == address }
        require(query == null || query.isNotBlank() && query.none { it == '\n' || it == '\r' }) {
            "--room must be a non-empty room code"
        }
        require(query == null || !destination.startsWith("get|")) {
            "A master connect descriptor already identifies a room; use host:port with --room"
        }
        session.executeSessionCommand { engine ->
            require(!engine.networkEngine.isServer || !engine.networkEngine.networkGameActive) {
                "Hosting a room; use rwx-disconnect before joining another server"
            }
            require(reconnect || !engine.networkEngine.networkGameActive) {
                "Already connected; use rwx-connect --reconnect true or rwx-disconnect"
            }
        }
        val name = opts["--name"]
        if (name != null) {
            require(name.trim().length in 2..20) { "--name must contain 2..20 characters" }
            check(session.updateMultiplayerPlayerName(name)) { "Unable to set player name" }
        }
        if (!reconnect || opts.containsKey("--password") || opts.containsKey("--password-env")) password = secret(opts)
        if (reconnect) session.executeSessionCommand { it.networkEngine.disconnectNetworking("reconnecting") }
        session.cancelBattleRoomJoin()
        val attempt = ++generation
        failure = null
        deadline = System.currentTimeMillis() + timeout
        phase = "connecting"
        address = destination
        roomQuery = query
        var prompts = 0
        session.executeSessionCommand { engine ->
            engine.networkEngine.callbacks = object : CoreUiNetworkCallbacks() {
                override fun onPasswordPrompt(handler: PasswordHandler): Boolean {
                    if (generation != attempt) return true
                    FrontendScope.launch {
                        if (generation != attempt) return@launch
                        if (password == null || prompts++ > 0) {
                            failure = if (password == null) "Password or relay input required; supply --password/--room" else "Password rejected"
                            phase = "error"
                            session.executeSessionCommand { handler.cancelPasswordEntry() }
                        } else session.executeSessionCommand { handler.submitPassword(password) }
                    }
                    return true
                }
                override fun onConnectionRejected(reason: String?) {
                    if (generation == attempt && !reason.isNullOrBlank()) failure = "Kicked: $reason"
                }
            }
            engine.networkEngine.roomPassword = password
        }
        fun join(descriptor: String) {
            if (generation != attempt) return
            require(query == null || !descriptor.startsWith("get|")) {
                "A master connect descriptor already identifies a room; use host:port with --room"
            }
            val target = if (query == null) descriptor else descriptor + "/" + query
            check(session.joinBattleRoom(target, null, false)) { session.latestBattleRoomJoinError ?: "Join request rejected" }
            room.returnToRoom()
            navigator.navigateTo(AppScreen.BattleRoom)
        }
        if (destination.equals("RELAY-CN", true)) {
            phase = "resolving"
            MasterServerClient.loadServerListAsync {
                FrontendScope.launch {
                    if (generation != attempt) return@launch
                    val match = synchronized(MasterServerClient.serverListLock) {
                        GameEngine.getInstance().networkEngine.discoveredServerList.firstOrNull {
                            (it.createdBy.orEmpty() + " " + it.serverId.orEmpty()).contains("RELAY-CN", true)
                        }
                    }
                    if (match == null) {
                        failure = "RELAY-CN was not found in the current master-server list; use an explicit relay host:port/room"
                        phase = "error"; deadline = 0L
                    } else runCatching { phase = "connecting"; join(
                        if (match.isPortOpen) "${match.publicHost}:${match.port}" else match.connectDescriptor) }
                        .onFailure { failure = it.message; phase = "error"; deadline = 0L }
                }
            }
        } else join(destination)
        return "Connection requested: $destination\nUse rwx-connection-status to verify registration; a connected socket alone is not a joined room"
    }

    private fun status(): String = session.executeSessionCommand { engine ->
        val network = engine.networkEngine
        val connected = network.activeServerConnection?.isConnected == true
        val registered = network.gameSetupReceived && network.localPlayerTeam != null && connected
        if (network.networkGameActive && network.isServer) { phase = "hosting"; deadline = 0L }
        else if (network.networkGameActive && registered) {
            phase = if (network.gameHasBeenStarted && engine.hasLoadedLevel) "in-game" else "joined"
            deadline = 0L
        } else if (deadline != 0L) {
            val joinError = session.latestBattleRoomJoinError
            if (joinError != null || phase == "error") {
                failure = failure ?: joinError; phase = "error"; deadline = 0L
                session.cancelBattleRoomJoin(); network.disconnectNetworking("join failed")
            } else if (System.currentTimeMillis() >= deadline) {
                failure = "Connection/registration timed out"
                phase = "error"; deadline = 0L
                ++generation; session.cancelBattleRoomJoin(); network.disconnectNetworking("join timeout")
            } else if (!session.isJoiningBattleRoom && phase != "resolving" && !connected) {
                failure = failure ?: "Server disconnected during handshake"
                phase = "error"; deadline = 0L
            }
        } else if (!connected && phase in setOf("joined", "in-game")) {
            phase = "disconnected"
            failure = failure ?: "Game server connection closed"
        }
        "state: $phase\naddress: ${address.orEmpty()}\nrole: ${if (network.isServer) "host" else "client"}" +
            "\nroomAdmin: ${network.isProxyController}\nroomManager: ${managedRoom?.ownerName.orEmpty()}" +
            "\nspectator: ${network.localPlayerTeam?.isSpectatorTeamColor() ?: false}" +
            "\ngamePort: ${network.networkPort}\nregistered: $registered" +
            "\nteam: ${network.localPlayerTeam?.teamId ?: "none"}\nstarted: ${network.gameHasBeenStarted}" +
            "\nerror: ${failure.orEmpty()}"
    }

    private fun host(args: List<String>): String {
        val opts = options(args, setOf("--port", "--name", "--map", "--password", "--password-env", "--public", "--spectator", "--auto-start-players", "--managed-room"))
        val port = opts["--port"]?.let { it.toIntOrNull() ?: error("--port must be an integer") } ?: 5123
        require(port in 1..65535) { "--port must be 1..65535" }
        val minimum = opts["--auto-start-players"]?.let { it.toIntOrNull() ?: error("--auto-start-players must be an integer") } ?: 0
        require(minimum == 0 || minimum in 2..100) { "--auto-start-players must be 0 or 2..100" }
        for (key in listOf("--public", "--spectator", "--managed-room")) require(opts[key] == null || opts[key] in setOf("true", "false")) { "$key must be true|false" }
        require(opts["--managed-room"] != "true" || minimum == 0) { "Managed rooms are started by their lobby manager; omit --auto-start-players" }
        session.executeSessionCommand { engine ->
            check(!engine.networkEngine.networkGameActive && !engine.hasLoadedLevel) { "Use rwx-disconnect before hosting a new room" }
            engine.settingsEngine.networkPort = port
        }
        opts["--name"]?.let { require(it.trim().length in 2..20); session.updateMultiplayerPlayerName(it) }
        val maps = room.enabledMaps()
        val wanted = opts["--map"]
        fun normalized(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
        val map = if (wanted == null) room.selectCommandSkirmishMap() else maps.singleOrNull {
            normalized(it.displayName) == normalized(wanted) || it.mapAssetPath == wanted
        }
        require(map != null) { "Map not found or ambiguous: $wanted; use rwx-battleroom-show --enabledMap" }
        check(session.hostBattleRoom(map.mapAssetPath, false, opts["--public"] == "true", secret(opts), false, false)) {
            "Unable to host on TCP/UDP port $port; check whether the port is already occupied"
        }
        room.prepareHostRoom(map)
        navigator.navigateTo(AppScreen.BattleRoom)
        if (opts["--spectator"] == "true" || opts["--managed-room"] == "true") check(session.configureBattleRoomPlayer("local", -3, null, null, null))
        managedRoom = if (opts["--managed-room"] == "true") session.executeSessionCommand {
            val network = it.networkEngine
            network.allowJoinInProgress = true
            network.roomSettings.allowSpectators = true
            RwxManagedRoom(network).also { policy -> network.callbacks = policy }
        } else null
        phase = "hosting"; failure = null; deadline = 0L
        autoStartPlayers = minimum; autoStartIssued = false
        return "Game server listening: TCP/UDP $port\nCLI remains loopback-only\n" + status()
    }

    private fun disconnect() {
        managedRoom = null
        ++generation; deadline = 0L
        session.cancelBattleRoomJoin()
        session.disconnectRunningMultiplayer()
        session.discardRunningGame()
        session.leaveBattleRoom()
        navigator.navigateTo(AppScreen.MainMenu)
        phase = "disconnected"; failure = null
    }

    private fun servers(args: List<String>): String {
        require(args.isEmpty() || args == listOf("--refresh", "true")) { "Usage: rwx-server-list [--refresh true]" }
        session.executeSessionCommand { }
        MasterServerClient.loadServerListAsync { }
        val network = GameEngine.getInstance().networkEngine
        return synchronized(MasterServerClient.serverListLock) {
            "Master-server refresh requested; current cached list:\n" + network.discoveredServerList.joinToString("\n") {
                "${it.createdBy} | ${it.connectDescriptor} | ${it.gameState} | version=${it.gameVersionString}"
            } + "\nmasterError: ${network.masterServerErrorMessage.orEmpty()}"
        }
    }
}
