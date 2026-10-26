package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * 查询命令的冒烟测试：不加载地图、不启动对局，只校验命令能组合出稳定文本。
 *
 * 通过把内置枚举注册表塞回 [UnitTypeEnum.ae] 并调用 `h()`，模拟"配置已加载"的
 * 最小环境；mod 的 ini 数据本身由游戏启动流程加载，这里不重复实现。
 */
class RwxUnitQueryCommandsTest {
    private val commands = RwxUnitQueryCommands()
    private var previousUnitTypes: ArrayList<UnitType>? = null

    @BeforeTest
    fun setUp() {
        // CustomUnitConfig 的静态初始化会构造 BaseUnit，而 BaseUnit 需要 GameEngine 单例。
        val engine = GameEngine.getInstance() ?: GameLogic()
        engine.networkEngine = NetworkEngine()
        engine.replayEngine = ReplayEngine()
        engine.commandController = CommandController()

        // h() 会读取 UnitTypeEnum.ae（编辑器动作列表），因此必须先填注册表。
        previousUnitTypes = UnitTypeEnum.ae
        UnitTypeEnum.ae = ArrayList(UnitTypeEnum.values().toList())
        UnitTypeEnum.values().forEach { type -> runCatching { type.h() } }
    }

    @AfterTest
    fun tearDown() {
        UnitTypeEnum.ae = previousUnitTypes
    }

    private fun run(command: RwxCliCommands.Command, vararg arguments: String): String =
        commands.execute(command, arguments.toList())

    @Test
    fun `registry exposes three query entries and drops the old ones`() {
        assertEquals(
            listOf(
                RwxCliCommands.Command.QUERY_UNIT,
                RwxCliCommands.Command.QUERY_BUILDING,
                RwxCliCommands.Command.QUERY_PRODUCTION,
            ),
            RwxCliCommands.commandsIn(RwxCliCommands.Domain.UNIT_QUERY),
        )
        RwxCliCommands.commandsIn(RwxCliCommands.Domain.UNIT_QUERY).forEach {
            assertEquals(it, RwxCliCommands.find(it.commandName()))
        }
        listOf(
            "rwx-query-unit-single",
            "rwx-query-unit-land",
            "rwx-query-building-turret",
            "rwx-query-unit-byfactory",
        ).forEach { assertNull(RwxCliCommands.find(it), it) }
        assertTrue(!RwxCliCommands.isInDomain(
            RwxCliCommands.find("rwx-game-state"), RwxCliCommands.Domain.UNIT_QUERY))
    }

    @Test
    fun `unit categories return a table with the documented columns`() {
        val land = run(RwxCliCommands.Command.QUERY_UNIT, "--category", "land")
        assertTrue(land.startsWith("陆地攻击单位（全部等级）"), land)
        assertTrue(land.contains("id | 名称 | 价格 | 工厂 | 等级 | 用时"), land)

        val transport = run(RwxCliCommands.Command.QUERY_UNIT, "--category", "transport")
        assertTrue(transport.startsWith("运输单位"), transport)
        assertTrue(transport.contains("载人容量"), transport)

        val builder = run(RwxCliCommands.Command.QUERY_UNIT, "--category", "builder")
        assertTrue(builder.startsWith("工程单位"), builder)
    }

    @Test
    fun `building categories keep the upgrade path column`() {
        val turret = run(RwxCliCommands.Command.QUERY_BUILDING, "--category", "turret")
        assertTrue(turret.startsWith("防御炮塔"), turret)
        assertTrue(turret.contains("升级路径"), turret)

        val economy = run(RwxCliCommands.Command.QUERY_BUILDING, "--category", "economy")
        assertTrue(economy.startsWith("经济建筑"), economy)
    }

    @Test
    fun `unit single composes id name kind cost and about`() {
        val text = run(RwxCliCommands.Command.QUERY_UNIT, "--name", "hovercraft")
        assertTrue(text.startsWith("id"), text)
        assertTrue(text.contains("hovercraft"), text)
        assertTrue(text.contains("\nname        "), text)
        assertTrue(text.contains("\nkind        unit"), text)
        assertTrue(text.contains("\ncost        "), text)
    }

