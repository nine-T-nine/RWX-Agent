package com.corrodinggames.rts.game.ai.custom.ai_automate

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.units.*
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.actions.NoneAction
import com.corrodinggames.rts.game.units.buildings.LandFactory
import com.corrodinggames.rts.game.units.land.Artillery
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.*
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.graphics.Texture
import kotlin.test.*

/**
 * 自动化规则的行为测试：接管、恢复、版本校验、区域防御、上下潜、修理、脱困、空闲提醒。
 *
 * 单位都是真实引擎对象的子类，只有地形、可见性与射程判断被替换，避免依赖已加载的地图。
 */
class AiAutomateRulesTest {
    private lateinit var engine: GameEngine
    private lateinit var own: AIController
    private lateinit var enemy: AIController
    private lateinit var helper: AiAutomate
    private var nextId = 900000L
    private var previousTypes: ArrayList<UnitType>? = null

    private open class Soldier : Artillery(true) {
        var visible = true
        var movement = UnitMovementType.LAND
        override fun getMovementType() = movement
        override fun d(team: PlayerTeam?) = visible
        override fun d(target: BaseUnit?) = visible
        override fun a(i: Int, target: BaseUnit?, ignoreRange: Boolean, extra: Boolean) = true
    }

    private class Builder : BuilderUnit(true) {
        var visible = true
        override fun d(team: PlayerTeam?) = visible
        override fun d(target: BaseUnit?) = visible
        override fun a(i: Int, target: BaseUnit?, ignoreRange: Boolean, extra: Boolean) = true
        override fun canRepairTarget(target: BaseUnit?) = target != null
    }

    private class Factory : LandFactory(true) {
        override fun b(texture: Texture?) {} // 不加载工厂贴图。
        override fun d(team: PlayerTeam?) = true
    }

    /** 带 Dive/Surface 动作的假潜艇：避开真实潜艇构造时的贴图依赖。 */
    private class Sub : Artillery(true) {
        var visible = true
        var submerged = false
        private val surfaceAction = object : NoneAction(151) {
            override fun getDisplayName() = "Surface"
            override fun getDescription() = "Surface unit"
        }
        private val diveAction = object : NoneAction(152) {
            override fun getDisplayName() = "Dive"
            override fun getDescription() = "Dive unit"
        }

        override fun getMovementType() = UnitMovementType.WATER
        override fun d(team: PlayerTeam?) = visible
        override fun d(target: BaseUnit?) = visible
        override fun a(i: Int, target: BaseUnit?, ignoreRange: Boolean, extra: Boolean) = true
        override fun Q() = submerged
        override fun getAvailableActions(): ArrayList<AbstractUnitAction> =
            arrayListOf(surfaceAction, diveAction)
    }

    /** 假炮塔：建筑标记 + 名称含 turret；射程与可攻击判断由测试直接控制。 */
    private class Turret : Artillery(true) {
        var visible = true
        override fun r() = UnitTypeEnum.turret
        override fun bI() = true
        override fun d(team: PlayerTeam?) = visible
        override fun d(target: BaseUnit?) = visible
        override fun a(i: Int, target: BaseUnit?, ignoreRange: Boolean, extra: Boolean) = true
        override fun isWithinEngagementRange(target: BaseUnit?) = true
        override fun canEngageTargetNow(target: BaseUnit?) = true
        override fun getMoveSpeed() = 0f
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

    private fun <T : OrderableUnit> spawn(unit: T, x: Float, y: Float, hostile: Boolean = false): T {
        unit.objectId = nextId++
        unit.team = if (hostile) enemy else own
        unit.posX = x
        unit.posY = y
        unit.buildProgress = 1f
        unit.movementLevels[0].rotation = 100f
        return unit
    }

    private fun soldier(x: Float = 500f, y: Float = 500f, hostile: Boolean = false) =
        spawn(Soldier(), x, y, hostile)

    private fun enable(rule: AiAutomateRuleId, block: AiAutomateRuleOptions.() -> Unit = {}) {
        val options = AiAutomateRuleOptions()
        options.rule = rule
        options.enabled = true
        options.block()
        helper.set(engine, own, options)
    }

