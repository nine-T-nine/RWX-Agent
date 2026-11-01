package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.gameFramework.GameEngine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Append-only NDJSON latency evidence for the whole delivery chain.
 *
 * <p>Three segments are recorded so "slow" can be attributed instead of guessed:</p>
 * <ul>
 *   <li>{@code produced → delivered} — the environment's pipeline (detect → deliver).</li>
 *   <li>{@code delivered → received} — the harness/model round trip (deliver → decide, approximate:
 *       the next command may be about something else entirely).</li>
 *   <li>{@code received → applied} — the transport plus the game-thread hand-off
 *       (decide → execute).</li>
 * </ul>
 *
 * <p>Everything is written on the caller's thread; nothing here touches the simulation. Clock reads
 * are plain field reads of {@code GameEngine.gameTimeMillis} (written by the game thread, read as a
 * possibly-one-tick-stale integer), because spending a game-thread round trip on evidence would
 * change the thing being measured.</p>
 */
public final class RwxDeliveryTrace {
    /** Directory for {@code delivery.jsonl}; unset means tracing is off. */
    public static final String DIRECTORY_PROPERTY = "rwx.trace.dir";
    private static final String FILE_NAME = "delivery.jsonl";

    private static volatile RwxDeliveryTrace active;

    private final BufferedWriter writer;

    private RwxDeliveryTrace(BufferedWriter writer) {
        this.writer = writer;
    }

    /**
     * The trace for this process, or null while no directory is configured.
     *
     * <p>The "off" state is never cached: a micro run only learns its evidence directory when the
     * case map loads, which is after the client's first liveness ping.</p>
     */
    private static RwxDeliveryTrace current() {
        RwxDeliveryTrace trace = active;
        if (trace != null) return trace;
        String directory = System.getProperty(DIRECTORY_PROPERTY);
        if (directory == null || directory.isBlank()) return null;
        synchronized (RwxDeliveryTrace.class) {
            if (active != null) return active;
            try {
                Path root = Path.of(directory);
                Files.createDirectories(root);
                BufferedWriter writer = Files.newBufferedWriter(root.resolve(FILE_NAME),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                RwxDeliveryTrace opened = new RwxDeliveryTrace(writer);
                // Written immediately: the calibration and delivery level are what a reader needs
                // first, and they are known before any match exists.
                opened.write(environment());
                active = opened;
            } catch (IOException | RuntimeException unavailable) {
                System.err.println("RWX delivery trace unavailable: " + unavailable.getMessage());
            }
            return active;
        }
    }

    /** True when a trace file is being written; cheap enough to call per command. */
    static boolean enabled() {
        return current() != null;
    }

    /** Game clock for evidence, without a game-thread hop; -1 when no match is loaded. */
    static int gameMillis() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || !engine.hasLoadedLevel) return -1;
        return engine.gameTimeMillis;
    }

    private static String environment() {
        GameEngine engine = GameEngine.getInstance();
        return "{\"kind\":\"env\",\"wall\":" + System.currentTimeMillis()
                + ",\"game\":" + gameMillis()
                + ",\"speed\":\"" + RwxSpeedProfile.active().id() + '"'
                + ",\"diagnostic\":" + RwxSpeedProfile.active().diagnostic()
                + ",\"leaderboardEligible\":" + RwxSpeedProfile.active().leaderboardEligible()
                + ",\"delivery\":\"" + RwxDelivery.levelByDefault().id + '"'
                + ",\"tier\":" + RwxTier.active().level
                + ",\"tierSuggestions\":" + RwxTier.active().suggestions
                + ",\"tierAgentSwitch\":" + RwxTier.active().agentMaySwitchAlerts
                + ",\"pid\":" + ProcessHandle.current().pid()
                + ",\"map\":\"" + json(engine == null ? "" : String.valueOf(engine.getCurrentMapName())) + "\"}";
    }

    /**
     * One session's cursor was advanced by more than one connection: the note the caller sees is
     * also evidence, because a shared cursor silently changes what the agent was told.
     */
    static void sharedSession(String session, long connection) {
        RwxDeliveryTrace trace = current();
        if (trace == null) return;
        trace.write("{\"kind\":\"warning\",\"code\":\"session-shared\""
                + ",\"session\":\"" + json(session) + '"'
                + ",\"connection\":" + connection
                + ",\"wall\":" + System.currentTimeMillis() + "}");
    }

    /**
     * One record per merged alert line: the earliest contributing occurrence is the "produced"
     * timestamp, so a group that accumulated over five seconds is not reported as instantaneous.
     */
    static void delivered(String session, RwxLocalGameTypes.EventPage page, RwxLocalGameTypes.AlertFeed feed,
                          long wall) {
        RwxDeliveryTrace trace = current();
        if (trace == null) return;
        for (int index = 0; index < page.lines.size(); index++) {
            RwxLocalGameTypes.EventRecord record =
                    page.items.size() > index ? page.items.get(index) : null;
            if (record == null) continue;
            long producedWall = 0;
            int producedGame = record.gameTimeMillis, events = 0;
            List<RwxLocalGameTypes.GameEvent> contributors = record.contributors;
            for (RwxLocalGameTypes.GameEvent event : contributors) {
                events++;
                if (event.wallTimeMillis > 0 && (producedWall == 0 || event.wallTimeMillis < producedWall)) {
                    producedWall = event.wallTimeMillis;
                }
                producedGame = Math.min(producedGame, event.gameTimeMillis);
            }
            trace.write("{\"kind\":\"deliver\",\"channel\":\"auto\""
                    + ",\"session\":\"" + json(session) + '"'
                    + ",\"seq\":" + (record.source == null ? 0 : record.source.seq)
                    + ",\"type\":\"" + record.type.name().toLowerCase(Locale.ROOT) + '"'
                    + ",\"events\":" + events
                    + ",\"produced\":{\"wall\":" + producedWall + ",\"game\":" + producedGame + "}"
                    + ",\"delivered\":{\"wall\":" + wall + ",\"game\":" + feed.gameTimeMillis + "}}");
        }
    }

    /** One record per command: received on the CLI thread, applied when the reply was ready. */
    static void command(String session, String command, long receivedWall, int receivedGame,
                        String channel, boolean failed) {
        RwxDeliveryTrace trace = current();
        if (trace == null) return;
        trace.write("{\"kind\":\"command\""
                + ",\"session\":\"" + json(session) + '"'
                + ",\"command\":\"" + json(command) + '"'
                + ",\"channel\":\"" + channel + '"'
                + ",\"failed\":" + failed
                + ",\"received\":{\"wall\":" + receivedWall + ",\"game\":" + receivedGame + "}"
                + ",\"applied\":{\"wall\":" + System.currentTimeMillis()
                + ",\"game\":" + gameMillis() + "}"
                // The hand-off is synchronous, so "reply ready" is an upper bound on "effect
                // applied". A blocking command (rwx-game-step) overstates it by its own duration.
                + ",\"appliedBound\":\"reply\"}");
    }

    private synchronized void write(String line) {
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException error) {
            System.err.println("RWX delivery trace write failed: " + error.getMessage());
        }
    }

    /** Test hook: forget the open file so the next write re-reads the directory property. */
    static void resetForTests() {
        RwxDeliveryTrace trace = active;
        active = null;
        if (trace == null) return;
        synchronized (trace) {
            try {
                trace.writer.close();
            } catch (IOException ignored) {
                // The file is evidence, not state; a failed close loses nothing that was flushed.
            }
        }
    }

    private static String json(String value) {
        return RwxCliText.json(value == null ? "" : value);
    }
}