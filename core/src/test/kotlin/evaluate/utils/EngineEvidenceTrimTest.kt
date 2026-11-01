package evaluate.utils

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Frame rows are per tick, so a 10-minute match writes tens of thousands of lines. Trim collapses a
 * run into one `frames` row while the clock step stays constant, and the reader rebuilds every
 * intermediate row from the endpoints and `stepMs`.
 */
class EngineEvidenceTrimTest {
    private fun frame(time: Long, tick: Long, seq: Long) =
        """{"kind":"frame","timeMs":$time,"tick":$tick,"seq":$seq}"""

    private fun trimmed(raw: List<String>): List<String> {
        val file = Files.createTempFile("telemetry", ".jsonl")
        try {
            Files.writeString(file, raw.joinToString("\n") + "\n")
            EngineEvidence.trimTelemetry(file)
            return Files.readAllLines(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `consecutive frames collapse into one row and other rows stay untouched`() {
        val raw = listOf(
            """{"kind":"meta","schema":1,"timeMs":0,"tick":0,"seq":1}""",
            frame(16, 1, 2), frame(32, 2, 3), frame(48, 3, 4),
            """{"kind":"unit","timeMs":48,"tick":3,"seq":5}""",
            frame(64, 4, 6), frame(80, 5, 7),
            """{"kind":"end","reason":"match_result","timeMs":80,"tick":5,"seq":8}""",
        )
        val lines = trimmed(raw)
        assertEquals(5, lines.size)
        assertEquals(raw[0], lines[0])
        assertEquals(raw[4], lines[2])
        assertEquals(raw[7], lines[4])
        assertEquals(
            """{"kind":"frames","timeMs":16,"tick":1,"seq":2,"toTimeMs":48,"toTick":3,"toSeq":4,"stepMs":16}""",
            lines[1],
        )
        assertEquals(
            """{"kind":"frames","timeMs":64,"tick":4,"seq":6,"toTimeMs":80,"toTick":5,"toSeq":7,"stepMs":16}""",
            lines[3],
        )
    }

    @Test
    fun `a speed change or a skipped tick ends the run`() {
        // 16 ms/tick at 1x, then 33 ms/tick at 2x: the step change must split the run.
        val lines = trimmed(listOf(
            frame(16, 1, 2), frame(32, 2, 3), frame(65, 3, 4), frame(98, 4, 5),
        ))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"toTick\":2") && lines[0].contains("\"stepMs\":16"), lines[0])
        assertTrue(lines[1].contains("\"toTick\":4") && lines[1].contains("\"stepMs\":33"), lines[1])

        // A gap in tick or seq means a row is missing; the pair must not be merged.
        val gap = trimmed(listOf(frame(16, 1, 2), frame(32, 2, 3), frame(48, 4, 4)))
        assertEquals(2, gap.size)
        assertEquals(frame(48, 4, 4), gap[1])

        // A single frame is left exactly as written.
        val single = trimmed(listOf(frame(16, 1, 2), """{"kind":"end","timeMs":16,"tick":1,"seq":3}"""))
        assertEquals(frame(16, 1, 2), single[0])
    }
}

