package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.serialization.json.*

/**
 * `rwx-game-brief` / `unit-list --compact|--json|--full|--delta` / `order-push` / `keep` 的命令层测试。
 *
 * 这些命令的价值在“一次返回多少有用的东西”，所以用假的 [RwxLocalGameService] 固定输入，
 * 断言输出里该出现什么、不该出现什么，以及参数是否真的传进了服务层。
 */
class RwxBriefAndKeepCommandsTest {
    private fun unit(
        id: Long,
        type: String = "c_tank",
        relation: String = "self",
        health: Float = 100f,
        maxHealth: Float = 100f,
        x: Float = 100f,
        y: Float = 100f,
        movement: String = "LAND",
        queue: Int? = null,
        production: List<RwxLocalGameTypes.QueueItem> = emptyList(),
        waypoints: List<RwxLocalGameTypes.QueueItem> = emptyList(),
        order: String? = null,
        controllable: Boolean = true,
    ) = RwxLocalGameTypes.UnitSnapshot(
        id, type, type, 0, relation, x, y, health, maxHealth, 1f, 1, 1, controllable,
        movement, order, null, null, null, null, null, queue, null, null,
        production, waypoints, 100,
    ).apply {
        productionCapable = movement in listOf("BUILDING", "NONE") && queue != null
    }

    private class FakeService : RwxLocalGameService {
        var ownUnits: List<RwxLocalGameTypes.UnitSnapshot> = emptyList()
        var enemyUnits: List<RwxLocalGameTypes.UnitSnapshot> = emptyList()
        var allyUnits: List<RwxLocalGameTypes.UnitSnapshot> = emptyList()
        var neutralUnits: List<RwxLocalGameTypes.UnitSnapshot> = emptyList()
        var issues = mutableListOf<RwxLocalGameTypes.Order>()
        var lastQuery: RwxLocalGameTypes.UnitQuery? = null
        var lastMapQuery: RwxLocalGameTypes.MapQuery? = null
        var mapSnapshot = RwxLocalGameTypes.MapSnapshot(
            2600f, 2400f, 20f, 20f, true, emptyList(), emptyList(), null)
        var actionList: List<RwxLocalGameTypes.ActionSnapshot> = emptyList()
        var keepCalls = mutableListOf<Triple<Long, String, Int>>()
        var keepPage = RwxLocalGameTypes.KeepPage(100, 6000, emptyList(), emptyList(), "keep rules: 0")
        var pendingPage = RwxLocalGameTypes.PendingPage(100, 6000, emptyList(), emptyList(), "pending: 0")
        /** One entry per `issue` call: either a receipt or a thrown message. */
        var issuePlan = mutableListOf<String>()

        override fun game() = RwxLocalGameTypes.GameSnapshot(
            100, 6000, false, 1f, "King of the Middle", 0, "undecided", 523.0, 8.3, 23, false)

        override fun units(query: RwxLocalGameTypes.UnitQuery): RwxLocalGameTypes.UnitPage {
            lastQuery = query
            val pool = (ownUnits + enemyUnits + allyUnits + neutralUnits)
                .filter { RwxEngineLocalGameService.matchesOwnerRelation(it.relation, query.owner) }
                .filter { query.hpBelowPercent == null
                    || it.health * 100f / it.maxHealth < query.hpBelowPercent!! }
            return RwxLocalGameTypes.UnitPage(100, 6000, pool, emptyList())
        }

        override fun state(owner: RwxLocalGameTypes.UnitOwner?) =
            RwxLocalGameTypes.GameStateSnapshot(game(), owner, ownUnits, emptyMap(), 0, 0)



        override fun pending() = pendingPage

        override fun issue(order: RwxLocalGameTypes.Order): RwxLocalGameTypes.CommandReceipt {
            issues.add(order)
            val planned = issuePlan.removeFirstOrNull()
            if (planned != null && planned.startsWith("error:")) {
                throw IllegalArgumentException(planned.removePrefix("error:"))
            }
            val status = when (planned) {
                "pending" -> RwxLocalGameTypes.ReceiptStatus.PENDING
                else -> RwxLocalGameTypes.ReceiptStatus.SUBMITTED
            }
            return RwxLocalGameTypes.CommandReceipt(100, order.unitIds, "accepted", false, status)
        }