    @Test
    fun `detail adds commands and description`() {
        val text = run(RwxCliCommands.Command.QUERY_UNIT, "--name", "builder", "--detail")
        assertTrue(text.contains("commands"), text)
        assertTrue(text.contains("description "), text)
    }

    @Test
    fun `production accepts the documented shorthands and lists real actions`() {
        val factoryName = RwxUnitQueryCatalog.displayName(
            RwxUnitQueryCatalog.resolve("landFactory")!!,
        )
        val text = run(RwxCliCommands.Command.QUERY_PRODUCTION, "--factory", "land")
        assertTrue(text.startsWith("$factoryName 可生产单位"), text)
        assertTrue(text.contains("id | 名称 | 价格 | 类型 | 生命 | 射程 | 用时"), text)
        assertTrue(text.contains("hovercraft"), text)
    }

    @Test
    fun `level filter splits level one and level two production`() {
        val levelOne = run(RwxCliCommands.Command.QUERY_UNIT, "--category", "land", "--level", "1")
        val levelTwo = run(RwxCliCommands.Command.QUERY_UNIT, "--category", "land", "--level", "2")
        assertTrue(levelOne.lines().any { it.startsWith("tank |") }, levelOne)
        assertTrue(levelOne.lines().none { it.startsWith("heavyTank |") }, levelOne)
        assertTrue(levelTwo.lines().any { it.startsWith("heavyTank |") }, levelTwo)
        assertTrue(levelTwo.lines().none { it.startsWith("tank |") }, levelTwo)
    }

    @Test
    fun `building single rejects non buildings and unit single redirects buildings`() {
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_BUILDING, "--name", "tank")
        }
        val redirect = assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--name", "landFactory")
        }
        assertTrue(redirect.message!!.contains("rwx-query-building"), redirect.message)
    }

    @Test
    fun `name resolution accepts ids localized names and normalized spelling`() {
        val byId = run(RwxCliCommands.Command.QUERY_UNIT, "--name", "hovercraft")
        assertTrue(byId.contains("hovercraft"), byId)

        val localized = RwxUnitQueryCatalog.displayName(RwxUnitQueryCatalog.resolve("hovercraft")!!)
        val byLocalized = run(RwxCliCommands.Command.QUERY_UNIT, "--name", localized)
        assertTrue(byLocalized.contains("hovercraft"), byLocalized)

        // 大小写、空格、下划线、连字符都不影响解析。
        val normalized = run(RwxCliCommands.Command.QUERY_UNIT, "--name", "HOVER_CRAFT")
        assertTrue(normalized.contains("hovercraft"), normalized)
    }

    @Test
    fun `queries without a selector print the full index`() {
        val units = run(RwxCliCommands.Command.QUERY_UNIT)
        assertTrue(units.startsWith("全部单位与建筑"), units)
        assertTrue(units.contains("id | 名称 | 分类"), units)
        assertTrue(units.contains("landFactory"), units)

        val buildings = run(RwxCliCommands.Command.QUERY_BUILDING)
        assertTrue(buildings.startsWith("全部建筑"), buildings)
        assertTrue(buildings.contains("landFactory"), buildings)
    }

    @Test
    fun `ambiguous or misspelled names list candidates`() {
        val ambiguous = assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--name", "heavy")
        }
        assertTrue(ambiguous.message!!.contains("可能想找"), ambiguous.message)
        assertTrue(ambiguous.message!!.contains("heavyTank"), ambiguous.message)

        val typo = assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--name", "tahnk")
        }
        assertTrue(typo.message!!.contains("tank"), typo.message)
    }

    @Test
    fun `unknown names options and category combinations fail loudly`() {
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--name", "no-such-unit")
        }
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--name", "tank", "--bogus")
        }
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--category", "land", "--level", "9")
        }
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--category", "spaceship")
        }
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--category", "builder", "--level", "1")
        }
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--name", "tank", "--category", "land")
        }
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_UNIT, "--showFeature")
        }
        assertFailsWith<IllegalArgumentException> {
            run(RwxCliCommands.Command.QUERY_BUILDING, "--category", "spaceship")
        }
    }
}
