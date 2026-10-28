package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import evaluate.utils.CommandRecorder
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RwxCliCommandCaptureTest {
    @BeforeTest
    fun ensureEngine() {
        // GameTeam's fixtures construct BaseUnit, which reads the engine singleton; this class
        // must also work when it is the only test class selected by --tests.
        if (GameEngine.getInstance() == null) GameLogic()
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        val temporaryRoot = Files.createDirectories(root.resolve("agent-tmp"))
        val directory = Files.createTempDirectory(temporaryRoot, "command-recorder-test-")
        try { block(directory) } finally {
            check(directory.toRealPath().startsWith(temporaryRoot.toRealPath()))
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun `match wall time is exported without waiting for a command and late entry keeps game clock`() = withDirectory { directory ->
        val recorder = CommandRecorder(directory, 3)
        recorder.use {
            val entered = OffsetDateTime.parse("2026-10-26T14:35:27.123+08:00")
            assertEquals(1, it.enterMatch(entered, 65_000, "Lake (2p)", "Alice;faction=A"))
            it.export()
            val header = Files.readString(it.statisticsPath())
            assertTrue(header.startsWith("实际进入对局时间（本地时区）：\n第1局：2026-10-26 14:35:27.123 +08:00"), header)
            assertTrue(header.contains("进入时游戏时间=01:05"), header)
            assertTrue(header.contains("命令总数：0"), header)
            it.record(-1, "unassigned", "rwx-help", "rwx-help")
            it.record(65_000, "Alice;faction=A;match=1", "rwx-game-state", "rwx-game-state")
            assertEquals(2, it.enterMatch(entered.plusMinutes(10), 0, "Lake (2p)", "Alice;faction=B"))
            it.record(0, "Alice;faction=B;match=2", "rwx-game-state", "rwx-game-state --summary")
        }
        val lines = Files.readAllLines(recorder.commandsPath())
        assertTrue(lines[0].startsWith("[--:--]"))
        assertTrue(lines[1].startsWith("[01:05]"))
        assertTrue(lines[2].startsWith("[00:00]"))
        assertTrue(Files.readString(recorder.statisticsPath()).contains("第2局：2026-10-26 14:45:27.123 +08:00"))
    }

    @Test fun `files are renamed after the first match map and keep the time token`() = withDirectory { directory ->
        val recorder = CommandRecorder(directory, 7)
        recorder.use {
            it.record(-1, "unassigned", "rwx-help", "rwx-help")
            val lobbyLog = it.commandsPath()
            val lobbyStatistics = it.statisticsPath()
            val token = lobbyLog.fileName.toString().substringAfter("--").removeSuffix(".txt")
            assertTrue(lobbyLog.fileName.toString().startsWith("commands-lobby-"), lobbyLog.toString())
            assertTrue(token.matches(Regex("""\d{2}-\d{2}(?:-\d+)?""")), lobbyLog.toString())

            it.enterMatch(OffsetDateTime.parse("2026-10-27T16:30:00+08:00"), 1_000,
                "King of the Middle (3p)", "Alice")

            val log = it.commandsPath()
            val statistics = it.statisticsPath()
            assertNotEquals(lobbyLog, log)
            val name = log.fileName.toString()
            assertTrue(name.matches(Regex("""commands-King-of-the-Middle-\(3p\)-\d{8}--$token\.txt""")), name)
            assertEquals(name.removeSuffix(".txt") + "-stats.txt", statistics.fileName.toString())
            assertFalse(Files.exists(lobbyLog), "lobby log must be moved, not copied")
            assertFalse(Files.exists(lobbyStatistics), "lobby statistics must be moved too")
            assertEquals(1, Files.readAllLines(log).size, "lobby command survives the rename")

            // The file is the session log: a later match keeps the first map name.
            it.enterMatch(OffsetDateTime.parse("2026-10-27T17:30:00+08:00"), 0, "Lake (2p)", "Alice")
            assertEquals(log, it.commandsPath())
            it.export()
            val stats = Files.readString(statistics)
            assertTrue(stats.contains("地图=King of the Middle (3p)"), stats)
            assertTrue(stats.contains("地图=Lake (2p)"), stats)
        }
    }

    @Test fun `map names become one safe filename token`() = withDirectory { directory ->
        val recorder = CommandRecorder(directory, 8)
        recorder.use {
            // A frame without a map name must not consume the one rename.
            it.enterMatch(OffsetDateTime.now(), 0, null, "Alice")
            assertTrue(it.commandsPath().fileName.toString().startsWith("commands-lobby-"))
            it.enterMatch(OffsetDateTime.parse("2026-10-27T16:30:00+08:00"), 1_000,
                "湖泊 (2p)/测试:*?", "Alice")
            val name = it.commandsPath().fileName.toString()
            assertTrue(name.matches(Regex("""commands-湖泊-\(2p\)-测试-\d{8}--\d{2}-\d{2}(?:-\d+)?\.txt""")), name)
        }
    }

    @Test fun `two recorders starting in the same minute get separate files`() = withDirectory { directory ->
        CommandRecorder(directory, 4).use { first ->
            CommandRecorder(directory, 5).use { second ->
                val one = first.commandsPath().fileName.toString()
                val two = second.commandsPath().fileName.toString()
                assertTrue(one.matches(Regex("""commands-lobby-\d{8}--\d{2}-\d{2}(?:-\d+)?\.txt""")), one)
                assertTrue(two.matches(Regex("""commands-lobby-\d{8}--\d{2}-\d{2}(?:-\d+)?\.txt""")), two)
                assertNotEquals(one, two)
            }
        }
    }

    @Test fun `counts merge parameters and retain every full command and executor`() = withDirectory { directory ->
        val recorder = CommandRecorder(directory, 1)
        recorder.use {
            it.record(65_000, "Alice;faction=A", "rwx-game-order-attack-mode",
                "rwx-game-order-attack-mode --apply-to-new --mode off")
            it.record(66_000, "Bob;faction=B", "RWX-GAME-ORDER-ATTACK-MODE",
                "rwx-game-order-attack-mode --units 42")
            it.record(3_601_000, "Alice;faction=A", "rwx-game-order-push", "rwx-game-order-push --units 42")
            it.export()
            val log = Files.readAllLines(it.commandsPath())
            assertEquals(3, log.size)
            assertEquals("[01:05]<Alice;faction=A><rwx-game-order-attack-mode --apply-to-new --mode off>", log[0])
            assertTrue(log[1].contains("<Bob;faction=B><rwx-game-order-attack-mode --units 42>"))
            assertTrue(log[2].startsWith("[60:01]"))
        }
        val statistics = Files.readString(recorder.statisticsPath())
        assertTrue(statistics.contains("命令总数：3\n命令种类：2"), statistics)
        assertTrue(statistics.contains("rwx-game-order-attack-mode\t2\nrwx-game-order-push\t1"), statistics)
        recorder.close() // Shutdown hooks may close the same instance twice.
    }

    @Test fun `concurrent records are complete and statistics sum without lost increments`() = withDirectory { directory ->
        val recorder = CommandRecorder(directory, 2)
        recorder.use {
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures = (0 until 4).map { worker -> pool.submit {
                    repeat(50) { index ->
                        it.record(index * 1000L, "player=$worker", "rwx-game-order-push",
                            "rwx-game-order-push --units $worker-$index")
                    }
                } }
                futures.forEach { future -> future.get(10, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
        }
        assertEquals(200, Files.readAllLines(recorder.commandsPath()).size)
        val statistics = Files.readString(recorder.statisticsPath())
        assertTrue(statistics.contains("命令总数：200\n命令种类：1"), statistics)
        assertTrue(statistics.contains("rwx-game-order-push\t200"), statistics)
    }

    @Test fun `player slot and faction are distinct and AI spectator flag does not mean spectator`() {
        val alice = GameTeam(2, false, "Alice")
        alice.teamColorId = 0
        alice.isTeamSpectator = true
        assertEquals("name=Alice;player=2;faction=A(0);role=player", RwxCliCommandCapture.executor(alice, null))
        alice.teamColorId = 1
        assertTrue(RwxCliCommandCapture.executor(alice, null).contains("faction=B(1)"))
        alice.teamColorId = -3
        assertEquals("name=Alice;player=2;faction=spectator(-3);role=spectator",
            RwxCliCommandCapture.executor(alice, null))
        assertEquals("name=Host;player=none;faction=none;role=unassigned",
            RwxCliCommandCapture.executor(null, "Host"))
    }

    private fun withListener(directory: Path, executor: RwxCliCommandExecutor,
                             block: (Int) -> Unit) {
        val properties = mapOf("rwx.cli.portFile" to directory.resolve("port").toString(),
            "rwx.cli.recordingDir" to directory.resolve("output").toString())
        val previous = properties.keys.associateWith { System.getProperty(it) }
        try {
            properties.forEach { (key, value) -> System.setProperty(key, value) }
            RwxCliTool.start(executor, false).use {
                block(Files.readString(directory.resolve("port")).trim().toInt())
            }
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    private fun logFile(directory: Path): Path = Files.list(directory.resolve("output")).use { files ->
        files.filter { it.toString().endsWith(".txt") && !it.toString().endsWith("-stats.txt") }.findFirst().orElseThrow()
    }

    @Test fun `socket captures before execution and preserves time of receipt`() = withDirectory { directory ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        withListener(directory, { _, _ -> entered.countDown(); release.await(5, TimeUnit.SECONDS); "done" }) { port ->
            Socket("127.0.0.1", port).use { socket ->
                try {
                    socket.getOutputStream().write("rwx-game-order-push --units 12\n".toByteArray(UTF_8))
                    socket.shutdownOutput()
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val beforeCompletion = Files.readString(logFile(directory))
                    assertTrue(beforeCompletion.contains("<rwx-game-order-push --units 12>"), beforeCompletion)
                } finally { release.countDown() }
                socket.soTimeout = 5_000
                assertEquals("done", socket.getInputStream().readBytes().toString(UTF_8).trim())
            }
        }
    }

    @Test fun `socket records framed base64 plain utf8 gbk and rejected commands without handshake`() = withDirectory { directory ->
        val executed = mutableListOf<List<String>>()
        withListener(directory, { _, args -> synchronized(executed) { executed.add(args.toList()) }; "ok" }) { port ->
            val arguments = listOf("--name", "湖泊 (2p)", "", "a\\b\"c", "line1\nline2<end>")
            val encoded = arguments.joinToString(",") { Base64.getEncoder().encodeToString(it.toByteArray(UTF_8)) }
            val lines = "!!framed\nrwx-battleroom-select-map\t$encoded\n" +
                "rwx-game-order-attack-mode --apply-to-new --mode off\n" +
                "rwx-game-order-attack-mode --units xxx\n" +
                "rwx-unknown --value original\nrwx-game-order-push --units \"unterminated\n" +
                "rwx-ping\nrwx-help --list\nrwx-game-order-push\t%%%\n"
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(lines.toByteArray(UTF_8))
                socket.shutdownOutput()
                val response = socket.getInputStream().readBytes().toString(UTF_8)
                assertTrue(response.startsWith("\u001eready\u001e"), response)
                assertTrue(response.contains("unknown command"), response)
            }
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("rwx-game-order-push --note 中文\r\n"
                    .toByteArray(charset("GBK")))
                socket.shutdownOutput()
                assertEquals("ok", socket.getInputStream().readBytes().toString(UTF_8).trim())
            }
            assertEquals(arguments, executed.first())
        }
        val log = Files.readAllLines(logFile(directory))
        assertEquals(9, log.size, log.joinToString("\n"))
        assertTrue(log.none { it.contains("!!framed") })
        assertTrue(log[0].contains("湖泊 (2p)"), log[0])
        assertTrue(log[0].contains("\\n"), log[0])
        assertTrue(log[0].contains("\\u003cend\\u003e"), log[0])
        assertTrue(log[4].contains("unterminated"), log[4])
        assertTrue(log[7].contains("%%%"), log[7])
        assertTrue(log.last().contains("--note 中文"), log.last())
        val statisticsPath = logFile(directory).let { it.resolveSibling(it.fileName.toString().removeSuffix(".txt") + "-stats.txt") }
        val statistics = Files.readString(statisticsPath)
        assertTrue(statistics.contains("rwx-game-order-attack-mode\t2"), statistics)
        assertTrue(statistics.contains("命令总数：9"), statistics)
    }
}
