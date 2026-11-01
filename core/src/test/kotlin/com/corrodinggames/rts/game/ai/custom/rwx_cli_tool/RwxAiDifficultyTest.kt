package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.ai.strategy.AiRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `--difficulty` parsing: registered codes, stable ids, and English/Chinese names. */
class RwxAiDifficultyTest {
    @Test
    fun `original difficulty codes and English names resolve`() {
        assertEquals(-2, RwxLocalBattleRoomCommands.resolveDifficulty("-2"))
        assertEquals(3, RwxLocalBattleRoomCommands.resolveDifficulty("3"))
        assertEquals(2, RwxLocalBattleRoomCommands.resolveDifficulty("Very Hard"))
        assertEquals(1, RwxLocalBattleRoomCommands.resolveDifficulty("hard"))
        assertEquals(0, RwxLocalBattleRoomCommands.resolveDifficulty("Medium"))
    }

    @Test
    fun `Chinese names resolve`() {
        assertEquals(-2, RwxLocalBattleRoomCommands.resolveDifficulty("非常简单"))
        assertEquals(-1, RwxLocalBattleRoomCommands.resolveDifficulty("简单"))
        assertEquals(0, RwxLocalBattleRoomCommands.resolveDifficulty("中等"))
        assertEquals(1, RwxLocalBattleRoomCommands.resolveDifficulty("困难"))
        assertEquals(2, RwxLocalBattleRoomCommands.resolveDifficulty("非常困难"))
        assertEquals(3, RwxLocalBattleRoomCommands.resolveDifficulty("不可能"))
    }

    @Test
    fun `stable ids work with and without the rwx prefix`() {
        assertEquals(1, RwxLocalBattleRoomCommands.resolveDifficulty("rwx.original.hard"))
        assertEquals(1, RwxLocalBattleRoomCommands.resolveDifficulty("hard"))
        assertEquals(4, RwxLocalBattleRoomCommands.resolveDifficulty("rwx.ai-high"))
        assertEquals(4, RwxLocalBattleRoomCommands.resolveDifficulty("AI-high"))
        assertEquals(7, RwxLocalBattleRoomCommands.resolveDifficulty("AI_S"))
        assertEquals(11, RwxLocalBattleRoomCommands.resolveDifficulty("rwx.ai-hard-1income"))
        assertEquals(11, RwxLocalBattleRoomCommands.resolveDifficulty("ai-hard-1income"))
        assertEquals(12, RwxLocalBattleRoomCommands.resolveDifficulty("AI-very-hard-1income"))
        assertEquals(12, RwxLocalBattleRoomCommands.resolveDifficulty("AI-非常困难-1倍收入"))
    }

    @Test
    fun `separators and case are ignored`() {
        assertEquals(2, RwxLocalBattleRoomCommands.resolveDifficulty("very_hard"))
        assertEquals(2, RwxLocalBattleRoomCommands.resolveDifficulty("VERY HARD"))
        assertEquals(12, RwxLocalBattleRoomCommands.resolveDifficulty("ai very hard 1income"))
    }

    @Test
    fun `unregistered numeric code and unknown name are rejected`() {
        assertFailsWith<IllegalStateException> { RwxLocalBattleRoomCommands.resolveDifficulty("5") }
        assertFailsWith<IllegalStateException> { RwxLocalBattleRoomCommands.resolveDifficulty("Very") }
        assertFailsWith<IllegalStateException> { RwxLocalBattleRoomCommands.resolveDifficulty("") }
    }

    @Test
    fun `showAi lists every registered AI with code, names and id`() {
        val text = RwxLocalBattleRoomCommands.showAi()
        for (definition in AiRegistry.definitions()) {
            assertTrue(text.contains(definition.id()), "missing id ${definition.id()}")
            assertTrue(text.contains(definition.displayName()), "missing name ${definition.displayName()}")
            assertTrue(
                text.contains(definition.displayName(java.util.Locale.SIMPLIFIED_CHINESE)),
                "missing zh-CN name for ${definition.id()}",
            )
            val line = String.format(java.util.Locale.ROOT, "%3d", definition.code())
            assertTrue(text.contains(line + " | "), "missing code ${definition.code()}")
        }
        assertTrue(text.contains("原版："))
        assertTrue(text.contains("自定义："))
        assertTrue(text.contains("  1 | Hard / 困难 | rwx.original.hard"))
        assertTrue(text.contains(" 11 | AI-hard-1income / AI-困难-1倍收入 | rwx.ai-hard-1income"))
    }

    @Test
    fun `showAI command name resolves regardless of case`() {
        assertEquals(
            RwxCliCommands.Command.BATTLE_ROOM_SHOW_AI,
            RwxCliCommands.find("rwx-battleroom-show-AI"),
        )
        assertEquals(
            RwxCliCommands.Command.BATTLE_ROOM_SHOW_AI,
            RwxCliCommands.find("rwx-battleroom-show-ai"),
        )
    }
}
