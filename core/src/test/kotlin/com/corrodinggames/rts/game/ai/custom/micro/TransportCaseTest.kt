package com.corrodinggames.rts.game.ai.custom.micro

import com.corrodinggames.rts.game.ai.custom.micro.case3.Case3Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream

class TransportCaseTest {
    private fun ground(path: java.nio.file.Path): IntArray {
        val xml = java.nio.file.Files.readString(path)
        val m = Regex("<layer name=\"Ground\".*?<data encoding=\"base64\" compression=\"gzip\">([^<]+)</data>", RegexOption.DOT_MATCHES_ALL).find(xml)!!
        val raw = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(m.groupValues[1]))).readBytes()
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(buffer.limit()) { buffer.get(it) }
    }
    private fun connected(tiles: Set<Int>, size: Int): Boolean {
        if (tiles.isEmpty()) return false
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(tiles.first()); seen.add(tiles.first())
        while (stack.isNotEmpty()) {
            val key = stack.removeLast()
            for (next in listOf(key - 1, key + 1, key - size, key + size)) {
                if (next in tiles && seen.add(next)) stack.addLast(next)
            }
        }
        return seen.size == tiles.size
    }
    private fun withMap(block: (java.nio.file.Path, IntArray) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("case3-map-")
        try {
            val config = MicroConfig.parse("CASE3-Transport", "task=3_1_1,seed=17,track=realtime", null)
            val path = Case3Transport().prepareMap(config, dir)
            block(path, ground(path))
        } finally {
            java.nio.file.Files.list(dir).use { files -> files.forEach { java.nio.file.Files.deleteIfExists(it) } }
            java.nio.file.Files.deleteIfExists(dir)
        }
    }
    @Test fun `task ids and spec table`() {
        val t = Case3Transport.task("3_3_2")
        assertEquals(3, t.type()); assertEquals(2, t.level())
        val spec = Case3Transport.spec(t)
        assertEquals(3, spec.transports()); assertEquals("dropship", spec.transportType())
        assertEquals(5, spec.extractors()); assertEquals(4000, spec.credits()); assertEquals(1500, spec.gameLimitSeconds())
        assertEquals(listOf(2, 2, 4, 6), Case3Transport.requirements(t).map { it["required"] })
        val four = Case3Transport.spec(Case3Transport.task("3_4_1"))
        assertEquals("experimentalDropship", four.transportType()); assertEquals(10, four.extractors()); assertEquals(20000, four.credits())
        for (bad in listOf("3_0_1", "3_5_1", "3_1_0", "3_1_4", "3_1", "transport")) {
            try { Case3Transport.task(bad); throw AssertionError("accepted $bad") } catch (expected: IllegalArgumentException) { }
        }
    }
    @Test fun `case limits follow the type table`() {
        assertEquals(900.0, MicroConfig.parse("CASE3-Transport", "task=3_1_1,seed=1,track=realtime", null).gameLimitSeconds)
        assertEquals(1020.0, MicroConfig.parse("CASE3-Transport", "task=3_1_1,seed=1,track=realtime", null).wallLimitSeconds)
        assertEquals(1800.0, MicroConfig.parse("CASE3-Transport", "task=3_4_1,seed=1,track=realtime", null).gameLimitSeconds)
        assertEquals(1920.0, MicroConfig.parse("CASE3-Transport", "task=3_4_1,seed=1,track=realtime", null).wallLimitSeconds)
    }
    @Test fun `islands keep their exact area and stay separated`() {
        withMap { _, tiles ->
            val grass = tiles.withIndex().filter { it.value == 20 }.map { it.index }.toSet()
            assertEquals(400 + 625, grass.size)
            val target = grass.filter { it % Case3MapSize.SIZE < 40 && it / Case3MapSize.SIZE < 40 }.toSet()
            val base = grass - target
            assertEquals(400, target.size); assertEquals(625, base.size)
            assertTrue(connected(target, Case3MapSize.SIZE)); assertTrue(connected(base, Case3MapSize.SIZE))
            // No grass tile of one island may touch the other island.
            assertTrue(target.none { key -> listOf(key - 1, key + 1, key - Case3MapSize.SIZE, key + Case3MapSize.SIZE).any { it in base } })
            assertTrue(tiles.all { it == 20 || it == 5 })
        }
    }
    @Test fun `layout keeps factories and transports on the base island`() {
        val config = MicroConfig.parse("CASE3-Transport", "task=3_3_2,seed=5,track=realtime", null)
        val dir = java.nio.file.Files.createTempDirectory("case3-layout-")
        try {
            val case = Case3Transport()
            case.prepareMap(config, dir)
            val meta = case.layout(config, null)
            val base = (meta["baseIslandTiles"] as List<*>).map { (it as Number).toInt() }.toSet()
            val target = (meta["targetIslandTiles"] as List<*>).map { (it as Number).toInt() }.toSet()
            assertEquals(400, target.size); assertEquals(625, base.size)
            val units = meta["units"] as List<*>
            @Suppress("UNCHECKED_CAST") val specs = units.map { it as Map<String, Any?> }
            assertEquals(4 + 5 + 3 + 4, specs.size)
            for (spec in specs) {
                val key = (spec["tileY"] as Int) * Case3MapSize.SIZE + (spec["tileX"] as Int)
                if (spec["team"] == 1) assertTrue(key in target, "turret on target island")
                else assertTrue(key in base, "own unit on base island")
            }
            val turrets = specs.filter { it["team"] == 1 }
            assertEquals(4, turrets.size)
            for (a in turrets) for (b in turrets) if (a !== b) {
                val dx = Math.abs((a["tileX"] as Int) - (b["tileX"] as Int)); val dy = Math.abs((a["tileY"] as Int) - (b["tileY"] as Int))
                assertTrue(dx + dy >= 10, "anti-air spacing")
            }
        } finally {
            java.nio.file.Files.list(dir).use { files -> files.forEach { java.nio.file.Files.deleteIfExists(it) } }
            java.nio.file.Files.deleteIfExists(dir)
        }
    }
}
private object Case3MapSize { const val SIZE = 130 }
