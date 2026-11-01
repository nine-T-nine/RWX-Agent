package evaluate.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Thread-safe receipt log, independent of the game engine.
 *
 * <p>Files start as {@code commands-lobby-<date>--<HH-mm>} because the CLI exists before any match
 * is loaded. The first match renames the log to {@code commands-<map>-<date>--<HH-mm>}: commands
 * received in the lobby stay in the same file, and the time token — the local wall clock when the
 * recorder started — is preserved after renaming.
 * Because a minute is not a unique name, a second recorder or a same-minute rename appends
 * {@code -2}, {@code -3}, ... instead of overwriting the earlier file.</p>
 */
public final class CommandRecorder implements AutoCloseable {
    /** Minute-accurate wall clock used as the file token, e.g. {@code 19-30}. */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH-mm");
    private static final int NAME_ATTEMPTS = 100;

    private final Path outputDirectory;
    private final String date;
    private final String id;
    private Path commandsPath;
    private BufferedWriter commands;
    private boolean mapNamed;
    private int nameAttempt = 1;
    private int matchCount;
    private boolean closed;

    /** Each listener gets one separate log, even when several games share the output directory. */
    public CommandRecorder(Path outputDirectory, int port) throws IOException {
        this.outputDirectory = outputDirectory;
        this.date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        // Wall clock when recording started; it replaces the random suffix the old rule carried.
        this.id = LocalTime.now().format(CLOCK);
        Files.createDirectories(outputDirectory);
        commandsPath = createNamed("commands-lobby-" + date + "--", ".txt");
        commands = Files.newBufferedWriter(commandsPath, StandardCharsets.UTF_8);
    }

    public synchronized Path commandsPath() { return commandsPath; }

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

    /** One candidate name for the current time token: {@code <prefix><HH-mm>[-n]<suffix>}. */
    private Path candidate(String prefix, int attempt, String suffix) {
        return outputDirectory.resolve(prefix + id + (attempt == 1 ? "" : "-" + attempt) + suffix);
    }

    /** Creates the log, so two instances starting in the same minute cannot share one name. */
    private Path createNamed(String prefix, String suffix) throws IOException {
        for (int attempt = 1; attempt <= NAME_ATTEMPTS; attempt++) {
            Path path = candidate(prefix, attempt, suffix);
            try {
                Files.createFile(path);
                nameAttempt = attempt;
                return path;
            } catch (FileAlreadyExistsException taken) {
                // Another run already owns this minute; take the next suffix.
            }
        }
        throw new IOException("cannot allocate a unique command log name in " + outputDirectory);
    }

    /** First free name for the sequence, preferring the suffix this session started with. */
    private Path freeName(String prefix, String suffix) {
        Path preferred = candidate(prefix, nameAttempt, suffix);
        if (!Files.exists(preferred)) return preferred;
        for (int attempt = 1; attempt <= NAME_ATTEMPTS; attempt++) {
            Path path = candidate(prefix, attempt, suffix);
            if (!Files.exists(path)) return path;
        }
        return outputDirectory.resolve(prefix + id + "-" + System.nanoTime() + suffix);
    }

    /** Renames the log once, when the first real match reveals its map. */
    private boolean renameForMap(String map) {
        String slug = mapSlug(map);
        if (slug.isEmpty()) return false;
        Path target = freeName("commands-" + slug + "-" + date + "--", ".txt");
        Path moved = null;
        try {
            commands.flush();
            commands.close();
            Files.move(commandsPath, target);
            moved = target;
            commands = Files.newBufferedWriter(moved, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            commandsPath = moved;
            System.out.println("RWX_CLI_COMMAND_RECORDING log=" + commandsPath.toAbsolutePath());
            return true;
        } catch (IOException error) {
            Path reopen = moved == null ? commandsPath : moved;
            try {
                commands = Files.newBufferedWriter(reopen, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                commandsPath = reopen;
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
        if (closed) return matchCount;
        if (!mapNamed) mapNamed = renameForMap(map);
        return ++matchCount;
    }

    /** Records each receipt, including rejected commands. */
    public synchronized void record(long gameMillis, String executor, String commandName,
                                    String fullCommand) throws IOException {
        if (closed) throw new IOException("command recorder is closed");
        String actor = executor == null ? "unknown" : executor;
        commands.write(String.format(Locale.ROOT, "[%s]<%s><%s>%n",
                timestamp(gameMillis), escape(actor), escape(fullCommand)));
        commands.flush();
    }

    /** Flushes the receipt log; statistics are produced by the offline evaluator. */
    public synchronized void export() throws IOException {
        if (closed) return;
        commands.flush();
    }

    private static String timestamp(long gameMillis) {
        if (gameMillis < 0) return "--:--:---";
        long seconds = gameMillis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%03d", seconds / 60, seconds % 60, gameMillis % 1000);
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
        try {
            export();
        } finally {
            closed = true;
            commands.close();
        }
    }
}
