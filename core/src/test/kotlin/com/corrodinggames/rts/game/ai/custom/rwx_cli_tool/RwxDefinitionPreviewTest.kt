package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 生成 4 个代表单位的百科预览（默认 / detail / JSON），写到 `core/build/definition-preview.txt`。
 *
 * <p>只是格式评审用的快照，不参与断言数值；真正的字段测试在后续提交里补。</p>
 */
class RwxDefinitionPreviewTest {
    private var previousUnitTypes: ArrayList<UnitType>? = null

    @BeforeTest
    fun setUp() {
        val engine = GameEngine.getInstance() ?: GameLogic()
        engine.networkEngine = NetworkEngine()
        engine.replayEngine = ReplayEngine()
        engine.commandController = CommandController()

        previousUnitTypes = UnitTypeEnum.ae
        UnitTypeEnum.ae = ArrayList(UnitTypeEnum.values().toList())
        UnitTypeEnum.values().forEach { type -> runCatching { type.h() } }
    }

    @AfterTest
    fun tearDown() {
        UnitTypeEnum.ae = previousUnitTypes
    }

    @Test
    fun `write four representative definitions`() {
        val ids = listOf("c_tank", "builder", "landFactory", "attackSubmarine")
        val index = RwxUnitQueryCatalog.buildIndex()
        val output = StringBuilder()
        for (id in ids) {
            val type = RwxUnitQueryCatalog.resolve(id)
            assertNotNull(type, "unit '$id' should resolve")
            val definition = RwxDefinitionBuilder.build(index, type)
            assertNotNull(definition, "definition for '$id' should build")
            output.append("===== ").append(id).append(" — class=")
                .append(type.javaClass.name).append(" =====\n")
            output.append(RwxDefinitionRenderer.text(definition, false)).append("\n\n")
            output.append("----- ").append(id).append(" — detail -----\n")
            output.append(RwxDefinitionRenderer.text(definition, true)).append("\n\n")
            output.append("----- ").append(id).append(" — json -----\n")
            output.append(RwxDefinitionRenderer.json(definition, false)).append("\n\n\n")
        }
        val file = File("build/definition-preview.txt")
        file.parentFile?.mkdirs()
        file.writeText(output.toString())
        assertTrue(file.length() > 0)
    }
}
