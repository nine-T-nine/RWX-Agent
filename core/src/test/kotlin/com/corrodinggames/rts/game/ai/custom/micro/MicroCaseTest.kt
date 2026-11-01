package com.corrodinggames.rts.game.ai.custom.micro

import com.corrodinggames.rts.game.ai.custom.micro.case1.Case1Duel
import com.corrodinggames.rts.game.ai.custom.micro.case1.Case1EnemyStrategy
import com.corrodinggames.rts.game.ai.custom.micro.case1.Case1Map
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command
import com.corrodinggames.rts.game.ai.strategy.AiPhase
import io.github.rwx.app.AppOptions
import kotlin.math.hypot
import kotlin.test.*

class MicroCaseTest {
    @Test fun `case forms preserve the old flag and require complete case inputs`() {
        // --headless only exists on the desktop build (it selects the headless render backend),
        // so the parse below has to say so; a bare --evaluate must still not name a case.
        assertNull(AppOptions.parseArgs(arrayOf("--evaluate", "--headless"), isDesktop = true).evaluateCase)
        for (prefix in listOf(arrayOf("--evaluate", "CASE1-duel"), arrayOf("--evaluate=CASE1-duel"))) {
            val options=AppOptions.parseArgs(prefix+arrayOf("--case-args", "task=1_2_2,seed=1,track=pause"))
            assertEquals("CASE1-duel",options.evaluateCase)
            assertTrue(options.rwxCliEnabled)
        }
        assertFailsWith<IllegalArgumentException> { MicroConfig.parse("CASE1-duel","task=1_1,seed=1,track=pause",null) }
        assertFailsWith<IllegalArgumentException> { MicroConfig.parse("CASE1-duel","task=1_1_1,seed=1",null) }
        assertFailsWith<IllegalArgumentException> { MicroConfig.parse("CASE1-duel","task=1_1_1,seed=1,track=pause,seed=2",null) }
        assertFailsWith<IllegalArgumentException> { MicroConfig.parse("CASE1-duel","task=1_1_1,seed=1,track=pause,turnTicks=60,turnSeconds=1",null) }
        // One game second per round by default; an explicit turn length still wins.
        assertEquals(60,MicroConfig.parse("CASE1-duel","task=1_2_2,seed=1,track=pause",null).turnTicks)
        assertEquals(60,MicroConfig.parse("CASE1-duel","task=1_2_2,seed=1,track=pause,turnSeconds=1",null).turnTicks)
        assertEquals(120,MicroConfig.parse("CASE1-duel","task=1_2_2,seed=1,track=pause,turnTicks=120",null).turnTicks)
    }
    @Test fun `profile contains exactly case commands and respects track availability`() {
        val pause=MicroCommandProfile("pause",*Case1Duel.COMMANDS)
        val realtime=MicroCommandProfile("realtime",*Case1Duel.COMMANDS)
        assertTrue(pause.contains(Command.MICRO_NEXT));assertFalse(realtime.contains(Command.MICRO_NEXT))
        for(command in listOf(Command.PAUSE,Command.STEP,Command.RESTART,Command.SURRENDER,Command.ORDER_BUILD,Command.AUTO_SET))assertFalse(pause.contains(command))
        // Namespaced help replaces the bundled manual: the bare rwx-help must never resolve here.
        assertTrue(pause.contains(Command.MICRO_HELP));assertFalse(pause.contains(Command.HELP))
        assertEquals("A",MicroCommandProfile.kind(Command.MICRO_UNITS))
        assertEquals("B",MicroCommandProfile.kind(Command.MICRO_BATCH))
        assertEquals("C",MicroCommandProfile.kind(Command.MICRO_NEXT))
    }
    @Test fun `each registered case declares its own command surface`() {
        // The runtime never names a case: the case itself decides what it accepts.
        for (task in listOf("CASE1-duel" to "1_1_1", "CASE2-LTE" to "2_4_1",
                            "CASE3-Transport" to "3_1_1", "CASE4-Retreat" to "4_1_1")) {
            val (id, name) = task
            val config = MicroConfig.parse(id, "task=$name,seed=1,track=realtime", null)
            val case = MicroCaseRegistry.find(id)
            val profile = case.profile(config)
            assertEquals(id, case.id())
            assertTrue(profile.contains(com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.find(case.helpCommand())),
                "$id must accept its own help command")
            // CASE2 keeps its own vocabulary: no full-game observation leaks into it.
            when (id) {
                "CASE2-LTE" -> assertFalse(profile.contains(Command.MAP_SHOW))
                "CASE1-duel" -> assertTrue(profile.contains(Command.MICRO_MAP), "$id maps through its own command")
                else -> assertTrue(profile.contains(Command.MAP_SHOW), "$id reuses the full-game map observation")
            }
        }
    }
    @Test fun `help sections stay addressable from the case manual`() {
        val document = javaClass.getResourceAsStream("/micro/CASE1-duel/commands.md")!!
            .readBytes().toString(Charsets.UTF_8)
        val listed = com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliTool
            .helpDocument(document, listOf("--list"), "rwx-micro-1-help")
        assertTrue(listed.contains("总览"), listed.take(200))
        val section = com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliTool
            .helpDocument(document, listOf("2"), "rwx-micro-1-help")
        assertTrue(section.contains("通用约定"), section.take(200))
        assertFalse(section.contains("单位输出字段"), "section 2 must stop at section 3")
        val unknown = com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliTool
            .helpDocument(document, listOf("--nope"), "rwx-micro-1-help")
        assertTrue(unknown.contains("rwx-micro-1-help --list"), unknown)
    }
    @Test fun `enemy runs no legacy macro phase and retreat is the radial weapon boundary`() {
        val definition=Case1EnemyStrategy.definition()
        for(phase in AiPhase.values())assertFalse(definition.runsOriginal(phase))
        for(p in listOf(floatArrayOf(30f,40f,0f,0f),floatArrayOf(-10f,0f,20f,0f),floatArrayOf(12f,-8f,10f,2f))) {
            val destination=Case1EnemyStrategy.retreatPoint(p[0],p[1],p[2],p[3],190f,1000)
            assertEquals(190.0,hypot((destination[0]-p[2]).toDouble(),(destination[1]-p[3]).toDouble()),0.001)
            assertTrue((destination[0]-p[0])*(p[0]-p[2])+(destination[1]-p[1])*(p[1]-p[3])>0)
        }
        val overlap=Case1EnemyStrategy.retreatPoint(1f,2f,1f,2f,190f,1000)
        assertContentEquals(floatArrayOf(191f,2f),overlap)
    }
    @Test fun `eight hundred generated maps have disjoint regions clear spawns and LAND connectivity`() {
        var retries=0;var maxRetries=0
        for(level in 1..4)for(seed in 0L..199L) {
            val task="1_1_$level";val layout=Case1Map.generate(seed,task)
            assertTrue(layout.width() in 80..120&&layout.height() in 80..120)
            assertTrue(layout.regions().size in 10..20)
            val all=mutableSetOf<Int>()
            var cliffs=0;var pools=0
            for(region in layout.regions()) {
                val cells=region["cells"] as List<*>
                for(cell in cells)assertTrue(all.add((cell as Number).toInt()),"overlap seed=$seed task=$task")
                assertEquals(region["area"],cells.size)
                assertTrue(cells.size in 10..20,"area seed=$seed task=$task kind=${region["kind"]}")
                when(region["kind"]) {
                    "cliff" -> {
                        cliffs++
                        val facings=(region["edge"] as List<*>).associate { edge ->
                            val row=edge as Map<*,*>
                            ((row["y"] as Number).toInt()*layout.width()+(row["x"] as Number).toInt()) to (row["facing"] as Number).toInt()
                        }
                        for(cell in cells) {
                            val key=(cell as Number).toInt();val facing=facings[key]!!
                            assertTrue(facing in 0..7,"cliff facing seed=$seed task=$task")
                            assertEquals(200+facing,layout.tiles()[key])
                        }
                    }
                    "water" -> {
                        pools++
                        for(cell in cells)assertEquals(100,layout.tiles()[(cell as Number).toInt()])
                    }
                    else -> throw AssertionError("unknown region kind ${region["kind"]}")
                }
            }
            assertTrue(cliffs in 5..10&&pools in 5..10,"counts seed=$seed task=$task cliffs=$cliffs pools=$pools")
            val anchor=layout.regions().first()
            assertEquals("water",anchor["kind"],"anchor kind seed=$seed task=$task")
            assertEquals(true,anchor["anchor"])
            // Anchored on our side of the approach: 1/3 or 1/4 of the way from A to B.
            val anchorX=(anchor["seedX"] as Number).toInt()
            val anchorY=(anchor["seedY"] as Number).toInt()
            val nearThird=anchorX==layout.ax()+(layout.bx()-layout.ax())/3&&anchorY==layout.ay()+(layout.by()-layout.ay())/3
            val nearQuarter=anchorX==layout.ax()+(layout.bx()-layout.ax())/4&&anchorY==layout.ay()+(layout.by()-layout.ay())/4
            assertTrue(nearThird||nearQuarter,"anchor seed=$seed task=$task at $anchorX,$anchorY")
            for(y in 0 until layout.height())for(x in 0 until layout.width()) {
                if(hypot((x-layout.ax()).toDouble(),(y-layout.ay()).toDouble())<=8||hypot((x-layout.bx()).toDouble(),(y-layout.by()).toDouble())<=8)
                    assertEquals(1,layout.tiles()[y*layout.width()+x])
            }
            assertTrue(Case1Map.connected(layout.width(),layout.height(),layout.tiles(),layout.ax(),layout.ay(),layout.bx(),layout.by()))
            assertContentEquals(layout.tiles(),Case1Map.generate(seed,task).tiles())
            retries+=layout.retries();maxRetries=maxOf(maxRetries,layout.retries())
        }
        println("CASE1_MAP_VALIDATION cases=800 retries=$retries maxRetries=$maxRetries")
    }
}
