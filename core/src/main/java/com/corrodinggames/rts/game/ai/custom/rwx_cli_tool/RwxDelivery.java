package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.*;

/**
 * Incremental alert delivery — the environment side of the harness-capability levels.
 *
 * <p>Every CLI reply can carry whatever important events the caller has not seen yet, so an agent
 * that never thinks to poll still gets told that its army is dying. The environment does not try
 * to solve the harness side (L2 push is out of scope by design); it only guarantees that the
 * information is attached to work the agent already does.</p>
 *
 * <p>Levels are a <em>comparison axis</em>, not a difficulty setting: {@code l0} (pull only),
 * {@code l1} (always attach, including an empty time-sense line) and {@code l1-lite} (attach only
 * when something actually happened, which costs far fewer tokens). The default is {@code l1}.</p>
 *
 * <p>State is per logical session: one cursor per caller id, advanced exactly once per delivered
 * reply. A caller that wants its own cursor passes {@code --session <id>}; the default session is
 * shared, which is correct for the documented one-agent-per-instance setup. When one session is
 * clearly being used by more than one connection the reply says so — as a note, never an error.</p>
 *
 * <p>Event text can contain strings an opponent controls (custom unit display names). The block is
 * therefore a data envelope: angle brackets and control characters are stripped, every line is
 * truncated, and {@code START.md} states that the block is observation, never instruction.</p>
 */
public final class RwxDelivery {
    public static final String DEFAULT_SESSION = "default";
    /** {@code l1} (default) / {@code l1-lite} / {@code l0}; also settable by environment variable. */
    public static final String SWITCH_PROPERTY = "rwx.alert.delivery";
    public static final String SWITCH_ENVIRONMENT = "RWX_ALERT_DELIVERY";
    /** Hard cap per reply; the rest is reported as {@code omitted} instead of being dumped. */
    public static final String LIMIT_PROPERTY = "rwx.alert.limit";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LINE_CHARS = 200;
    /** How many connection ids one session remembers when deciding whether it is shared. */
    private static final int MAX_TRACKED_CONNECTIONS = 8;
    /** At most one sharing note per session per minute, so a shared session stays readable. */
    private static final long WARNING_COOLDOWN_MILLIS = 60_000L;
    /**
     * What is worth interrupting a decision for. {@code info} (production/upgrade notices) is the
     * low-priority remainder: counted in the header, never attached.
     */
    private static final List<EventType> ALERT_TYPES =
            List.of(EventType.DAMAGED, EventType.DESTROYED, EventType.LOSSES, EventType.NEW);

    /** Harness capability levels the environment can be configured to. */
    public enum Level {
        /** Pull only: replies never carry events. */
        L0("l0"),
        /** Always attach, including a one-line time-sense header when nothing happened. */
        L1("l1"),
        /** Attach only when there is something new; cheapest level that still informs. */
        L1_LITE("l1-lite");

        public final String id;

        Level(String id) {
            this.id = id;
        }

        boolean attaches() {
            return this != L0;
        }

        /** Lite exists to cut tokens: nothing new means nothing attached. */
        boolean quietWhenEmpty() {
            return this == L1_LITE;
        }
    }

    private final RwxLocalGameService service;
    /** Mutable so tier 1 can let the agent turn its own assistance off/on. */
    private volatile Level mode;
    private final int limit;
    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();

    private static final class Cursor {
        long seq;
        long wallMillis = -1;
        int gameMillis = -1;
        /** Last connection that used this session; 0 until the first one. */
        long lastConnection;
        /** Recently seen connection ids, most recent last. */
        final LinkedHashSet<Long> connections = new LinkedHashSet<>();
        long lastWarningWall;
    }

    public RwxDelivery(RwxLocalGameService service) {
        this(service, levelByDefault(), limitFromEnvironment());
    }

