package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import com.corrodinggames.rts.gameFramework.network.NetworkConnection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.net.CoreUiNetworkCallbacks
import io.github.rwx.session.GameSession
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Optional server policy. Uses native proxy permissions, chat transport, spectators and packet 122. */
class RwxManagedRoom(private val network: NetworkEngine) : CoreUiNetworkCallbacks() {
    data class Request(val connection: NetworkConnection, val epoch: Long,
                       val command: RwxCliCommands.Command, val arguments: List<String>)
    private val sequence = AtomicLong()
    private val joined = ConcurrentHashMap<NetworkConnection, Long>()
    private val requests = ConcurrentLinkedQueue<Request>()
    @Volatile private var owner: NetworkConnection? = null
    @Volatile private var epoch = 0L
    private var returning = false
    private var wasRunning = false
    private var roundStartedNanos = 0L
    private var cleanupAfterNanos = 0L
    private var hadPlayers = false
    /** Connections that were registered while the room was in the lobby; they belong to the current round. */
    private val lobbyConnections = HashSet<NetworkConnection>()
    /** Names present when the current round started; re-registering clients keep their team. */
    private val roundPlayers = HashSet<String>()
    /** Name of the connection whose start request opened the current round. */
    private var startingPlayer = ""

    val ownerName: String get() = owner?.player?.teamName.orEmpty()

    // This existing callback is serialized into native server-info packet 106.
    override fun b(connection: NetworkConnection?): Boolean = connection != null && connection === owner

    override fun a(team: PlayerTeam) {
        // Teams that were present when the round started keep playing (their team is created right after the start
        // event). Any identity that appears later joins as a spectator.
        val name = team.teamName.orEmpty()
        val units = team.getUnitCount(true, false)
        if (network.gameHasBeenStarted && !team.isTeamSpectator && units == 0 && name !in roundPlayers) {
            GameEngine.log("RWX_ROOM_SPECTATE $name units=$units")
            network.a(team, -3)
        }
    }

    override fun c(connection: NetworkConnection?, name: String?, previousName: String?) {
        if (connection == null) return
        joined.putIfAbsent(connection, sequence.incrementAndGet())
    }

    override fun onStartGameEvent() {
        epoch++
        roundStartedNanos = System.nanoTime()
        roundPlayers.clear()
        lobbyConnections.forEach { connection ->
            connection.player?.teamName?.takeIf { it.isNotBlank() }?.let { roundPlayers.add(it) }
            runCatching { connection.getPlayerDisplayName() }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { roundPlayers.add(it) }
        }
        startingPlayer.takeIf { it.isNotBlank() }?.let { roundPlayers.add(it) }
        GameEngine.log("RWX_ROOM_START players=$roundPlayers starting=$startingPlayer")
        super.onStartGameEvent()
    }

    /** Receive workers only enqueue. All authorization and room edits run on their original owners. */
    override fun a(connection: NetworkConnection?, name: String?, message: String?): Boolean {
        val text = message?.removePrefix("-qc ")?.trim() ?: return true
        val verb = text.substringBefore(' ').removePrefix("-").lowercase()
        if (verb !in NATIVE_COMMANDS && verb != "rwx-room") return true
        if (connection == null || connection !== owner || network.gameHasBeenStarted) {
            network.a("Room management is available only to the lobby manager", connection)
            return false
        }
        runCatching {
            require(text.length <= 8192 && requests.size < 64) { "Too many room requests" }
            val args = if (verb == "rwx-room") decode(text.substringAfter(' ')) else nativeArguments(text)
            val command = RwxCliCommands.find(args.firstOrNull().orEmpty())
            require(command in ROOM_COMMANDS) { "Unsupported room command" }
            requests.add(Request(connection, epoch, command!!, args.drop(1)))
        }.onFailure { network.a("Room command rejected: ${it.message}", connection) }
        return false
    }

    /** Called on the simulation owner, including in the lobby. Does not elect while a game runs. */
    fun tick() = synchronized(network) { tickLocked() }

