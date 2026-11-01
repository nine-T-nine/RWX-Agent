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
        assertEquals(mapOf("c_turret_t3_gun" to 2, "c_turret_t2_artillery" to 2, "c_turret_t2_gun" to 10),
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
            assertEquals(180 * 180, tiles.size)
            assertTrue(tiles.all { it == 20 }, "every tile is plain grass")
        } finally {
            java.nio.file.Files.list(dir).use { files -> files.forEach { java.nio.file.Files.deleteIfExists(it) } }
            java.nio.file.Files.deleteIfExists(dir)
        }
    }
    @Test fun `turrets stay inside their rings and keep their spacing`() {
        for (task in listOf("4_1_1", "4_2_1", "4_3_1", "4_3_3")) {
            val meta = layout(task)
            val turrets = rows(meta, "turrets")
            assertEquals(Case4Retreat.turretCounts(Case4Retreat.task(task)).values.sum(), turrets.size, task)
            for (turret in turrets) {
                val radius = (turret["radiusTiles"] as Int).toDouble()
                val ring = turret["ring"] as String
                if (ring == "A") assertTrue(radius in 20.0..30.0, "$task ring A radius $radius")
                else assertTrue(radius in 30.0..50.0, "$task ring B radius $radius")
                val tileX = turret["tileX"] as Int; val tileY = turret["tileY"] as Int
                assertTrue(tileX > 0 && tileY > 0 && tileX < 180 && tileY < 180, "inside the map")
                assertTrue(Math.hypot(tileX.toDouble(), tileY.toDouble()) in radius - 1.5..radius + 1.5, "radius matches its tiles")
            }
            val wanted = mapOf("A" to 10.0, "B" to 3.0)
            val achieved = (meta["achievedTurretSpacingTiles"] as Number).toDouble()
            assertTrue(achieved >= 3.0, "$task spacing $achieved")
            for (a in turrets) for (b in turrets) if (a !== b) {
                val gap = Math.hypot(((a["tileX"] as Int) - (b["tileX"] as Int)).toDouble(),
                    ((a["tileY"] as Int) - (b["tileY"] as Int)).toDouble())
                val minimum = if (a["ring"] == b["ring"]) wanted[a["ring"] as String]!! else 0.0
                assertTrue(gap >= minOf(minimum, achieved), "$task turret gap $gap")
            }
        }
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
