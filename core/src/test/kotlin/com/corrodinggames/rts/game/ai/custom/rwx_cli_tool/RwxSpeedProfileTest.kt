package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import org.junit.jupiter.api.Test
import kotlin.test.*

class RwxSpeedProfileTest {
    @Test fun `realtime is the default and never pins the clock`() {
        assertEquals(RwxSpeedProfile.Kind.REALTIME, RwxSpeedProfile.parse(null).kind)
        assertEquals("realtime", RwxSpeedProfile.parse("").id())
        assertEquals("realtime", RwxSpeedProfile.parse("realtime").id())
        assertEquals("realtime", RwxSpeedProfile.parse("1").id())
        assertEquals("realtime", RwxSpeedProfile.parse("1x").id())
        assertFalse(RwxSpeedProfile.realtime().enforcesSpeed())
        assertEquals(1.0f, RwxSpeedProfile.realtime().speed)
    }

    @Test fun `slow accepts a bare multiplier and the slow prefix`() {
        assertEquals("slow:0.5", RwxSpeedProfile.parse("slow").id())
        assertEquals(0.5f, RwxSpeedProfile.parse("slow").speed)
        assertEquals("slow:0.25", RwxSpeedProfile.parse("slow:0.25").id())
        assertEquals("slow:0.25", RwxSpeedProfile.parse("0.25").id())
        assertEquals("slow:0.5", RwxSpeedProfile.parse("SLOW:0.5x").id())
        assertTrue(RwxSpeedProfile.parse("slow:0.25").enforcesSpeed())
        assertEquals(RwxSpeedProfile.Kind.SLOW, RwxSpeedProfile.parse("0.5").kind)
    }

    @Test fun `sync is refused because it would delete the latency being measured`() {
        for (value in listOf("sync", "sync:1", "SYNC")) {
            val error = assertFailsWith<IllegalArgumentException>(value) { RwxSpeedProfile.parse(value) }
            // The refusal must read as "not offered", never as "not yet": a pause-while-thinking
            // clock would remove the very thing a realtime benchmark scores.
            assertTrue(error.message!!.contains("realtime benchmark"), error.message!!)
            assertTrue(error.message!!.contains("compensates for agent"), error.message!!)
        }
    }

    @Test fun `only realtime is leaderboard eligible and everything else is diagnostic`() {
        assertTrue(RwxSpeedProfile.realtime().leaderboardEligible())
        assertFalse(RwxSpeedProfile.realtime().diagnostic())
        for (value in listOf("slow", "slow:0.5", "0.25")) {
            val profile = RwxSpeedProfile.parse(value)
            assertFalse(profile.leaderboardEligible(), value)
            assertTrue(profile.diagnostic(), value)
        }
    }

    @Test fun `out of range and unknown profiles are rejected`() {
        for (value in listOf("0", "0.0", "1.5", "2", "-0.5", "fast", "slow:0", "slow:1")) {
            assertFailsWith<IllegalArgumentException>(value) { RwxSpeedProfile.parse(value) }
        }
    }

    @Test fun `installing a profile is process wide and reversible`() {
        try {
            RwxSpeedProfile.install(RwxSpeedProfile.parse("slow:0.25"))
            assertEquals("slow:0.25", RwxSpeedProfile.active().id())
            assertEquals(0.25f, RwxSpeedProfile.active().speed)
            RwxSpeedProfile.install(null)
            assertEquals("realtime", RwxSpeedProfile.active().id())
            assertFalse(RwxSpeedProfile.active().enforcesSpeed())
        } finally {
            RwxSpeedProfile.install(null)
        }
    }
}