    public RwxDelivery(RwxLocalGameService service, Level mode, int limit) {
        this.service = service;
        this.mode = mode == null ? Level.L1 : mode;
        this.limit = Math.max(1, limit);
    }

    /** {@code l0} / {@code l1} / {@code l1-lite}, for logs and evidence. */
    public String level() {
        return mode.id;
    }

    public Level mode() {
        return mode;
    }

    /**
     * Changes the delivery level at runtime. Only tier 1 lets the agent do this; the caller is
     * responsible for checking {@link RwxTier#agentMaySwitchAlerts} first.
     */
    public void setLevel(Level level) {
        if (level != null) mode = level;
    }

    /** Whether replies carry a suggested next command; tier 1 only. Read live from the tier. */
    public boolean suggestions() {
        return RwxTier.active().suggestions;
    }

    public boolean enabled() {
        return mode.attaches();
    }

    /**
     * Default level from {@code -Drwx.alert.delivery} / {@code RWX_ALERT_DELIVERY}. A typo is
     * reported once and ignored rather than aborting startup: losing alerts is recoverable,
     * refusing to start the game is not.
     */
    public static Level levelByDefault() {
        String value = System.getProperty(SWITCH_PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv(SWITCH_ENVIRONMENT);
        // The tier owns the default; an explicit operator override still wins, and the evidence
        // records both, so a mismatch is visible instead of silent.
        if (value == null || value.isBlank()) return RwxTier.active().alertLevel;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "l0", "off", "none", "0" -> Level.L0;
            case "l1", "auto", "on", "1" -> Level.L1;
            case "l1-lite", "l1lite", "lite", "minimal" -> Level.L1_LITE;
            default -> {
                System.err.println("RWX alert delivery: unknown level '" + value.trim()
                        + "' (use l0, l1 or l1-lite); keeping the tier default");
                yield RwxTier.active().alertLevel;
            }
        };
    }