    private fun advance(vararg units: BaseUnit, ticks: Int = 15) {
        engine.currentTick += ticks
        helper.update(engine, own, units.toList())
    }

    private fun execute() {
        val batch = engine.commandController.pendingCommands.toList()
        engine.commandController.pendingCommands.clear()
        batch.forEach { it.executeCommand() }
    }

    private fun firstCommand(): UnitCommand? =
        engine.commandController.pendingCommands.firstOrNull()?.unitCommand

    @Test
    fun `retreat backs off then restores the original mission once safe`() {
        val unit = soldier()
        unit.currentHealth = 10f
        unit.maxHealth = 100f
        unit.appendAttackMoveWaypoint(1400f, 500f)
        val threat = soldier(650f, hostile = true)
        enable(AiAutomateRuleId.RETREAT)
        advance(unit, threat)
        val flee = assertNotNull(firstCommand(), "low health with a nearby enemy must retreat")
        assertEquals(UnitCommandType.move, flee.commandType)
        assertTrue(flee.targetX < unit.posX, "retreat must move away from the threat")
        execute()
        // 威胁消失、血量恢复：原任务必须原样回来。
        threat.visible = false
        unit.currentHealth = 100f
        advance(unit, threat)
        val restored = assertNotNull(firstCommand())
        assertEquals(UnitCommandType.attackMove, restored.commandType)
        assertEquals(1400f, restored.targetX)
        execute()
        assertEquals(1, unit.waypointCount)
        assertEquals(UnitCommandType.attackMove, unit.currentWaypoint.commandType)
    }

    @Test
    fun `retreat never restores a stale mission after the agent issues a new order`() {
        val unit = soldier()
        unit.currentHealth = 10f
        unit.maxHealth = 100f
        unit.appendAttackMoveWaypoint(1400f, 500f)
        val threat = soldier(650f, hostile = true)
        enable(AiAutomateRuleId.RETREAT)
        advance(unit, threat)
        assertNotNull(firstCommand())
        execute()

        // Agent 中途改令：这条新指令不能被 2 秒后的“恢复快照”覆盖。
        unit.clearAllWaypoints()
        unit.appendMoveWaypoint(300f, 900f)
        threat.visible = false
        unit.currentHealth = 100f
        advance(unit, threat)
        assertTrue(engine.commandController.pendingCommands.isEmpty(), "no restore may be queued")
        assertEquals(1, unit.waypointCount)
        assertEquals(300f, unit.currentWaypoint.targetX)
        assertEquals(900f, unit.currentWaypoint.targetY)
    }

    @Test
    fun `guard attacks intruders and gathers back at the anchor`() {
        val unit = soldier()
        val intruder = soldier(600f, 500f, hostile = true)
        enable(AiAutomateRuleId.GUARD) {
            x = 500f
            y = 500f
            radius = 200f
        }
        advance(unit, intruder)
        val engage = assertNotNull(firstCommand())
        assertEquals(UnitCommandType.attackMove, engage.commandType)
        assertEquals(600f, engage.targetX)
        execute()

        intruder.visible = false
        unit.posX = 420f // 走出锚点 80 个单位
        advance(unit, intruder)
        val gather = assertNotNull(firstCommand())
        assertEquals(UnitCommandType.move, gather.commandType)
        assertEquals(500f, gather.targetX)
    }

    @Test
    fun `guard cannot repeatedly reclaim a manually ordered attack move or stopped unit`() {
        val unit = soldier(420f)
        enable(AiAutomateRuleId.GUARD) { x = 500f; y = 500f; radius = 200f }
        advance(unit)
        execute()
        helper.yieldToOrders(engine, listOf(unit))
        unit.clearAllWaypoints()
        unit.appendAttackMoveWaypoint(1600f, 900f)
        repeat(8) {
            advance(unit, ticks = 60)
            assertTrue(engine.commandController.pendingCommands.isEmpty(), "guard must not pull back again")
            assertEquals(1600f, unit.currentWaypoint.targetX)
        }
        unit.clearAllWaypoints() // stop/completion does not silently reauthorize the old guard
        repeat(3) { advance(unit, ticks = 60) }
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        enable(AiAutomateRuleId.GUARD) { x = 500f; y = 500f }
        advance(unit)
        assertEquals(500f, assertNotNull(firstCommand()).targetX, "explicit auto-set authorizes guard again")
    }

