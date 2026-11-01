package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import java.nio.file.Files
import kotlin.test.*

class RwxCliDiscoveryTest {
    @Test fun `all canonical commands drop local prefix without retaining old aliases`() {
        for (command in RwxCliCommands.Command.entries) {
            val name = command.commandName()
            assertFalse(name.startsWith("rwx-local-"))
            assertSame(command, RwxCliCommands.find(name))
            assertNull(RwxCliCommands.find(name.replaceFirst("rwx-", "rwx-local-")))
        }
        assertEquals(RwxCliCommands.Command.CONNECT, RwxCliCommands.find("rwx-connect"))
    }

    @Test fun `primary port is atomic numeric and secondary cannot overwrite or delete it`() {
        val dir = Files.createTempDirectory("rwx-cli-discovery")
        val path = dir.resolve("port")
        val first = RwxCliPortFile(path, 41390)
        val second = RwxCliPortFile(path, 41391)
        try {
            assertEquals("41390", Files.readString(path))
            assertEquals("41391", Files.readString(second.instancePath()))
            second.close()
            assertEquals("41390", Files.readString(path))
            assertFalse(Files.exists(second.instancePath()))
        } finally { second.close(); first.close() }
        assertFalse(Files.exists(path))
        assertFalse(Files.exists(first.instancePath()))
    }

    @Test fun `successor takes over when primary exits and stale startup port is replaced`() {
        val dir = Files.createTempDirectory("rwx-cli-successor")
        val path = dir.resolve("port")
        Files.writeString(path, "1") // abnormal exit left a stale port
        val first = RwxCliPortFile(path, 41392)
        val second = RwxCliPortFile(path, 41393)
        try {
            assertEquals("41392", Files.readString(path))
            first.close()
            val until = System.nanoTime() + 4_000_000_000L
            while ((!Files.exists(path) || Files.readString(path) != "41393") && System.nanoTime() < until) {
                Thread.sleep(20)
            }
            assertEquals("41393", Files.readString(path))
            first.close()
            assertEquals("41393", Files.readString(path), "old owner cannot delete its successor")
        } finally { first.close(); second.close() }
    }
}
