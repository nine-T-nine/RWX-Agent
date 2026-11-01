package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RwxHelpSectionsTest {
    private val flags = listOf(
        "session", "observe", "combat", "develop",
        "automation", "history", "reference", "system",
    )

    @Test
    fun `list defaults to the eight numbered h2 headings`() {
        val text = RwxCliTool.helpDocument(listOf("--list"))
        val lines = text.lines()
        assertEquals(8, lines.size)
        for ((index, flag) in flags.withIndex()) {
            assertTrue(lines[index].startsWith("${index + 1}. ${flag.replaceFirstChar { it.uppercase() }}"), text)
        }
        assertFalse(text.contains("5.3.1"), text)
    }

    @Test
    fun `all original module flags still select their complete section`() {
        for ((index, flag) in flags.withIndex()) {
            val text = RwxCliTool.helpDocument(listOf("--$flag"))
            assertTrue(text.startsWith("## ${index + 1}. "), text)
            assertEquals(1, text.lineSequence().count { it.startsWith("## ") }, text)
            assertEquals(RwxCliTool.helpDocument(listOf("${index + 1}")), text)
        }
    }

    @Test
    fun `one flag returns only that section with its heading`() {
        val text = RwxCliTool.helpDocument(listOf("--reference"))
        assertTrue(text.startsWith("## 7. Reference"), text)
        assertTrue(text.contains("rwx-query-unit"), text)
        assertFalse(text.contains("## 1. Session"), text)
        assertFalse(text.contains("## 8. System"), text)
    }

    @Test
    fun `multiple flags follow document order`() {
        val text = RwxCliTool.helpDocument(listOf("--system", "--session"))
        assertTrue(text.indexOf("## 1. Session") < text.indexOf("## 8. System"), text)
        assertFalse(text.contains("## 2. Observe"), text)
    }

    @Test
    fun `no flags returns the whole manual including every heading`() {
        val text = RwxCliTool.helpDocument(emptyList())
        for (index in 1..8) {
            assertTrue(text.contains("## $index. "), "missing section $index")
        }
    }

    @Test
    fun `unknown flag reports the valid list hint`() {
        val text = RwxCliTool.helpDocument(listOf("--nope"))
        assertTrue(text.startsWith("error: unknown help section"), text)
        assertTrue(text.contains("rwx-help --list"), text)
    }

    @Test
    fun `number selects only that subsection including its heading`() {
        val text = RwxCliTool.helpDocument(listOf("5.3.1"))
        assertTrue(text.startsWith("#### 5.3.1 "), text)
        assertTrue(text.contains("--rule kite"), text)
        assertFalse(text.contains("#### 5.3.2 "), text)
        assertFalse(text.contains("#### 5.3.3 "), text)
        assertFalse(text.contains("## 5. "), text)
    }

    @Test
    fun `numbers take precedence over module flags and overlapping subtrees appear once`() {
        val text = RwxCliTool.helpDocument(listOf("5.3.1", "--session", "5", "--automation", "5.3.1"))
        assertTrue(text.startsWith("## 5. Automation"), text)
        assertFalse(text.contains("## 1. Session"), text)
        assertEquals(1, text.lineSequence().count { it.startsWith("#### 5.3.1 ") }, text)
        assertFalse(text.contains("## 2. Observe"), text)
        assertEquals(RwxCliTool.helpDocument(listOf("5.3.1")), RwxCliTool.helpDocument(listOf("--combat", "5.3.1")))
        assertEquals(help("1.2"), help("1.2", "--observe"))
        assertEquals(help("1", "2"), help("2", "1"))
    }

    @Test
    fun `nested headings include descendants but exclude siblings and ancestors`() {
        val text = help("1.2")
        assertTrue(text.startsWith("### 1.2 Second"), text)
        assertTrue(text.contains("#### 1.2.1 Detail"), text)
        assertTrue(text.contains("deep body"), text)
        assertFalse(text.contains("first body"), text)
        assertFalse(text.contains("### 1.3 "), text)
        assertFalse(text.contains("## 2. "), text)
        assertEquals("#### 1.2.1 Detail\ndetail body", help("1.2.1"))
    }

    @Test
    fun `list traverses headings in dfs order up to the requested absolute depth`() {
        assertEquals("1. Session — Root\n2. Observe — Other", help("--list"))
        assertEquals(
            "1. Session — Root\n  1.1 First\n  1.2 Second\n  1.3 Third\n2. Observe — Other",
            help("--list", "2"),
        )
        assertEquals(
            "1. Session — Root\n  1.1 First\n  1.2 Second\n    1.2.1 Detail\n    1.2.2 Nested\n" +
                "  1.3 Third\n2. Observe — Other",
            help("--list", "3"),
        )
    }

    @Test
    fun `list supports module and number filters without duplicate headings`() {
        assertEquals(
            "  1.2 Second\n    1.2.1 Detail\n    1.2.2 Nested\n2. Observe — Other",
            help("2", "1.2", "--list", "3", "1.2.1"),
        )
        assertEquals(help("1", "--list", "2"), help("--session", "--list", "2"))
        assertEquals(help("1.2", "--list", "3"), help("--observe", "1.2", "--list", "3"))
        assertEquals("", help("1.2", "--list"))
    }

    @Test
    fun `depth has no fixed upper limit and heading parser goes beyond h4`() {
        val all = help("--list", "999999999999999999999999999999999")
        assertTrue(all.contains("      1.2.2.1 Deep"), all)
        assertEquals(help("--list", "100"), all)
        assertEquals("##### 1.2.2.1 Deep\ndeep body", help("1.2.2.1"))
        assertEquals(help("--list"), help("--list", "1"))
    }

    @Test
    fun `code fence headings do not become sections or truncate content`() {
        assertTrue(help("1.1").contains("## 9. Fake"))
        assertTrue(help("9").startsWith("error: unknown help section"))
        assertTrue(help("8.1").startsWith("error: unknown help section"))
        assertFalse(help("--list", "100").contains("Fake"))
        val windowsDocument = document.replace("\n", "\r\n")
        assertEquals(help("1.2.1"), RwxCliTool.helpDocument(windowsDocument, listOf("1.2.1")).replace("\r\n", "\n"))
    }

    @Test
    fun `invalid depth or selection reports error instead of ignoring arguments`() {
        for (depth in listOf("0", "-1", "-99999999999999999999999")) {
            assertTrue(help("--list", depth).startsWith("error: --list depth"), depth)
        }
        for (selection in listOf("5.99", "--nope", "--", "1.2.1.7", "--first", "--second")) {
            assertTrue(help(selection).startsWith("error: unknown help section"), selection)
        }
        assertTrue(help("--list", "--nope").startsWith("error:"))
        assertTrue(help("--list", "--list").startsWith("error:"))
    }

    private fun help(vararg arguments: String) = RwxCliTool.helpDocument(document, arguments.toList())

    private val document = """
        # Manual
        introduction
        ## 1. Session — Root
        root body
        ### 1.1 First
        first body
        ````text
        ## 9. Fake
        ```
        ### 9.1 Fake child
        ````
        ~~~text
        ### 8.1 Fake tilde
        ~~~
        ### 1.2 Second
        second body
        #### 1.2.1 Detail
        detail body
        #### 1.2.2 Nested
        nested body
        ##### 1.2.2.1 Deep
        deep body
        ### 1.3 Third
        third body
        ## 2. Observe — Other
        other body
    """.trimIndent()
}
