package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Plain-text command line parsing: quotes must group values, not become part of them. */
class RwxCommandLineTest {
    @Test
    fun `double quotes keep a map name together`() {
        assertEquals(
            listOf("rwx-battleroom-select-map", "--name", "Lake (2p)"),
            RwxCommandLine.split("""rwx-battleroom-select-map --name "Lake (2p)""""),
        )
    }

    @Test
    fun `single quotes and empty values work`() {
        assertEquals(
            listOf("cmd", "--name", "Two Sides (8p)", "--note", ""),
            RwxCommandLine.split("cmd --name 'Two Sides (8p)' --note \"\""),
        )
    }

    @Test
    fun `backslash escapes a quote`() {
        assertEquals(
            listOf("cmd", "--name", "a\"b"),
            RwxCommandLine.split("""cmd --name "a\"b""""),
        )
    }

    @Test
    fun `plain whitespace still splits`() {
        assertEquals(listOf("cmd", "a", "b"), RwxCommandLine.split("  cmd\ta  b  "))
    }

    @Test
    fun `unterminated quote is rejected`() {
        assertFailsWith<IllegalArgumentException> { RwxCommandLine.split("cmd --name \"Lake") }
    }
}