    @Test
    fun `all issuing paths respect manual priority and reauthorization is scoped per rule and unit`() {
        val unit = soldier()
        val other = soldier(600f)
        enable(AiAutomateRuleId.GUARD) { x = 500f; y = 500f }
        helper.yieldToOrders(engine, listOf(unit, other))
        val micro = UnitCommand().apply { setMoveTarget(100f, 100f) }
        val action = object : NoneAction(151) {
            override fun getDisplayName() = "Dive"
            override fun getDescription() = "Dive"
        }
        unit.appendMoveWaypoint(900f, 900f)
        for (rule in AiAutomateRuleId.entries) {
            assertFalse(helper.apply(unit, rule, micro, true), rule.id)
            assertFalse(helper.action(unit, rule, action), rule.id)
        }
        assertFalse(helper.repath(unit))
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        enable(AiAutomateRuleId.GUARD) { units = unit.objectId.toString(); x = 500f; y = 500f }
        assertFalse(helper.manuallyOverridden(unit, AiAutomateRuleId.GUARD))
        assertTrue(helper.manuallyOverridden(other, AiAutomateRuleId.GUARD))
        assertTrue(helper.manuallyOverridden(unit, AiAutomateRuleId.RETREAT))
        assertTrue(helper.manuallyOverridden(unit, AiAutomateRuleId.DIVE))
        enable(AiAutomateRuleId.REPAIR) { units = unit.objectId.toString() }
        assertFalse(helper.manuallyOverridden(unit, AiAutomateRuleId.REPAIR),
            "currently busy is a trigger condition, not a permanent authorization exclusion")
        helper.yieldToOrders(engine, listOf(unit, other))
        helper.setGroupLookup { listOf(unit.objectId) }
        enable(AiAutomateRuleId.GUARD) { group = "main" }
        assertFalse(helper.manuallyOverridden(unit, AiAutomateRuleId.GUARD))
        assertTrue(helper.manuallyOverridden(other, AiAutomateRuleId.GUARD))
    }

    @Test
    fun `manual orders cancel detached restoration batches and dive actions`() {
        val unit = soldier(420f)
        enable(AiAutomateRuleId.GUARD) { x = 500f; y = 500f }
        advance(unit)
        execute()
        helper.clear(engine, own, AiAutomateRuleId.GUARD)
        assertTrue(engine.commandController.pendingCommands.isNotEmpty(), "restoration is pending without a claim")
        helper.yieldToOrders(engine, listOf(unit))
        assertTrue(engine.commandController.pendingCommands.isEmpty())

        val sub = spawn(Sub(), 500f, 500f).apply { submerged = true }
        val target = soldier(560f, hostile = true)
        sub.attackTarget = target
        enable(AiAutomateRuleId.DIVE)
        advance(sub, target)
        assertTrue(engine.commandController.pendingCommands.isNotEmpty())
        helper.yieldToOrders(engine, listOf(sub))
        repeat(3) { advance(sub, target, ticks = 90) }
        assertTrue(engine.commandController.pendingCommands.isEmpty(), "neither pending nor new dive may override manual action")
    }

