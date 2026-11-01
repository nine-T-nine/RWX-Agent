package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.serialization.json.*

/**
 * `rwx-game-state` / `unit-list --compact|--json|--full|--delta` / `order-push` / `keep` 的命令层测试。
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
            2600f, 2400f, 20f, 20f, true, emptyList(), false, null)
        var actionList: List<RwxLocalGameTypes.ActionSnapshot> = emptyList()
        var keepCalls = mutableListOf<Triple<Long, String, Int>>()
        var keepPage = RwxLocalGameTypes.KeepPage(100, 6000, emptyList(), emptyList(), "keep rules: 0")
        var pendingPage = RwxLocalGameTypes.PendingPage(100, 6000, emptyList(), emptyList(), "pending: 0")
        var pendingSupported = true
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
            RwxLocalGameTypes.GameStateSnapshot(game(), owner, ownUnits, allyUnits, enemyUnits, emptyMap(), 0, 0)



        override fun pending(): RwxLocalGameTypes.PendingPage {
            if (!pendingSupported) throw UnsupportedOperationException()
            return pendingPage
        }

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
        var eventHistory = mutableListOf<RwxLocalGameTypes.GameEvent>()
        var lastEventQuery: RwxLocalGameTypes.EventQuery? = null
        var eventSupported = true
        override fun events(query: RwxLocalGameTypes.EventQuery): RwxLocalGameTypes.EventPage {
            if (!eventSupported) throw UnsupportedOperationException("event service unavailable")
            lastEventQuery = query
            return RwxEventProcessor.query(eventHistory, 6000, query)
        }
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
        return RwxLocalGameCommands(service).execute(RwxCliCommands.Command.STATE, arguments.toList())
    }

    private fun runUnitList(service: FakeService, vararg arguments: String): String =
        RwxLocalGameCommands(service).execute(RwxCliCommands.Command.UNIT_LIST, arguments.toList())

    @Test
    fun `state summary includes all macro statistics`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1, "tank", health = 9f), unit(2, "factory", movement = "NONE", queue = 2,
            production = listOf(RwxLocalGameTypes.QueueItem("produce", "tank", "Tank", null, null, null),
                RwxLocalGameTypes.QueueItem("upgrade", "t2", "T2", null, null, null, .3f))))
        service.allyUnits = listOf(unit(3, "ally", relation = "ally"))
        service.enemyUnits = listOf(unit(4, "enemy", relation = "enemy"))
        val result = Json.parseToJsonElement(run(service, "--summary", "--json")).jsonObject
        assertEquals(523.0, result["economy"]!!.jsonObject["credits"]!!.jsonPrimitive.double)
        val forces = result["forces"]!!.jsonObject
        assertEquals(1, forces["self"]!!.jsonObject["unitCount"]!!.jsonPrimitive.int)
        assertEquals(1, forces["allies"]!!.jsonObject["unitCount"]!!.jsonPrimitive.int)
        assertEquals(1, forces["visibleEnemies"]!!.jsonObject["unitCount"]!!.jsonPrimitive.int)
        assertFalse("idleUnitCount" in forces["visibleEnemies"]!!.jsonObject)
        assertEquals("enemy", forces["visibleEnemies"]!!.jsonObject["typeCounts"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        val production = result["production"]!!.jsonObject
        assertEquals(1, production["busyFactoryCount"]!!.jsonPrimitive.int)
        assertEquals(1, production["productionQueueEntryCount"]!!.jsonPrimitive.int)
        assertEquals(1, result["upgrades"]!!.jsonArray.size)
        assertEquals(1, result["healthStatus"]!!.jsonObject["units"]!!.jsonArray[0].jsonObject["criticalCount"]!!.jsonPrimitive.int)
        assertEquals(0, result["pending"]!!.jsonObject["pendingOrders"]!!.jsonPrimitive.int)
        assertTrue(result["notableUnits"]!!.jsonArray.isEmpty())
        assertTrue(result["notableBuildings"]!!.jsonArray.isEmpty())
        val text = run(service)
        for (heading in listOf("Battle State", "Economy:", "Forces:", "Production:", "Upgrades:", "Health:", "Pending:", "Notable Units", "Notable Buildings", "Recent Events")) assertTrue(text.contains(heading), text)
    }

    @Test
    fun `event options share the event service without consuming history`() {
        val service = FakeService()
        val subject = RwxLocalGameTypes.EventEntity(1, "tank", "Tank", 0, "self", 10f, 20f)
        service.eventHistory.add(RwxLocalGameTypes.GameEvent(5000, RwxLocalGameTypes.EventType.INFO, subject, null, "Tank created"))
        val defaults = Json.parseToJsonElement(run(service, "--json")).jsonObject
        assertEquals(10.0, defaults["events"]!!.jsonObject["windowSeconds"]!!.jsonPrimitive.double)
        assertTrue(defaults["events"]!!.jsonObject["items"]!!.jsonArray.isEmpty())
        val info = Json.parseToJsonElement(run(service, "--json", "--event-types", "info", "--event-seconds", "2", "--event-allies")).jsonObject
        assertEquals("Tank created", info["events"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject["description"]!!.jsonPrimitive.content)
        assertTrue(service.lastEventQuery!!.allies)
        assertEquals(1, service.eventHistory.size)
        assertFalse("events" in Json.parseToJsonElement(run(service, "--json", "--no-events")).jsonObject)
        assertEquals(1, service.eventHistory.size)
        for (args in listOf(listOf("--no-events", "--event-allies"), listOf("--no-events", "--event-seconds", "10"), listOf("--event-seconds", "181"), listOf("--event-seconds", "0")))
            assertFailsWith<IllegalArgumentException> { run(service, *args.toTypedArray()) }
        assertEquals(null, RwxCliCommands.find("rwx-game-" + "brief"))
    }

    @Test
    fun `events match standalone command and use game time and ally filter`() {
        val service = FakeService()
        val ally = RwxLocalGameTypes.EventEntity(1, "tank", "Tank", 2, "ally", 10f, 20f)
        val own = RwxLocalGameTypes.EventEntity(2, "tank", "Tank", 0, "self", 10f, 20f)
        service.eventHistory.add(RwxLocalGameTypes.GameEvent(1000, RwxLocalGameTypes.EventType.LOSSES, own, null, null))
        service.eventHistory.add(RwxLocalGameTypes.GameEvent(5000, RwxLocalGameTypes.EventType.LOSSES, ally, null, null))
        val executor = RwxLocalGameCommands(service)
        val before = executor.execute(RwxCliCommands.Command.EVENTS, listOf("--types", "losses", "--seconds", "2", "--allies"))
        val state = executor.execute(RwxCliCommands.Command.STATE, listOf("--event-types", "losses", "--event-seconds", "2", "--event-allies"))
        val after = executor.execute(RwxCliCommands.Command.EVENTS, listOf("--types", "losses", "--seconds", "2", "--allies"))
        assertTrue(state.contains(before), state)
        assertTrue(before.contains("allied-2 lost"), before)
        assertEquals(before, after)
        assertEquals(2, service.eventHistory.size)
    }

    @Test
    fun `unsupported pending remains unknown in both formats`() {
        val service = FakeService().apply { pendingSupported = false }
        val result = Json.parseToJsonElement(run(service, "--json")).jsonObject
        assertEquals(JsonNull, result["pending"])
        assertTrue(run(service).contains("Total: unknown"))
    }

    @Test
    fun `pending waits are counted separately and event failure is explicit`() {
        val service = FakeService()
        service.pendingPage = RwxLocalGameTypes.PendingPage(100, 6000, listOf(
            RwxLocalGameTypes.PendingOrderSnapshot(1, 10, 100, "action", "tank", listOf(1L),
                null, null, null, "credits", "credits"),
            RwxLocalGameTypes.PendingOrderSnapshot(2, 10, 100, "action", "tank", listOf(1L),
                null, null, null, "upgrade", "upgrade")), emptyList(), "pending: 2")
        val pending = Json.parseToJsonElement(run(service, "--json")).jsonObject["pending"]!!.jsonObject
        assertEquals(2, pending["pendingOrders"]!!.jsonPrimitive.int)
        assertEquals(1, pending["waitingForCreditsCount"]!!.jsonPrimitive.int)
        assertEquals(1, pending["waitingForUpgradeCount"]!!.jsonPrimitive.int)
        service.eventSupported = false
        assertFailsWith<UnsupportedOperationException> { run(service) }
        assertFalse("events" in Json.parseToJsonElement(run(service, "--json", "--no-events")).jsonObject)
    }

    @Test
    fun `health boundaries and upgrade queues remain distinct`() {
        val service = FakeService()
        service.ownUnits = listOf(unit(1, "tank", health = 9f), unit(2, "tank", health = 10f),
            unit(3, "tank", health = 50f), unit(4, "tank", health = 80f), unit(5, "tank", health = 80.1f),
            unit(6, "factory", movement = "NONE", queue = 1,
                production = listOf(RwxLocalGameTypes.QueueItem("upgrade", "t2", "T2", null, null, null, .75f))),
            unit(7, "factory", movement = "NONE", queue = 0),
            unit(8, "turret", movement = "NONE", health = 49f))
        val result = Json.parseToJsonElement(run(service, "--summary", "--json")).jsonObject
        val health = result["healthStatus"]!!.jsonObject
        val units = health["units"]!!.jsonArray.single().jsonObject
        assertEquals(1, units["criticalCount"]!!.jsonPrimitive.int)
        assertEquals(1, units["lowHealthCount"]!!.jsonPrimitive.int)
        assertEquals(2, units["injuredCount"]!!.jsonPrimitive.int)
        assertEquals(1, health["buildings"]!!.jsonArray.single().jsonObject["lowHealthCount"]!!.jsonPrimitive.int)
        val production = result["production"]!!.jsonObject
        assertEquals(2, production["factoryCount"]!!.jsonPrimitive.int)
        assertEquals(1, production["busyFactoryCount"]!!.jsonPrimitive.int)
        assertEquals(1, production["idleFactoryCount"]!!.jsonPrimitive.int)
        assertEquals(0, production["productionQueueEntryCount"]!!.jsonPrimitive.int)
        assertEquals(.75, result["upgrades"]!!.jsonArray.single().jsonObject["progress"]!!.jsonPrimitive.double)
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
        assertEquals(listOf(1, 2, 8), default["notableUnits"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int })
        assertEquals(listOf(5, 7, 9), default["notableBuildings"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int })
        assertFalse("count" in default)
        assertFalse("units" in default)
        assertFalse(state().contains("actions:"))
        assertEquals(1, state().lineSequence().count { it.trimStart().startsWith("#7 ") })
        assertTrue(state().contains("attacking, upgrading"))
        val combined = Json.parseToJsonElement(state("--json", "--units-acting", "--units-damaged")).jsonObject
        assertEquals(listOf(1, 2, 8), combined["notableUnits"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int })
        assertTrue(combined["notableBuildings"]!!.jsonArray.isEmpty())
        fun ids(flag: String, field: String = "notableUnits") = Json.parseToJsonElement(state("--json", flag)).jsonObject[field]!!
            .jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }
        assertEquals(listOf(1, 8), ids("--units-acting"))
        assertEquals(listOf(2, 8), ids("--units-damaged"))
        assertEquals(listOf(7, 9), ids("--building-acting", "notableBuildings"))
        assertEquals(listOf(5), ids("--building-actionable", "notableBuildings"))
        assertTrue(ids("--building-acting").isEmpty())
        assertTrue(state().contains("Notable Units (3):\n  #1 "))
        assertTrue(state().contains("Notable Buildings (3):\n  #5 "))
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
    fun `summary rejects selection flags`() {
        val service = FakeService()
        for (flag in listOf("--units-acting", "--units-damaged", "--building-acting", "--building-actionable"))
            assertFailsWith<IllegalArgumentException> { run(service, "--summary", flag) }
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
        assertTrue(result["notableUnits"]!!.jsonArray.isEmpty())
        assertTrue(result["notableBuildings"]!!.jsonArray.isEmpty())
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
        assertEquals(listOf(1, 2, 3, 4), ids("--owner", "any"))
        assertTrue(runUnitList(service, "--owner", "any", "--full").contains("Filter: owner=any | hp=all"))
    }

    @Test
    fun `unit-list compact does not change the effective filter`() {
        val service = FakeService().apply {
            ownUnits = listOf(unit(1, health = 55f), unit(2, health = 100f))
        }
        val plain = runUnitList(service, "--owner", "self")
        val compact = runUnitList(service, "--owner", "self", "--compact")
        assertTrue(plain.contains("Filter: owner=self | hp<80%"), plain)
        assertTrue(compact.contains("Filter: owner=self | hp<80%"), compact)
        assertTrue(plain.contains("#1 "), plain)
        assertTrue(compact.contains("#1 "), compact)
        assertFalse(plain.contains("#2 "), plain)
        assertFalse(compact.contains("#2 "), compact)
    }

    private fun terrainWindow(
        codes: Array<IntArray>,
        mode: RwxLocalGameTypes.MapUnitMode = RwxLocalGameTypes.MapUnitMode.NONE,
        cells: Array<Array<String?>>? = null,
        entities: List<RwxLocalGameTypes.MapEntity> = emptyList(),
        clipped: Boolean = false,
    ) = RwxLocalGameTypes.TerrainWindow(
        91, 85, codes[0].size, codes.size, 1820f, 1700f, 20f, 20f,
        codes, mode, cells, entities, true, clipped)

    private fun mapService(
        terrain: RwxLocalGameTypes.TerrainWindow,
        pools: List<RwxLocalGameTypes.ResourcePool> = emptyList(),
        requested: Boolean = false,
        fog: Boolean = true,
    ): FakeService = FakeService().apply {
        mapSnapshot = RwxLocalGameTypes.MapSnapshot(
            2600f, 2400f, 20f, 20f, fog, pools, requested, terrain)
    }

    private fun map(service: FakeService, vararg args: String) = RwxLocalGameCommands(service)
        .execute(RwxCliCommands.Command.MAP_SHOW, args.toList())

    /** Grid rows live between the window header (plus an optional clip note) and the legend. */
    private fun gridRows(text: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("  window:") } + 1
        val end = lines.indexOfFirst { it.startsWith("  terrain:") }
        return lines.subList(start, end).filterNot { it.startsWith("  note:") }
    }

    @Test
    fun `map defaults to single character ascii terrain with automatic fog masking`() {
        val codes = arrayOf(intArrayOf(1, -1, -7, -9, 0, 8, -2))
        val service = mapService(terrainWindow(codes))
        val text = map(service)
        assertTrue(text.startsWith("map(WxH/2600.0x2400.0,world unit):\n1 tile=20 world units"), text)
        assertTrue(text.contains("range: x=1820..1960 y=1700..1720(world unit) x=91..97 y=85..85(tiles)"), text)
        assertTrue(text.contains("scale: 140x20(world unit) 7x1(tile)"), text)
        assertTrue(text.contains("origin=tile 91,85(top-left) x=left-to-right y=top-to-bottom"), text)
        assertTrue(text.contains("cell=1 char"), text)
        assertTrue(text.contains("mode=terrain"), text)
        assertTrue(text.contains("fog=on"), text)
        assertEquals(listOf(". ~ ^ * 0 8 ?"), gridRows(text))
        assertTrue(text.contains(
            "terrain: .=land, ~=water, ^=mountain, *=lava, 0=free_resource, " +
                "8=occupied_resource, ?=fog"), text)
        assertFalse(text.contains("  units:"), text)
        assertFalse(text.contains("  own:"), text)
        assertFalse(text.contains("resourcePools"), text)
        assertEquals(RwxLocalGameTypes.MapUnitMode.NONE, service.lastMapQuery!!.unitMode)
        assertFalse(service.lastMapQuery!!.includeResourcePools)
        assertEquals(8, service.lastMapQuery!!.terrainHalfWidthTiles)
        assertEquals(8, service.lastMapQuery!!.terrainHalfHeightTiles)
    }

    @Test
    fun `fog masking reports off when the map has fog disabled`() {
        val service = mapService(terrainWindow(arrayOf(intArrayOf(1))), fog = false)
        assertTrue(map(service).contains("fog=off"), map(service))
    }

    @Test
    fun `unit mode pads every cell to five characters and keeps rows equal width`() {
        val codes = arrayOf(intArrayOf(1, 1, -1), intArrayOf(-2, 0, 8))
        val cells = arrayOf(arrayOf<String?>("+lhtk", null, "-LHTK"),
            arrayOf<String?>(null, "+lbld", null))
        val entities = listOf(
            RwxLocalGameTypes.MapEntity(7, 91, 85, "Lhtk", "heavy_tank", "self", false),
            RwxLocalGameTypes.MapEntity(12, 92, 85, "Lhtk", "heavy_tank", "self", false),
            RwxLocalGameTypes.MapEntity(3, 93, 85, "Lhtk", "heavy_tank", "enemy", false),
            RwxLocalGameTypes.MapEntity(21, 92, 86, "Lbld", "builder", "self", false),
        )
        val text = map(mapService(terrainWindow(codes, RwxLocalGameTypes.MapUnitMode.LAND, cells, entities)))
        val rows = gridRows(text)
        assertEquals(listOf("+lhtk ..... -LHTK", "????? +lbld 88888"), rows)
        assertTrue(rows.all { it.length == 17 }, rows.toString())
        assertTrue(rows.all { it.split(" ").all { cell -> cell.length == 5 } }, rows.toString())
        assertTrue(text.contains("cell=5 chars"), text)
        assertTrue(text.contains("mode=unit-land"), text)
        assertTrue(text.contains("faction: +xxxx=own +XXXX=ally -XXXX=enemy -xxxx=neutral"), text)
        assertTrue(text.contains("units: LHTK/lhtk = heavy_tank | LBLD/lbld = builder"), text)
        assertTrue(text.contains("terrain: .....=land, ~~~~~=water, 00000=free_resource, " +
            "88888=occupied_resource, ?????=fog"), text)
        assertTrue(text.contains("    heavy_tank: #007(id=7)@tile(91,85), #012(id=12)@tile(92,85)"), text)
        assertTrue(text.contains("    builder: #021(id=21)@tile(92,86)"), text)
        assertFalse(text.contains("#003"), text)
    }

    @Test
    fun `unit mode without any entity repeats the terrain character five times`() {
        val codes = arrayOf(intArrayOf(1, -1, -7))
        val text = map(mapService(terrainWindow(codes, RwxLocalGameTypes.MapUnitMode.AIR)))
        assertEquals(listOf("..... ~~~~~ ^^^^^"), gridRows(text))
        assertTrue(text.contains("  own: none"), text)
        assertFalse(text.contains("  units:"), text)
    }

    @Test
    fun `own unit ids keep three digits and are never truncated`() {
        val codes = arrayOf(intArrayOf(1, 1))
        val cells = arrayOf(arrayOf<String?>("+lbld", "+lbld"))
        val entities = listOf(
            RwxLocalGameTypes.MapEntity(5, 91, 85, "Lbld", "builder", "self", false),
            RwxLocalGameTypes.MapEntity(1557, 92, 85, "Lbld", "builder", "self", false),
        )
        val text = map(mapService(
            terrainWindow(codes, RwxLocalGameTypes.MapUnitMode.LAND, cells, entities)))
        assertTrue(text.contains("builder: #005(id=5)@tile(91,85), #1557(id=1557)@tile(92,85)"), text)
    }

    @Test
    fun `spawns appends window scoped resource pools without changing the grid`() {
        val codes = arrayOf(intArrayOf(0, 8))
        val pools = listOf(
            RwxLocalGameTypes.ResourcePool(1910f, 1710f, 95, 85, null, null, null, null),
            RwxLocalGameTypes.ResourcePool(1930f, 1710f, 96, 85, 42L, 3, "extractorT2", "self"),
        )
        val service = mapService(terrainWindow(codes), pools, requested = true)
        val text = map(service, "--spawns")
        assertEquals(listOf("0 8"), gridRows(text))
        assertTrue(text.contains("resourcePools: 2"), text)
        assertTrue(text.contains("tile 95,85 world 1910,1710 | free"), text)
        assertTrue(text.contains("tile 96,85 world 1930,1710 | occupied | self extractorT2 id=42"), text)
        assertTrue(service.lastMapQuery!!.includeResourcePools)
        assertEquals(listOf("0 8"), gridRows(map(mapService(terrainWindow(codes), pools))))
    }

    @Test
    fun `spawns reports an empty window without dropping occupancy from the grid`() {
        val service = mapService(terrainWindow(arrayOf(intArrayOf(8))), emptyList(), requested = true)
        val text = map(service, "--spawns")
        assertTrue(text.contains("resourcePools: 0 (none in window)"), text)
        assertEquals(listOf("8"), gridRows(text))
    }

    @Test
    fun `unit modes are mutually exclusive and removed switches are rejected`() {
        val service = mapService(terrainWindow(arrayOf(intArrayOf(1))))
        for (args in listOf(
            listOf("--unit-land", "--unit-air"),
            listOf("--unit-land", "--unit-underwater"),
            listOf("--unit-air", "--unit-building"),
            listOf("--fog"),
            listOf("--terrain"),
            listOf("--ascii"),
            listOf("--unit-land", "--unit-land"),
            listOf("--radius", "4"),
            listOf("--x", "1"),
            listOf("--half-width", "4"),
            listOf("--half-width", "0", "--half-height", "2"),
            listOf("--half-width", "41", "--half-height", "2"),
        )) assertFailsWith<IllegalArgumentException>(args.toString()) { map(service, *args.toTypedArray()) }
    }

    @Test
    fun `each unit mode reaches the service with its own filter`() {
        val service = mapService(terrainWindow(arrayOf(intArrayOf(1))))
        val expected = mapOf(
            "--unit-land" to RwxLocalGameTypes.MapUnitMode.LAND,
            "--unit-air" to RwxLocalGameTypes.MapUnitMode.AIR,
            "--unit-underwater" to RwxLocalGameTypes.MapUnitMode.UNDERWATER,
            "--unit-building" to RwxLocalGameTypes.MapUnitMode.BUILDING,
        )
        for ((flag, mode) in expected) {
            map(service, flag, "--x", "2090", "--y", "1790", "--half-width", "4", "--half-height", "2")
            assertEquals(mode, service.lastMapQuery!!.unitMode, flag)
            assertEquals(2090f, service.lastMapQuery!!.terrainX)
            assertEquals(1790f, service.lastMapQuery!!.terrainY)
            assertEquals(4, service.lastMapQuery!!.terrainHalfWidthTiles)
            assertEquals(2, service.lastMapQuery!!.terrainHalfHeightTiles)
        }
    }

    @Test
    fun `clipped windows keep the header note and the grid width`() {
        val text = map(mapService(terrainWindow(arrayOf(intArrayOf(1, 1)), clipped = true)))
        assertTrue(text.contains("已裁剪"), text)
        assertEquals(listOf(". ."), gridRows(text))
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
        assertTrue(text.contains("#2 c_tank @100,100 hp100% | q3"), text)
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


