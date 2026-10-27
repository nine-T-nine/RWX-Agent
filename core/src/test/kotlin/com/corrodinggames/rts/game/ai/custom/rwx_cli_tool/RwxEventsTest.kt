package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.land.Artillery
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameStatistics
import com.corrodinggames.rts.gameFramework.UnitEventListener
import com.corrodinggames.rts.gameFramework.UnitEventManager
import java.lang.reflect.Proxy
import kotlin.test.*
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.*

class RwxEventsTest {
    private fun entity(id: Long, name: String = "Tank", team: Int = 0, side: String = "self",
                       x: Float? = 100f, y: Float? = 200f, type: String = name) =
        EventEntity(id, type, name, team, side, x, y)

    private fun event(time: Int, type: EventType = EventType.LOSSES, id: Long = 1,
                      killer: EventEntity? = entity(90, "Heavy Tank", 1, "enemy", 120f, 210f),
                      subject: EventEntity = entity(id), detail: String? = null) =
        GameEvent(time, type, subject, killer, detail)

    private fun query(raw: List<GameEvent>, now: Int = 30_000, types: List<EventType>? = null,
                      seconds: Double = 30.0, allies: Boolean = false) =
        RwxEventProcessor.query(raw, now, EventQuery(types, seconds, allies)).lines

    @Test fun `structured results describe the same merged events as text`() {
        val raw = listOf(event(10_000, id = 1), event(13_000, id = 2))
        val page = RwxEventProcessor.query(raw, 30_000, EventQuery(null, 30.0, false))
        assertEquals(1, page.items.size)
        assertEquals(10_000, page.items[0].gameTimeMillis)
        assertEquals(EventType.LOSSES, page.items[0].type)
        assertEquals("we lost Tank x2 by Heavy Tank(120,210)x2", page.items[0].description)
        assertEquals("[00:10][losses] ${page.items[0].description}", page.lines.single())
        assertEquals(2, raw.size)
        assertEquals("[61:01][info] ready", RwxEventProcessor.format(3_661_000, "info", "ready"))
    }

    @Test fun `defaults omit info and format only event lines`() {
        val raw = listOf(
            event(572_000, EventType.DAMAGED, 123),
            event(573_000, EventType.INFO, detail = "Tank,1 was created"),
            event(574_000, EventType.LOSSES, 124),
        )
        assertEquals(listOf(
            "[09:32][damaged] Tank,123(100,200) was damaged by Heavy Tank(120,210)",
            "[09:34][losses] we lost Tank,124(100,200) by Heavy Tank(120,210)",
        ), query(raw, now = 580_000))
        assertEquals(listOf("[09:33][info] Tank,1 was created"),
            query(raw, 580_000, listOf(EventType.INFO)))
        assertEquals(emptyList(), query(emptyList()))
    }

    @Test fun `allies switches only kills and losses while own damage info and discoveries remain`() {
        val us = entity(11)
        val ally = entity(12, "Artillery", 2, "ally")
        val hostile = entity(13, "Turret", 1, "enemy")
        val raw = listOf(
            event(1000, EventType.DAMAGED, subject = us),
            event(1000, EventType.DAMAGED, subject = ally),
            event(1000, EventType.LOSSES, subject = us),
            event(1000, EventType.LOSSES, subject = ally),
            event(1000, EventType.DESTROYED, subject = hostile, killer = us),
            event(1000, EventType.DESTROYED, subject = entity(14, "Turret", 1, "enemy"), killer = ally),
            event(1000, EventType.INFO, subject = us, detail = "Tank,11 was created"),
            event(1000, EventType.INFO, subject = ally, detail = "must be filtered"),
            event(1000, EventType.NEW, subject = hostile),
        )
        val own = query(raw, types = EventType.values().toList())
        val allies = query(raw, types = EventType.values().toList(), allies = true)
        assertEquals(5, own.size)
        assertEquals(5, allies.size)
        assertTrue(own.any { "our Tank,11 has destroyed" in it })
        assertTrue(allies.any { "allied-2 Artillery has destroyed" in it })
        assertTrue(allies.any { "allied-2 lost Artillery(100,200)" in it })
        assertFalse(allies.any { "allied-2 Artillery,12" in it || "we lost" in it })
        assertTrue(allies.any { "Tank,11(100,200) was damaged" in it })
        assertTrue(allies.any { "[info] Tank,11 was created" in it })
        assertTrue(allies.any { "[new] found Turret at" in it })
    }