    @Test
    fun `manual queued handoff removes temporary guard and retains the original full mission`() {
        val unit = soldier(420f)
        unit.appendAttackMoveWaypoint(1400f, 500f)
        unit.appendMoveWaypoint(1600f, 600f)
        enable(AiAutomateRuleId.GUARD) { x = 500f; y = 500f }
        advance(unit)
        execute()
        val prefix = helper.yieldToOrders(engine, listOf(unit), true)
        assertEquals(2, prefix.size)
        execute()
        unit.appendAttackMoveWaypoint(1800f, 900f)
        assertEquals(3, unit.waypointCount)
        assertEquals(1400f, unit.getWaypointAt(0).targetX)
        assertEquals(1600f, unit.getWaypointAt(1).targetX)
        assertEquals(1800f, unit.getWaypointAt(2).targetX)
        repeat(5) { advance(unit, ticks = 90) }
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test
    fun `changed GUI attack move drops the claim without reacquiring on later passes`() {
        val unit = soldier(420f)
        enable(AiAutomateRuleId.GUARD) { x = 500f; y = 500f }
        advance(unit)
        execute()
        unit.clearAllWaypoints()
        unit.appendAttackMoveWaypoint(1900f, 600f)
        repeat(5) { advance(unit, ticks = 60) }
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        helper.reset(engine, own)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        assertEquals(1900f, unit.currentWaypoint.targetX)
    }

    @Test
    fun `disabling dive cancels its unexecuted action`() {
        val sub = spawn(Sub(), 500f, 500f).apply { submerged = true }
        val target = soldier(560f, hostile = true)
        sub.attackTarget = target
        enable(AiAutomateRuleId.DIVE)
        advance(sub, target)
        assertTrue(engine.commandController.pendingCommands.isNotEmpty())
        helper.clear(engine, own, AiAutomateRuleId.DIVE)
        assertTrue(engine.commandController.pendingCommands.isEmpty())
    }

    @Test
    fun `queued handoff retains a restoration already pending after guard was disabled`() {
        val unit = soldier(420f)
        unit.appendAttackMoveWaypoint(1400f, 500f)
        unit.appendMoveWaypoint(1600f, 600f)
        enable(AiAutomateRuleId.GUARD) { x = 500f; y = 500f }
        advance(unit)
        execute()
        helper.clear(engine, own, AiAutomateRuleId.GUARD)
        assertNull(helper.claim(unit))
        val prefix = helper.yieldToOrders(engine, listOf(unit), true)
        assertEquals(2, prefix.size)
        execute()
        assertEquals(listOf(1400f, 1600f), (0..1).map { unit.getWaypointAt(it).targetX })
    }

    @Test
    fun `dive surfaces a submerged submarine whose target is on land`() {
        val sub = spawn(Sub(), 500f, 500f)
        sub.submerged = true
        val target = soldier(560f, 500f, hostile = true)
        sub.attackTarget = target
        enable(AiAutomateRuleId.DIVE)
        advance(sub, target)
        val command = engine.commandController.pendingCommands.firstOrNull()
        assertNotNull(command, "a submerged sub with a land target must surface")
        assertEquals("151", command.actionId?.id, "151 is the native Surface action")
        execute()
    }

    @Test
    fun `repair sends an idle builder to a damaged ally and walks it back afterwards`() {
        val builder = spawn(Builder(), 500f, 500f)
        val damaged = soldier(600f, 500f)
        damaged.currentHealth = 50f
        damaged.maxHealth = 100f
        enable(AiAutomateRuleId.REPAIR)
        advance(builder, damaged)
        val repair = assertNotNull(firstCommand(), "an idle builder must repair a damaged unit in range")
        assertEquals(UnitCommandType.repair, repair.commandType)
        assertSame(damaged, repair.targetUnit)
        execute()

        damaged.currentHealth = 100f
        advance(builder, damaged)
        val home = assertNotNull(firstCommand())
        assertEquals(UnitCommandType.move, home.commandType)
        assertEquals(500f, home.targetX)
    }

    @Test
    fun `unstuck re-issues the queued waypoint when a unit makes no progress`() {
        val unit = soldier()
        unit.appendMoveWaypoint(900f, 500f)
        enable(AiAutomateRuleId.UNSTUCK)
        advance(unit)                  // 建立跟踪
        advance(unit, ticks = 130)     // 超过 2 秒无位移
        val repath = assertNotNull(firstCommand(), "a stuck unit must get its waypoint re-issued")
        assertEquals(UnitCommandType.move, repath.commandType)
        assertEquals(900f, repath.targetX)
    }

    @Test
    fun `idle-alert reports idle units without issuing orders`() {
        val unit = soldier()
        enable(AiAutomateRuleId.IDLE_ALERT) { includeUnits = true }
        advance(unit)
        assertTrue(engine.commandController.pendingCommands.isEmpty(), "idle-alert must never order units")
        val report = helper.list(engine, own)
        assertTrue(report.recent.any { it.contains("个攻击单位空闲") },
            "the idle count must show up in the rule log")
    }

    @Test
    fun `idle-alert defaults to factories only and keeps factory alerts when filtered`() {
        val factory = spawn(Factory(), 500f, 500f)
        val unit = soldier(550f)
        enable(AiAutomateRuleId.IDLE_ALERT)
        advance(factory, unit)
        var report = helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT)
        assertFalse(report.rules.single().includeUnits)
        assertTrue(report.recent.any { it.contains("工厂 #${factory.objectId} 队列为空") })
        assertFalse(report.recent.any { it.contains("个攻击单位空闲") })
        assertTrue(engine.commandController.pendingCommands.isEmpty())

        // 即使筛选到不存在的部队，所有空闲工厂仍然提醒。
        enable(AiAutomateRuleId.IDLE_ALERT) { units = "999" }
        advance(factory, unit, ticks = 310)
        report = helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT)
        assertTrue(report.rules.single().includeUnits)
        assertEquals(2, report.recent.count { it.contains("工厂 #${factory.objectId} 队列为空") })
        assertFalse(report.recent.any { it.contains("个攻击单位空闲") })
    }

