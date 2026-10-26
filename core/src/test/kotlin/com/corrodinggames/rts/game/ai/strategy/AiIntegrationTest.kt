package com.corrodinggames.rts.game.ai.strategy

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.ai.AIController
import com.corrodinggames.rts.game.ai.BaseZone
import com.corrodinggames.rts.game.ai.custom.high.CustomHighAiStrategy
import com.corrodinggames.rts.game.ai.custom.myai.MyHighStrategy
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.land.BuilderUnit
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.GameInputStream
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.app.battleRoomOptionsForm
import io.github.rwx.app.playerConfigForm
import io.github.rwx.ui.model.BattleRoomPlayer
import io.github.rwx.ui.model.DialogFormField
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.*

class AiIntegrationTest {
    private val testCode = 1000
    private lateinit var engine: GameEngine
    private val agents = mutableListOf<RecordingStrategy>()
    private var previousUnitTypes: ArrayList<UnitType>? = null

    private class RecordingStrategy : AiStrategy {
        var frames = 0
        var restored = false
        var buildingOverride = false
        val phases = mutableListOf<String>()
        var frameAction: ((AiContext) -> Unit)? = null
        override fun id() = "test.ai-high.v1"
        override fun onStart(context: AiContext, restored: Boolean) {
            this.restored = restored
            phases += "start"
        }
        override fun onFrame(context: AiContext, deltaFrames: Float) {
            frames++
            frameAction?.invoke(context)
        }
        override fun onEconomy(context: AiContext, elapsedFrames: Float) { phases += "economy:$elapsedFrames" }
        override fun overridesBuildingProduction() = buildingOverride
        override fun onBuildingProduction(context: AiContext, elapsedFrames: Float) {
            if (buildingOverride) phases += "buildings:$elapsedFrames"
        }
        override fun onTactics(context: AiContext, elapsedFrames: Float) { phases += "tactics:$elapsedFrames" }
        override fun onStrategy(context: AiContext, elapsedFrames: Float) { phases += "strategy:$elapsedFrames" }
        override fun onStop(context: AiContext) { phases += "stop" }
        override fun writeState(output: DataOutputStream) { output.writeInt(frames) }
        override fun readState(input: DataInputStream) { frames = input.readInt() }
    }

    @BeforeTest
    fun setUp() {
        engine = GameEngine.getInstance() ?: GameLogic()
        engine.networkEngine = NetworkEngine()
        engine.replayEngine = ReplayEngine()
        engine.commandController = CommandController()
        previousUnitTypes = UnitTypeEnum.ae
        UnitTypeEnum.ae = arrayListOf()
        AiRegistry.register(testDefinition())
    }

    @AfterTest
    fun tearDown() {
        AiRegistry.unregister(testCode)
        UnitTypeEnum.ae = previousUnitTypes
    }

    private fun testDefinition() = AiDefinition.custom(testCode, "test.ai", "Test AI", "测试AI") {
        RecordingStrategy().also { agents += it }
    }

    private fun replaceDefinition(definition: AiDefinition) {
        AiRegistry.unregister(testCode)
        AiRegistry.register(definition)
    }

    private fun team(slot: Int = 0, difficulty: Int = testCode) =
        AIController(slot, false).apply {
            isTeamLocked = true
            teamPingTime = difficulty
        }

    @Test
    fun `AI-high runs its own phases without creating original bases or groups`() {
        // No map is loaded: original updateTeam would attempt map initialization and fail here.
        val team = team()
        team.updateTeam(24f)
        assertEquals(listOf("start"), agents.single().phases)
        team.updateTeam(1f)
        team.updateTeam(55f)
        team.updateTeam(170f)
        assertEquals(
            listOf("start", "economy:25.0", "economy:55.0", "tactics:80.0",
                "economy:170.0", "tactics:170.0", "strategy:250.0"),
            agents.single().phases,
        )
        assertTrue(engine.commandController.pendingCommands.isEmpty())
        assertEquals(1f, team.spectatorEnergyFactor)
        assertEquals(3.7f, team(difficulty = 3).spectatorEnergyFactor, 0.0001f)
    }

