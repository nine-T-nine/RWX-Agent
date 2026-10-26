package evaluate.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Thread-safe receipt log and command-name statistics, independent of the game engine. */
public final class CommandRecorder implements AutoCloseable {
    private final Path commandsPath;
    private final Path statisticsPath;
    private final BufferedWriter commands;
    private final Map<String, Long> counts = new TreeMap<>();
    private final Map<String, Map<String, Long>> executorCounts = new TreeMap<>();
    private final List<String> matchEntries = new ArrayList<>();
    private final ScheduledExecutorService exporter;
    private long total;
    private boolean dirty;
    private boolean closed;

    /** Each listener gets separate files, even when several games share the output directory. */
    public CommandRecorder(Path outputDirectory, int port) throws IOException {
        Files.createDirectories(outputDirectory);
        String session = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-pid" + ProcessHandle.current().pid() + "-port" + port;
        commandsPath = Files.createTempFile(outputDirectory, "commands-" + session + "-", ".txt");
        String filename = commandsPath.getFileName().toString();
        statisticsPath = commandsPath.resolveSibling(filename.substring(0, filename.length() - 4) + "-stats.txt");
        commands = Files.newBufferedWriter(commandsPath, StandardCharsets.UTF_8);
        try {
            export();
        } catch (IOException error) {
            commands.close();
            throw error;
        }
        exporter = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "RWX-command-statistics-" + port);
            thread.setDaemon(true);
            return thread;
        });
        exporter.scheduleWithFixedDelay(this::exportIfDirty, 1, 1, TimeUnit.SECONDS);
    }

    public Path commandsPath() { return commandsPath; }
    public Path statisticsPath() { return statisticsPath; }

    /** Called on the first active game frame, independently of when the next CLI command arrives. */
    public synchronized int enterMatch(OffsetDateTime enteredAt, long gameMillis, String map,
                                       String executor) {
        if (closed) return matchEntries.size();
        matchEntries.add("第" + (matchEntries.size() + 1) + "局："
                + enteredAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx"))
                + " | 进入时游戏时间=" + timestamp(gameMillis)
                + " | 地图=" + escape(map) + " | " + escape(executor));
        dirty = true;
        return matchEntries.size();
    }

    /** Records receipt, including rejected commands; parameters never participate in the count key. */
    public synchronized void record(long gameMillis, String executor, String commandName,
                                    String fullCommand) throws IOException {
        if (closed) throw new IOException("command recorder is closed");
        String name = commandName == null || commandName.isBlank()
                ? "<empty>" : commandName.toLowerCase(Locale.ROOT);
        String actor = executor == null ? "unknown" : executor;
        commands.write(String.format(Locale.ROOT, "[%s]<%s><%s>%n",
                timestamp(gameMillis), escape(actor), escape(fullCommand)));
        commands.flush();
        counts.merge(name, 1L, Long::sum);
        executorCounts.computeIfAbsent(actor, ignored -> new TreeMap<>()).merge(name, 1L, Long::sum);
        total++;
        dirty = true;
    }

    /** Flushes the full log and atomically replaces the statistics snapshot. */
    public synchronized void export() throws IOException {
        if (closed) return;
        commands.flush();
        StringBuilder text = new StringBuilder("实际进入对局时间（本地时区）：");
        if (matchEntries.isEmpty()) text.append("未进入对局\n");
        else {
            text.append('\n');
            for (String entry : matchEntries) text.append(entry).append('\n');
        }
        text.append("\n统计口径：端口收到的命令；同名命令的不同参数合并；包含被拒绝的命令。\n")
                .append("时间格式：[分:秒]，使用收到命令时的对局游戏时间；大厅/未进入对局为 [--:--]。\n")
                .append("命令总数：").append(total).append('\n')
                .append("命令种类：").append(counts.size()).append("\n\n每种命令的使用次数\n");
        appendCounts(text, counts);
        text.append("\n按执行者汇总\n");
        for (Map.Entry<String, Map<String, Long>> entry : executorCounts.entrySet()) {
            text.append('<').append(escape(entry.getKey())).append(">\n");
            appendCounts(text, entry.getValue());
        }
        Path temporary = statisticsPath.resolveSibling(statisticsPath.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, statisticsPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statisticsPath, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private synchronized void exportIfDirty() {
        if (!dirty || closed) return;
        try {
            export();
        } catch (IOException error) {
            System.err.println("RWX command statistics export failed: " + error.getMessage());
        }
    }

    private static void appendCounts(StringBuilder output, Map<String, Long> values) {
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            output.append(escape(entry.getKey())).append('\t').append(entry.getValue()).append('\n');
        }
    }

    private static String timestamp(long gameMillis) {
        if (gameMillis < 0) return "--:--";
        long seconds = gameMillis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Keeps one receipt per line and makes delimiters and control characters reversible. */
    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '<' -> result.append("\\u003c");
                case '>' -> result.append("\\u003e");
                default -> {
                    if (Character.isISOControl(ch)) {
                        result.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        result.append(ch);
                    }
                }
            }
        }
        return result.toString();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        exporter.shutdown();
        try {
            export();
        } finally {
            closed = true;
            commands.close();
        }
    }
}