    @Test
    fun `idle-alert unit filters automatically enable notifications for matching units`() {
        val first = soldier()
        val second = soldier(550f)
        first.currentHealth = first.maxHealth * 0.5f
        val filters: List<AiAutomateRuleOptions.() -> Unit> = listOf(
            { units = first.objectId.toString() },
            { group = "3" },
            { excludedIds = arrayListOf(second.objectId) },
            { includeTypes = arrayListOf("artillery") },
            { excludeTypes = arrayListOf("builder") },
            { excludeBuilders = true },
            { hpPercent = 60f },
            { idleOnly = true },
            { x = 500f; y = 500f; radius = 20f },
        )
        val expectedCounts = listOf(1, 1, 1, 2, 2, 2, 1, 2, 1)
        for ((index, filter) in filters.withIndex()) {
            helper = AiAutomate { _, _, _ -> true }
            helper.setGroupLookup { name -> if (name == "3") listOf(first.objectId) else null }
            enable(AiAutomateRuleId.IDLE_ALERT, filter)
            advance(first, second)
            val report = helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT)
            assertTrue(report.rules.single().includeUnits)
            assertEquals(expectedCounts[index], report.rules.single().eligible, "filter $index")
            assertTrue(report.recent.any { it.contains("${expectedCounts[index]} 个攻击单位空闲") })
            assertTrue(engine.commandController.pendingCommands.isEmpty())
        }
    }

    @Test
    fun `idle-alert can disable unit notifications and clear or reset restores factory only`() {
        val unit = soldier()
        enable(AiAutomateRuleId.IDLE_ALERT) { includeUnits = true }
        advance(unit)
        val count = helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT).recent
            .count { it.contains("个攻击单位空闲") }
        assertEquals(1, count)
        enable(AiAutomateRuleId.IDLE_ALERT) { units = "all-army"; includeUnits = false }
        advance(unit, ticks = 310)
        var report = helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT)
        assertFalse(report.rules.single().includeUnits)
        assertEquals(count, report.recent.count { it.contains("个攻击单位空闲") })
        enable(AiAutomateRuleId.IDLE_ALERT) // 未提供筛选或开关时保留关闭状态。
        assertFalse(helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT).rules.single().includeUnits)

        enable(AiAutomateRuleId.IDLE_ALERT) { includeUnits = true }
        helper.clear(engine, own, AiAutomateRuleId.IDLE_ALERT)
        assertFalse(helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT).rules.single().includeUnits)
        enable(AiAutomateRuleId.IDLE_ALERT) { includeUnits = true }
        helper.reset(engine, own)
        report = helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT)
        assertFalse(report.rules.single().includeUnits)
        assertFalse(report.rules.single().enabled)
        assertFailsWith<IllegalArgumentException> {
            enable(AiAutomateRuleId.MECH) { includeUnits = true }
        }
    }

    @Test
    fun `mech defaults to forty percent health after clear and reset`() {
        enable(AiAutomateRuleId.MECH)
        assertEquals(40f, helper.show(engine, own, AiAutomateRuleId.MECH).rules.single().hpPercent)
        enable(AiAutomateRuleId.MECH) { hpPercent = 70f }
        helper.clear(engine, own, AiAutomateRuleId.MECH)
        assertEquals(40f, helper.show(engine, own, AiAutomateRuleId.MECH).rules.single().hpPercent)
        enable(AiAutomateRuleId.MECH) { hpPercent = 100f }
        helper.reset(engine, own)
        assertEquals(40f, helper.show(engine, own, AiAutomateRuleId.MECH).rules.single().hpPercent)
    }

    @Test
    fun `auto-set patches fields and auto-clear restores defaults`() {
        enable(AiAutomateRuleId.DIVE) {
            hpPercent = 55f
            radius = 120f
        }
        var view = helper.show(engine, own, AiAutomateRuleId.DIVE).rules.single()
        assertTrue(view.enabled)
        assertEquals(55f, view.hpPercent)
        assertEquals(120f, view.radius)
        assertEquals(1000, view.cooldownMillis, "cooldown is a fixed rule default, not a CLI option")

        helper.clear(engine, own, AiAutomateRuleId.DIVE)
        view = helper.show(engine, own, AiAutomateRuleId.DIVE).rules.single()
        assertFalse(view.enabled)
        assertNull(view.hpPercent)
        assertNull(view.radius)

        assertFailsWith<IllegalArgumentException> {
            helper.set(engine, own, AiAutomateRuleOptions().apply {
                rule = AiAutomateRuleId.GUARD
                enabled = true
            })
        }
    }

    @Test
    fun `group filter follows the group's current members`() {
        val first = soldier(500f, 500f)
        val second = soldier(540f, 500f)
        var members = listOf(first.objectId)
        helper.setGroupLookup { name -> if (name == "3") members else null }
        enable(AiAutomateRuleId.IDLE_ALERT) { group = "3" }

        var view = helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT).rules.single()
        assertEquals("group:3", view.units.trim())

        members = listOf(first.objectId, second.objectId)
        advance(first, second)
        view = helper.show(engine, own, AiAutomateRuleId.IDLE_ALERT).rules.single()
        assertEquals(2, view.eligible, "new group members must be picked up on the next pass")

        assertFailsWith<IllegalArgumentException> {
            helper.set(engine, own, AiAutomateRuleOptions().apply {
                rule = AiAutomateRuleId.RETREAT
                enabled = true
                group = "missing"
            })
        }
    }

    @Test
    fun `auto-reset releases every controlled unit and disables all rules`() {
        val unit = soldier()
        unit.currentHealth = 10f
        unit.maxHealth = 100f
        unit.appendAttackMoveWaypoint(1400f, 500f)
        val threat = soldier(650f, hostile = true)
        enable(AiAutomateRuleId.RETREAT)
        enable(AiAutomateRuleId.IDLE_ALERT)
        advance(unit, threat)
        assertNotNull(firstCommand(), "retreat must have taken over before the reset")
        execute()

        val report = helper.reset(engine, own)
        assertTrue(report.rules.none { it.enabled }, "every rule must be disabled")
        assertTrue(report.rules.all { it.engaged == 0 }, "no unit may stay controlled")
        // 重置会先恢复被接管单位的原任务，再关闭规则。
        val restored = assertNotNull(firstCommand())
        assertEquals(UnitCommandType.attackMove, restored.commandType)
        assertEquals(1400f, restored.targetX)
    }

    @Test
    fun `artillery still works through auto-set`() {
        val gun = soldier()
        val target = soldier(700f, hostile = true)
        enable(AiAutomateRuleId.ARTILLERY)
        gun.appendAttackMoveWaypoint(1400f, 500f)
        advance(gun, target)
        assertNotNull(firstCommand(), "artillery micro must still work through auto-set")
        assertTrue(helper.show(engine, own, AiAutomateRuleId.ARTILLERY).rules.single().enabled)
        helper.clear(engine, own, AiAutomateRuleId.ARTILLERY)
        assertFalse(helper.show(engine, own, AiAutomateRuleId.ARTILLERY).rules.single().enabled)
    }

    // ---------------------------------------------------------------- 智能炮塔

    @Test
    fun `turret family and tier pick the documented behaviour`() {
        assertEquals(TurretRule.Kind.AIR_DEFENSE,
            TurretRule.kindOf("c_antiAirTurret", true, 1))
        assertEquals(TurretRule.Kind.AIR_DEFENSE,
            TurretRule.kindOf("c_turret_t2_gun", true, 2), "只对空就是防空塔")
        assertEquals(TurretRule.Kind.LIGHTNING_LOW,
            TurretRule.kindOf("c_turret_t2_lightning", false, 2))
        assertEquals(TurretRule.Kind.LIGHTNING_HIGH,
            TurretRule.kindOf("c_turret_t3_lightning", false, 3))
        assertEquals(TurretRule.Kind.LIGHTNING_LOW,
            TurretRule.kindOf("闪电炮塔 T2", false, 2), "本地化名称也能识别")
        assertEquals(TurretRule.Kind.ARTILLERY,
            TurretRule.kindOf("c_turret_t2_artillery", false, 2))
        assertEquals(TurretRule.Kind.GUN,
            TurretRule.kindOf("c_turret_t2_flame", false, 2))
        assertEquals(TurretRule.Kind.GUN,
            TurretRule.kindOf("c_turret_t3_gun", false, 3))
        assertEquals(TurretRule.Kind.GUN, TurretRule.kindOf("turret", false, 1))
        assertEquals(TurretRule.Kind.NONE, TurretRule.kindOf("commandCenter", false, 1))
    }

    @Test
    fun `turret target order follows the per-family priority`() {
        fun candidate(
            unit: BaseUnit,
            hp: Float,
            mech: Boolean = false,
            mechKey: Boolean = false,
            transport: Boolean = false,
            gunship: Boolean = false,
            bomber: Boolean = false,
            attacker: Boolean = false,
        ) = TurretRule.Candidate(unit, hp, mech, mechKey, transport, gunship, bomber, attacker)

        val transport = soldier(hostile = true)
        val gunship = soldier(hostile = true)
        val bomber = soldier(hostile = true)
        val other = soldier(hostile = true)
        // 防空塔：运输机优先，即使它血量最高。
        assertEquals(transport, TurretRule.best(listOf(
            candidate(other, 5f),
            candidate(bomber, 10f, bomber = true),
            candidate(gunship, 20f, gunship = true),
            candidate(transport, 900f, transport = true),
        ), TurretRule.Kind.AIR_DEFENSE))
        // 同样四个目标去掉运输机后，武装直升机排第二档。
        assertEquals(gunship, TurretRule.best(listOf(
            candidate(other, 5f), candidate(bomber, 10f, bomber = true),
            candidate(gunship, 20f, gunship = true),
        ), TurretRule.Kind.AIR_DEFENSE))
        // 机枪/喷火：最低血。
        assertEquals(other, TurretRule.best(listOf(
            candidate(transport, 900f), candidate(other, 5f),
        ), TurretRule.Kind.GUN))
        // 火炮塔：先打正在攻击自己的单位，即使它更健康。
        assertEquals(transport, TurretRule.best(listOf(
            candidate(other, 5f), candidate(transport, 900f, attacker = true),
        ), TurretRule.Kind.ARTILLERY))
        // T2 电击塔：机枪/火炮机甲 > 其他机甲 > 非机甲，组内最低血。
        val mech = soldier(hostile = true)
        val mechKey = soldier(hostile = true)
        assertEquals(mechKey, TurretRule.best(listOf(
            candidate(other, 1f), candidate(mech, 5f, mech = true),
            candidate(mechKey, 800f, mech = true, mechKey = true),
        ), TurretRule.Kind.LIGHTNING_LOW))
        assertEquals(mech, TurretRule.best(listOf(
            candidate(other, 1f), candidate(mech, 5f, mech = true),
        ), TurretRule.Kind.LIGHTNING_LOW))
        // T3 电击塔：最高血机甲 > 最高血非机甲。
        val bigMech = soldier(hostile = true)
        val bigOther = soldier(hostile = true)
        assertEquals(bigMech, TurretRule.best(listOf(
            candidate(bigOther, 1000f), candidate(bigMech, 500f, mech = true),
        ), TurretRule.Kind.LIGHTNING_HIGH))
        assertEquals(bigOther, TurretRule.best(listOf(
            candidate(other, 10f), candidate(bigOther, 1000f),
        ), TurretRule.Kind.LIGHTNING_HIGH))
    }

    @Test
    fun `turret locks the lowest health enemy and hands control back when clear`() {
        val turret = spawn(Turret(), 500f, 500f)
        val weak = soldier(540f, hostile = true).apply { currentHealth = 20f }
        val strong = soldier(560f, hostile = true).apply { currentHealth = 90f }
        enable(AiAutomateRuleId.TURRET)
        assertTrue(turret.b(weak, true), "turret can attack the weak enemy")
        assertTrue(turret.isWithinEngagementRange(weak), "range check")
        assertTrue(turret.canEngageTargetNow(weak), "weapon check")
        assertTrue(weak.d(own), "enemy is visible")
        advance(turret, weak, strong)
        val view = helper.show(engine, own, AiAutomateRuleId.TURRET).rules.single()
        val order = assertNotNull(firstCommand(),
            "an idle turret must pick a target; state=${view.state} eligible=${view.eligible}")
        assertEquals(UnitCommandType.attack, order.commandType)
        assertSame(weak, order.targetUnit, "GUN turrets always focus the lowest health target")
        execute()

        // 目标全部离开视野：撤掉锁定，交回原版自动索敌。
        weak.visible = false
        strong.visible = false
        advance(turret, weak, strong)
        val clear = assertNotNull(engine.commandController.pendingCommands.firstOrNull())
        assertTrue(clear.clearExistingOrders, "releasing a turret clears its forced target")
    }

    @Test
    fun `turret never overrides an explicit attack order`() {
        val turret = spawn(Turret(), 500f, 500f)
        val weak = soldier(540f, hostile = true).apply { currentHealth = 20f }
        turret.appendWaypointCopy(UnitCommand().apply { setAttackTarget(weak) })
        enable(AiAutomateRuleId.TURRET)
        advance(turret, weak)
        assertTrue(engine.commandController.pendingCommands.isEmpty(),
            "an explicit attack order from the player must win")
    }

    @Test
    fun `turret only accepts the enable switch`() {
        for (configure in listOf<AiAutomateRuleOptions.() -> Unit>(
            { includeTypes = listOf("c_turret_t2_gun") },
            { units = "12" },
            { hpPercent = 30f },
            { radius = 100f },
            { group = "3" },
            { idleOnly = false },
        )) {
            assertFailsWith<IllegalArgumentException>("scope options must be rejected") {
                enable(AiAutomateRuleId.TURRET, configure)
            }
        }
        // 只带开关时正常开启，并作用于全部炮塔。
        enable(AiAutomateRuleId.TURRET)
        val view = helper.show(engine, own, AiAutomateRuleId.TURRET).rules.single()
        assertTrue(view.enabled)
        assertNull(view.hpPercent)
        assertNull(view.radius)
        assertTrue(view.includeTypes.isEmpty())
    }
}