    @Test
    fun `custom building production runs on the economy cadence and reports the override`() {
        replaceDefinition(AiDefinition.custom(testCode, "test.ai", "Test AI", "测试AI") {
            RecordingStrategy().also { it.buildingOverride = true; agents += it }
        })
        val team = team()
        assertFalse(team.overridesBuildingProduction())
        team.updateTeam(25f)
        assertTrue(team.overridesBuildingProduction())
        assertEquals(listOf("start", "economy:25.0", "buildings:25.0"), agents.single().phases)
    }

    @Test
    fun `composite reports and forwards the building override of its modules`() {
        val module = object : AiStrategy {
            var calls = 0
            override fun id() = "buildings.v1"
            override fun overridesBuildingProduction() = true
            override fun onBuildingProduction(context: AiContext, frames: Float) { calls++ }
        }
        val composite = CompositeAiStrategy("composite.buildings.v1", RecordingStrategy(), module)
        assertTrue(composite.overridesBuildingProduction())
        composite.onBuildingProduction(AiContext(team()), 1f)
        assertEquals(1, module.calls)
    }

    @Test
    fun `each team has independent strategy state`() {
        val first = team(0)
        val second = team(1)
        first.updateTeam(1f)
        second.updateTeam(1f)
        first.updateTeam(1f)
        assertEquals(listOf(2, 1), agents.map { it.frames })
        assertNotSame(agents[0], agents[1])
    }

    @Test
    fun `clients and replay playback never generate decisions but recording still does`() {
        val team = team()
        engine.networkEngine.networkGameActive = true
        engine.networkEngine.isServer = false
        team.updateTeam(1f)
        assertTrue(agents.isEmpty())
        engine.networkEngine.isServer = true
        team.updateTeam(1f)
        assertEquals(1, agents.single().frames)
        engine.networkEngine.networkGameActive = false
        engine.replayEngine = object : ReplayEngine() {
            override fun j() = true
        }
        team.updateTeam(1f)
        assertEquals(1, agents.single().frames)
        engine.replayEngine = ReplayEngine().apply { P = true } // recording, not playback
        team.updateTeam(1f)
        assertEquals(2, agents.single().frames)
    }

    @Test
    fun `save restores state and phase timing only after world loading completes`() {
        val original = team()
        original.updateTeam(24f)
        val output = GameOutputStream()
        original.a(output)
        val restored = team(1)
        restored.readExtendedTeamState(GameInputStream(output.toByteArray()))
        assertEquals(1, agents.size, "Deserialization must not create a strategy or issue orders")
        // Saving again before the first active tick must preserve the deferred payload.
        val deferred = GameOutputStream()
        restored.a(deferred)
        val restoredAgain = team(2)
        restoredAgain.readExtendedTeamState(GameInputStream(deferred.toByteArray()))
        restoredAgain.updateTeam(1f)
        assertTrue(agents.last().restored)
        assertEquals(2, agents.last().frames)
        assertEquals(listOf("start", "economy:25.0"), agents.last().phases)
        assertEquals(testCode, restoredAgain.getTeamColorId())
    }

    @Test
    fun `ordinary difficulty saves retain version 9 and still round trip`() {
        val original = team(difficulty = 3)
        val output = GameOutputStream()
        original.a(output)
        val bytes = output.toByteArray()
        // Header: bool, four floats, global float, int, float, bool, int, zone count.
        assertEquals(9, bytes[38].toInt())
        val restored = team(1)
        restored.readExtendedTeamState(GameInputStream(bytes))
        assertEquals(3, restored.getTeamColorId())
        assertTrue(agents.isEmpty())
    }

    @Test
    fun `strategy mismatch is explicit and does not run the wrong policy`() {
        val original = team()
        original.updateTeam(1f)
        val output = GameOutputStream()
        original.a(output)
        val restored = team(1)
        restored.readExtendedTeamState(GameInputStream(output.toByteArray()))
        replaceDefinition(AiDefinition.custom(testCode, "test.ai", "Test AI", "测试AI", ::CustomHighAiStrategy))
        val error = assertFailsWith<IllegalStateException> { restored.updateTeam(1f) }
        assertTrue(error.message!!.contains("test.ai-high.v1"))
    }

