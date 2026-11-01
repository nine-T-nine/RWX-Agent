package com.corrodinggames.rts.game.ai.custom.ai_automate

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.units.*
import com.corrodinggames.rts.game.units.land.Artillery
import com.corrodinggames.rts.gameFramework.*
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import kotlin.test.*

class AiAutomateTest {
    private lateinit var engine: GameEngine
    private lateinit var own: AIController
    private lateinit var enemy: AIController
    private lateinit var helper: AiAutomate
    private var nextId = 700000L
    private var previousTypes: ArrayList<UnitType>? = null

    // Real engine commands and waypoints, with terrain/rendering isolated from a loaded map.
    private class Gun : Artillery(true) {
        var visible = true
        var movement = UnitMovementType.LAND
        override fun getMovementType() = movement
        override fun d(team: com.corrodinggames.rts.game.PlayerTeam?) = visible
        override fun d(target: BaseUnit?) = visible
        override fun a(i: Int, target: BaseUnit?, ignoreRange: Boolean, extra: Boolean) = true
    }

    @BeforeTest
    fun setup() {
        engine = GameEngine.getInstance() ?: GameLogic()
        engine.networkEngine = NetworkEngine()
        engine.replayEngine = ReplayEngine()
        engine.commandController = CommandController()
        engine.formationEngine = FormationEngine()
        engine.hasLoadedLevel = true
        engine.isStopped = false
        engine.isPaused = false
        engine.gameSpeed = 1f
        engine.currentTick = 100
        previousTypes = UnitTypeEnum.ae
        UnitTypeEnum.ae = arrayListOf()
        own = AIController(0, false).apply { teamId = 0 }
        enemy = AIController(1, false).apply { teamId = 1 }
        helper = AiAutomate { _, _, _ -> true }
    }

    @AfterTest
    fun cleanup() {
        UnitTypeEnum.ae = previousTypes
        engine.commandController.clearAllCommands()
        engine.isPaused = false
        engine.replayEngine = ReplayEngine()
    }

    private fun gun(x: Float = 500f, y: Float = 500f, hostile: Boolean = false) = Gun().apply {
        objectId = nextId++
        team = if (hostile) enemy else own
        posX = x
        posY = y
        buildProgress = 1f
        movementLevels[0].rotation = 100f
    }

    private fun advance(vararg units: BaseUnit) {
        engine.currentTick += 15
        helper.update(engine, own, units.toList())
    }

    private fun execute() {
        // Commands are recorded by the normal command controller in production. Here execute
        // the actual Command implementation without opening a replay file or rendering a map.
        val batch = engine.commandController.pendingCommands.toList()
        engine.commandController.pendingCommands.clear()
        batch.forEach { it.executeCommand() }
    }

    /** 通过新的 auto-set 接口开关火炮规则（旧的 auto-combat 兼容入口已移除）。 */
    private fun artillery(on: Boolean) {
        val options = AiAutomateRuleOptions()
        options.rule = AiAutomateRuleId.ARTILLERY
        options.enabled = on
        helper.set(engine, own, options)
    }

    @Test
    fun `default is passive and explicit moves attacks and patrols are never taken over`() {
        val gun = gun()
        val target = gun(700f, hostile = true)
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        artillery(true)
        for (order in listOf(
            UnitCommand().apply { setMoveTarget(900f, 500f) },
            UnitCommand().apply { setAttackTarget(target) },
            UnitCommand().apply { setPatrolPoint(900f, 500f) },
            UnitCommand().apply { setFollowTargetUnit(target) },
        )) {
            gun.clearAllWaypoints()
            gun.appendWaypointCopy(order)
            advance(gun, target)
            assertTrue(engine.commandController.pendingCommands.isEmpty())
            assertTrue(order.isSameCommand(gun.currentWaypoint))
        }
    }