    // The native registration handler holds this same monitor while registering teams.
    // In particular, never sweep a team between its constructor and connection assignment.
    private fun tickLocked() {
        if (!network.isServer || !network.networkGameActive) return
        val running = network.gameHasBeenStarted
        if (running && !wasRunning) {
            // currentTick is not reset synchronously with the start packet; use wall time for the setup grace period.
            wasRunning = true
            roundStartedNanos = System.nanoTime()
        }
        val live = network.sendQueue.filter { it.isConnected && it.allowLargeIncomingPackets &&
            !it.isRelayServer && it.player != null && !it.player.isTeamSpectator }
        // allowLargeIncomingPackets is a map-transfer flag and is cleared once a client has the map; it cannot be used
        // to decide whether a player is still in the running round.
        val connected = network.sendQueue.filter {
            it.isConnected && !it.isRelayServer && it.player != null
        }
        live.forEach { joined.putIfAbsent(it, sequence.incrementAndGet()) }
        if (!running) {
            returning = false
            // Remember who is in the lobby so the next round can tell existing players from mid-round arrivals.
            lobbyConnections.clear()
            lobbyConnections.addAll(network.sendQueue.filter { it.isConnected && !it.isRelayServer })
            if (wasRunning) {
                wasRunning = false
                // Let clients consume native RETURN before removing reconnectable team slots.
                cleanupAfterNanos = System.nanoTime() + 1_000_000_000L
            }
            joined.keys.removeIf { it !in live }
            if (live.isEmpty() && hadPlayers) {
                // Everyone left the lobby: the next session starts from a clean room instead of inheriting the last
                // session's income/fog/AI/credit settings and leftover AI slots.
                resetRoomForNewSession()
            }
            hadPlayers = live.isNotEmpty()
            val next = selectManager(owner, live, joined, false)
            // Keep a live manager even if a later client reconnects with an earlier team id.
            if (owner !in live && owner !== next) {
                owner = next
                epoch++
                network.broadcastServerInfoToLargePacketConnections()
                network.markPlayerUpdatePending()
                GameEngine.log("RWX_ROOM_MANAGER name=$ownerName")
            }
            // Disconnected humans retain slots during a game for reconnection; release them now.
            val teams = live.map { it.player }.toSet()
            if (System.nanoTime() >= cleanupAfterNanos) {
                val stale = PlayerTeam.getTeams().filter { !it.isTeamSpectator && it !== network.localPlayerTeam && it !in teams }
                stale.forEach { it.removeFromTeamRegistry() }
                if (stale.isNotEmpty()) network.markPlayerUpdatePending()
            }
        } else if (!returning && System.nanoTime() - roundStartedNanos > 4_000_000_000L && !humanPlayerActive()) {
            // Teams, units and the client status handshake settle within the first seconds after START; reading
            // elimination state earlier turns every new round into an immediate "no active player" return.
            returning = true
            epoch++
            val detail = PlayerTeam.getTeams().joinToString(" ") { team ->
                "${team.teamName}:color=${team.teamColorId},spec=${team.isTeamSpectator}," +
                    "wipe=${team.isTeamWipedOut},units=${team.getUnitCount(true, false)}"
            }
            GameEngine.log("RWX_ROOM_RETURN reason=no-active-client-player [$detail]")
            network.sendReturnToBattleroomEvent(null)
        }
    }

    /**
     * True while a connected human still owns a live team in the round. The per-connection team pointer goes stale when
     * a player switches spawn slots or re-registers (it keeps pointing at the old spectator team), and every networked
     * team reports isTeamControlledByAI, so match player names from the live connections against team names.
     */
    private fun humanPlayerActive(): Boolean {
        val names = network.sendQueue.filter { it.isConnected }.mapNotNull { connection ->
            connection.player?.teamName?.takeIf { it.isNotBlank() }
                ?: runCatching { connection.getPlayerDisplayName() }.getOrNull()?.takeIf { it.isNotBlank() }
        }.toSet()
        if (names.isEmpty()) return false
        return PlayerTeam.getTeams().any { team ->
            (team.teamName ?: "") in names && !team.isSpectatorTeamColor() && !team.isTeamWipedOut &&
                !team.isTeamVictory && !team.isTeamDefeatedTech
        }
    }

    /**
     * Restores the room to engine defaults when the last player leaves the lobby. The map stays selected (the host
     * chooses it per session), everything else -- income, fog, credits, starting units, AI difficulty, locks and the
     * leftover AI slots -- goes back to defaults so the next group starts from a clean room.
     */
    private fun resetRoomForNewSession() {
        // AI slots are flagged isTeamSpectator by the engine (isAi = isTeamSpectator || isTeamControlledByAI), so
        // select slots by colour instead: every real slot has a team colour, the server's own observer team does not.
        PlayerTeam.getTeams()
            .filter { it !== network.localPlayerTeam && !it.isSpectatorTeamColor() }
            .forEach { it.removeFromTeamRegistry() }
        val settings = network.getEditableRoomSettings() ?: return
        val defaults = GameRoomSettings()
        settings.startingCredits = defaults.startingCredits
        settings.fogMode = defaults.fogMode
        settings.revealedMap = defaults.revealedMap
        settings.aiDifficulty = defaults.aiDifficulty
        settings.startingUnits = defaults.startingUnits
        settings.incomeMultiplier = defaults.incomeMultiplier
        settings.noNukes = defaults.noNukes
        settings.sharedControl = defaults.sharedControl
        settings.teamLock = defaults.teamLock
        settings.fixedAllyTeams = defaults.fixedAllyTeams
        settings.allowSpectators = defaults.allowSpectators
        settings.roomLock = defaults.roomLock
        settings.randomSeed = defaults.randomSeed
        network.markPlayerUpdatePending()
        network.a(settings)
        GameEngine.log("RWX_ROOM_RESET settings=defaults")
    }