    @Test
    fun `commands use the standard queue and reject foreign units before enqueueing`() {
        val team = team()
        val own = BuilderUnit(true).apply { this.team = team }
        val foreign = BuilderUnit(true).apply { this.team = team(1) }
        team.updateTeam(0f)
        lateinit var context: AiContext
        agents.single().frameAction = {
            context = it
            assertFailsWith<IllegalArgumentException> { it.command(own, foreign) }
            assertFailsWith<IllegalArgumentException> { it.command() }
            assertTrue(engine.commandController.pendingCommands.isEmpty())
            it.orders().move(100f, 200f, own)
        }
        team.updateTeam(1f)
        val command = engine.commandController.pendingCommands.single()
        assertSame(team, command.team)
        assertEquals(1, command.affectedUnitCount)
        assertFailsWith<IllegalStateException> { context.command(own) }
    }

    @Test
    fun `original AI is not instantiated for a paused AI-high team`() {
        val team = team().apply { canBuild = true }
        team.updateTeam(500f)
        assertTrue(agents.isEmpty())
        team.canBuild = false
        team.updateTeam(1f)
        assertEquals(1, agents.single().frames)
    }

    @Test
    fun `new registration automatically appears in room UI and naming`() {
        val options = engine.networkEngine.roomSettings.apply { aiDifficulty = testCode }
        val field = battleRoomOptionsForm(options).fields.filterIsInstance<DialogFormField.Choice>()
            .single { it.id == "aiDifficulty" }
        assertEquals(testCode.toString(), field.options[field.selectedIndex].value)
        assertEquals("Test AI", engine.networkEngine.c(testCode))
        assertTrue(field.options.any { it.value == "4" && it.label == "AI-high" })
        val player = BattleRoomPlayer("0", "AI", "1", "A", isAI = true, aiDifficultyOverride = testCode)
        val override = playerConfigForm(player, options, isHost = true).fields
            .filterIsInstance<DialogFormField.Choice>().single { it.id == "aiDifficulty" }
        assertEquals(testCode.toString(), override.options[override.selectedIndex].value)
    }

    @Test
    fun `original presets retain all names income arithmetic and serialization format`() {
        val names = listOf("Very Easy", "Easy", "Medium", "Hard", "Very Hard", "Impossible")
        for ((index, code) in (-2..3).withIndex()) {
            assertEquals(names[index], AiRegistry.displayName(code))
            var income = if (code > 0) 1f + code * 0.4f else 1f + code * 0.3f
            if (code == 3) income += 1.5f
            assertEquals(income, team(difficulty = code).spectatorEnergyFactor)
            assertFalse(AiRegistry.find(code)!!.isCustom)
            val output = GameOutputStream()
            team(difficulty = code).a(output)
            assertEquals(9, output.toByteArray()[38].toInt())
        }
    }

    @Test
    fun `registration rejects duplicate codes and ids and preserves original presets`() {
        assertFailsWith<IllegalArgumentException> { AiRegistry.register(testDefinition()) }
        assertFailsWith<IllegalArgumentException> {
            AiRegistry.register(AiDefinition.custom(1001, "test.ai", "Other", "其他", ::CustomHighAiStrategy))
        }
        assertFailsWith<IllegalArgumentException> { AiRegistry.unregister(3) }
        assertFailsWith<IllegalArgumentException> { AiCadence(0f, 80f, 250f) }
    }

    @Test
    fun `profile controls income cadence and translated name from one definition`() {
        replaceDefinition(testDefinition().withIncomeMultiplier(1.25f).withCadence(AiCadence(10f, 20f, 30f)))
        val team = team()
        assertEquals(1.25f, team.spectatorEnergyFactor)
        assertEquals("测试AI", AiRegistry.find(testCode)!!.displayName(java.util.Locale.SIMPLIFIED_CHINESE))
        team.updateTeam(10f)
        assertEquals(listOf("start", "economy:10.0"), agents.single().phases)
        team.updateTeam(10f)
        assertEquals("tactics:20.0", agents.single().phases.last())
        team.updateTeam(10f)
        assertEquals("strategy:30.0", agents.single().phases.last())
    }