    @Test
    fun `reload retreats aiming stops and disabling restores the complete mission queue`() {
        val gun = gun()
        gun.appendAttackMoveWaypoint(1400f, 500f)
        gun.appendMoveWaypoint(1600f, 600f)
        val target = gun(700f, hostile = true)
        artillery(true)
        advance(gun, target)
        val retreat = engine.commandController.pendingCommands.first().unitCommand
        assertEquals(UnitCommandType.move, retreat.commandType)
        assertTrue(retreat.targetX < gun.posX)
        execute()
        assertEquals(3, gun.waypointCount)
        assertEquals(1400f, gun.getWaypointAt(1).targetX)
        assertEquals(1600f, gun.getWaypointAt(2).targetX)
        gun.movementLevels[0].rotation = 0f
        advance(gun, target)
        assertEquals(UnitCommandType.attackMove,
            engine.commandController.pendingCommands.first().unitCommand.commandType)
        execute()
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty(), "no duplicate hold orders")
        gun.movementLevels[0].rotation = 220f // native weapon has fired and entered reload
        advance(gun, target)
        assertEquals(UnitCommandType.move,
            engine.commandController.pendingCommands.first().unitCommand.commandType)
        execute()
        artillery(false)
        execute()
        assertEquals(2, gun.waypointCount)
        assertEquals(1400f, gun.currentWaypoint.targetX)
        assertEquals(1600f, gun.getWaypointAt(1).targetX)
    }

    @Test
    fun `hidden enemies allied units and non land artillery do not trigger assistance`() {
        val gun = gun()
        val target = gun(700f, hostile = true).apply { visible = false }
        artillery(true)
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        target.visible = true
        gun.team = enemy
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        gun.team = own
        gun.movement = UnitMovementType.AIR
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        gun.movement = UnitMovementType.LAND
        gun.attackMode = AttackMode.holdFire
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test
    fun `lost target restores guard position and manual movement wins over stale assistance`() {
        val gun = gun()
        gun.appendWaypoint().setGuardAt(500f, 500f)
        val target = gun(700f, hostile = true)
        artillery(true)
        advance(gun, target)
        execute()
        target.visible = false
        advance(gun, target)
        execute()
        assertEquals(UnitCommandType.guardAt, gun.currentWaypoint.commandType)
        assertEquals(500f, gun.currentWaypoint.targetX)
        target.visible = true
        advance(gun, target)
        helper.yieldToOrders(engine, listOf(gun))
        assertTrue(engine.commandController.pendingCommands.isEmpty(), "cancel unexecuted micro batch")
        gun.clearAllWaypoints()
        gun.appendMoveWaypoint(1200f, 900f)
        advance(gun, target)
        artillery(false)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        assertEquals(1200f, gun.currentWaypoint.targetX)
    }

    @Test
    fun `pause playback blocked retreat and clock reset do not generate movement`() {
        val gun = gun()
        val target = gun(700f, hostile = true)
        artillery(true)
        engine.isPaused = true
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        engine.isPaused = false
        engine.replayEngine = object : ReplayEngine() { override fun j() = true }
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        engine.replayEngine = ReplayEngine()
        helper = AiAutomate { _, _, _ -> false }
        artillery(true)
        advance(gun, target)
        assertEquals(UnitCommandType.attackMove,
            engine.commandController.pendingCommands.first().unitCommand.commandType)
        engine.commandController.pendingCommands.clear()
        engine.currentTick = 0
        helper.update(engine, own, listOf(gun, target))
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        assertFalse(helper.show(engine, own, AiAutomateRuleId.ARTILLERY).rules.single().enabled)
    }

    @Test
    fun `retreat avoids occupied points and stays within the original guard area`() {
        val gun = gun()
        gun.appendWaypoint().setGuardAt(500f, 500f)
        val target = gun(700f, hostile = true)
        val blocker = gun(435f).apply { movement = UnitMovementType.WATER }
        artillery(true)
        advance(gun, blocker, target)
        val point = engine.commandController.pendingCommands.first().unitCommand
        assertEquals(UnitCommandType.move, point.commandType)
        assertTrue(kotlin.math.hypot(point.targetX - blocker.posX, point.targetY - blocker.posY) >= 40)
        assertTrue(kotlin.math.hypot(point.targetX - 500f, point.targetY - 500f) <= 120)
        assertTrue(kotlin.math.hypot(point.targetX - target.posX, point.targetY - target.posY) > 200)
        execute()
        gun.posX = 385f // already close to the limit of the original guard anchor
        advance(gun, target)
        val next = engine.commandController.pendingCommands.first().unitCommand
        assertTrue(next.commandType != UnitCommandType.move
            || kotlin.math.hypot(next.targetX - 500f, next.targetY - 500f) <= 120)
    }

    @Test
    fun `GUI movement and queued player commands take priority over an active micro order`() {
        val gun = gun()
        gun.appendAttackMoveWaypoint(1400f, 500f)
        val target = gun(700f, hostile = true)
        artillery(true)
        advance(gun, target)
        execute()
        gun.clearAllWaypoints()
        gun.appendMoveWaypoint(1000f, 900f) // a GUI-issued order replaced the helper's waypoint
        advance(gun, target)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        artillery(false)
        assertTrue(engine.commandController.pendingCommands.isEmpty(), "do not restore a stale attack-move")
        gun.clearAllWaypoints()
        artillery(true)
        val manual = engine.commandController.newCommandForTeam(own).apply {
            addUnitToCommand(gun)
            setMoveTarget(1000f, 900f)
        }
        advance(gun, target)
        assertEquals(listOf(manual), engine.commandController.pendingCommands)
    }
}
