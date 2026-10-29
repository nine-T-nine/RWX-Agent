package io.github.rwx.app

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
}