    fun poll(): Request? = requests.poll()
    fun mayExecute(request: Request): Boolean {
        val allowed = !network.gameHasBeenStarted && request.epoch == epoch &&
            request.connection === owner && request.connection.isConnected
        if (allowed && request.command == RwxCliCommands.Command.BATTLE_ROOM_START) {
            startingPlayer = request.connection.player?.teamName
                ?: runCatching { request.connection.getPlayerDisplayName() }.getOrDefault("")
        }
        return allowed
    }

    companion object {
        internal fun selectManager(current: NetworkConnection?, live: List<NetworkConnection>,
                                   joined: Map<NetworkConnection, Long>, gameStarted: Boolean): NetworkConnection? =
            if (gameStarted || current in live) current else live.minByOrNull { joined[it] ?: Long.MAX_VALUE }
        private val ROOM_COMMANDS = setOf(RwxCliCommands.Command.BATTLE_ROOM_SELECT_MAP,
            RwxCliCommands.Command.BATTLE_ROOM_SETTINGS, RwxCliCommands.Command.BATTLE_ROOM_SET_PLAYER,
            RwxCliCommands.Command.BATTLE_ROOM_ADD_AI, RwxCliCommands.Command.BATTLE_ROOM_START)
        private val NATIVE_COMMANDS = setOf("start", "map", "addai", "kick", "move", "team", "ai",
            "fog", "credits", "income", "startingunits", "revealedmap", "nukes", "sharedcontrol", "roomlock")

        @JvmStatic fun forwardIfProxy(session: GameSession, command: RwxCliCommands.Command,
                                      args: List<String>): String? {
            if (command !in ROOM_COMMANDS || command == RwxCliCommands.Command.BATTLE_ROOM_SETTINGS && args.isEmpty()) return null
            return session.executeSessionCommand { engine ->
                val network = engine.networkEngine
                if (!network.isProxyController || network.isServer) return@executeSessionCommand null
                check(!network.gameHasBeenStarted) { "Room settings cannot change during a game" }
                val normalized = args.toMutableList()
                val playerIndex = normalized.indexOf("--player") + 1
                if (playerIndex > 0 && normalized.getOrNull(playerIndex) == "local")
                    normalized[playerIndex] = (network.localPlayerTeam.teamId + 1).toString()
                val wire = (listOf(command.commandName()) + normalized).joinToString(",") {
                    Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                network.k("-rwx-room $wire")
                "Room request submitted to game server; query the room to confirm the result"
            }
        }

        private fun decode(value: String): List<String> = value.split(',').map {
            String(Base64.getDecoder().decode(it), Charsets.UTF_8) }

        internal fun nativeArguments(text: String): List<String> {
            val parts = RwxCommandLine.split(text)
            val verb = parts.first().removePrefix("-").lowercase()
            val args = parts.drop(1)
            val value = args.firstOrNull() ?: ""
            fun settings(key: String, setting: String = value) = listOf("rwx-battleroom-settings", key, setting)
            return when (verb) {
                "start" -> listOf("rwx-battleroom-start")
                "map" -> listOf("rwx-battleroom-select-map", "--name", args.joinToString(" "))
                "addai" -> listOf("rwx-battleroom-add-ai")
                "kick" -> listOf("rwx-battleroom-set-player", "--player", value, "--kick")
                "move" -> listOf("rwx-battleroom-set-player", "--player", value, "--move",
                    if (args.getOrNull(1) == "-2") "spec" else args.getOrNull(1).orEmpty())
                "team" -> listOf("rwx-battleroom-set-player", "--player", value, "--switch", args.getOrNull(1).orEmpty())
                "ai" -> settings("--aiDifficulty")
                "fog" -> settings("--fog", if (value == "off") "none" else value)
                "credits" -> settings("--credits")
                "income" -> settings("--income")
                "startingunits" -> settings("--startUnits")
                "revealedmap" -> settings("--revealedMap", (!value.toBooleanStrict()).toString())
                "nukes" -> settings("--noNukes", (!value.toBooleanStrict()).toString())
                "sharedcontrol" -> settings("--sharedControl")
                "roomlock" -> settings("--roomLock")
                else -> error("Unsupported room command: $verb")
            }
        }
    }
}
