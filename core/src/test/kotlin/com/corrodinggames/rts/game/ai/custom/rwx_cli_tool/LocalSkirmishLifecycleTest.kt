package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.LocalSkirmishLifecycle
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.NetworkConnection
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.*

class LocalSkirmishLifecycleTest {
    private lateinit var engine: GameEngine
    private var previousTypes: ArrayList<UnitType>? = null

    @BeforeTest fun setup() {
        engine = GameEngine.getInstance() ?: GameLogic()
        engine.networkEngine = NetworkEngine()
        engine.replayEngine = ReplayEngine()
        engine.commandController = CommandController()
        engine.hasLoadedLevel = true
        engine.isMenuBackgroundMap = false
        engine.isStopped = false
        engine.currentTick = 100
        previousTypes = UnitTypeEnum.ae
        UnitTypeEnum.ae = arrayListOf()
        PlayerTeam.resetTeamRegistry()
    }

    @AfterTest fun cleanup() {
        UnitTypeEnum.ae = previousTypes
        engine.commandController.clearAllCommands()
        PlayerTeam.resetTeamRegistry()
    }

    @Test fun `multiplayer step is rejected without triggering local stall recovery or pause`() {
        val previousSpeed = engine.gameSpeed
        val previousPaused = engine.isPaused
        engine.networkEngine.networkGameActive = true
        engine.gameSpeed = 1f
        engine.isPaused = false
        try {
            val failure = assertFailsWith<IllegalStateException> {
                RwxEngineLocalGameService().step(1.0, 1000)
            }
            assertTrue(failure.message.orEmpty().contains("local single-player"))
            assertEquals(1f, engine.gameSpeed)
            assertFalse(engine.isPaused)
            assertEquals(100, engine.currentTick)
        } finally {
            engine.networkEngine.networkGameActive = false
            engine.gameSpeed = previousSpeed
            engine.isPaused = previousPaused
        }
    }

    @Test fun `local disconnect flushes the native packet and closes a silent peers socket`() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { client ->
                listener.accept().use { peer ->
                    peer.soTimeout = 3000
                    val connection = NetworkConnection(engine.networkEngine, client)
                    connection.startWorkers()
                    connection.sendPacket("test disconnect")
                    val input = DataInputStream(peer.getInputStream())
                    assertTrue(input.readInt() > 0)
                    assertEquals(111, input.readInt())
                    assertEquals("test disconnect", input.readUTF())
                    assertEquals(-1, input.read(), "Local disconnect must not wait for the peer to close first")
                }
            }
        }
    }

    @Test fun `local surrender queues a replayable system action and forfeits the team`() {
        val player = GameTeam(0)
        engine.playerTeam = player
        engine.networkEngine.localPlayerTeam = player
        engine.networkEngine.singleplayerServer = true
        engine.networkEngine.networkGameActive = false
        // The completed local room has no active network chat command processing.
        engine.networkEngine.gameHasBeenStarted = false
        engine.networkEngine.requestSurrender()
        val command = engine.commandController.pendingCommands.single()
        assertSame(player, command.team)
        assertTrue(command.isSystemAction)
        assertEquals(100, command.systemActionType)
        assertFalse(player.isTeamVictory, "Surrender must pass through the replay command queue")
        command.executeCommand()
        assertTrue(player.isTeamVictory)
        val outcome = RwxEngineLocalGameService::class.java.getDeclaredMethod("outcome", PlayerTeam::class.java)
            .apply { isAccessible = true }
        assertEquals("defeat", outcome.invoke(null, player), "Legacy surrender flag must not report victory")
        engine.commandController.clearAllCommands()
        engine.networkEngine.requestSurrender()
        assertTrue(engine.commandController.pendingCommands.isEmpty(), "Already forfeited is a no-op")
    }

    @Test fun `restart restores exact roster settings and overrides without battle state`() {
        val network = engine.networkEngine
        network.singleplayerServer = true
        network.roomSettings.apply {
            incomeMultiplier = 2.5f
            fogMode = 1
            startingCredits = 4
            startingUnits = 3
            aiDifficulty = 3
            noNukes = true
            sharedControl = true
            teamLock = true
        }
        val player = GameTeam(0).apply {
            teamName = "Human"; teamColorId = 2; playerColorOverride = 4
            isTeamVictory = true; credits = 9.0
        }
        val opponent = AIController(3).apply {
            teamName = "Custom opponent"; teamColorId = 5; teamPingTime = 3
            teamAIDifficultyOverride = 2; startingUnitsOverride = 4
            playerColorOverride = 7; isTeamWipedOut = true; isTeamVictory = true
        }
        engine.playerTeam = player
        network.localPlayerTeam = player
        val oldSettings = network.roomSettings
        LocalSkirmishLifecycle.prepareRestart(engine)
        val fresh = assertNotNull(PlayerTeam.k(0))
        val ai = assertNotNull(PlayerTeam.k(3))
        assertNotSame(player, fresh)
        assertNotSame(opponent, ai)
        assertTrue(ai is AIController)
        assertSame(fresh, engine.playerTeam)
        assertSame(fresh, network.localPlayerTeam)
        assertNull(PlayerTeam.k(1), "Do not add default AI opponents to empty slots")
        assertNull(PlayerTeam.k(2))
        assertEquals("Human", fresh.teamName)
        assertEquals(2, fresh.teamColorId)
        assertEquals(4, fresh.playerColorOverride)
        assertEquals("Custom opponent", ai.teamName)
        assertEquals(5, ai.teamColorId)
        assertEquals(2, ai.teamAIDifficultyOverride)
        assertEquals(4, ai.startingUnitsOverride)
        assertEquals(7, ai.playerColorOverride)
        assertFalse(fresh.isTeamVictory)
        assertFalse(ai.isTeamVictory)
        assertFalse(ai.isTeamWipedOut)
        assertNotSame(oldSettings, network.roomSettings)
        assertEquals(2.5f, network.roomSettings.incomeMultiplier)
        assertEquals(1, network.roomSettings.fogMode)
        assertEquals(4, network.roomSettings.startingCredits)
        assertEquals(3, network.roomSettings.startingUnits)
        assertEquals(3, network.roomSettings.aiDifficulty)
        assertTrue(network.roomSettings.noNukes)
        assertTrue(network.roomSettings.sharedControl)
        assertTrue(network.roomSettings.teamLock)
        assertTrue(network.networkGameActive, "Reload must take the room setup branch")
        network.networkGameActive = false
        LocalSkirmishLifecycle.prepareRestart(engine)
        assertEquals(2.5f, network.roomSettings.incomeMultiplier)
        assertNull(PlayerTeam.k(1))
        assertEquals(2, (0 until PlayerTeam.TEAM_NEUTRAL).count { PlayerTeam.k(it) != null })
    }

    @Test fun `local surrender typed as chat also reaches the system command`() {
        val player = GameTeam(0)
        engine.playerTeam = player
        engine.networkEngine.localPlayerTeam = player
        engine.networkEngine.sendChatMessage("-surrender")
        assertEquals(100, engine.commandController.pendingCommands.single().systemActionType)
    }
}