    @Test fun `destruction dedup runs after window and side filtering and keeps earliest valid hit`() {
        val target = entity(500, "Turret", 1, "enemy", null, null)
        val raw = listOf(
            event(1000, EventType.DESTROYED, subject = target, killer = null),
            event(2000, EventType.DESTROYED, subject = target, killer = entity(22, side = "ally", team = 2)),
            event(3000, EventType.DESTROYED, subject = target, killer = entity(23)),
            event(4000, EventType.DESTROYED, subject = target, killer = entity(24)),
        )
        assertEquals(listOf("[00:03][destroyed] our Tank,23 has destroyed Turret"), query(raw))
        assertEquals(listOf("[00:04][destroyed] our Tank,24 has destroyed Turret"),
            query(raw, now = 4000, seconds = 0.5))
    }

    @Test fun `losses dedup then chain merges with exact three second boundary`() {
        val raw = listOf(event(10_000, id = 1), event(13_000, id = 2),
            event(13_001, id = 2), event(16_000, id = 3), event(19_001, id = 4))
        assertEquals(listOf(
            "[00:10][losses] we lost Tank x3 by Heavy Tank(120,210)x3",
            "[00:19][losses] we lost Tank,4(100,200) by Heavy Tank(120,210)",
        ), query(raw))
        assertEquals(5, raw.size)
    }

