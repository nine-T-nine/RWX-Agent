package com.corrodinggames.rts.game.ai.custom.micro

import com.corrodinggames.rts.game.ai.custom.micro.case4.Case4Retreat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream

class RetreatCaseTest {
    private fun ground(path: java.nio.file.Path): IntArray {
        val xml = java.nio.file.Files.readString(path)
        val m = Regex("<layer name=\"Ground\".*?<data encoding=\"base64\" compression=\"gzip\">([^<]+)</data>", RegexOption.DOT_MATCHES_ALL).find(xml)!!
        val raw = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(m.groupValues[1]))).readBytes()
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(buffer.limit()) { buffer.get(it) }
    }
    private fun layout(task: String, seed: String = "5"): Map<String, Any?> {
        val config = MicroConfig.parse("CASE4-Retreat", "task=$task,seed=$seed,track=realtime", null)
        val dir = java.nio.file.Files.createTempDirectory("case4-layout-")
        try {
            val case = Case4Retreat()
            case.prepareMap(config, dir)
            return case.layout(config, null)
        } finally {
            java.nio.file.Files.list(dir).use { files -> files.forEach { java.nio.file.Files.deleteIfExists(it) } }
            java.nio.file.Files.deleteIfExists(dir)
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun rows(meta: Map<String, Any?>, key: String) = (meta[key] as List<*>).map { it as Map<String, Any?> }

    @Test fun `task ids and spec table`() {
        val t = Case4Retreat.task("4_3_2")
        assertEquals(3, t.type()); assertEquals(2, t.level())
        val spec = Case4Retreat.spec(t)
        assertEquals(3, spec.tanks()); assertEquals(4, spec.bays()); assertEquals(1500, spec.gameLimitSeconds())
        assertEquals(mapOf("c_turret_t3_gun" to 2, "c_turret_t2_artillery" to 2, "c_turret_t2_gun" to 8),
            Case4Retreat.turretCounts(t))
        val one = Case4Retreat.spec(Case4Retreat.task("4_1_1"))
        assertEquals(0, one.fogMode()); assertEquals(2, one.tanks()); assertEquals(2, one.bays())
        assertEquals(2, Case4Retreat.spec(Case4Retreat.task("4_2_3")).fogMode())
        assertEquals(4, Case4Retreat.spec(Case4Retreat.task("4_2_1")).bays())
        for (bad in listOf("4_0_1", "4_4_1", "4_1_0", "4_1_4", "4_1", "retreat")) {
            try { Case4Retreat.task(bad); throw AssertionError("accepted $bad") } catch (expected: IllegalArgumentException) { }
        }
    }
    @Test fun `case limits follow the type table`() {
        assertEquals(900.0, MicroConfig.parse("CASE4-Retreat", "task=4_1_1,seed=1,track=realtime", null).gameLimitSeconds)
        assertEquals(1020.0, MicroConfig.parse("CASE4-Retreat", "task=4_1_1,seed=1,track=realtime", null).wallLimitSeconds)
        assertEquals(1500.0, MicroConfig.parse("CASE4-Retreat", "task=4_3_1,seed=1,track=realtime", null).gameLimitSeconds)
        assertEquals(1620.0, MicroConfig.parse("CASE4-Retreat", "task=4_3_1,seed=1,track=realtime", null).wallLimitSeconds)
    }
    @Test fun `map is one continuous grassland`() {
        val config = MicroConfig.parse("CASE4-Retreat", "task=4_1_1,seed=3,track=realtime", null)
        val dir = java.nio.file.Files.createTempDirectory("case4-map-")
        try {
            val path = Case4Retreat().prepareMap(config, dir)
            val tiles = ground(path)
            // seed 3 -> width 75+3, height 75+0; both sides stay inside [75,80).
            assertEquals(78 * 75, tiles.size)
            assertTrue(tiles.all { it == 20 }, "every tile is plain grass")
        } finally {
            java.nio.file.Files.list(dir).use { files -> files.forEach { java.nio.file.Files.deleteIfExists(it) } }
            java.nio.file.Files.deleteIfExists(dir)
        }
    }
    @Test fun `map sides stay inside the requested range`() {
        for (seed in 0L..24L) {
            val config = MicroConfig.parse("CASE4-Retreat", "task=4_1_1,seed=$seed,track=realtime", null)
            val dir = java.nio.file.Files.createTempDirectory("case4-size-")
            try {
                val path = Case4Retreat().prepareMap(config, dir)
                val xml = java.nio.file.Files.readString(path)
                val width = Regex("width=\"(\\d+)\"").find(xml)!!.groupValues[1].toInt()
                val height = Regex("height=\"(\\d+)\"").find(xml)!!.groupValues[1].toInt()
                assertTrue(width in 75..79, "seed $seed width $width")
                assertTrue(height in 75..79, "seed $seed height $height")
                assertEquals(width * height, ground(path).size)
            } finally {
                java.nio.file.Files.list(dir).use { files -> files.forEach { java.nio.file.Files.deleteIfExists(it) } }
                java.nio.file.Files.deleteIfExists(dir)
            }
        }
    }
    @Test fun `turrets stay inside the field and keep their spacing`() {
        for (task in listOf("4_1_1", "4_2_1", "4_3_1", "4_3_3")) {
            val meta = layout(task)
            val turrets = rows(meta, "turrets")
            val region = meta["region"] as Map<*, *>
            val centreX = (region["centreTileX"] as Number).toDouble()
            val centreY = (region["centreTileY"] as Number).toDouble()
            val radius = (region["radiusTiles"] as Number).toDouble()
            val width = (meta["mapWidth"] as Number).toInt()
            val height = (meta["mapHeight"] as Number).toInt()
            assertEquals(Case4Retreat.turretCounts(Case4Retreat.task(task)).values.sum(), turrets.size, task)
            for (turret in turrets) {
                val tileX = turret["tileX"] as Int; val tileY = turret["tileY"] as Int
                assertTrue(tileX > 0 && tileY > 0 && tileX < width && tileY < height, "$task inside the map")
                // The blob is the base radius plus at most 35% of coastline noise.
                val distance = Math.hypot(tileX - centreX, tileY - centreY)
                assertTrue(distance <= radius * 1.4, "$task turret at $distance outside the field of $radius")
            }
            val achieved = meta["achievedTurretSpacingTiles"] as Map<*, *>
            if (turrets.count { it["role"] == "long" } >= 2)
                assertTrue((achieved["long"] as Number).toDouble() >= 10.0, "$task long spacing ${achieved["long"]}")
            if (turrets.count { it["role"] == "short" } >= 2)
                assertTrue((achieved["short"] as Number).toDouble() >= 3.0, "$task short spacing ${achieved["short"]}")
            for (a in turrets) for (b in turrets) if (a !== b) {
                val gap = Math.hypot(((a["tileX"] as Int) - (b["tileX"] as Int)).toDouble(),
                    ((a["tileY"] as Int) - (b["tileY"] as Int)).toDouble())
                val same = a["role"] == b["role"]
                val minimum = if (same && a["role"] == "long") 10.0 else 3.0
                assertTrue(gap >= minimum, "$task turret gap $gap")
            }
        }
    }
    @Test fun `long range turrets lean to the upper left of the field`() {
        // Two guns per seed are far too few to see a bias in, so the whole sample is pooled first.
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        var upperLeft = 0
        for (task in listOf("4_2_1", "4_3_1")) {
            for (seed in 1..8) {
                val meta = layout(task, seed.toString())
                val region = meta["region"] as Map<*, *>
                val centreX = (region["centreTileX"] as Number).toDouble()
                val centreY = (region["centreTileY"] as Number).toDouble()
                for (gun in rows(meta, "turrets").filter { it["role"] == "long" }) {
                    val dx = (gun["tileX"] as Int) - centreX
                    val dy = (gun["tileY"] as Int) - centreY
                    sumX += dx; sumY += dy; count++
                    if (dx < 0 && dy < 0) upperLeft++
                }
            }
        }
        assertTrue(count >= 12, "only $count long-range guns sampled")
        // The bias points at 225 degrees (up-left, i.e. away from the player's corner); a fifth of
        // the guns is allowed to ignore it, so the pooled mean still has to point up-left.
        assertTrue(sumX / count < -2.0, "mean x offset ${sumX / count} is not to the left")
        assertTrue(sumY / count < -2.0, "mean y offset ${sumY / count} is not upward")
        assertTrue(upperLeft * 10 >= count * 6, "only $upperLeft of $count guns are up-left")
    }
    @Test fun `repair bays form an L and the tanks start beside them`() {
        for (task in listOf("4_1_1", "4_2_1", "4_3_1")) {
            val meta = layout(task)
            val bays = rows(meta, "repairBays"); val tanks = rows(meta, "tanks")
            assertEquals(Case4Retreat.spec(Case4Retreat.task(task)).bays(), bays.size)
            assertEquals(Case4Retreat.spec(Case4Retreat.task(task)).tanks(), tanks.size)
            // L shape: every bay shares the corner row or the corner column of the first bay; with at
            // least three bays both arms must be used, two bays degenerate to a single straight arm.
            val cornerX = bays[0]["tileX"] as Int; val cornerY = bays[0]["tileY"] as Int
            for (bay in bays) assertTrue(bay["tileX"] == cornerX || bay["tileY"] == cornerY, "$task bay outside the L")
            if (bays.size >= 3)
                assertTrue(bays.any { it["tileX"] != cornerX } && bays.any { it["tileY"] != cornerY }, "$task not an L")
            for (tank in tanks) {
                val tileX = tank["tileX"] as Int; val tileY = tank["tileY"] as Int
                assertTrue(tileX < cornerX && tileY < cornerY, "$task tank must spawn inside the corner")
                val nearest = bays.minOf { Math.hypot((it["tileX"] as Int - tileX).toDouble(), (it["tileY"] as Int - tileY).toDouble()) }
                assertTrue(nearest in 1.0..16.0, "$task tank is $nearest tiles from a bay")
            }
            for (a in tanks) for (b in tanks) if (a !== b)
                assertTrue(a["tileX"] != b["tileX"] || a["tileY"] != b["tileY"], "$task tanks overlap")
        }
    }
    @Test fun `layout is reproducible for the same task and seed`() {
        val first = layout("4_2_2", "17"); val second = layout("4_2_2", "17")
        assertEquals(first["turrets"], second["turrets"])
        assertEquals(first["repairBays"], second["repairBays"])
        assertEquals(first["tanks"], second["tanks"])
        assertTrue(first["turrets"] != layout("4_2_2", "18")["turrets"], "a different seed moves the turrets")
    }
}