        override fun keep(unitId: Long, actionId: String, count: Int): RwxLocalGameTypes.KeepPage {
            keepCalls.add(Triple(unitId, actionId, count))
            return keepPage
        }

        override fun keeps() = keepPage
        override fun clearKeep(id: Long?) = keepPage

        override fun setPaused(paused: Boolean) = throw UnsupportedOperationException()
        override fun setSpeed(speed: Float) = throw UnsupportedOperationException()
        override fun teams() = throw UnsupportedOperationException()
        override fun unit(unitId: Long) = throw UnsupportedOperationException()
        override fun actions(unitId: Long) = actionList
        override fun map(query: RwxLocalGameTypes.MapQuery): RwxLocalGameTypes.MapSnapshot {
            lastMapQuery = query
            return mapSnapshot
        }
        override fun reach(unitId: Long, x: Float, y: Float) = throw UnsupportedOperationException()
        override fun canPlace(builderId: Long, unitTypeId: String, x: Float, y: Float,
                              searchRadiusTiles: Int) = throw UnsupportedOperationException()
        override fun events(query: RwxLocalGameTypes.EventQuery) = throw UnsupportedOperationException()
        override fun threats(query: RwxLocalGameTypes.ThreatQuery) = throw UnsupportedOperationException()
        override fun armReplay() = throw UnsupportedOperationException()
        override fun startReplay(fileName: String?) = throw UnsupportedOperationException()
        override fun stopReplay() = throw UnsupportedOperationException()
        override fun replayStatus() = throw UnsupportedOperationException()
        override fun step(seconds: Double, timeoutMillis: Int) = throw UnsupportedOperationException()
        override fun camera() = throw UnsupportedOperationException()
        override fun moveCamera(centerX: Float?, centerY: Float?, left: Float?, top: Float?,
                                zoom: Float?) = throw UnsupportedOperationException()
        override fun groups() = throw UnsupportedOperationException()
        override fun saveGroup(name: String, unitsOption: String, excludedIds: List<Long>,
                               includeTypes: List<String>, excludeTypes: List<String>,
                               hpBelowPercent: Float?, idleOnly: Boolean,
                               excludeBuilders: Boolean) = throw UnsupportedOperationException()
        override fun deleteGroup(name: String) = throw UnsupportedOperationException()
        override fun observeReset(keepGroups: Boolean) = throw UnsupportedOperationException()
    }

    private fun run(service: FakeService, vararg arguments: String): String {
        return RwxLocalGameCommands(service).execute(RwxCliCommands.Command.BRIEF, arguments.toList())
    }

    private fun runUnitList(service: FakeService, vararg arguments: String): String =
        RwxLocalGameCommands(service).execute(RwxCliCommands.Command.UNIT_LIST, arguments.toList())

    @Test
    fun `brief composes current economy army production and health`() {
        val service = FakeService()
        service.ownUnits = listOf(
            unit(1, "c_tank"),
            unit(2, "c_tank", health = 40f),
            unit(3, "c_artillery", health = 20f),
            unit(4, "c_builder"),
            unit(5, "c_landFactory", movement = "BUILDING", queue = 2,
                production = listOf(RwxLocalGameTypes.QueueItem("produce", "c_tank", "坦克", null, null, null))),
            unit(6, "c_landFactory", movement = "BUILDING", queue = 1,
                production = listOf(RwxLocalGameTypes.QueueItem("upgrade", "upgrade", "升级", null, null, null, 0.42f))),
        )
        service.enemyUnits = listOf(unit(90, "e_tank", relation = "enemy"), unit(91, "e_tank", relation = "enemy"))

        val text = run(service)

        assertTrue(text.contains("战况 00:06"), text)
        assertTrue(text.contains("资金 523 (+8.3/s)"), text)
        assertTrue(text.contains("c_tank x2"), text)
        assertTrue(text.contains("视野内敌军:"), text)
        assertTrue(text.contains("e_tank"), text)
        assertTrue(text.contains("工厂 2（队列占用 2，空闲 0）"), text)
        assertTrue(text.contains("c_tank 濒死0/残血1/受伤0"), text)
        assertFalse(text.contains("最近15秒受击"), text)
        assertFalse(text.contains("击毁 30 | 损失 12"), text)
    }