    @Test fun `different allied teams and victim types never merge`() {
        val raw = listOf(
            event(1000, subject = entity(1, team = 2, side = "ally")),
            event(2000, subject = entity(2, team = 3, side = "ally")),
            event(3000, subject = entity(3, "Artillery", 2, "ally")),
            event(4000, subject = entity(4, team = 2, side = "ally")),
        )
        val lines = query(raw, allies = true)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("allied-2 lost Tank x2"))
        assertTrue(lines[1].contains("allied-3 lost Tank("))
        assertTrue(lines[2].contains("allied-2 lost Artillery("))
    }

    @Test fun `killer counts are actual deduplicated losses ordered by count then first occurrence`() {
        val tank = entity(90, "Tank", 1, "enemy", 10f, 20f)
        val tank2 = entity(91, "Tank", 1, "enemy", 900f, 900f)
        val heavy = entity(92, "Heavy Tank", 1, "enemy", 30f, 40f)
        val raw = listOf(event(1000, id = 1, killer = tank), event(1100, id = 2, killer = heavy),
            event(1200, id = 3, killer = tank2), event(1300, id = 4, killer = heavy),
            event(1400, id = 5, killer = null), event(1500, id = 6, killer = null),
            event(1600, id = 6, killer = null))
        assertEquals("[00:01][losses] we lost Tank x6 by Tank(10,20)x2, Heavy Tank(30,40)x2, etc.", query(raw).single())
        assertFalse(query(raw).single().contains("900"))
        assertEquals("[00:01][losses] we lost Tank x4 by unknown x3, Tank(10,20)",
            query(listOf(event(1000, id = 1, killer = tank), event(1100, id = 2, killer = null),
                event(1200, id = 3, killer = null), event(1300, id = 4, killer = null))).single())
    }

    @Test fun `hidden killer coordinates are never filled from later visible same-type attackers`() {
        val raw = listOf(event(1000, id = 1, killer = entity(99, "Tank", 1, "enemy", null, null)),
            event(2000, id = 2, killer = entity(100, "Tank", 1, "enemy", 999f, 999f)))
        assertEquals("[00:01][losses] we lost Tank x2 by Tank x2", query(raw).single())
        assertEquals("[00:01][damaged] Tank,1(100,200) was damaged by unknown",
            query(listOf(event(1000, EventType.DAMAGED, killer = null))).single())
    }

    @Test fun `discoveries chain by neighboring events and average original coordinates`() {
        val raw = listOf(
            event(10_000, EventType.NEW, subject = entity(1, "Tank", 1, "enemy", 0f, 0f)),
            event(12_000, EventType.NEW, subject = entity(2, "Tank", 1, "enemy", 90f, 30f)),
            event(14_000, EventType.NEW, subject = entity(3, "Turret T1", 1, "enemy", 30f, 60f)),
        )
        assertEquals("[00:10][new] found Tank x2, Turret T1 x1 around (40,30)", query(raw).single())
        val reappeared = raw + event(25_000, EventType.NEW, subject = entity(1, "Tank", 1, "enemy", 600f, 800f))
        val lines = query(reappeared)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("found Tank x1, Turret T1 x1 around (60,45)"))
        assertEquals("[00:25][new] found Tank at (600,800)", lines[1])
        assertEquals(4, reappeared.size)
    }

    @Test fun `all raw events are aggregated before fifty display rows are selected`() {
        val raw = (1L..100L).map { event(1000, id = it) } +
            (101L..149L).map { event(2000, EventType.DAMAGED, it) }
        val lines = query(raw)
        assertEquals(50, lines.size)
        assertTrue(lines.first().contains("Tank x100"))
        assertEquals(149, raw.size)
        val many = (1L..100L).map { event(1000, EventType.INFO, it, detail = "entry $it") }
        val newest = query(many, types = listOf(EventType.INFO))
        assertEquals(50, newest.size)
        assertTrue(newest.first().endsWith("entry 51"))
        assertTrue(newest.last().endsWith("entry 100"))
    }

    @Test fun `time boundary is inclusive and queries leave the raw store untouched`() {
        val store = RwxEventStore()
        store.append(event(0))
        assertEquals(1, query(store.snapshot(), now = 180_000, seconds = 180.0).size)
        val before = store.snapshot()
        assertEquals(emptyList(), query(store.snapshot(), now = 180_001, seconds = 180.0))
        assertEquals(before, store.snapshot())
        store.maintain(180_000)
        assertEquals(1, store.size())
        store.maintain(180_001)
        assertEquals(0, store.size())
    }

    @Test fun `damage sampling throttle uses victim id not attacker and discarded hits do not advance it`() {
        val store = RwxEventStore()
        assertTrue(store.append(event(0, EventType.DAMAGED)))
        assertFalse(store.append(event(4999, EventType.DAMAGED, killer = entity(999))))
        assertTrue(store.append(event(4999, EventType.DAMAGED, id = 2)))
        assertTrue(store.append(event(5000, EventType.DAMAGED)))
        assertFalse(store.append(event(9999, EventType.DAMAGED)))
        assertTrue(store.append(event(10_000, EventType.DAMAGED)))
        assertFalse(store.append(event(11_000, EventType.DAMAGED, subject = entity(3, side = "ally", team = 2))))
        assertEquals(4, store.size())
        store.clear()
        assertTrue(store.append(event(10_001, EventType.DAMAGED)))
    }

    @Test fun `capacity evicts oldest raw records without dedup or dropping damage throttles`() {
        val store = RwxEventStore()
        store.append(event(0, EventType.DAMAGED))
        repeat(1000) { store.append(event(1000, id = 5)) }
        assertEquals(1000, store.size())
        assertTrue(store.snapshot().all { it.type == EventType.LOSSES })
        assertFalse(store.append(event(4999, EventType.DAMAGED)))
        assertTrue(store.append(event(5000, EventType.DAMAGED)))
        assertEquals(1000, store.size())
    }

    @Test fun `command accepts only the three new options and rejects removed commands`() {
        var received: EventQuery? = null
        val service = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RwxLocalGameService::class.java)) { _, method, args ->
            assertEquals("events", method.name)
            received = args!![0] as EventQuery
            EventPage(listOf("[00:01][new] found Tank at (100,200)"))
        } as RwxLocalGameService
        val executor = RwxLocalGameCommands(service)
        val command = assertNotNull(RwxCliCommands.find("rwx-game-events"))
        val result = executor.execute(command, emptyList())
        assertEquals("[00:01][new] found Tank at (100,200)", result)
        assertEquals(30.0, received!!.seconds)
        assertEquals(setOf(EventType.DAMAGED, EventType.DESTROYED, EventType.LOSSES, EventType.NEW), received!!.types.toSet())
        executor.execute(command, listOf("--types", "losses,info,losses", "--seconds", "180", "--allies"))
        assertEquals(listOf(EventType.LOSSES, EventType.INFO), received!!.types)
        assertTrue(received!!.allies)
        for (args in listOf(listOf("--wait"), listOf("--after", "1"), listOf("--tail"), listOf("--limit", "1"),
            listOf("--relation", "self"), listOf("--since", "1"), listOf("--since-seconds", "1"),
            listOf("--type", "info"), listOf("--types", "left"), listOf("--types", "created"), listOf("--types", ""),
            listOf("--seconds", "181"), listOf("--seconds", "0"), listOf("--seconds", "NaN"))) {
            assertFailsWith<IllegalArgumentException>(args.toString()) { executor.execute(command, args) }
        }
        for (name in listOf("alerts", "stats", "loss-list")) assertNull(RwxCliCommands.find("rwx-game-$name"))
    }
}

