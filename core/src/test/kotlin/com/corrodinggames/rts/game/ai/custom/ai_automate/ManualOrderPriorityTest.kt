package com.corrodinggames.rts.game.ai.custom.ai_automate

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxEngineLocalGameService
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes
import com.corrodinggames.rts.game.units.*
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.actions.QueueUnitAction
import com.corrodinggames.rts.game.units.buildings.LandFactory
import com.corrodinggames.rts.game.units.land.Artillery
import com.corrodinggames.rts.gameFramework.*
import com.corrodinggames.rts.gameFramework.graphics.Texture
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import java.lang.reflect.InvocationTargetException
import kotlin.test.*

/** Uses the actual CLI service, engine commands, queues and units without rendering a map. */
class ManualOrderPriorityTest {
    private lateinit var engine: GameEngine
    private lateinit var own: GameTeam
    private lateinit var service: RwxEngineLocalGameService
    private lateinit var helper: AiAutomate
    private var previousTypes: ArrayList<UnitType>? = null
    private var previousUnits = emptyList<BaseUnit>()
    private var nextId = 980000L

    private class Factory : LandFactory(true) {
        override fun b(texture: Texture?) {}
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> =
            arrayListOf(QueueUnitAction(UnitTypeEnum.tank))
    }

    @BeforeTest fun setup() {
        engine = GameEngine.getInstance() ?: GameLogic()
        engine.networkEngine = NetworkEngine()
        engine.replayEngine = ReplayEngine()
        engine.commandController = CommandController()
        engine.formationEngine = FormationEngine()
        engine.hasLoadedLevel = true
        engine.isStopped = false
        engine.isMenuBackgroundMap = false
        engine.isPaused = true
        engine.gameSpeed = 1f
        engine.currentTick = 100
        engine.gameTimeMillis = 1000
        engine.tileMap = null
        previousTypes = UnitTypeEnum.ae
        UnitTypeEnum.ae = arrayListOf()
        previousUnits = BaseUnit.bE.toList()
        BaseUnit.bE.clear()
        own = GameTeam(0).apply { credits = 100000.0 }
        engine.playerTeam = own
        service = RwxEngineLocalGameService()
        helper = RwxEngineLocalGameService::class.java.getDeclaredField("automate")
            .apply { isAccessible = true }.get(service) as AiAutomate
    }

    @AfterTest fun cleanup() {
        engine.commandController.clearAllCommands()
        engine.isPaused = false
        UnitTypeEnum.ae = previousTypes
        BaseUnit.bE.clear()
        previousUnits.forEach { BaseUnit.bE.add(it) }
        PlayerTeam.resetTeamRegistry()
    }

    private fun <T : OrderableUnit> spawn(unit: T) = unit.apply {
        objectId = nextId++; team = own; posX = 420f; posY = 500f; buildProgress = 1f
        BaseUnit.bE.add(this)
    }

    private fun order(unit: OrderableUnit, type: RwxLocalGameTypes.OrderType, queued: Boolean = false,
                      action: String? = null, mode: String? = null, x: Float = 1800f) = RwxLocalGameTypes.Order(
        type, listOf(unit.objectId), null, null, null, action, mode, x, 900f, queued, false,
    )

