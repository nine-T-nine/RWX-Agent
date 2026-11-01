package io.github.rwx.app

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxDelivery
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxSpeedProfile
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxTier
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `--evaluate` is the single switch for the built-in instrumentation: without it a normal match must not start the
 * CLI recorder or the per-frame engine evidence collection, because both cost frame time.
 */
class AppOptionsTest {

    @Test
    fun `evaluate also enables the command recorder while plain play stays uninstrumented`() {
        val plain = AppOptions.parseArgs(arrayOf("--no-steam"))
        assertFalse(plain.evaluate)
        assertFalse(plain.rwxCliEnabled)

        val measured = AppOptions.parseArgs(arrayOf("--evaluate"))
        assertTrue(measured.evaluate)
        assertTrue(measured.rwxCliEnabled)
    }

    @Test
    fun `evaluate defaults the evidence directory but an explicit property still wins`() {
        val previous = System.getProperty("rwx.evaluate.dir")
        System.clearProperty("rwx.evaluate.dir")
        try {
            AppOptions.parseArgs(arrayOf("--evaluate")).applyEvaluationDefaults()
            val evidence = requireNotNull(System.getProperty("rwx.evaluate.dir")) { "evidence directory missing" }
            assertTrue(Path.of(evidence).endsWith(Path.of("evaluate", "output", "events-live")))

            System.setProperty("rwx.evaluate.dir", "custom-evidence")
            AppOptions.parseArgs(arrayOf("--evaluate")).applyEvaluationDefaults()
            assertEquals("custom-evidence", System.getProperty("rwx.evaluate.dir"))
        } finally {
            if (previous == null) System.clearProperty("rwx.evaluate.dir")
            else System.setProperty("rwx.evaluate.dir", previous)
        }
    }

    @Test
    fun `plain play leaves the evidence directory unset`() {
        val previous = System.getProperty("rwx.evaluate.dir")
        System.clearProperty("rwx.evaluate.dir")
        try {
            AppOptions.parseArgs(arrayOf("--no-steam", "--rwx-cli")).applyEvaluationDefaults()
            assertNull(System.getProperty("rwx.evaluate.dir"))
        } finally {
            if (previous == null) System.clearProperty("rwx.evaluate.dir")
            else System.setProperty("rwx.evaluate.dir", previous)
        }
    }

    @Test
    fun `unknown arguments are still rejected`() {
        assertFailsWith<IllegalArgumentException> { AppOptions.parseArgs(arrayOf("--evaluate-all")) }
    }

    @Test
    fun `speed profile and alert delivery are validated at launch and installed process wide`() {
        val previousProfile = RwxSpeedProfile.active()
        val previousSwitch = System.getProperty(
            RwxDelivery.SWITCH_PROPERTY,
        )
        try {
            val slow = AppOptions.parseArgs(arrayOf("--no-steam", "--speed-profile=slow:0.25", "--alert-delivery=l0"))
            assertEquals("slow:0.25", slow.speedProfile)
            assertEquals("l0", slow.alertDelivery)
            slow.applyEvaluationDefaults()
            assertEquals("slow:0.25", RwxSpeedProfile.active().id())
            assertEquals(
                "l0",
                System.getProperty(RwxDelivery.SWITCH_PROPERTY),
            )

            // A typo must not start a match that is silently mislabelled.
            assertFailsWith<IllegalArgumentException> { AppOptions.parseArgs(arrayOf("--speed-profile=sync")) }
            assertFailsWith<IllegalArgumentException> { AppOptions.parseArgs(arrayOf("--speed-profile=2")) }
            assertFailsWith<IllegalArgumentException> { AppOptions.parseArgs(arrayOf("--alert-delivery=l2")) }
            // l1-lite is a peer level, not a typo.
            assertEquals("l1-lite", AppOptions.parseArgs(arrayOf("--alert-delivery=l1-lite")).alertDelivery)
            // Realtime stays the default so an existing invocation keeps its exact behaviour.
            AppOptions.parseArgs(arrayOf("--no-steam")).applyEvaluationDefaults()
            assertEquals("realtime", RwxSpeedProfile.active().id())
        } finally {
            RwxSpeedProfile.install(previousProfile)
            if (previousSwitch == null) {
                System.clearProperty(RwxDelivery.SWITCH_PROPERTY)
            } else {
                System.setProperty(
                    RwxDelivery.SWITCH_PROPERTY, previousSwitch,
                )
            }
        }
    }

    @Test
    fun `window flag is opt in and conflicts with headless`() {
        assertFalse(AppOptions.parseArgs(arrayOf("--no-steam")).window)
        val watched = AppOptions.parseArgs(arrayOf("--window", "--evaluate", "CASE1-duel", "--case-args", "task=1_2_2,seed=1,track=pause"))
        assertTrue(watched.window)
        assertFalse(watched.headless)
        assertFailsWith<IllegalArgumentException> {
            AppOptions.parseArgs(arrayOf("--window", "--headless", "--evaluate", "CASE1-duel", "--case-args", "task=1_2_2,seed=1,track=pause"))
        }
    }

    @Test
    fun `evaluate selects a tier while a case id still selects a case`() {
        val default = AppOptions.parseArgs(arrayOf("--evaluate"))
        assertTrue(default.evaluate)
        assertNull(default.evaluateCase)
        assertNull(default.tier)

        val easy = AppOptions.parseArgs(arrayOf("--evaluate", "1"))
        assertEquals(1, easy.tier)
        assertNull(easy.evaluateCase)

        val hard = AppOptions.parseArgs(arrayOf("--evaluate=3"))
        assertEquals(3, hard.tier)
        assertNull(hard.evaluateCase)

        val case = AppOptions.parseArgs(arrayOf("--evaluate", "CASE1-duel", "--case-args", "task=1_2_2,seed=1,track=pause"))
        assertNull(case.tier)
        assertEquals("CASE1-duel", case.evaluateCase)

        val explicit = AppOptions.parseArgs(arrayOf("--tier", "1", "--evaluate", "CASE1-duel", "--case-args", "task=1_2_2,seed=1,track=pause"))
        assertEquals(1, explicit.tier)
        assertEquals("CASE1-duel", explicit.evaluateCase)

        assertFailsWith<IllegalArgumentException> { AppOptions.parseArgs(arrayOf("--evaluate", "4")) }
        assertFailsWith<IllegalArgumentException> { AppOptions.parseArgs(arrayOf("--tier", "0")) }
    }

    @Test
    fun `the tier is installed before any session exists`() {
        val previous = RwxTier.active()
        try {
            AppOptions.parseArgs(arrayOf("--evaluate", "1")).applyEvaluationDefaults()
            assertEquals(1, RwxTier.active().level)
            assertTrue(RwxTier.active().suggestions)
            AppOptions.parseArgs(arrayOf("--evaluate")).applyEvaluationDefaults()
            assertEquals(2, RwxTier.active().level)
            assertFalse(RwxTier.active().suggestions)
        } finally {
            RwxTier.install(previous)
        }
    }
}