class RwxEventCollectorTest {
    private lateinit var engine: GameEngine
    private lateinit var us: AIController
    private lateinit var enemy: AIController
    private lateinit var collector: RwxEventCollector
    private var previousTypes: ArrayList<UnitType>? = null

    private open class Unit : Artillery(true) {
        var visible = true
        override fun d(team: PlayerTeam?) = visible
    }

    @BeforeTest fun setup() {
        engine = GameEngine.getInstance() ?: GameLogic()
        engine.hasLoadedLevel = true; engine.isStopped = false; engine.isPaused = true
        engine.gameTimeMillis = 10_000; engine.currentTick = 600
        previousTypes = UnitTypeEnum.ae; UnitTypeEnum.ae = arrayListOf()
        us = AIController(0, false).apply { teamId = 0; isTeamSpectator = false }
        enemy = AIController(1, false).apply { teamId = 1 }
        engine.playerTeam = us
        collector = RwxEventCollector()
        collector.synchronize(engine, us)
    }

    @AfterTest fun cleanup() { UnitTypeEnum.ae = previousTypes; engine.isPaused = false }

    private fun spawn(id: Long, hostile: Boolean = false) = Unit().apply {
        objectId = id; team = if (hostile) enemy else us
        posX = 100f; posY = 200f; buildProgress = 1f
    }

    @Test fun `damage snapshots preserve names and visibility when attacker later dies or moves`() {
        val victim = spawn(1)
        val attacker = spawn(2, true).apply { visible = false }
        collector.damaged(victim, attacker)
        val first = collector.store.snapshot().single()
        assertNull(first.attacker.x)
        attacker.visible = true; attacker.posX = 999f; attacker.isDead = true
        assertNull(first.attacker.x)
        assertFalse(RwxEventProcessor.query(collector.store.snapshot(), 10_000, EventQuery(null, 30.0, true))
            .lines.single().contains("999"))
        engine.gameTimeMillis += 5000
        collector.damaged(victim, attacker)
        val second = collector.store.snapshot().last()
        assertEquals(999f, second.attacker.x)
        attacker.posX = 2000f
        assertEquals(999f, second.attacker.x)
    }