    /**
     * Level by name, for the command line: {@code l0} / {@code l1} / {@code l1-lite}.
     */
    public static Level parseLevel(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("--level requires l0, l1 or l1-lite");
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "l0", "off", "none", "0" -> Level.L0;
            case "l1", "auto", "on", "1" -> Level.L1;
            case "l1-lite", "l1lite", "lite", "minimal" -> Level.L1_LITE;
            default -> throw new IllegalArgumentException("--level must be l0, l1 or l1-lite");
        };
    }

    private static int limitFromEnvironment() {
        String value = System.getProperty(LIMIT_PROPERTY);
        if (value == null || value.isBlank()) return DEFAULT_LIMIT;
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException invalid) {
            return DEFAULT_LIMIT;
        }
    }

    /** Cursor of one session; the transport calls this only for the evidence header. */
    public long cursorOf(String session) {
        Cursor cursor = cursors.get(key(session));
        return cursor == null ? 0 : cursor.seq;
    }

    /** Drops every cursor (used when a run's identity changes); cursors are cheap to rebuild. */
    public void reset() {
        cursors.clear();
    }

    /**
     * Returns {@code reply} with the unseen-alert digest attached, or unchanged when there is
     * nothing to attach or no match is being simulated. Never throws: a delivery failure must not
     * turn a working command into an error.
     */
    public String decorate(String session, RwxCliCommands.Command command, List<String> arguments,
                           String reply) {
        return decorate(session, command, arguments, reply, 0L);
    }

    /**
     * Same, with the transport's connection id so a shared session can be reported. {@code 0}
     * means "unknown connection" and disables that check.
     */
    public String decorate(String session, RwxCliCommands.Command command, List<String> arguments,
                           String reply, long connection) {
        if (!mode.attaches() || reply == null || reply.isEmpty() || command == null) return reply;
        if (command == RwxCliCommands.Command.PING || command == RwxCliCommands.Command.HELP) return reply;
        String sessionKey = key(session);
        Cursor cursor = cursors.computeIfAbsent(sessionKey, ignored -> new Cursor());
        try {
            String warning = sharedSessionNote(sessionKey, cursor, connection);
            AlertFeed feed = service.alerts(cursor.seq);
            if (feed == null) return attach(reply, warning, null);
            if (command == RwxCliCommands.Command.EVENTS) {
                // An explicit event read *is* a delivery. A --since read is stateless by contract:
                // the caller owns that cursor and asked for exactly that slice.
                if (!hasOption(arguments, "--since")) cursor.seq = feed.nextSeq;
                return attach(reply, warning, null);
            }
            if (feed.cursorAhead) {
                // The sequence restarted (new match / new process): restart the cursor and read the
                // new match's backlog instead of reporting "nothing new" forever.
                cursor.seq = 0;
                feed = service.alerts(0);
                if (feed == null) return attach(reply, warning, null);
            }
            long wall = System.currentTimeMillis();
            EventPage page = RwxEventProcessor.query(feed.events, feed.gameTimeMillis,
                    EventQuery.since(ALERT_TYPES, cursor.seq, false), limit);
            int total = feed.events.size();
            if (mode.quietWhenEmpty() && total == 0) {
                // Nothing happened: attach nothing and leave the cursor untouched, so the next real
                // digest still reports the time since the last thing that mattered.
                return attach(reply, warning, null);
            }
            long wallDelta = cursor.wallMillis < 0 ? -1 : wall - cursor.wallMillis;
            long gameDelta = cursor.gameMillis < 0 ? -1 : (long) feed.gameTimeMillis - cursor.gameMillis;
            cursor.seq = feed.nextSeq;
            cursor.wallMillis = wall;
            cursor.gameMillis = feed.gameTimeMillis;
            RwxDeliveryTrace.delivered(sessionKey, page, feed, wall);
            return attach(reply, warning,
                    renderText(feed, page, total, wallDelta, gameDelta, suggestions()),
                    renderJson(feed, page, total, wallDelta, gameDelta, suggestions()));
        } catch (RuntimeException failure) {
            // Unsupported service, stalled game thread, malformed state: deliver the plain reply.
            return reply;
        }
    }

    private static String key(String session) {
        return session == null || session.isBlank() ? DEFAULT_SESSION : session;
    }

    /**
     * A session is shared when a connection id reappears after a different connection advanced the
     * same cursor — i.e. two long-lived clients alternating. Per-command clients (a fresh port for
     * every call) never trip this, which is what makes the check usable at all: they are the
     * documented default and must stay silent.
     */
    private static String sharedSessionNote(String session, Cursor cursor, long connection) {
        if (connection <= 0) return null;
        boolean reappeared = cursor.lastConnection != 0 && connection != cursor.lastConnection
                && cursor.connections.contains(connection);
        rememberConnection(cursor, connection);
        if (!reappeared) return null;
        long now = System.currentTimeMillis();
        if (now - cursor.lastWarningWall < WARNING_COOLDOWN_MILLIS) return null;
        cursor.lastWarningWall = now;
        RwxDeliveryTrace.sharedSession(session, connection);
        return "note: session '" + session + "' is being advanced by more than one connection; "
                + "pass --session <id> so each consumer keeps its own delivery cursor";
    }

    private static void rememberConnection(Cursor cursor, long connection) {
        cursor.connections.remove(connection);
        cursor.connections.add(connection);
        if (cursor.connections.size() > MAX_TRACKED_CONNECTIONS) {
            Iterator<Long> oldest = cursor.connections.iterator();
            oldest.next();
            oldest.remove();
        }
        cursor.lastConnection = connection;
    }

    private static boolean hasOption(List<String> arguments, String option) {
        if (arguments == null) return false;
        for (String argument : arguments) {
            if (argument == null) continue;
            if (argument.equalsIgnoreCase(option) || argument.toLowerCase(Locale.ROOT)
                    .startsWith(option.toLowerCase(Locale.ROOT) + "=")) return true;
        }
        return false;
    }

    private static String attach(String reply, String warning, String alerts) {
        return attach(reply, warning, alerts, null);
    }

    /**
     * Text replies get the warning line and/or the block appended; a single-object JSON reply gets
     * {@code sessionWarning} and {@code alerts} fields instead, so {@code json.loads} keeps working
     * and nothing already there is renamed.
     */
    private static String attach(String reply, String warning, String alerts, String alertsJson) {
        StringBuilder text = new StringBuilder();
        if (warning != null) text.append(warning);
        if (alerts != null) {
            if (text.length() > 0) text.append('\n');
            text.append(alerts);
        }
        StringBuilder fields = new StringBuilder();
        if (warning != null) fields.append("\"sessionWarning\":\"").append(RwxCliText.json(warning)).append('"');
        if (alertsJson != null) {
            if (fields.length() > 0) fields.append(',');
            fields.append("\"alerts\":").append(alertsJson);
        }
        if (text.length() == 0) return reply;
        String trimmed = reply.trim();
        int end = reply.lastIndexOf('}');
        if (end >= 0 && trimmed.startsWith("{") && trimmed.endsWith("}")) {
            String head = reply.substring(0, end).stripTrailing();
            return head + (head.endsWith("{") ? "" : ",") + fields + "}" + reply.substring(end + 1);
        }
        return reply + "\n\n" + text;
    }

    private static String renderText(AlertFeed feed, EventPage page, int total, long wallDelta,
                                     long gameDelta, boolean suggestions) {
        StringBuilder text = new StringBuilder("<rwx-alerts game=").append(RwxCliText.time(feed.gameTimeMillis))
                .append(" wallDeltaMs=").append(wallDelta)
                .append(" gameDeltaMs=").append(gameDelta)
                .append(" new=").append(total)
                .append(" low=").append(page.lowPriority);
        if (page.lines.isEmpty()) {
            return text.append(" nextSeq=").append(feed.nextSeq).append("/>").toString();
        }
        text.append(" omitted=").append(page.omitted)
                .append(" dropped=").append(feed.dropped)
                .append(" nextSeq=").append(feed.nextSeq).append(">\n");
        for (int index = 0; index < page.lines.size(); index++) {
            text.append(sanitize(page.lines.get(index)))
                    .append(" | age=").append(RwxCliText.number(ageSeconds(feed, page, index))).append("s");
            String check = check(page, index, suggestions);
            if (check != null) text.append(" check=").append(check);
            text.append('\n');
        }
        return text.append("</rwx-alerts>").toString();
    }

    private static String renderJson(AlertFeed feed, EventPage page, int total, long wallDelta,
                                     long gameDelta, boolean suggestions) {
        StringBuilder json = new StringBuilder("{\"game\":\"").append(RwxCliText.time(feed.gameTimeMillis))
                .append("\",\"gameMillis\":").append(feed.gameTimeMillis)
                .append(",\"wallDeltaMillis\":").append(wallDelta)
                .append(",\"gameDeltaMillis\":").append(gameDelta)
                .append(",\"new\":").append(total)
                .append(",\"low\":").append(page.lowPriority)
                .append(",\"omitted\":").append(page.omitted)
                .append(",\"dropped\":").append(feed.dropped)
                .append(",\"nextSeq\":").append(feed.nextSeq)
                .append(",\"items\":[");
        for (int index = 0; index < page.lines.size(); index++) {
            if (index > 0) json.append(',');
            EventRecord record = page.items.size() > index ? page.items.get(index) : null;
            GameEvent source = record == null ? null : record.source;
            EventEntity subject = source == null ? null : source.subject;
            EventEntity attacker = source == null ? null : source.attacker;
            int time = record == null ? feed.gameTimeMillis : record.gameTimeMillis;
            json.append("{\"seq\":").append(sequenceOf(record))
                    .append(",\"type\":\"").append(record == null ? "event"
                            : record.type.name().toLowerCase(Locale.ROOT)).append('"')
                    .append(",\"at\":\"").append(RwxCliText.time(time)).append('"')
                    .append(",\"gameTime\":").append(time)
                    .append(",\"wallTime\":").append(source == null ? 0 : source.wallTimeMillis)
                    .append(",\"ageMillis\":").append(Math.round(ageSeconds(feed, page, index) * 1000.0))
                    .append(",\"where\":");
            if (subject == null || subject.x == null || subject.y == null) {
                json.append("null");
            } else {
                json.append("{\"x\":").append(RwxCliText.number(subject.x))
                        .append(",\"y\":").append(RwxCliText.number(subject.y)).append('}');
            }
            json.append(",\"what\":\"").append(RwxCliText.json(sanitize(page.lines.get(index)))).append('"')
                    .append(",\"why\":\"").append(RwxCliText.json(reason(record, attacker))).append('"');
            String check = check(page, index, suggestions);
            if (check != null) json.append(",\"suggested_check\":\"").append(RwxCliText.json(check)).append('"');
            json.append('}');
        }
        return json.append("]}").toString();
    }

    /**
     * One short sentence for "why this happened", kept separate from the rendered line so a script
     * does not have to parse English. Unknown/unknown-visibility attackers stay explicit.
     */
    private static String reason(EventRecord record, EventEntity attacker) {
        if (record == null) return "unknown";
        String name = attacker == null ? null : sanitize(attacker.name);
        return switch (record.type) {
            case DAMAGED -> name == null ? "damaged, attacker not visible" : "damaged by " + name;
            case LOSSES -> name == null ? "lost, killer unknown" : "lost to " + name;
            case DESTROYED -> "destroyed by us";
            case NEW -> "entered our vision";
            case INFO -> "own production or lifecycle";
        };
    }

    private static long sequenceOf(EventRecord record) {
        return record == null || record.source == null ? 0 : record.source.seq;
    }

    private static double ageSeconds(AlertFeed feed, EventPage page, int index) {
        EventRecord record = page.items.size() > index ? page.items.get(index) : null;
        int time = record == null ? feed.gameTimeMillis : record.gameTimeMillis;
        return Math.max(0, feed.gameTimeMillis - time) / 1000.0;
    }

    /** A concrete next command, so "something happened" also answers "what do I do about it". */
    private static String check(EventPage page, int index, boolean suggestions) {
        // A suggested next command is scaffolding: only the easy tier offers it.
        if (!suggestions) return null;
        EventRecord record = page.items.size() > index ? page.items.get(index) : null;
        if (record == null) return null;
        return switch (record.type) {
            case DAMAGED, LOSSES -> RwxCliCommands.Command.UNIT_LIST.commandName() + " --owner self --hp";
            case NEW -> RwxCliCommands.Command.UNIT_LIST.commandName() + " --owner enemy";
            case DESTROYED -> RwxCliCommands.Command.STATE.commandName() + " --summary";
            case INFO -> RwxCliCommands.Command.STATE.commandName() + " --summary";
        };
    }

    /**
     * Untrusted text into a data envelope: no angle brackets (cannot close the block), no control
     * characters (cannot rewrite a terminal), bounded length (cannot flood the context).
     */
    static String sanitize(String line) {
        if (line == null) return "";
        StringBuilder text = new StringBuilder(Math.min(line.length(), MAX_LINE_CHARS));
        for (int index = 0; index < line.length() && text.length() < MAX_LINE_CHARS; index++) {
            char ch = line.charAt(index);
            if (ch == '<' || ch == '>') continue;
            if (ch < 0x20 || ch == 0x7f) {
                text.append(' ');
                continue;
            }
            text.append(ch);
        }
        if (line.length() > MAX_LINE_CHARS) text.append('…');
        return text.toString();
    }
}