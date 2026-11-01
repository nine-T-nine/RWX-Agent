package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.AlertFeed
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.EventEntity
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.EventPage
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.EventRecord
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.EventType
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.GameEvent
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RwxDeliveryTraceTest {
    private fun entity(id: Long) = EventEntity(id, "tank", "Tank", 0, "self", 100f, 200f)

    private fun lossEvent(time: Int, seq: Long, wall: Long) =
        GameEvent(time, EventType.LOSSES, entity(seq), null, null).apply {
            this.seq = seq
            this.wallTimeMillis = wall
        }

    private fun withTrace(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("rwx-trace-test")
        val previous = System.getProperty(RwxDeliveryTrace.DIRECTORY_PROPERTY)
        System.setProperty(RwxDeliveryTrace.DIRECTORY_PROPERTY, directory.toString())
        RwxDeliveryTrace.resetForTests()
        try {
            test(directory)
        } finally {
            RwxDeliveryTrace.resetForTests()
            if (previous == null) System.clearProperty(RwxDeliveryTrace.DIRECTORY_PROPERTY)
            else System.setProperty(RwxDeliveryTrace.DIRECTORY_PROPERTY, previous)
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun lines(directory: Path): List<JsonObject> =
        Files.readAllLines(directory.resolve("delivery.jsonl"))
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }

    @Test fun `the trace records both ends of a delivery and the command that followed`() {
        withTrace { directory ->
            val first = lossEvent(10_000, 1, 1_000_000)
            val second = lossEvent(11_000, 2, 1_000_500)
            val page = EventPage(listOf("[00:10][losses] we lost Tank,1(100,200) by unknown"),
                listOf(EventRecord(10_000, EventType.LOSSES, "x", first, listOf(first, second))),
                2L, 0L, 1L, false, 0, 0)
            val feed = AlertFeed(12_000, 750, 2L, 1L, 0L, false, listOf(first, second))
            RwxDeliveryTrace.delivered("agentA", page, feed, 1_000_200)
            RwxDeliveryTrace.command("agentA", "rwx-game-state", 1_000_400, 11_900, "state", false)

            val rows = lines(directory)
            val environment = rows.single { it["kind"]!!.jsonPrimitive.content == "env" }
            assertEquals(RwxSpeedProfile.active().id(), environment["speed"]!!.jsonPrimitive.content)
            // A run's leaderboard eligibility travels with its evidence, not with someone's memory.
            assertEquals(RwxSpeedProfile.active().diagnostic(), environment["diagnostic"]!!.jsonPrimitive.boolean)
            assertEquals(RwxSpeedProfile.active().leaderboardEligible(),
                environment["leaderboardEligible"]!!.jsonPrimitive.boolean)
            assertEquals(RwxDelivery.levelByDefault().id, environment["delivery"]!!.jsonPrimitive.content)
            assertTrue(environment["pid"]!!.jsonPrimitive.long > 0)

            val delivered = rows.single { it["kind"]!!.jsonPrimitive.content == "deliver" }
            assertEquals("auto", delivered["channel"]!!.jsonPrimitive.content)
            assertEquals("agentA", delivered["session"]!!.jsonPrimitive.content)
            assertEquals(1L, delivered["seq"]!!.jsonPrimitive.long)
            assertEquals("losses", delivered["type"]!!.jsonPrimitive.content)
            // A merged group reports its earliest occurrence as "produced", not its last.
            assertEquals(1_000_000L, delivered["produced"]!!.jsonObject["wall"]!!.jsonPrimitive.long)
            assertEquals(10_000, delivered["produced"]!!.jsonObject["game"]!!.jsonPrimitive.int)
            assertEquals(1_000_200L, delivered["delivered"]!!.jsonObject["wall"]!!.jsonPrimitive.long)
            assertEquals(12_000, delivered["delivered"]!!.jsonObject["game"]!!.jsonPrimitive.int)
            assertEquals(2, delivered["events"]!!.jsonPrimitive.int)

            val command = rows.single { it["kind"]!!.jsonPrimitive.content == "command" }
            assertEquals("rwx-game-state", command["command"]!!.jsonPrimitive.content)
            assertEquals("state", command["channel"]!!.jsonPrimitive.content)
            assertEquals(1_000_400L, command["received"]!!.jsonObject["wall"]!!.jsonPrimitive.long)
            assertFalse(command["failed"]!!.jsonPrimitive.boolean)
            // "Applied" is the reply boundary, and the record says so instead of pretending.
            assertEquals("reply", command["appliedBound"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `no configured directory means no file and no failure`() {
        val previous = System.getProperty(RwxDeliveryTrace.DIRECTORY_PROPERTY)
        System.clearProperty(RwxDeliveryTrace.DIRECTORY_PROPERTY)
        RwxDeliveryTrace.resetForTests()
        try {
            assertFalse(RwxDeliveryTrace.enabled())
            // These must be silent no-ops, not exceptions, on the hot path of every reply.
            RwxDeliveryTrace.command("default", "rwx-ping", 1, -1, "none", false)
            RwxDeliveryTrace.delivered("default",
                EventPage(emptyList(), emptyList(), 0L, 0L, 1L, false, 0, 0),
                AlertFeed(0, 0, 0L, 1L, 0L, false, emptyList()), 1)
        } finally {
            RwxDeliveryTrace.resetForTests()
            if (previous != null) System.setProperty(RwxDeliveryTrace.DIRECTORY_PROPERTY, previous)
        }
    }
}
