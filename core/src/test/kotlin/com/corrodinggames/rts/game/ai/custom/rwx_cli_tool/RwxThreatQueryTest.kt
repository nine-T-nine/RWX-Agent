package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.land.Artillery
import com.corrodinggames.rts.gameFramework.GameEngine
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

/**
 * `rwx-game-threat-list` 的能力分类、可见性与空结果输出。
 *
 * 威胁分类抽成了 [RwxEngineLocalGameService.threatProfile]，文本/JSON 用假服务固定输入，
 * 这样不用真的加载地图也能覆盖“未武装单位不算防空/反装甲”“视野外敌军不参与统计”。
 */
class RwxThreatQueryTest {
    private lateinit var engine: GameEngine
    private var previousTypes: ArrayList<UnitType>? = null

    /** A world-less unit: only the capability surface the threat query actually reads. */
    private class Unit(
        private val range: Float,
        private val air: Boolean = true,
        private val surface: Boolean = true,
        private val submerged: Boolean = false,
    ) : Artillery(true) {
        var visible = true
        override fun m() = range
        override fun canAttackFlyingUnits() = air
        override fun canAttackSurfaceUnits() = surface
        override fun canAttackSubmergedUnits() = submerged
        override fun d(team: PlayerTeam?) = visible
    }

    @BeforeTest
    fun setUp() {
        engine = GameEngine.getInstance() ?: GameLogic()
        engine.hasLoadedLevel = true
        engine.isStopped = false
        engine.isPaused = true
        previousTypes = UnitTypeEnum.ae
        UnitTypeEnum.ae = arrayListOf()
    }

    @AfterTest
    fun tearDown() {
        UnitTypeEnum.ae = previousTypes
        engine.isPaused = false
    }

    private fun threat(
        id: Long,
        distance: Float,
        team: Int = 1,
        type: String = "c_tank",
        flying: Boolean = false,
        building: Boolean = false,
        antiAir: Boolean = true,
        antiGround: Boolean = true,
        antiSubmerged: Boolean = false,
        range: Float = 150f,
    ) = RwxLocalGameTypes.ThreatUnit(
        id, type, "Tank", team, 100f + distance, 200f, distance, 50f, 100f,
        flying, building, antiAir, antiGround, antiSubmerged, range,
    )

    private fun snapshot(units: List<RwxLocalGameTypes.ThreatUnit>, nearest: Float = -1f) =
        RwxLocalGameTypes.ThreatSnapshot(
            500f, 500f, 300f, units.size,
            units.count { it.flying }, units.count { !it.flying && !it.building },
            units.count { it.building }, units.count { it.antiAir },
            units.count { it.antiGround }, units.count { it.antiSubmerged },
            nearest, units,
        )

    private fun service(value: RwxLocalGameTypes.ThreatSnapshot): RwxLocalGameService =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RwxLocalGameService::class.java)) { _, method, _ ->
            when (method.name) {
                "threats" -> value
                else -> throw UnsupportedOperationException(method.name)
            }
        } as RwxLocalGameService

    private fun run(value: RwxLocalGameTypes.ThreatSnapshot, vararg extra: String): String =
        RwxLocalGameCommands(service(value)).execute(
            RwxCliCommands.Command.THREATS,
            listOf("--x", "500", "--y", "500", "--radius", "300") + extra,
        )

    @Test
    fun `empty area reports visibility and explicit none blocks`() {
        val text = run(snapshot(emptyList()))
        assertTrue(text.contains("visibility: currently visible enemies only"), text)
        assertTrue(text.contains("敌方可见单位: 0"), text)
        assertTrue(text.contains("nearestDistance: none"), text)
        assertTrue(text.contains("按类型: none"), text)
    }

    @Test
    fun `nearest entity and per team type counts use every returned unit`() {
        val units = (1L..35L).map { threat(it, it * 10f) } + threat(99L, 400f, team = 2, type = "c_builder")
        val text = run(snapshot(units, nearest = 10f))
        assertTrue(text.contains("nearestDistance: 10 | #1 c_tank team=1 pos=110,200"), text)
        assertTrue(text.contains("按类型: team 1 c_tank x35, team 2 c_builder x1"), text)
        // 30 is the text display cap; it must not change the statistics above.
        assertTrue(text.contains("… 6 more"), text)
    }

    @Test
    fun `json reflects the same snapshot as the text output`() {
        val units = listOf(threat(7, 12f, antiSubmerged = true))
        val json = Json.parseToJsonElement(run(snapshot(units, nearest = 12f), "--json")).jsonObject
        assertEquals(1, json["enemyCount"]!!.jsonPrimitive.int)
        assertEquals(12.0, json["nearestDistance"]!!.jsonPrimitive.double)
        assertEquals(1, json["antiAir"]!!.jsonPrimitive.int)
        assertTrue("units" in json)
    }

    @Test
    fun `unarmed units never count as anti-air or anti-ground`() {
        val profile = RwxEngineLocalGameService.threatProfile(Unit(0f))
        assertFalse(profile.armed())
        assertFalse(profile.antiAir())
        assertFalse(profile.antiGround())
        assertFalse(profile.antiSubmerged())
        assertEquals(0f, profile.range())
    }

    @Test
    fun `armed profile reports engine capabilities and range`() {
        val gunship = RwxEngineLocalGameService.threatProfile(Unit(150f, air = false, submerged = true))
        assertTrue(gunship.armed())
        assertFalse(gunship.antiAir())
        assertTrue(gunship.antiGround())
        assertTrue(gunship.antiSubmerged())
        assertEquals(150f, gunship.range())
    }

    @Test
    fun `visible owner scope excludes hidden enemies but keeps own and allied entities`() {
        val us = AIController(0, false).apply { teamId = 0 }
        val hostile = AIController(1, false).apply { teamId = 1 }
        val friendly = AIController(2, false).apply {
            teamId = 2
            teamColorId = us.teamColorId
        }
        val own = Unit(100f).apply { team = us }
        val seen = Unit(100f).apply { team = hostile; visible = true }
        val hidden = Unit(100f).apply { team = hostile; visible = false }
        val ally = Unit(100f).apply { team = friendly; visible = true }

        assertTrue(RwxEngineLocalGameService.matchesVisibleOwner(own, us, RwxLocalGameTypes.UnitOwner.SELF))
        assertTrue(RwxEngineLocalGameService.matchesVisibleOwner(seen, us, RwxLocalGameTypes.UnitOwner.ENEMY))
        assertFalse(RwxEngineLocalGameService.matchesVisibleOwner(hidden, us, RwxLocalGameTypes.UnitOwner.ENEMY))
        assertFalse(RwxEngineLocalGameService.matchesVisibleOwner(hidden, us, RwxLocalGameTypes.UnitOwner.ANY))
        assertTrue(RwxEngineLocalGameService.matchesVisibleOwner(ally, us, RwxLocalGameTypes.UnitOwner.ALLY))
        assertTrue(RwxEngineLocalGameService.matchesOwnerRelation("neutral", RwxLocalGameTypes.UnitOwner.ANY))
        assertFalse(RwxEngineLocalGameService.matchesOwnerRelation("neutral", RwxLocalGameTypes.UnitOwner.SELF))
    }
}