    @Test fun `reentering sight adds a fresh snapshot and sustained visibility does not refresh it`() {
        val target = spawn(2, true).apply { visible = false }
        collector.observe(emptyList(), listOf(target))
        assertEquals(0, collector.store.size())
        target.visible = true
        collector.observe(emptyList(), listOf(target))
        engine.gameTimeMillis = 12_000; collector.observe(emptyList(), listOf(target))
        assertEquals(1, collector.store.size())
        target.visible = false; collector.observe(emptyList(), listOf(target))
        engine.gameTimeMillis = 14_000; target.visible = true; target.posX = 500f
        collector.observe(emptyList(), listOf(target))
        assertEquals(2, collector.store.size())
        val lines = RwxEventProcessor.query(collector.store.snapshot(), 14_000, EventQuery(null, 30.0, false)).lines
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("[00:14][new]"))
        assertTrue(lines.single().endsWith("(500,200)"))
        assertEquals(100f, collector.store.snapshot().first().subject.x)
    }

    @Test fun `engine damage notification observes actual health or shield loss before removal`() {
        val unit = spawn(1)
        val attacker = spawn(2, true)
        GameStatistics.f.addObserver(collector)
        GameStatistics.f.addObserver(collector)
        try {
            unit.applyDamage(attacker, 0f, null)
            assertEquals(0, collector.store.size())
            unit.applyDamage(attacker, 10f, null)
            assertEquals(1, collector.store.size())
            assertEquals(EventType.DAMAGED, collector.store.snapshot().single().type)
            engine.gameTimeMillis += 5000
            val health = unit.currentHealth
            unit.shield = 100f; unit.energy = 0f
            unit.applyDamage(attacker, 10f, null)
            assertEquals(health, unit.currentHealth)
            assertEquals(2, collector.store.size())
        } finally { GameStatistics.f.removeObserver(collector) }
    }

    @Test fun `info reports real phases with participant ids and history survives deleted objects`() {
        val factory = spawn(1)
        val product = spawn(2)
        collector.info(factory, product, "produced")
        collector.info(product, factory, "construction-started")
        collector.info(product, factory, "construction-finished")
        collector.info(factory, null, "upgrade-started")
        collector.info(factory, null, "upgrade-finished")
        factory.isDead = true; product.isDead = true
        val lines = RwxEventProcessor.query(collector.store.snapshot(), 10_000, EventQuery(listOf(EventType.INFO), 30.0, false)).lines
        assertEquals(5, lines.size)
        assertTrue(lines[0].contains(",1 produced "))
        assertTrue(lines[0].endsWith(",2"))
        assertTrue(lines[1].contains("started constructing"))
        assertTrue(lines[2].contains("completed"))
        assertTrue(lines[3].contains("started upgrading"))
        assertTrue(lines[4].contains("upgraded to T"))
    }

    @Test fun `lethal engine damage records loss and kill before removal changes unit state`() {
        fun victim(id: Long, hostile: Boolean) = object : Unit() {
            override fun bu() { isDead = true; posX = 999f }
            override fun e() = true
            override fun bt() { }
        }.apply {
            objectId = id; team = if (hostile) enemy else us
            posX = 100f; posY = 200f; currentHealth = 10f; buildProgress = 1f
        }
        val attacker = spawn(2, true)
        val ownVictim = victim(1, false)
        val enemyVictim = victim(3, true).apply { visible = false }
        GameStatistics.f.addObserver(collector)
        try {
            ownVictim.applyDamage(attacker, 20f, null)
            enemyVictim.applyDamage(spawn(4), 20f, null)
            val raw = collector.store.snapshot()
            assertEquals(listOf(EventType.DAMAGED, EventType.LOSSES, EventType.DESTROYED), raw.map { it.type })
            assertTrue(ownVictim.isDead)
            assertEquals(100f, raw[1].subject.x)
            assertNull(raw[2].subject.x)
            assertEquals(4L, raw[2].attacker.id)
        } finally { GameStatistics.f.removeObserver(collector) }
    }

    @Test fun `clock rollback and team changes reset history and damage throttles`() {
        val unit = spawn(1)
        collector.damaged(unit, null)
        engine.gameTimeMillis = 5000
        collector.damaged(unit, null)
        assertEquals(1, collector.store.size())
        assertEquals(5000, collector.store.snapshot().single().gameTimeMillis)
        engine.playerTeam = enemy
        collector.synchronize(engine, enemy)
        assertEquals(0, collector.store.size())
    }

    @Test fun `queries never trim history or reset it after a perspective change or rollback`() {
        collector.damaged(spawn(1), null)
        val raw = collector.store.snapshot()
        engine.gameTimeMillis = 200_001
        assertTrue(RwxEventProcessor.query(collector.history(engine, us), engine.gameTimeMillis,
            EventQuery(null, 180.0, false)).lines.isEmpty())
        assertEquals(raw, collector.store.snapshot())
        assertTrue(collector.history(engine, enemy).isEmpty())
        assertEquals(raw, collector.store.snapshot())
        engine.gameTimeMillis = 5000
        assertTrue(collector.history(engine, us).isEmpty())
        assertEquals(raw, collector.store.snapshot())
    }
}