    private fun invoke(name: String, vararg args: Any?): Any? {
        val method = RwxEngineLocalGameService::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }.apply { isAccessible = true }
        return try { method.invoke(service, *args) } catch (e: InvocationTargetException) { throw e.targetException }
    }

    private fun enableGuard() = helper.set(engine, own, AiAutomateRuleOptions().apply {
        rule = AiAutomateRuleId.GUARD; x = 500f; y = 500f
    })

    private fun execute() {
        val batch = engine.commandController.pendingCommands.toList()
        engine.commandController.pendingCommands.clear()
        batch.forEach { it.executeCommand() }
    }

    private fun takeOver(unit: OrderableUnit) {
        enableGuard()
        assertTrue(helper.apply(unit, AiAutomateRuleId.GUARD,
            UnitCommand().apply { setMoveTarget(500f, 500f) }))
        execute()
    }

    @Test fun `CLI attack move and stop win for many guard samples`() {
        val unit = spawn(Artillery(true))
        takeOver(unit)
        service.issue(order(unit, RwxLocalGameTypes.OrderType.ATTACK_MOVE))
        assertEquals(1800f, unit.currentWaypoint.targetX)
        engine.isPaused = false
        repeat(6) {
            engine.currentTick += 60
            helper.update(engine, own, listOf(unit))
            assertTrue(engine.commandController.pendingCommands.isEmpty())
        }
        engine.isPaused = true
        service.issue(order(unit, RwxLocalGameTypes.OrderType.STOP))
        assertEquals(0, unit.waypointCount)
        engine.isPaused = false
        engine.currentTick += 60
        helper.update(engine, own, listOf(unit))
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test fun `queued manual order restores full original mission before appending while paused or running`() {
        for (paused in listOf(true, false)) {
            engine.isPaused = paused
            val unit = spawn(Artillery(true))
            unit.appendAttackMoveWaypoint(1400f, 500f)
            unit.appendMoveWaypoint(1600f, 600f)
            takeOver(unit)
            invoke("issueOnGameThread", order(unit, RwxLocalGameTypes.OrderType.ATTACK_MOVE, queued = true))
            invoke("issueOnGameThread", order(unit, RwxLocalGameTypes.OrderType.ATTACK_MOVE, queued = true, x = 1900f))
            if (!paused) execute()
            assertEquals(4, unit.waypointCount)
            assertEquals(listOf(1400f, 1600f, 1800f, 1900f), (0..3).map { unit.getWaypointAt(it).targetX })
        }
    }

    @Test fun `manual stance cancels takeover restores mission and protects the new stance`() {
        val unit = spawn(Artillery(true))
        unit.appendAttackMoveWaypoint(1400f, 500f)
        takeOver(unit)
        service.issue(order(unit, RwxLocalGameTypes.OrderType.ATTACK_MODE, mode = "holdFire"))
        assertEquals(AttackMode.holdFire, unit.attackMode)
        assertEquals(1400f, unit.currentWaypoint.targetX)
        assertTrue(helper.manuallyOverridden(unit, AiAutomateRuleId.GUARD))
    }

    @Test fun `manual production pauses keep and keep-set explicitly authorizes it again`() {
        val factory = spawn(Factory())
        service.keep(factory.objectId, "u_tank", 2)
        invoke("processKeepRules", engine, own)
        assertEquals(1, factory.a(factory.availableActions.single().actionId, true))
        service.issue(order(factory, RwxLocalGameTypes.OrderType.ACTION, action = "u_tank"))
        assertTrue(service.keeps().rules.single().state.contains("手动"))
        engine.currentTick += 120
        engine.gameTimeMillis += 2000
        invoke("processKeepRules", engine, own)
        assertEquals(2, factory.a(factory.availableActions.single().actionId, true))
        service.keep(factory.objectId, "u_tank", 3)
        invoke("processKeepRules", engine, own)
        assertEquals(3, factory.a(factory.availableActions.single().actionId, true))
    }

    @Test fun `manual cancellation is not undone by keep or a delayed production action`() {
        val factory = spawn(Factory())
        service.keep(factory.objectId, "u_tank", 1)
        invoke("processKeepRules", engine, own)
        // Queue another request as an explicit delayed order, just as a credit shortage would.
        val pendingKind = Class.forName(RwxEngineLocalGameService::class.java.name + "\$PendingKind")
            .enumConstants.first()
        invoke("holdPendingOrder", engine, order(factory, RwxLocalGameTypes.OrderType.ACTION, action = "u_tank"),
            listOf(factory), "waiting", pendingKind)
        service.cancel(factory.objectId, "u_tank", 1, true)
        engine.currentTick += 120
        engine.gameTimeMillis += 2000
        invoke("processPendingOrders", engine, own)
        invoke("processKeepRules", engine, own)
        assertEquals(0, factory.a(factory.availableActions.single().actionId, true))
    }

    @Test fun `new manual task removes only its units from an older multi unit delayed task`() {
        val first = spawn(Artillery(true))
        val second = spawn(Artillery(true))
        val pendingKind = Class.forName(RwxEngineLocalGameService::class.java.name + "\$PendingKind")
            .enumConstants.first()
        val original = RwxLocalGameTypes.Order(RwxLocalGameTypes.OrderType.ATTACK_MOVE,
            emptyList(), "all-army", null, null, null, null, 1000f, 500f, false, false)
        invoke("holdPendingOrder", engine, original, listOf(first, second), "waiting", pendingKind)
        service.issue(order(first, RwxLocalGameTypes.OrderType.MOVE))
        val late = spawn(Artillery(true))
        invoke("processPendingOrders", engine, own)
        assertEquals(1800f, first.currentWaypoint.targetX)
        assertEquals(1000f, second.currentWaypoint.targetX)
        assertEquals(0, late.waypointCount, "delayed all-army must not expand to new units")
    }

    @Test fun `pending keep commands release their reserved credits for a manual action`() {
        val factory = spawn(Factory())
        own.credits = factory.availableActions.single().costAmount.toDouble()
        service.keep(factory.objectId, "u_tank", 1)
        engine.isPaused = false
        invoke("processKeepRules", engine, own)
        assertEquals(1, engine.commandController.pendingCommands.size)
        invoke("issueOnGameThread", order(factory, RwxLocalGameTypes.OrderType.ACTION, action = "u_tank"))
        assertEquals(1, engine.commandController.pendingCommands.size, "the automatic production was preempted")
        execute()
        assertEquals(1, factory.a(factory.availableActions.single().actionId, true))
    }
}
