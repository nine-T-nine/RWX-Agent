package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import org.junit.jupiter.api.Test
import kotlin.test.*

class RwxTierTest {
    @Test fun `default tier is the middle one`() {
        assertEquals(2, RwxTier.DEFAULT)
        assertEquals(2, RwxTier.active().level)
        assertEquals(RwxTier.of(2), RwxTier.active())
        RwxTier.install(null)
        assertEquals(2, RwxTier.active().level)
    }

    @Test fun `only the easy tier hands the switches back to the agent`() {
        assertTrue(RwxTier.of(1).agentMaySwitchAlerts)
        assertFalse(RwxTier.of(2).agentMaySwitchAlerts)
        assertFalse(RwxTier.of(3).agentMaySwitchAlerts)
        // Suggestions are scaffolding: easy tier only, everywhere else off.
        assertTrue(RwxTier.of(1).suggestions)
        assertFalse(RwxTier.of(2).suggestions)
        assertFalse(RwxTier.of(3).suggestions)
        // Every tier still tells the agent what happened.
        for (level in 1..3) assertEquals(RwxDelivery.Level.L1, RwxTier.of(level).alertLevel)
    }

    @Test fun `the scored tiers are identical for now and only the label differs`() {
        // Tier 3 has no stricter axis yet, so 2 and 3 must not silently drift apart: if they ever
        // differ, this test fails and whoever changed it has to say which capability moved.
        assertEquals(RwxTier.of(2).alertLevel, RwxTier.of(3).alertLevel)
        assertEquals(RwxTier.of(2).suggestions, RwxTier.of(3).suggestions)
        assertEquals(RwxTier.of(2).agentMaySwitchAlerts, RwxTier.of(3).agentMaySwitchAlerts)
        assertNotEquals(RwxTier.of(2).describe(), RwxTier.of(3).describe())
    }

    @Test fun `tiers parse from the command line and reject anything else`() {
        assertEquals(1, RwxTier.parse("1").level)
        assertEquals(3, RwxTier.parse(" 3 ").level)
        assertEquals(RwxTier.DEFAULT, RwxTier.parse(null).level)
        assertEquals(RwxTier.DEFAULT, RwxTier.parse("").level)
        for (bad in listOf("0", "4", "-1", "two", "1.0")) {
            assertFailsWith<IllegalArgumentException>(bad) { RwxTier.parse(bad) }
        }
        assertFailsWith<IllegalArgumentException> { RwxTier.of(0) }
        assertFailsWith<IllegalArgumentException> { RwxTier.of(4) }
    }

    @Test fun `a case id is never mistaken for a tier`() {
        assertTrue(RwxTier.looksLikeTier("1"))
        assertTrue(RwxTier.looksLikeTier(" 2 "))
        assertFalse(RwxTier.looksLikeTier("CASE1-duel"))
        assertFalse(RwxTier.looksLikeTier("4"))
        assertFalse(RwxTier.looksLikeTier(null))
    }

    @Test fun `the evidence description names every difference between tiers`() {
        val easy = RwxTier.of(1).describe()
        assertEquals(1, easy["level"])
        assertEquals("l1", easy["alertLevel"])
        assertEquals(true, easy["suggestions"])
        assertEquals(true, easy["agentMaySwitchAlerts"])
        val hard = RwxTier.of(3).describe()
        assertEquals(3, hard["level"])
        assertEquals(false, hard["suggestions"])
        assertEquals(false, hard["agentMaySwitchAlerts"])
        assertEquals(
            "tier=2 suggestions=false agentSwitch=false",
            RwxTier.of(2).summary(),
        )
    }

    @Test fun `installing a tier is process wide and reversible`() {
        try {
            RwxTier.install(RwxTier.of(3))
            assertEquals(3, RwxTier.active().level)
            assertFalse(RwxTier.active().suggestions)
        } finally {
            RwxTier.install(null)
        }
        assertEquals(RwxTier.DEFAULT, RwxTier.active().level)
    }
}
