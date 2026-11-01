package com.corrodinggames.rts.game.ai.custom.ai_automate

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.units.*
import com.corrodinggames.rts.game.units.land.Artillery
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.*
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import kotlin.math.hypot
import kotlin.test.*

class AssistCounterRuleTest {
    private lateinit var engine: GameEngine
    private lateinit var own: PlayerTeam
    private lateinit var enemy: PlayerTeam
    private lateinit var helper: AiAutomate
    private var previousTypes: ArrayList<UnitType>? = null
    private var nextId = 980000L

    private open class Soldier : Artillery(true) {
        var visible = true
        var airborne = false
        override fun d(team: PlayerTeam?) = visible
        override fun d(target: BaseUnit?) = visible
        override fun i() = airborne
        override fun canAttackFlyingUnits() = false
        override fun a(i: Int, target: BaseUnit?, ignoreRange: Boolean, extra: Boolean) = true
    }
    private class Worker : BuilderUnit(true) {
        override fun d(team: PlayerTeam?) = true
    }
    private class Building : Soldier() {
        override fun bI() = true
        override fun getMoveSpeed() = 0f
    }

    @BeforeTest fun setup() {
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
        engine.gameTimeMillis = 10000
        previousTypes = UnitTypeEnum.ae
        UnitTypeEnum.ae = arrayListOf()
        own = AIController(0, false)
        enemy = AIController(1, false)
        helper = AiAutomate { _, _, _ -> true }
        enable()
    }
    @AfterTest fun cleanup() {
        UnitTypeEnum.ae = previousTypes
        engine.commandController.clearAllCommands()
        engine.isPaused = false
    }
    private fun enable() {
        helper.set(engine, own, AiAutomateRuleOptions().apply { rule = AiAutomateRuleId.ASSIST_COUNTER })
    }
    private fun <T : OrderableUnit> spawn(u: T, x: Float = 500f, y: Float = 500f, hostile: Boolean = false): T = u.apply {
        objectId = nextId++
        team = if (hostile) enemy else own
        posX = x; posY = y
        currentHealth = 100f; maxHealth = 100f; buildProgress = 1f
    }
    private fun hit(victim: BaseUnit, attacker: BaseUnit) {
        victim.bs = engine.gameTimeMillis
        victim.unitTarget1 = attacker
    }
    private fun advance(vararg units: BaseUnit) {
        engine.currentTick += 15
        engine.gameTimeMillis += 240
        helper.update(engine, own, units.toList())
    }
    private fun command(u: OrderableUnit): Command? = engine.commandController.pendingCommands.firstOrNull {
        val field = Command::class.java.getDeclaredField("selectedUnits").apply { isAccessible = true }
        (field.get(it) as Iterable<*>).any { selected -> selected === u }
    }
    private fun execute() {
        val commands = engine.commandController.pendingCommands.toList()
        engine.commandController.pendingCommands.clear()
        commands.forEach { it.executeCommand() }
    }

    @Test fun `healthy idle unit assists a friendly building and pursues its attacker`() {
        val unit = spawn(Soldier())
        val friend = spawn(Building(), 650f)
        val threat = spawn(Soldier(), 950f, hostile = true)
        hit(friend, threat)
        advance(unit, friend, threat)
        val action = assertNotNull(command(unit)?.unitCommand)
        assertEquals(UnitCommandType.attack, action.commandType)
        assertSame(threat, action.targetUnit)
    }

    @Test fun `assist health must exceed forty but self defence allows exactly forty`() {
        val unit = spawn(Soldier()).apply { currentHealth = 40f }
        val friend = spawn(Building(), 600f)
        val threat = spawn(Soldier(), 900f, hostile = true)
        hit(friend, threat)
        advance(unit, friend, threat)
        assertNull(command(unit))
        hit(unit, threat)
        advance(unit, friend, threat)
        assertEquals(UnitCommandType.attack, command(unit)?.unitCommand?.commandType)
    }

    @Test fun `allied units are assisted but distant allies and friendly damage are ignored`() {
        val unit = spawn(Soldier())
        val ally = AIController(2, false).apply { teamColorId = own.teamColorId }
        val friend = spawn(Building(), 600f).apply { team = ally }
        val threat = spawn(Soldier(), 950f, hostile = true)
        hit(friend, threat)
        advance(unit, friend, threat)
        assertSame(threat, command(unit)?.unitCommand?.targetUnit)
        helper.reset(engine, own); engine.commandController.clearAllCommands(); enable()
        friend.posX = 1500f
        advance(unit, friend, threat)
        assertNull(command(unit))
        friend.posX = 600f
        hit(friend, unit)
        advance(unit, friend, threat)
        assertNull(command(unit))
    }

