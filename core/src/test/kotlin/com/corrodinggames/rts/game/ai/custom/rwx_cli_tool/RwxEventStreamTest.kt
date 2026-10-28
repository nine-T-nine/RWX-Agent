package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RwxEventStreamTest {
    private val protocol = "rwx-events-v1"

    private fun subject(id: Long, name: String = "Tank", x: Float = 10f, y: Float = 20f) =
        RwxLocalGameTypes.EventEntity(id, "tank", name, 1, "enemy", x, y)

    private fun discovery(time: Int, id: Long, x: Float = 10f, y: Float = 20f) =
        RwxLocalGameTypes.GameEvent(time, RwxLocalGameTypes.EventType.NEW, subject(id, x = x, y = y), null, null)

    /** One subscription; every read is one NDJSON message from the server. */
    private class Client(port: Int, after: Int = 0, session: String? = null,
                         info: Boolean = false, allies: Boolean = false) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port)
        private val reader: BufferedReader

        init {
            socket.soTimeout = 5000
            val requested = session?.let { "\"$it\"" } ?: "null"
            val request = """{"protocol":"rwx-events-v1","kind":"subscribe","after":$after,""" +
                """"session":$requested,"info":$info,"allies":$allies}"""
            socket.getOutputStream().write((request + "\n").toByteArray())
            reader = socket.getInputStream().bufferedReader()
        }

        fun message(): JsonObject {
            val line = reader.readLine() ?: fail("event stream closed before an expected message")
            return Json.parseToJsonElement(line).jsonObject
        }

        fun kind(): String = message()["kind"]!!.jsonPrimitive.content

        override fun close() = socket.close()
    }

    private fun withStream(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("rwx-events-test")
        val portFile = directory.resolve("events-port")
        val stream = RwxEventStream.start(portFile)
        try {
            test(portFile)
        } finally {
            stream.close()
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun port(portFile: Path) = Files.readString(portFile).trim().toInt()

    private fun text(message: JsonObject, field: String) = message[field]!!.jsonPrimitive.content

    @Test
    fun `stream pushes aggregate revisions and replays them independently to clients`() {
        withStream { portFile ->
            val port = port(portFile)
            Client(port).use { first ->
                val hello = first.message()
                assertEquals("hello", text(hello, "kind"))
                val session = text(hello, "session")
                RwxEventStream.publish(listOf(discovery(1000, 1)), 1000)
                val initial = first.message()
                assertEquals("event", text(initial, "kind"))
                assertEquals(1, initial["revision"]!!.jsonPrimitive.int)
                RwxEventStream.publish(listOf(discovery(1000, 1), discovery(2000, 2)), 2000)
                val update = first.message()
                assertEquals("update", text(update, "kind"))
                assertEquals(initial["eventId"], update["eventId"])
                assertEquals(2, update["revision"]!!.jsonPrimitive.int)
                assertTrue(text(update["event"]!!.jsonObject, "description").contains("x2"))
                Client(port, after = 1, session = session).use { second ->
                    assertEquals("hello", second.kind())
                    assertEquals(update, second.message())
                }
            }
        }
    }

    @Test
    fun `aggregate id survives the expiry of its earliest contributor`() {
        withStream { portFile ->
            Client(port(portFile)).use { client ->
                client.kind()
                val first = discovery(1000, 1)
                val second = discovery(2000, 2)
                RwxEventStream.publish(listOf(first, second), 2000)
                val combined = client.message()
                assertEquals("event", text(combined, "kind"))
                assertEquals(1, combined["revision"]!!.jsonPrimitive.int)
                assertTrue(text(combined["event"]!!.jsonObject, "description").contains("x2"))
                val id = combined["eventId"]!!.jsonPrimitive.long

                // Retention dropped the earliest occurrence: the remaining member still forms the
                // same run, so it must be a revision instead of a new event plus a removal.
                RwxEventStream.publish(listOf(second), 2001)
                val revised = client.message()
                assertEquals("update", text(revised, "kind"))
                assertEquals(id, revised["eventId"]!!.jsonPrimitive.long)
                assertEquals(2, revised["revision"]!!.jsonPrimitive.int)
            }
        }
    }

    @Test
    fun `replaced discovery inside the aggregation window keeps the revision id`() {
        withStream { portFile ->
            Client(port(portFile)).use { client ->
                client.kind()
                val earlier = discovery(1000, 5)
                RwxEventStream.publish(listOf(earlier), 1000)
                val initial = client.message()
                val id = initial["eventId"]!!.jsonPrimitive.long

                // The same entity is re-discovered 1.5s later; the query keeps only the latest
                // occurrence, so the contributing object differs from the previous frame.
                val rediscovered = discovery(2500, 5)
                RwxEventStream.publish(listOf(earlier, rediscovered), 2500)
                val revised = client.message()
                assertEquals("update", text(revised, "kind"))
                assertEquals(id, revised["eventId"]!!.jsonPrimitive.long)
                assertEquals(2, revised["revision"]!!.jsonPrimitive.int)
            }
        }
    }

    @Test
    fun `a rediscovery after three seconds still revises the same aggregate`() {
        withStream { portFile ->
            Client(port(portFile)).use { client ->
                client.kind()
                val earlier = discovery(1000, 5)
                RwxEventStream.publish(listOf(earlier), 1000)
                val initial = client.message()
                val id = initial["eventId"]!!.jsonPrimitive.long

                // 4.5s later: outside the old three second window, inside the five second one.
                val later = discovery(5500, 5, x = 600f, y = 800f)
                RwxEventStream.publish(listOf(earlier, later), 5500)
                val revised = client.message()
                assertEquals("update", text(revised, "kind"))
                assertEquals(id, revised["eventId"]!!.jsonPrimitive.long)
                assertEquals(2, revised["revision"]!!.jsonPrimitive.int)
            }
        }
    }

    @Test
    fun `aggregate leaving the query window is removed and a later encounter gets a new id`() {
        withStream { portFile ->
            Client(port(portFile)).use { client ->
                client.kind()
                RwxEventStream.publish(listOf(discovery(1000, 1)), 1000)
                val initial = client.message()
                val id = initial["eventId"]!!.jsonPrimitive.long

                RwxEventStream.publish(emptyList(), 400_000)
                val removed = client.message()
                assertEquals("remove", text(removed, "kind"))
                assertEquals(id, removed["eventId"]!!.jsonPrimitive.long)
                assertEquals("expired", text(removed, "reason"))

                RwxEventStream.publish(listOf(discovery(400_001, 2)), 400_001)
                val fresh = client.message()
                assertEquals("event", text(fresh, "kind"))
                assertNotEquals(id, fresh["eventId"]!!.jsonPrimitive.long)
                assertEquals(1, fresh["revision"]!!.jsonPrimitive.int)
            }
        }
    }

    @Test
    fun `stale session and a cursor ahead of the journal both rewind to the retained history`() {
        withStream { portFile ->
            val port = port(portFile)
            RwxEventStream.publish(listOf(discovery(1000, 1)), 1000)
            Client(port, after = 999_999).use { ahead ->
                assertEquals("hello", ahead.kind())
                val gap = ahead.message()
                assertEquals("gap", text(gap, "kind"))
                assertTrue(text(gap, "message").contains("ahead"))
                assertEquals("event", ahead.kind())
            }
            Client(port, after = 5, session = "00000000-0000-0000-0000-000000000000").use { stale ->
                assertEquals("hello", stale.kind())
                val gap = stale.message()
                assertEquals("gap", text(gap, "kind"))
                assertTrue(text(gap, "message").contains("session"))
                assertEquals("event", stale.kind())
            }
        }
    }

    @Test
    fun `reset clears the journal and the aggregate state for every subscriber`() {
        withStream { portFile ->
            val port = port(portFile)
            Client(port).use { client ->
                client.kind()
                RwxEventStream.publish(listOf(discovery(1000, 1)), 1000)
                assertEquals("event", client.kind())
                RwxEventStream.reset()
                val reset = client.message()
                assertEquals("reset", text(reset, "kind"))
                val cursor = reset["seq"]!!.jsonPrimitive.int

                Client(port, after = cursor).use { fresh ->
                    assertEquals("hello", fresh.kind())
                    // The pre-reset event was pruned from the journal, so the next message is the
                    // new encounter, not a replay of the old one.
                    RwxEventStream.publish(listOf(discovery(10_000, 2)), 10_000)
                    val next = fresh.message()
                    assertEquals("event", text(next, "kind"))
                    assertEquals(1, next["revision"]!!.jsonPrimitive.int)
                }
            }
        }
    }

    @Test
    fun `a cursor before the retained journal gets a gap and then resumes`() {
        withStream { portFile ->
            val port = port(portFile)
            val attacker = RwxLocalGameTypes.EventEntity(9999, "gun", "Gun", 0, "self", 0f, 0f)
            val events = (1L..1001L).map { id ->
                RwxLocalGameTypes.GameEvent(1000, RwxLocalGameTypes.EventType.DESTROYED,
                    subject(id), attacker, null)
            }
            Client(port).use { drain ->
                drain.kind()
                RwxEventStream.publish(events, 1000)
                // Reading the last message proves every add() finished, so the journal is pruned
                // below the subscriber's cursor when the second client connects.
                repeat(events.size) { drain.kind() }

                Client(port, after = 0).use { late ->
                    assertEquals("hello", late.kind())
                    val gap = late.message()
                    assertEquals("gap", text(gap, "kind"))
                    assertEquals(1, gap["from"]!!.jsonPrimitive.int)
                    assertEquals(1, gap["to"]!!.jsonPrimitive.int)
                    val first = late.message()
                    assertEquals("event", text(first, "kind"))
                    assertEquals(2, first["seq"]!!.jsonPrimitive.int)
                }
            }
        }
    }

    @Test
    fun `clients replay from their own cursor without interfering`() {
        withStream { portFile ->
            val port = port(portFile)
            val first = discovery(1000, 1)
            // Spaced beyond the five second discovery window so each one stays its own aggregate.
            val second = discovery(7000, 2)
            RwxEventStream.publish(listOf(first), 1000)
            RwxEventStream.publish(listOf(first, second), 7000)
            Client(port, after = 0).use { full ->
                assertEquals("hello", full.kind())
                assertEquals("event", full.kind())
                val latest = full.message()
                assertEquals("event", text(latest, "kind"))
                Client(port, after = latest["seq"]!!.jsonPrimitive.int).use { tail ->
                    assertEquals("hello", tail.kind())
                    val third = discovery(14_000, 3)
                    RwxEventStream.publish(listOf(first, second, third), 14_000)
                    val next = tail.message()
                    assertEquals("event", text(next, "kind"))
                    assertNotEquals(latest["eventId"], next["eventId"])
                }
            }
        }
    }
}
