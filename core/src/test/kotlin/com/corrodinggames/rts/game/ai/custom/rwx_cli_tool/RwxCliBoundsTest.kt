package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 纯逻辑边界的回归测试：不加载地图、不启动游戏。
 *
 * 覆盖两处曾经会静默出错的地方——"已见过单位"账本的软上限，以及回执里数字/时间的格式。
 */
class RwxCliBoundsTest {
    @Test
    fun `bounded id set evicts the oldest when full`() {
        val ids = RwxBoundedIdSet(3)
        ids.add(1)
        ids.add(2)
        ids.add(3)
        assertTrue(ids.contains(2))

        ids.add(4)

        assertEquals(3, ids.size())
        assertFalse(ids.contains(1), "the oldest id should be evicted")
        assertTrue(ids.contains(2))
        assertTrue(ids.contains(4))
    }

    @Test
    fun `bounded id set keeps accepting new ids after the cap is reached`() {
        val ids = RwxBoundedIdSet(2)
        repeat(100) { ids.add(it.toLong()) }

        assertEquals(2, ids.size())
        assertTrue(ids.contains(99), "new ids must still be recorded once the cap is hit")

        ids.add(100)
        assertTrue(ids.contains(100))
        assertEquals(2, ids.size())
    }

    @Test
    fun `bounded id set ignores duplicates and clears`() {
        val ids = RwxBoundedIdSet(2)
        ids.add(7)
        ids.add(7)
        assertEquals(1, ids.size())

        ids.clear()
        assertEquals(0, ids.size())
    }

    @Test
    fun `cli text keeps one number format and one clock format`() {
        assertEquals("3", RwxCliText.number(3.0))
        assertEquals("3.5", RwxCliText.number(3.46))
        assertEquals("00:05", RwxCliText.time(5_000))
        assertEquals("05:43", RwxCliText.time(343_000))
        assertEquals("1:01:01", RwxCliText.time(3_661_000))
        assertEquals("00:00", RwxCliText.time(-1))
        assertEquals("1,2,3", RwxCliText.joinIds(listOf(1L, 2L, 3L)))
        assertEquals("none", RwxCliText.joinIds(emptyList()))
        assertEquals("none", RwxCliText.joinIds(null))
    }

    @Test
    fun `credit shortages stay retryable but permanent failures do not`() {
        // 实战 F-002：重试时抛的是 "cannot be afforded by unit"，曾被当成永久失效丢弃。
        assertTrue(RwxEngineLocalGameService.isCreditShortage(
            RwxLocalGameTypes.CreditShortageException("need 1600, available 1437")))
        assertTrue(RwxEngineLocalGameService.isCreditShortage(
            IllegalArgumentException("Action '110' cannot be afforded by unit 288")))
        assertTrue(RwxEngineLocalGameService.isCreditShortage(
            IllegalArgumentException("Insufficient credits for action 'x': need 1, available 0")))
        assertFalse(RwxEngineLocalGameService.isCreditShortage(
            IllegalArgumentException("Unit 288 (c_tank) has no action '110'")))
        assertFalse(RwxEngineLocalGameService.isCreditShortage(
            IllegalArgumentException("Cannot place c_repairbay: blocked")))
        assertFalse(RwxEngineLocalGameService.isCreditShortage(null))
    }
}