    @Test
    fun `brief reports held orders by what they wait for`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1))
        service.pendingPage = RwxLocalGameTypes.PendingPage(100, 6000, listOf(
            RwxLocalGameTypes.PendingOrderSnapshot(1, 10, 100, "action", "c_tank", listOf(1L),
                null, null, null, "need 500, available 100", "credits"),
            RwxLocalGameTypes.PendingOrderSnapshot(2, 10, 100, "action", "c_heavyTank", listOf(1L),
                null, null, null, "upgrading", "upgrade"),
        ), emptyList(), "pending: 2")

        val text = run(service)
        assertTrue(text.contains("待发: 2（等待资金 1，等待升级 1）"), text)
    }

    @Test
    fun `brief accepts seconds and rejects removed minutes or invalid windows`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1))
        val defaults = Json.parseToJsonElement(run(service, "--json")).jsonObject
        assertEquals(60.0, defaults["windowSeconds"]!!.jsonPrimitive.double)
        val largest = Json.parseToJsonElement(run(service, "--seconds", "6000", "--json")).jsonObject
        assertEquals(6000.0, largest["windowSeconds"]!!.jsonPrimitive.double)
        for (args in listOf(listOf("--minutes", "1"), listOf("--seconds", "6001"), listOf("--seconds", "0"))) {
            assertFailsWith<IllegalArgumentException> { run(service, *args.toTypedArray()) }
        }
    }

    @Test
    fun `brief separates sides real factories upgrades and health severity buckets`() {
        val service = FakeService()
        service.ownUnits = listOf(
            unit(1, "a_tank", health = 9f, queue = 0).apply { productionCapable = true },
            unit(2, "a_tank", health = 10f),
            unit(3, "a_tank", health = 50f),
            unit(4, "a_tank", health = 80f),
            unit(5, "ignored", health = 80.1f),
            unit(6, "z_tank", health = 5f), unit(7, "z_tank", health = 5f),
            unit(8, "turret", health = 49f, movement = "NONE", queue = 1,
                production = listOf(RwxLocalGameTypes.QueueItem("upgrade", "t2", "T2", null, null, null, .3f)))
                .apply { productionCapable = false },
            unit(9, "factory", movement = "NONE", queue = 1,
                production = listOf(RwxLocalGameTypes.QueueItem("produce", "a_tank", "Tank", null, null, null))),
        )
        service.allyUnits = listOf(unit(10, "ally_tank", relation = "ally"),
            unit(11, "ally_factory", relation = "ally", movement = "NONE"))
        service.enemyUnits = listOf(unit(12, "enemy_tank", relation = "enemy"))
        val result = Json.parseToJsonElement(run(service, "--json")).jsonObject
        assertFalse("unitCount" in result)
        assertEquals(7, result["self"]!!.jsonObject["unitCount"]!!.jsonPrimitive.int)
        assertEquals(2, result["self"]!!.jsonObject["buildingCount"]!!.jsonPrimitive.int)
        assertEquals(1, result["allies"]!!.jsonObject["unitCount"]!!.jsonPrimitive.int)
        assertEquals(1, result["VisibleEnemies"]!!.jsonObject["enemy_tank"]!!.jsonPrimitive.int)
        assertEquals(1, result["production"]!!.jsonObject["factoryCount"]!!.jsonPrimitive.int)
        assertEquals(1, result["upgrades"]!!.jsonArray.size)
        val health = result["healthStatus"]!!.jsonObject
        val rows = health["units"]!!.jsonArray
        assertEquals("z_tank", rows[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("a_tank", rows[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1, rows[1].jsonObject["criticalCount"]!!.jsonPrimitive.int)
        assertEquals(1, rows[1].jsonObject["lowHealthCount"]!!.jsonPrimitive.int)
        assertEquals(2, rows[1].jsonObject["injuredCount"]!!.jsonPrimitive.int)
        assertEquals(1, health["building"]!!.jsonArray.size)
        assertFalse("pending" in result["production"]!!.jsonObject)
    }

    @Test
    fun `state filters union categories without menus or duplicate rows`() {
        val service = FakeService()
        service.ownUnits = listOf(
            unit(1, order = "moving"),
            unit(2, health = 69f), unit(3, health = 70f), unit(4),
            unit(5, "factory", movement = "NONE").apply { canProduce = true },
            unit(6, "locked_factory", movement = "NONE").apply { productionCapable = true },
            unit(7, "turret", movement = "NONE").apply { statuses = listOf("attacking", "upgrading") },
            unit(8, health = 30f, order = "moving"),
            unit(9, "construction", movement = "NONE").apply { statuses = listOf("constructing") },
            unit(10, "ally", relation = "ally", health = 10f, order = "moving"),
        )
        fun state(vararg args: String) = RwxLocalGameCommands(service)
            .execute(RwxCliCommands.Command.STATE, args.toList())
        val default = Json.parseToJsonElement(state("--json")).jsonObject
        assertEquals(listOf(1, 2, 8), default["notable Units"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int })
        assertEquals(listOf(5, 7, 9), default["notable buildings"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int })
        assertFalse("count" in default)
        assertFalse("units" in default)
        assertFalse(state().contains("actions:"))
        assertEquals(1, state().lineSequence().count { it.startsWith("#7 ") })
        assertTrue(state().contains("attacking,upgrading"))
        val combined = Json.parseToJsonElement(state("--json", "--units-acting", "--units-damaged")).jsonObject
        assertEquals(listOf(1, 2, 8), combined["notable Units"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int })
        assertTrue(combined["notable buildings"]!!.jsonArray.isEmpty())
        fun ids(flag: String, field: String = "notable Units") = Json.parseToJsonElement(state("--json", flag)).jsonObject[field]!!
            .jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        assertEquals(listOf(1, 8), ids("--units-acting"))
        assertEquals(listOf(2, 8), ids("--units-damaged"))
        assertEquals(listOf(7, 9), ids("--building-acting", "notable buildings"))
        assertEquals(listOf(5), ids("--building-actionable", "notable buildings"))
        assertTrue(ids("--building-acting").isEmpty())
        assertTrue(state().contains("\nnotable Units:\n#1 "))
        assertTrue(state().contains("\nnotable buildings:\n#5 "))
        assertFailsWith<IllegalArgumentException> { state("--owner", "self") }
        assertFailsWith<IllegalArgumentException> { state("--compact") }
    }

    @Test
    fun `unit-list type filter accepts ids and names and rejects the old include flag`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1, "c_tank"))
        runUnitList(service, "--full", "--type", "c_tank")
        runUnitList(service, "--full", "--type", "tank")
        // 旧的 --include 已并入 --type。
        assertFailsWith<IllegalArgumentException> {
            runUnitList(service, "--full", "--include", "c_tank")
        }
    }

    @Test
    fun `state summary keeps the macro fields and rejects selection flags`() {
        val service = FakeService()
        fun state(vararg args: String) = RwxLocalGameCommands(service)
            .execute(RwxCliCommands.Command.STATE, args.toList())
        val text = state("--summary")
        assertTrue(text.startsWith("游戏状态\ntick: 100"), text)
        assertTrue(text.contains("paused: false"), text)
        assertTrue(text.contains("speed: 1.0"), text)
        assertTrue(text.contains("map: King of the Middle"), text)
        assertTrue(text.contains("credits: 523"), text)
        assertTrue(text.contains("units: 23"), text)
        assertFalse(text.contains("notable Units"), text)
        val json = Json.parseToJsonElement(state("--summary", "--json")).jsonObject
        assertEquals(100, json["tick"]!!.jsonPrimitive.int)
        assertEquals(23, json["units"]!!.jsonPrimitive.int)
        assertEquals("King of the Middle", json["map"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { state("--summary", "--units-acting") }
    }

    @Test
    fun `unit-list summary counts every match by type and team`() {
        val service = FakeService()
        service.ownUnits = listOf(
            unit(1, "c_tank", health = 10f), unit(2, "c_tank"), unit(3, "c_builder"))
        service.allyUnits = listOf(unit(4, "a_tank", relation = "ally"))
        val text = runUnitList(service, "--summary")
        assertTrue(text.startsWith("单位汇总 (tick 100"), text)
        assertTrue(text.contains("count: 3"), text)
        assertTrue(service.lastQuery!!.summaryOnly)
        assertEquals(null, service.lastQuery!!.hpBelowPercent)
        assertFailsWith<IllegalArgumentException> { runUnitList(service, "--summary", "--json") }
        assertFailsWith<IllegalArgumentException> { runUnitList(service, "--summary", "--hp", "50") }
    }

    @Test
    fun `order-attack requires a target id and points coordinates at attack-move`() {
        val service = FakeService()
        fun run(vararg args: String) = RwxLocalGameCommands(service)
            .execute(RwxCliCommands.Command.ORDER_ATTACK, args.toList())
        val point = assertFailsWith<IllegalArgumentException> {
            run("--units", "1", "--x", "100", "--y", "200")
        }
        assertTrue(point.message!!.contains("rwx-game-order-attack-move"), point.message)
        assertFailsWith<IllegalArgumentException> { run("--units", "1") }
        assertFailsWith<IllegalArgumentException> { run("--units", "1", "--target", "2", "--x", "1") }
    }

    @Test
    fun `speed accepts only positive values and points zero at pause`() {
        val service = FakeService()
        fun speed(value: String) = RwxLocalGameCommands(service)
            .execute(RwxCliCommands.Command.SPEED, listOf("--value", value))
        val zero = assertFailsWith<IllegalArgumentException> { speed("0") }
        assertTrue(zero.message!!.contains("rwx-game-pause"), zero.message)
        assertFailsWith<IllegalArgumentException> { speed("65") }
    }

    @Test
    fun `state returns both empty notable arrays when nothing requires attention`() {
        val idle = unit(1)
        val service = FakeService().apply { ownUnits = listOf(idle) }
        val result = Json.parseToJsonElement(RwxLocalGameCommands(service)
            .execute(RwxCliCommands.Command.STATE, listOf("--json"))).jsonObject
        assertTrue(result["notable Units"]!!.jsonArray.isEmpty())
        assertTrue(result["notable buildings"]!!.jsonArray.isEmpty())
        assertFalse("count" in result)
    }

    @Test
    fun `unit-list defaults to own and visible enemy entities and requires explicit ally scope`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1, "own_tank"))
        service.enemyUnits = listOf(unit(2, "enemy_tank", relation = "enemy"))
        service.allyUnits = listOf(unit(3, "ally_tank", relation = "ally"))
        service.neutralUnits = listOf(unit(4, "tree", relation = "neutral"))
        fun ids(vararg args: String) = Json.parseToJsonElement(runUnitList(service, "--json", "--full", *args))
            .jsonObject["units"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        assertEquals(listOf(1, 2), ids())
        assertEquals(listOf(3), ids("--owner", "ally"))
        assertEquals(listOf(1), ids("--owner", "self"))
        assertEquals(listOf(2), ids("--owner", "enemy"))
        assertEquals(listOf(4), ids("--owner", "neutral"))
    }

    private fun terrainService(ascii: Boolean = false, clipped: Boolean = false): FakeService {
        val terrain = RwxLocalGameTypes.TerrainWindow(
            91, 85, 7, 1, 1820f, 1700f, 20f, 20f,
            arrayOf(intArrayOf(1, -1, -7, -9, 0, 8, -2)), ascii, clipped)
        return FakeService().apply {
            mapSnapshot = RwxLocalGameTypes.MapSnapshot(2600f, 2400f, 20f, 20f, true,
                listOf(RwxLocalGameTypes.ResourcePool(1910f, 1710f, null, null, null),
                    RwxLocalGameTypes.ResourcePool(1930f, 1710f, 42L, 3, "extractorT2")),
                emptyList(), terrain)
        }
    }

    private fun map(service: FakeService, vararg args: String) = RwxLocalGameCommands(service)
        .execute(RwxCliCommands.Command.MAP_SHOW, args.toList())

    @Test
    fun `map defaults to semantic codes and always reports resource occupancy`() {
        val service = terrainService()
        val text = map(service, "--terrain")
        assertTrue(text.startsWith("map(WxH/2600.0x2400.0,world unit):\n1 tile=20 world units"), text)
        assertTrue(text.contains("range: x=1820..1960 y=1700..1720(world unit) x=91..97 y=85..85(tiles)"), text)
        assertTrue(text.contains("scale: 140x20(world unit) 7x1(tile)"), text)
        assertTrue(text.contains("1910,1710 | 0 (unoccupied)"), text)
        assertTrue(text.contains("1930,1710 | 8 (occupied) | team 3 extractorT2 #42"), text)
        assertTrue(text.contains("y=85 |  1 -1 -7 -9  0  8 -2"), text)
        assertTrue(text.contains("legend:"), text)
        assertFalse(text.contains("(?)"), text)
        assertFalse(service.lastMapQuery!!.terrainAscii)
        assertEquals(8, service.lastMapQuery!!.terrainHalfWidthTiles)
        assertEquals(8, service.lastMapQuery!!.terrainHalfHeightTiles)
    }

    @Test
    fun `map ascii separates every cell and preserves global resource occupancy under fog`() {
        val service = terrainService(ascii = true, clipped = true)
        val text = map(service, "--terrain", "--ascii", "--fog", "--x", "2090", "--y", "1790",
            "--half-width", "4", "--half-height", "2")
        assertTrue(text.contains("y=85 | . ~ ^ * 0 8 ?"), text)
        assertTrue(text.contains("1930,1710 | 8 (occupied)"), text)
        assertTrue(text.contains("已裁剪"), text)
        assertTrue(service.lastMapQuery!!.terrainAscii)
        assertTrue(service.lastMapQuery!!.includeFog)
        assertEquals(2090f, service.lastMapQuery!!.terrainX)
        assertEquals(1790f, service.lastMapQuery!!.terrainY)
        assertEquals(4, service.lastMapQuery!!.terrainHalfWidthTiles)
        assertEquals(2, service.lastMapQuery!!.terrainHalfHeightTiles)
    }

    @Test
    fun `map numeric building labels are equal width and ascii excludes the overlay`() {
        val service = FakeService()
        val codes = arrayOf(intArrayOf(-3, 3, 1, 4, 1, 1), intArrayOf(1, 1, 1, 1, 1, 1))
        val labels = arrayOf(arrayOf(null, null, null, "4", "#123", "#21"),
            arrayOf("-4", null, null, null, null, null))
        fun snapshot(ascii: Boolean) = RwxLocalGameTypes.MapSnapshot(100f, 100f, 20f, 20f, true,
            emptyList(), emptyList(), RwxLocalGameTypes.TerrainWindow(
                0, 9, 6, 2, 0f, 180f, 20f, 20f, codes, ascii, false, labels))
        service.mapSnapshot = snapshot(false)
        val text = map(service, "--terrain")
        assertTrue(text.contains("4=allied-building"), text)
        assertTrue(text.contains("-4=enemy-building"), text)
        assertTrue(text.contains("#ID=our-building"), text)
        val rows = text.lines().filter { " |" in it }
        assertEquals(2, rows.size)
        assertEquals("   -3    3    1    4 #123  #21", rows.first().substringAfter('|'))
        assertEquals(rows[0].indexOf('|'), rows[1].indexOf('|'))
        assertEquals(rows[0].substringAfter('|').length, rows[1].substringAfter('|').length)
        codes[0] = intArrayOf(-1, 1, 1, 1, 1, 1)
        service.mapSnapshot = snapshot(true)
        val ascii = map(service, "--terrain", "--ascii")
        assertFalse(ascii.contains("#123"), ascii)
        assertFalse(ascii.contains("allied-building"), ascii)
    }

    @Test
    fun `map header matches the requested world and tile rectangle`() {
        val service = FakeService()
        service.mapSnapshot = RwxLocalGameTypes.MapSnapshot(2600f, 2400f, 20f, 20f, false,
            emptyList(), emptyList(), RwxLocalGameTypes.TerrainWindow(
                91, 85, 21, 21, 1820f, 1700f, 20f, 20f,
                Array(21) { IntArray(21) { 1 } }, false, false))
        val text = map(service, "--terrain", "--x", "2030", "--y", "1900",
            "--half-width", "10", "--half-height", "10")
        assertEquals(listOf(
            "map(WxH/2600.0x2400.0,world unit):",
            "1 tile=20 world units",
            "  range: x=1820..2240 y=1700..2120(world unit) x=91..111 y=85..105(tiles)",
            "  scale: 420x420(world unit) 21x21(tile)",
        ), text.lines().take(4))
        val rows = text.lines().filter { it.startsWith("  y=") }
        assertEquals(21, rows.size)
        assertTrue(rows.all { it.substringAfter('|').trim().split(Regex("\\s+")).size == 21 })
    }

    @Test
    fun `map validates rectangular arguments and rejects removed switches`() {
        val service = FakeService()
        for (args in listOf(
            listOf("--terrain", "--radius", "4"), listOf("--resources"),
            listOf("--terrain", "--codes"), listOf("--ascill"),
            listOf("--terrain", "--x", "1"), listOf("--terrain", "--half-width", "4"),
            listOf("--half-width", "4", "--half-height", "2"),
            listOf("--terrain", "--half-width", "0", "--half-height", "2"),
            listOf("--terrain", "--half-width", "41", "--half-height", "2"),
        )) assertFailsWith<IllegalArgumentException>(args.toString()) { map(service, *args.toTypedArray()) }
        val text = map(service)
        assertTrue(text.contains("resourcePools: 0"), text)
        assertFalse(service.lastMapQuery!!.includeTerrain)
    }

    @Test
    fun `unit-list compact prints one short line per unit`() {
        val service = FakeService()
        service.ownUnits = listOf(
            unit(1, "c_tank", health = 55f, order = "MOVE"),
            unit(2, "c_tank", queue = 3),
        )
        val text = runUnitList(service, "--owner", "self", "--compact", "--full")
        assertTrue(text.contains("#1 c_tank hp55% @100,100 | MOVE"), text)
        assertTrue(text.contains("#2 c_tank @100,100 | q3"), text)
        // compact 不打印完整明细里的 tick/build 字段
        assertTrue(!text.contains("build: "), text)
    }

    @Test
    fun `unit-list shows only entities under 80 percent health unless full or hp says otherwise`() {
        val service = FakeService()
        service.ownUnits = listOf(
            unit(1, "healthy", health = 100f),
            unit(2, "exactly80", health = 80f),
            unit(3, "under80", health = 79.9f),
            unit(4, "hurt", health = 30f),
        )
        fun ids(vararg args: String) = Json.parseToJsonElement(runUnitList(service, "--json", *args))
            .jsonObject["units"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }

        assertEquals(listOf(3, 4), ids())
        assertEquals(80f, service.lastQuery?.hpBelowPercent)
        assertEquals(listOf(2, 3, 4), ids("--hp", "100"))
        assertEquals(listOf(4), ids("--hp", "31"))
        assertEquals(listOf(1, 2, 3, 4), ids("--full"))
        assertEquals(null, service.lastQuery?.hpBelowPercent)

        assertFailsWith<IllegalArgumentException> { runUnitList(service, "--full", "--hp", "50") }
    }

    @Test
    fun `unit-list json is machine readable and read only`() {
        val service = FakeService()
        service.ownUnits = listOf(
            unit(1, "c_tank", health = 50f), unit(2, "c_artillery", health = 50f))
        val text = runUnitList(service, "--owner", "self", "--json")

        assertTrue(text.startsWith("{"), text)
        assertTrue(text.contains("\"count\":2"), text)
        assertTrue(text.contains("\"units\":[{\"id\":1,\"type\":\"c_tank\""), text)
        assertTrue(text.contains("\"types\":{\"c_tank\":1,\"c_artillery\":1}"), text)
        assertEquals(true, service.lastQuery?.summaryOnly, "JSON 视图不应消费变化游标")
    }

    @Test
    fun `unit-list only advances the change baseline with delta`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1, health = 50f))

        val plain = runUnitList(service, "--owner", "self")
        assertTrue(!plain.contains("变化 ("), plain)
        assertEquals(true, service.lastQuery?.summaryOnly, "默认观察不应消费变化游标")

        runUnitList(service, "--owner", "self", "--delta")
        assertEquals(false, service.lastQuery?.summaryOnly, "--delta 才推进变化基线")

        assertFailsWith<IllegalArgumentException> {
            runUnitList(service, "--delta", "--json")
        }
    }

    @Test
    fun `unit-list rejects the removed switches`() {
        val service = FakeService()
        for (removed in listOf("--damaged", "--count-only", "--no-delta")) {
            assertFailsWith<IllegalArgumentException>(removed) {
                runUnitList(service, removed)
            }
        }
    }



    @Test
    fun `actions drop the count field and report real queue depth`() {
        val service = FakeService()
        service.actionList = listOf(
            RwxLocalGameTypes.ActionSnapshot(
                "u_lightGunship", "Light Gun Ship", true, 3, false, false,
                "lightGunship", "250", null))
        val json = RwxLocalGameCommands(service).execute(
            RwxCliCommands.Command.ACTIONS, listOf("--unit", "24", "--json"))
        assertFalse(json.contains("\"count\""), json)
        assertTrue(json.contains("\"queued\":3"), json)
    }









    @Test
    fun `push queues a rally move then an attack move`() {        val service = FakeService()
        service.ownUnits = listOf(unit(1), unit(2), unit(3))
        val text = RwxLocalGameCommands(service).execute(RwxCliCommands.Command.PUSH, listOf(
            "--group", "main", "--rally-x", "1200", "--rally-y", "900", "--x", "2400", "--y", "1100"))

        assertEquals(2, service.issues.size)
        val move = service.issues[0]
        assertEquals(RwxLocalGameTypes.OrderType.MOVE, move.type)
        assertEquals(listOf(1L, 2L, 3L), move.unitIds)
        assertEquals(1200f, move.x)
        assertEquals(false, move.queued)
        val attack = service.issues[1]
        assertEquals(RwxLocalGameTypes.OrderType.ATTACK_MOVE, attack.type)
        assertEquals(true, attack.queued)
        assertTrue(text.contains("push: 3 units"), text)
        assertTrue(text.contains("集结: 1200,900"), text)
        assertTrue(text.contains("推进: attack-move 2400,1100（队列中，等集结完成）"), text)
    }

    @Test
    fun `push refuses to advance with fewer units than min count`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1))
        assertFailsWith<IllegalArgumentException> {
            RwxLocalGameCommands(service).execute(RwxCliCommands.Command.PUSH,
                listOf("--x", "1", "--y", "2", "--min-count", "5"))
        }
        assertTrue(service.issues.isEmpty())
    }

    @Test
    fun `keep forwards the rule and prints the rule list`() {
        val service = FakeService()
        service.keepPage = RwxLocalGameTypes.KeepPage(100, 6000, listOf(
            RwxLocalGameTypes.KeepRuleSnapshot(7, 294, "u_c_lightTank", 5, 2, 3, "已补到 3/5")),
            emptyList(), "keep rules: 1")
        val text = RwxLocalGameCommands(service).execute(RwxCliCommands.Command.KEEP,
            listOf("--unit", "294", "--action", "u_c_lightTank", "--count", "5"))

        assertEquals(listOf(Triple(294L, "u_c_lightTank", 5)), service.keepCalls)
        assertTrue(text.contains("#7 单位 294 · u_c_lightTank x5（当前 3） | 已补 2 | 已补到 3/5"), text)
    }

    @Test
    fun `receipts distinguish submitted pending and partial`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1))
        val submitted = RwxLocalGameCommands(service).execute(RwxCliCommands.Command.ORDER_MOVE,
            listOf("--units", "1", "--x", "10", "--y", "20"))
        assertTrue(submitted.contains("result: submitted"), submitted)

        service.issuePlan = mutableListOf("pending")
        val pending = RwxLocalGameCommands(service).execute(RwxCliCommands.Command.ORDER_MOVE,
            listOf("--units", "1", "--x", "10", "--y", "20"))
        assertTrue(pending.contains("result: pending"), pending)

        // 第 1 条提交成功，第 2 条失败：不能假装回滚，也不能丢掉第 1 条。
        service.issuePlan = mutableListOf("submitted", "error:工厂已被摧毁")
        val partial = RwxLocalGameCommands(service).execute(RwxCliCommands.Command.ORDER_ACTION,
            listOf("--units", "1", "--action", "c_tank", "--count", "2", "--allow-unaffordable"))
        assertTrue(partial.contains("result: partial"), partial)
        assertTrue(partial.contains("requested: 2 | submitted: 1 | pending: 0 | failed: 1"), partial)
        assertTrue(partial.contains("failure: 工厂已被摧毁"), partial)
    }

    @Test
    fun `allow-unaffordable says so when the failure is not about credits`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1))
        service.issuePlan = mutableListOf("error:Unit 1 (c_tank) has no action 'x'")
        val error = assertFailsWith<IllegalArgumentException> {
            RwxLocalGameCommands(service).execute(RwxCliCommands.Command.ORDER_ACTION,
                listOf("--units", "1", "--action", "x", "--allow-unaffordable"))
        }
        assertNotNull(error.message)
        assertTrue(error.message!!.contains("--allow-unaffordable 不会重试它"), error.message!!)
    }
}
