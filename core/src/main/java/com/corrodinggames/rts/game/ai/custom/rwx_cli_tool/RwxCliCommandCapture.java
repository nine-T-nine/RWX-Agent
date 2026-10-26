package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.network.NetworkEngine;
import evaluate.utils.CommandRecorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Observes decoded socket receipts before dispatch, without changing command execution. */
final class RwxCliCommandCapture implements AutoCloseable {
    private final CommandRecorder recorder;
    private final ScheduledExecutorService engineWatcher;
    private volatile GameEngine attachedEngine;
    private volatile boolean closed;
    private Object previousWorld;
    private Object previousMap;
    private boolean inMatch;
    private int lastGameMillis;
    private int match;

    private RwxCliCommandCapture(CommandRecorder recorder) {
        this.recorder = recorder;
        if (recorder == null) {
            engineWatcher = null;
        } else {
            engineWatcher = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "RWX-command-match-observer");
                thread.setDaemon(true);
                return thread;
            });
            attachEngine();
            engineWatcher.scheduleWithFixedDelay(this::attachEngine, 100, 100, TimeUnit.MILLISECONDS);
        }
    }

    static RwxCliCommandCapture open(Path root, int port) {
        try {
            String override = System.getProperty("rwx.cli.recordingDir");
            Path directory = override == null || override.isBlank()
                    ? root.resolve("evaluate/output") : Path.of(override);
            CommandRecorder recorder = new CommandRecorder(directory, port);
            System.out.println("RWX_CLI_COMMAND_RECORDING log=" + recorder.commandsPath().toAbsolutePath()
                    + " statistics=" + recorder.statisticsPath().toAbsolutePath());
            return new RwxCliCommandCapture(recorder);
        } catch (IOException | RuntimeException error) {
            System.err.println("RWX command recording unavailable: " + error.getMessage());
            return new RwxCliCommandCapture(null);
        }
    }

    void receive(String line) {
        if (recorder == null || closed) return;
        try {
            receiveCommand(line);
        } catch (RuntimeException error) {
            System.err.println("RWX command recording failed: " + error.getMessage());
        }
    }

    private void receiveCommand(String line) {
        Receipt receipt = receipt();
        String full = line;
        String normalized = line.trim().replaceFirst("^\\uFEFF", "");
        int separator = normalized.indexOf('\t');
        // Retain even malformed input; a failed parse must never hide a receipt or reject execution.
        int end = 0;
        while (end < normalized.length() && !Character.isWhitespace(normalized.charAt(end))) end++;
        String name = normalized.substring(0, end);
        try {
            if (separator >= 0) {
                name = normalized.substring(0, separator).trim();
                StringBuilder decoded = new StringBuilder(name);
                String encoded = normalized.substring(separator + 1);
                if (!encoded.isEmpty()) {
                    for (String argument : encoded.split(",", -1)) {
                        String value = new String(Base64.getDecoder().decode(argument), StandardCharsets.UTF_8);
                        decoded.append(' ').append(quoteArgument(value));
                    }
                }
                full = decoded.toString();
            } else {
                List<String> parts = RwxCommandLine.split(normalized);
                if (!parts.isEmpty()) name = parts.get(0);
            }
        } catch (IllegalArgumentException malformed) {
            // Record the original transport line if decoding/quoting is invalid.
        }
        try {
            recorder.record(receipt.gameMillis(), receipt.executor(), name.toLowerCase(Locale.ROOT), full);
        } catch (IOException | RuntimeException error) {
            System.err.println("RWX command recording failed: " + error.getMessage());
        }
    }

    private static String quoteArgument(String value) {
        if (!value.isEmpty() && value.chars().noneMatch(ch -> Character.isWhitespace(ch)
                || ch == '\\' || ch == '"' || ch == '\'')) return value;
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Register through the existing game-frame task queue; no engine lifecycle patch is needed. */
    private void attachEngine() {
        if (closed) return;
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine == attachedEngine) return;
        synchronized (engine.gameStateLock) {
            if (closed) return;
            engine.recurringGameThreadTasks.a(() -> {
                try {
                    observeGame(engine);
                } catch (RuntimeException error) {
                    System.err.println("RWX match time recording failed: " + error.getMessage());
                }
            });
            attachedEngine = engine;
        }
    }

    private static boolean activeGame(GameEngine engine) {
        return engine != null && engine.hasLoadedLevel && !engine.isStopped && !engine.isMenuBackgroundMap
                && (engine.networkEngine == null || !engine.networkEngine.networkGameActive
                    || engine.networkEngine.gameHasBeenStarted);
    }

    private synchronized void observeGame(GameEngine engine) {
        if (closed) return;
        boolean active = activeGame(engine);
        if (active) {
            int gameMillis = engine.gameTimeMillis;
            if (!inMatch || previousWorld != engine || previousMap != engine.tileMap || gameMillis < lastGameMillis) {
                match = recorder.enterMatch(OffsetDateTime.now(), gameMillis,
                        engine.getCurrentMapName(), currentExecutor(engine));
            }
            lastGameMillis = gameMillis;
            previousWorld = engine;
            previousMap = engine.tileMap;
        }
        inMatch = active;
    }

    private record Receipt(long gameMillis, String executor) { }

    private synchronized Receipt receipt() {
        GameEngine engine = GameEngine.getInstance();
        observeGame(engine);
        return new Receipt(inMatch ? engine.gameTimeMillis : -1,
                currentExecutor(engine) + ";match=" + (inMatch ? match : "none"));
    }

    private static String currentExecutor(GameEngine engine) {
        if (engine == null) return executor(null, null);
        NetworkEngine network = engine.networkEngine;
        PlayerTeam team = activeGame(engine) ? engine.playerTeam
                : network != null && network.networkGameActive ? network.localPlayerTeam : null;
        // Unit orders use engine.playerTeam. In the lobby the networking identity owns the commands.
        return executor(team, network == null ? null : network.playerName);
    }

    static String executor(PlayerTeam team, String fallbackName) {
        String name = team != null && team.teamName != null ? team.teamName : fallbackName;
        String player = name == null || name.isBlank() ? "unknown" : name;
        if (team == null) return "name=" + player + ";player=none;faction=none;role=unassigned";
        // isTeamSpectator is also used for AI in this engine; -3 is the actual spectator faction.
        boolean spectator = team.isSpectatorTeamColor();
        return "name=" + player + ";player=" + team.teamId
                + ";faction=" + (spectator ? "spectator" : team.getTeamSlotLabel())
                + "(" + team.teamColorId + ");role=" + (spectator ? "spectator" : "player");
    }

    @Override public void close() {
        if (recorder == null) return;
        closed = true;
        engineWatcher.shutdownNow();
        try {
            recorder.close();
        } catch (IOException error) {
            System.err.println("RWX command recording export failed: " + error.getMessage());
        }
    }
}
