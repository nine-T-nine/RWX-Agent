package com.corrodinggames.rts.game.ai.custom.micro

import com.corrodinggames.rts.game.ai.custom.micro.case2.Case2Lte
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command
import kotlin.math.hypot
import kotlin.test.*

class LteCaseTest {
    @Test fun `written map uses the stock misc resource pool tileset`() {
        val dir=java.nio.file.Files.createTempDirectory("lte-map-")
        try {
            val config=MicroConfig.parse("CASE2-LTE","task=2_4_1,seed=17,track=realtime",null)
            val path=Case2Lte().prepareMap(config,dir)
            val xml=java.nio.file.Files.readString(path)
            // Same definition as assets/maps/skirmish: misc sheet, tile 4 carries res_pool.
            assertTrue(xml.contains("<tileset firstgid=\"100\" name=\"misc\""))
            assertTrue(xml.contains("<image source=\"bitmaps/misc.png\" width=\"79\" height=\"120\"/>"))
            assertTrue(xml.contains("<tile id=\"4\"><properties><property name=\"res_pool\"/></properties></tile>"))
            assertFalse(xml.contains("bitmaps/water.png"))
            assertFalse(xml.contains("name=\"water\""))
            val size=Case2Lte.mapSize(4)
            assertTrue(xml.contains("width=\"$size\""))
            val items=Regex("<layer name=\"Items\".*?<data encoding=\"base64\" compression=\"gzip\">([^<]+)</data>",RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
            val raw=java.util.zip.GZIPInputStream(java.util.Base64.getDecoder().decode(items).inputStream()).readBytes()
            val gids=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            assertEquals(setOf(104),(0 until gids.limit()).map{gids.get(it)}.filter{it!=0}.toSet())
        } finally {
            java.nio.file.Files.deleteIfExists(dir.resolve("map.tmx"))
            java.nio.file.Files.delete(dir)
        }
    }
    @Test fun `lte restricts tracks and namespaces without changing duel`() {
        val c=MicroConfig.parse("CASE2-LTE","task=2_4_1,seed=1,track=realtime",null)
        assertEquals(1320.0,c.wallLimitSeconds)
        assertEquals(1200.0,c.gameLimitSeconds)
        val p=MicroCaseRegistry.find(c.caseId).profile(c)
        for(cmd in listOf(Command.LTE_STATE,Command.LTE_BUILDER,Command.LTE_RESOURCES,Command.LTE_BUILD,Command.LTE_UPGRADE,Command.LTE_BEGIN,Command.LTE_HELP,Command.PING))assertTrue(p.contains(cmd))
        for(cmd in listOf(Command.MICRO_MOVE,Command.MICRO_HELP,Command.MICRO_NEXT,Command.ORDER_BUILD,Command.ORDER_MOVE,Command.PAUSE,Command.STEP,Command.AUTO_SET))assertFalse(p.contains(cmd))
        assertFalse(MicroCommandProfile("realtime").contains(Command.LTE_BUILD))
        for(args in listOf("track=pause","track=realtime,mode=human","track=realtime,turnTicks=1","track=realtime,enemyDelaySeconds=1"))
            assertFailsWith<IllegalArgumentException> { MicroConfig.parse("CASE2-LTE","task=2_4_1,seed=1,$args",null) }
        for(task in listOf("lte-default","2_3_1","2_9_1","2_4_0","2_4_6","1_1_2"))
            assertFailsWith<IllegalArgumentException> { MicroConfig.parse("CASE2-LTE","task=$task,seed=1",null) }
    }
    @Test fun `task id encodes node count and tier`() {
        for(nodes in 4..8) for(tier in 1..5) {
            val task=Case2Lte.task("2_${nodes}_$tier")
            assertEquals(nodes,task.nodes());assertEquals(tier,task.tier())
        }
        // Documented targets from the loaded income values 8/12/20.
        assertEquals(32,Case2Lte.target(Case2Lte.task("2_4_1"),8,12,20))
        assertEquals(40,Case2Lte.target(Case2Lte.task("2_4_2"),8,12,20))
        assertEquals(48,Case2Lte.target(Case2Lte.task("2_4_3"),8,12,20))
        assertEquals(64,Case2Lte.target(Case2Lte.task("2_4_4"),8,12,20))
        assertEquals(80,Case2Lte.target(Case2Lte.task("2_4_5"),8,12,20))
        assertEquals("2 x T1 + 2 x T2",Case2Lte.composition(Case2Lte.task("2_4_2")))
        // Odd counts split as floor(Y/2) of the lower level and the rest of the higher one.
        assertEquals(52,Case2Lte.target(Case2Lte.task("2_5_2"),8,12,20))
        assertEquals("2 x T2 + 3 x T3",Case2Lte.composition(Case2Lte.task("2_5_4")))
    }
    @Test fun `map side follows the poisson density formula`() {
        // a = ceil(sqrt(Y*400/0.7 + pi*225))
        assertEquals(55,Case2Lte.mapSize(4))
        assertEquals(60,Case2Lte.mapSize(5))
        assertEquals(65,Case2Lte.mapSize(6))
        assertEquals(69,Case2Lte.mapSize(7))
        assertEquals(73,Case2Lte.mapSize(8))
    }
    @Test fun `seed only changes nodes and every layout keeps spacing and centre clearance`() {
        for(nodes in 4..8) {
            val size=Case2Lte.mapSize(nodes)
            for(seed in 0L..24L) {
                val first=Case2Lte.nodes(seed,nodes,size)
                assertEquals(first,Case2Lte.nodes(seed,nodes,size))
                assertNotEquals(first,Case2Lte.nodes(seed+1,nodes,size))
                assertEquals(nodes,first.size)
                val centre=size/2.0
                for((n,a) in first.withIndex()) {
                    val tx=(a["tileX"] as Number).toDouble();val ty=(a["tileY"] as Number).toDouble()
                    assertTrue(tx>=0&&ty>=0&&tx<size&&ty<size)
                    assertTrue(hypot(tx+0.5-centre,ty+0.5-centre)>=Case2Lte.CENTER_CLEARANCE-1e-9)
                    for(b in first.drop(n+1))assertTrue(hypot(tx-(b["tileX"] as Number).toDouble(),ty-(b["tileY"] as Number).toDouble())>=Case2Lte.SPACING-1e-9)
                }
            }
        }
    }
}