    @Test fun `damaged workers and ground units unable to attack aircraft flee three tiles`() {
        val worker = spawn(Worker())
        val soldier = spawn(Soldier(), 500f, 800f)
        val threat = spawn(Soldier(), 800f, 650f, true).apply { airborne = true }
        hit(worker, threat); hit(soldier, threat)
        advance(worker, soldier, threat)
        for (u in listOf(worker, soldier)) {
            val action = assertNotNull(command(u)?.unitCommand)
            assertEquals(UnitCommandType.move, action.commandType)
            assertEquals(60f, hypot(action.targetX - u.posX, action.targetY - u.posY), .01f)
            assertTrue(action.targetX < u.posX)
        }
    }

    @Test fun `a disconnected firing position causes retreat instead of futile pursuit`() {
        helper = AiAutomate(object : AiAutomate.Terrain {
            override fun canStand(unit: OrderableUnit, x: Float, y: Float) = true
            override fun canReach(unit: OrderableUnit, x: Float, y: Float) = false
        })
        enable()
        val unit = spawn(Soldier())
        val threat = spawn(Soldier(), 1200f, hostile = true)
        hit(unit, threat)
        advance(unit, threat)
        assertEquals(UnitCommandType.move, command(unit)?.unitCommand?.commandType)
    }

    @Test fun `retreat repeats in sixty unit steps and restores idle at the safe position`() {
        val unit = spawn(Soldier()).apply { currentHealth = 39f }
        val threat = spawn(Soldier(), 700f, hostile = true)
        hit(unit, threat)
        advance(unit, threat)
        val first = assertNotNull(command(unit)?.unitCommand)
        assertEquals(440f, first.targetX, .01f)
        execute()
        advance(unit, threat)
        assertNull(command(unit), "Do not slide the retreat goal every sample")
        unit.posX = 440f
        advance(unit, threat)
        val second = assertNotNull(command(unit)?.unitCommand)
        assertEquals(380f, second.targetX, .01f)
        execute()
        unit.posX = 380f
        threat.posX = 1300f
        engine.gameTimeMillis += 6000
        advance(unit, threat)
        assertTrue(assertNotNull(command(unit)).clearExistingOrders)
        execute()
        assertNull(unit.currentWaypoint)
        assertEquals(380f, unit.posX, "Escaping must not walk back into the old danger")
    }

    @Test fun `explicit movement and attack move orders are never interrupted`() {
        for (attackMove in listOf(false, true)) {
            val unit = spawn(Soldier()).apply {
                currentHealth = 10f
                if (attackMove) appendAttackMoveWaypoint(1500f, 500f) else appendMoveWaypoint(1500f, 500f)
            }
            val threat = spawn(Soldier(), 700f, hostile = true)
            hit(unit, threat)
            advance(unit, threat)
            assertNull(command(unit))
            assertEquals(1500f, unit.currentWaypoint.targetX)
        }
    }

    @Test fun `new manual command wins over a retreat and disabling restores idle`() {
        val unit = spawn(Soldier()).apply { currentHealth = 10f }
        val threat = spawn(Soldier(), 700f, hostile = true)
        hit(unit, threat); advance(unit, threat); execute()
        helper.yieldToOrders(engine, listOf(unit))
        unit.clearAllWaypoints(); unit.appendMoveWaypoint(1400f, 900f)
        advance(unit, threat)
        assertNull(command(unit))
        assertEquals(1400f, unit.currentWaypoint.targetX)
        unit.clearAllWaypoints(); advance(unit, threat); execute()
        helper.clear(engine, own, AiAutomateRuleId.ASSIST_COUNTER)
        execute()
        assertNull(unit.currentWaypoint)
    }

    @Test fun `stationary armed buildings can counter in range and hidden enemies are never pursued`() {
        val turret = spawn(Building())
        val threat = spawn(Soldier(), 650f, hostile = true)
        hit(turret, threat); advance(turret, threat)
        assertSame(threat, command(turret)?.unitCommand?.targetUnit)
        helper.reset(engine, own); engine.commandController.clearAllCommands(); enable()
        val unit = spawn(Soldier())
        threat.visible = false
        hit(unit, threat); advance(unit, threat)
        assertEquals(UnitCommandType.move, command(unit)?.unitCommand?.commandType)
    }

    @Test fun `fixed rule refuses tuning and filters`() {
        for (configure in listOf<AiAutomateRuleOptions.() -> Unit>(
            { hpPercent = 50f }, { radius = 100f }, { units = "all-army" },
            { idleOnly = true }, { group = "1" }, { excludeBuilders = true },
        )) {
            assertFailsWith<IllegalArgumentException> {
                helper.set(engine, own, AiAutomateRuleOptions().apply {
                    rule = AiAutomateRuleId.ASSIST_COUNTER; configure()
                })
            }
        }
    }
}
