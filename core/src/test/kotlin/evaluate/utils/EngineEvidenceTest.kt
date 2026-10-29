package evaluate.utils

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.land.Artillery
import com.corrodinggames.rts.game.units.management.UnitSpatialIndex
import com.corrodinggames.rts.gameFramework.*
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EngineEvidenceTest {
    private class FixtureUnit : Artillery(true) { override fun d(team: PlayerTeam?) = true }
    private fun withEvidence(block: (GameEngine, PlayerTeam, Path) -> Unit) {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        val tmp = Files.createTempDirectory(Files.createDirectories(root.resolve("agent-tmp")), "engine-evidence-test-")
        val engine = GameEngine.getInstance() ?: GameLogic()
        val oldTeam = engine.playerTeam; val oldSlot = PlayerTeam.k(0); val oldMap = engine.tileMap
        val oldReplay = engine.replayEngine; val oldIndex = engine.unitSpatialIndex
        val oldNetwork = engine.networkEngine
        val oldTime = engine.gameTimeMillis; val oldTick = engine.currentTick
        val oldLoaded = engine.hasLoadedLevel; val oldStopped = engine.isStopped; val oldBackground = engine.isMenuBackgroundMap
        val oldPath = engine.currentMapPath
        val oldTypes = UnitTypeEnum.ae
        EngineEvidence.close("test_setup")
        try {
            engine.tileMap = TileMap(); engine.replayEngine = ReplayEngine(); engine.unitSpatialIndex = UnitSpatialIndex()
            engine.networkEngine = NetworkEngine()
            engine.hasLoadedLevel = true; engine.isStopped = false; engine.isMenuBackgroundMap = false
            engine.currentMapPath = "maps/skirmish/Test.tmx"; engine.gameTimeMillis = 0; engine.currentTick = 0
            UnitTypeEnum.ae = arrayListOf()
            val team = GameTeam(0).apply { credits = 1000.0 }
            engine.playerTeam = team
            System.setProperty("rwx.evaluate.dir", tmp.toString()); System.setProperty("rwx.evaluate.team", "0")
            EngineEvidence.beforeTick()
            block(engine, team, tmp)
        } finally {
            EngineEvidence.close("test_end"); System.clearProperty("rwx.evaluate.dir"); System.clearProperty("rwx.evaluate.team")
            engine.playerTeam = oldTeam; engine.tileMap = oldMap; engine.replayEngine = oldReplay; engine.unitSpatialIndex = oldIndex
            engine.networkEngine = oldNetwork
            engine.gameTimeMillis = oldTime; engine.currentTick = oldTick; engine.currentMapPath = oldPath
            engine.hasLoadedLevel = oldLoaded; engine.isStopped = oldStopped; engine.isMenuBackgroundMap = oldBackground
            UnitTypeEnum.ae = oldTypes
            PlayerTeam.k(0)?.removeFromTeamRegistry(); oldSlot?.let { it.c(-3, false); it.setTeamId(0) }
            check(tmp.toRealPath().startsWith(root.resolve("agent-tmp").toRealPath()))
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    private fun rows(directory: Path): List<JsonObject> {
        EngineEvidence.close("test_end")
        val file = Files.walk(directory).use { it.filter { p -> p.fileName.toString() == "telemetry.jsonl" }.findFirst().orElseThrow() }
        return Files.readAllLines(file).map { Json.parseToJsonElement(it).jsonObject }
    }
    private fun register(team: PlayerTeam, id: Long) = FixtureUnit().apply {
        objectId = id; this.team = team; buildProgress = 1f; currentHealth = 100f
        GameObject.fastGameObjectList.add(this); BaseUnit.bE.add(this); EngineEvidence.observeObject(this)
    }
    private fun unregister(unit: BaseUnit) { GameObject.fastGameObjectList.remove(unit); BaseUnit.bE.remove(unit) }

    @Test fun `economy samples fixed seconds once through pause and flushes partial minute`() = withEvidence { engine, team, tmp ->
        repeat(5) { EngineEvidence.afterTick() }
        for (second in 1..60) {
            engine.gameTimeMillis = second * 1000; engine.currentTick++
            team.credits = 1000.0 + second / 2.0; EngineEvidence.afterTick()
        }
        val samples = rows(tmp).filter { it["kind"]!!.jsonPrimitive.content == "economy" }
        assertEquals(61, samples.size)
        assertEquals(60_000, samples.last()["sampleMs"]!!.jsonPrimitive.int)
        val file = Files.walk(tmp).use { it.filter { p -> p.fileName.toString() == "economy.log" }.findFirst().orElseThrow() }
        val lines = Files.readAllLines(file)
        assertEquals(listOf(60, 1), lines.map { it.split(',').size })
        assertTrue(lines[0].startsWith("1000(+"))
    }
    @Test fun `large simulation time jumps report sampling gaps instead of fabricating past values`() = withEvidence { engine, _, tmp ->
        engine.gameTimeMillis = 5000; EngineEvidence.afterTick()
        val data = rows(tmp)
        assertEquals(2, data.count { it["kind"]!!.jsonPrimitive.content == "economy" })
        assertTrue(data.any { it["kind"]!!.jsonPrimitive.content == "sampling_gap" })
    }
    @Test fun `socket receipt freezes dead and missing references and retains parameters`() = withEvidence { engine, team, tmp ->
        val unit = register(team, 901).apply { isDead = true }
        try {
            engine.gameTimeMillis = 1234
            EngineEvidence.beginRequest("rwx-game-order-attack", listOf("--units", "901,902", "--target", "903"))
            EngineEvidence.endRequest("error: no controllable unit")
            val command = rows(tmp).single { it["kind"]!!.jsonPrimitive.content == "command" }
            assertEquals(1234, command["timeMs"]!!.jsonPrimitive.int)
            val refs = command["refs"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("dead", "missing", "missing"), refs.map { it["status"]!!.jsonPrimitive.content })
            assertEquals("target", refs.last()["role"]!!.jsonPrimitive.content)
        } finally { unregister(unit) }
    }
    @Test fun `request context crosses game-thread handoff and native changes are linked`() = withEvidence { engine, team, tmp ->
        val unit = register(team, 911)
        try {
            engine.gameTimeMillis = 3000
            val request = EngineEvidence.beginRequest("rwx-game-order-move", listOf("--units", "911", "--x", "50", "--y", "90"))
            val work = EngineEvidence.propagate(java.util.concurrent.Callable {
                EngineEvidence.intent(listOf(911L), null, "move", 50f, 90f)
                val command = Command(CommandController()).apply { this.team = team }
                EngineEvidence.tag(command, "MANUAL")
                val before = EngineEvidence.beforeCommand(command, listOf(911L))
                unit.appendMoveWaypoint(50f, 90f)
                EngineEvidence.afterCommand(command, before)
            })
            val worker = Thread { work.call() }; worker.start(); worker.join()
            EngineEvidence.endRequest("accepted move")
            val applied = rows(tmp).single { it["kind"]!!.jsonPrimitive.content == "native" && it["phase"]!!.jsonPrimitive.content == "after" }
            assertEquals(request!!.id(), applied["commandId"]!!.jsonPrimitive.content)
            assertTrue(applied["results"]!!.jsonArray.single().jsonObject["applied"]!!.jsonPrimitive.boolean)
            assertEquals("agent", applied["source"]!!.jsonPrimitive.content)
        } finally { unregister(unit) }
    }
    @Test fun `death observations are deduplicated before engine removal changes positions`() = withEvidence { engine, team, tmp ->
        val unit = register(team, 921).apply { posX = 123f }
        try {
            engine.gameTimeMillis = 1000
            GameStatistics.f.removed(unit, null); GameStatistics.f.removed(unit, null)
            unit.posX = 999f; unit.isDead = true
            val death = rows(tmp).single { it["kind"]!!.jsonPrimitive.content == "death" }
            assertEquals(123f, death["unit"]!!.jsonObject["x"]!!.jsonPrimitive.float)
            assertFalse(death["unit"]!!.jsonObject["alive"]!!.jsonPrimitive.boolean)
        } finally { unregister(unit) }
    }
    @Test fun `new matches receive independent evidence and economy files`() = withEvidence { engine, _, tmp ->
        engine.gameTimeMillis = 2000; EngineEvidence.afterTick(); EngineEvidence.close("match_end")
        engine.tileMap = TileMap(); engine.gameTimeMillis = 0; engine.currentTick = 0; EngineEvidence.beforeTick()
        EngineEvidence.close("match_end")
        val economies = Files.walk(tmp).use { it.filter { p -> p.fileName.toString() == "economy.log" }.toList() }
        assertEquals(2, economies.size)
        assertTrue(economies.all { Files.readString(it).isNotBlank() })
    }
}
