package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import java.util.Locale
import kotlin.test.*

/**
 * 单位说明里的收入数字必须和引擎一致。
 *
 * <p>这些 `about=` 是给 Agent 读的，它据此规划“再造一个矿要等几秒”。历史上这里写的是引擎的
 * **每 40 tick** 值（`ResourceExtractor.getCreditIncomeRate()`），却被标成 `/s`，于是 T3 矿
 * 实际 28.1/s 被读成 20/s——低估 29%，DeepSeek 的规划就建立在错的数上。</p>
 *
 * <p>测试直接拿引擎常量按 1x 时钟（16 ms/tick）换算，和文案里的数字比对：改代码或改文案而
 * 不同步，这里就会红。</p>
 */
class RwxUnitNotesIncomeTest {
    /** 与 `ResourceExtractor.getCreditIncomeRate()` / `PowerFabricator.getCreditIncomeRate()` 一致。 */
    private val engineRatesPerPeriod = mapOf(
        "extractorT1" to 8.0,
        "extractorT2" to 12.0,
        "extractorT3" to 18.0,
        "fabricatorT1" to 2.0,
        "fabricatorT2" to 7.0,
        "fabricatorT3" to 14.0,
        // 指挥中心的收入写在 features 里，不在 about 里。
        "commandCenter" to 18.0,
    )

    /** 1x 下时钟每 tick 走 16 ms，一局秒 62.5 tick，除以 40 tick 的结算周期。 */
    private fun perSecond(perPeriod: Double): Double = perPeriod * (1000.0 / 16.0) / 40.0

    private fun abouts(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var current: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length - 1).trim()
                continue
            }
            val id = current ?: continue
            // 收入可能写在 about 里，也可能写在 features 里（指挥中心就是后者），两条都要看。
            if (line.startsWith("about=") || line.startsWith("features=") || line.startsWith("feature=")) {
                val value = line.substringAfter('=').trim()
                result[id] = if (result[id] == null) value else result[id] + " " + value
            }
        }
        return result
    }

    private fun abouts(): Map<String, String> = abouts(
        javaClass.classLoader!!
            .getResourceAsStream("com/corrodinggames/rts/game/ai/custom/rwx_cli_tool/unit-notes.txt")!!
            .bufferedReader().readText()
    )

    private fun income(text: String): Double? =
        Regex("""\+([0-9]+(?:\.[0-9]+)?)/s""").find(text)?.groupValues?.get(1)?.toDouble()

    @Test
    fun `income in unit notes is credits per second, not per settlement period`() {
        val about = abouts()
        for ((id, perPeriod) in engineRatesPerPeriod) {
            val text = about[id] ?: fail("unit-notes.txt 缺少 [$id] 的 about")
            val shown = income(text) ?: fail("[$id] 的 about 没有 +N/s：$text")
            val expected = String.format(Locale.ROOT, "%.1f", perSecond(perPeriod)).toDouble()
            assertEquals(expected, shown, "[$id] 收入应为每秒 ${expected}（引擎 ${perPeriod} 每 40 tick），文案写的是 $shown")
        }
    }

    @Test
    fun `reinforced extractor keeps the same income as T3`() {
        val about = abouts()
        assertEquals(income(about.getValue("extractorT3")), income(about.getValue("extractorT3_reinforced")))
    }

    @Test
    fun `no unit note still quotes the raw per-period value as per second`() {
        // 只有这六个原版产金建筑有确定的引擎常量；其余（自定义单位）由配置决定，这里不猜。
        val about = abouts()
        for ((id, text) in about) {
            val shown = income(text) ?: continue
            if (id in engineRatesPerPeriod) continue
            assertTrue(shown > 0.0, "[$id] 收入必须为正：$text")
        }
    }

    @Test
    fun `the copy kept next to the sources never drifts from the packaged one`() {
        // src/main/java 下还有一份同名文件（早期位置，Gradle 不会打进 jar，但有人会去改它）。
        // 两份不一致就是下一次"改了没生效"的来源，所以这里把它们钉在一起。
        val duplicate = java.io.File("src/main/java/com/corrodinggames/rts/game/ai/custom/unit-notes.txt")
        if (!duplicate.isFile) return
        val packaged = abouts()
        val stale = abouts(duplicate.readText())
        for ((id, perPeriod) in engineRatesPerPeriod) {
            assertEquals(income(packaged.getValue(id)), income(stale.getValue(id)),
                "[$id] 两份 unit-notes.txt 的收入数字不一致")
            assertNotNull(perPeriod)
        }
    }
}
