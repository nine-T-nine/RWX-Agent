package evaluate.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
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

/**
 * Thread-safe receipt log and command-name statistics, independent of the game engine.
 *
 * <p>Files start as {@code commands-lobby-<date>--<id>} because the CLI exists before any match is
 * loaded. The first match renames them to {@code commands-<map>-<date>--<id>}: commands received in
 * the lobby stay in the same file, and the unique id is preserved so the pair stays easy to match.</p>
 */
public final class CommandRecorder implements AutoCloseable {
    private final Path outputDirectory;
    private final String date;
    private final String id;
    private final Map<String, Long> counts = new TreeMap<>();
    private final Map<String, Map<String, Long>> executorCounts = new TreeMap<>();
    private final List<String> matchEntries = new ArrayList<>();
    private final ScheduledExecutorService exporter;
    private Path commandsPath;
    private Path statisticsPath;
    private BufferedWriter commands;
    private boolean mapNamed;
    private long total;
    private boolean dirty;
    private boolean closed;

    /** Each listener gets separate files, even when several games share the output directory. */
    public CommandRecorder(Path outputDirectory, int port) throws IOException {
        this.outputDirectory = outputDirectory;
        this.date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Files.createDirectories(outputDirectory);
        String prefix = "commands-lobby-" + date + "--";
        commandsPath = Files.createTempFile(outputDirectory, prefix, ".txt");
        String filename = commandsPath.getFileName().toString();
        // Keep the temp file's random number as the stable session id.
        id = filename.substring(prefix.length(), filename.length() - ".txt".length());
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

    public synchronized Path commandsPath() { return commandsPath; }
    public synchronized Path statisticsPath() { return statisticsPath; }

    /**
     * One safe filename token for a map: whitespace and Windows-reserved characters become single
     * dashes, trailing dots/dashes are dropped, and the readable part is length-capped.
     */
    static String mapSlug(String map) {
        String value = map == null ? "" : map.trim();
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (int index = 0; index < value.length() && out.length() < 48; index++) {
            char ch = value.charAt(index);
            boolean unsafe = ch < 0x20 || "<>:\"/\\|?*".indexOf(ch) >= 0;
            if (Character.isWhitespace(ch) || unsafe) {
                if (out.length() > 0 && !dash) { out.append('-'); dash = true; }
            } else {
                out.append(ch);
                dash = ch == '-';
            }
        }
        while (out.length() > 0 && (out.charAt(out.length() - 1) == '-'
                || out.charAt(out.length() - 1) == '.')) out.setLength(out.length() - 1);
        return out.toString();
    }

    /** Renames both files once, when the first real match reveals its map. */
    private boolean renameForMap(String map) {
        String slug = mapSlug(map);
        if (slug.isEmpty()) return false;
        Path target = outputDirectory.resolve("commands-" + slug + "-" + date + "--" + id + ".txt");
        Path targetStats = outputDirectory.resolve("commands-" + slug + "-" + date + "--" + id + "-stats.txt");
        if (target.equals(commandsPath)) return true;
        Path moved = null;
        try {
            commands.flush();
            commands.close();
            Files.move(commandsPath, target);
            moved = target;
            commands = Files.newBufferedWriter(moved, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Path oldStats = statisticsPath;
            commandsPath = moved;
            statisticsPath = targetStats;
            if (Files.exists(oldStats)) {
                try {
                    Files.move(oldStats, targetStats);
                } catch (IOException moveError) {
                    // A stale lobby statistics file must not survive next to the renamed log.
                    Files.deleteIfExists(oldStats);
                }
            }
            export();
            System.out.println("RWX_CLI_COMMAND_RECORDING log=" + commandsPath.toAbsolutePath()
                    + " statistics=" + statisticsPath.toAbsolutePath());
            return true;
        } catch (IOException error) {
            Path reopen = moved == null ? commandsPath : moved;
            try {
                commands = Files.newBufferedWriter(reopen, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                commandsPath = reopen;
                if (moved != null) statisticsPath = targetStats;
            } catch (IOException ignored) {
                // Recording already reported the failure; keep the previous name.
            }
            System.err.println("RWX command recording could not use map name '" + map + "': "
                    + error.getMessage());
            // Do not retry on every later match; the lobby name stays valid for this process.
            return true;
        }
    }

    /** Called on the first active game frame, independently of when the next CLI command arrives. */
    public synchronized int enterMatch(OffsetDateTime enteredAt, long gameMillis, String map,
                                       String executor) {
        if (closed) return matchEntries.size();
        if (!mapNamed) mapNamed = renameForMap(map);
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