    @Test
    fun `selected original economy runs without invoking original tactics or strategy`() {
        replaceDefinition(testDefinition().withOriginalPhases(3, AiPhase.ECONOMY))
        val team = team()
        var economyCalls = 0
        var productionCalls = 0
        object : BaseZone(team, 0f, 0f) {
            override fun updateZone(frames: Float) { economyCalls++ }
            override fun updateZoneStrategy(frames: Float) { productionCalls++ }
        }
        // All other original phases would require a map, which this test intentionally lacks.
        team.updateTeam(500f)
        assertEquals(1, economyCalls)
        assertEquals(1, productionCalls)
        assertTrue(team.isInsaneDifficulty)
        assertEquals(3, team.aiDifficulty)
        assertEquals(testCode, team.getTeamColorId())
    }

    @Test
    fun `missing custom registration refuses a saved game instead of silently running original AI`() {
        val original = team()
        original.updateTeam(1f)
        val output = GameOutputStream()
        original.a(output)
        val restored = team(1)
        restored.readExtendedTeamState(GameInputStream(output.toByteArray()))
        AiRegistry.unregister(testCode)
        assertFailsWith<IllegalStateException> { restored.updateTeam(1f) }
    }

    @Test
    fun `initial AI-high runtime version 1 remains readable`() {
        val legacy = GameOutputStream()
        legacy.writeByte(1)
        repeat(3) { legacy.writeFloat(0f) }
        legacy.writeStringNullable("rwx.ai-high.custom.v1")
        legacy.writeBytesWithLength(byteArrayOf())
        val runtime = AiRuntime.readFrom(GameInputStream(legacy.toByteArray()))
        val definition = AiRegistry.find(4)!!
        runtime.update(AiContext(team(difficulty = 4)), 1f, definition)
        assertTrue(runtime.isStarted)
    }

    @Test
    fun `composed modules retain independent state and callback order after save`() {
        val economy = RecordingStrategy()
        val combat = object : AiStrategy {
            var ticks = 0
            override fun id() = "combat.v1"
            override fun onFrame(context: AiContext, deltaFrames: Float) { ticks++ }
            override fun writeState(output: DataOutputStream) { output.writeInt(ticks) }
            override fun readState(input: DataInputStream) { ticks = input.readInt() }
        }
        val composite = CompositeAiStrategy("composite.v1", economy, combat)
        val context = AiContext(team())
        composite.onFrame(context, 1f)
        composite.onFrame(context, 1f)
        val buffer = java.io.ByteArrayOutputStream()
        composite.writeState(DataOutputStream(buffer))
        economy.frames = 0
        combat.ticks = 0
        composite.readState(DataInputStream(java.io.ByteArrayInputStream(buffer.toByteArray())))
        assertEquals(2, economy.frames)
        assertEquals(2, combat.ticks)
        assertFailsWith<IllegalArgumentException> { CompositeAiStrategy("bad", economy, economy) }
    }

    @Test
    fun `leaving custom AI stops its instance and resumes original initialization`() {
        val team = team()
        team.updateTeam(1f)
        team.teamPingTime = 0
        // Disable map-dependent original passes; a native update must not invoke custom callbacks.
        val wait = AIController::class.java.getDeclaredField("attackTimerEasy").apply { isAccessible = true }
        val core = AIController::class.java.getDeclaredField("attackTimerHard").apply { isAccessible = true }
        wait.setFloat(team, -1000f)
        core.setFloat(team, -1000f)
        val initialization = AIController::class.java.getDeclaredField("enableNaval").apply { isAccessible = true }
        initialization.setBoolean(team, false)
        team.updateTeam(1f)
        assertEquals("stop", agents.single().phases.last())
        assertEquals(1, agents.single().frames)
        assertEquals(0, team.aiDifficulty)
    }

    @Test
    fun `documented example registers as a second custom AI without changing engine or UI code`() {
        AiRegistry.register(MyHighStrategy.definition())
        try {
            val team = team(difficulty = 5)
            team.updateTeam(1f)
            assertEquals(1f, team.spectatorEnergyFactor)
            assertEquals("My AI", engine.networkEngine.c(5))
            val output = GameOutputStream()
            team.a(output)
            val restored = team(1)
            restored.readExtendedTeamState(GameInputStream(output.toByteArray()))
            restored.updateTeam(1f)
            assertEquals(5, restored.getTeamColorId())
        } finally {
            AiRegistry.unregister(5)
        }
    }
}
