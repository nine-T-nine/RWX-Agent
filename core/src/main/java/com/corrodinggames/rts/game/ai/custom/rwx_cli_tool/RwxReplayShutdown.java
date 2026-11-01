package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.ReplayEngine;

/**
 * Finalizes an in-progress replay while the process is going away.
 *
 * <p>A replay only becomes playable once its {@code end} and {@code endReplayMetaData} blocks are
 * written. Killing the process — Task Manager, a harness timeout, closing the window — leaves the
 * file truncated: the game refuses to open it and the whole match is lost even though every command
 * is in the file. The evaluation evidence already survives that path through its own shutdown hook;
 * this gives the replay the same treatment.</p>
 *
 * <p>It runs inside that hook, so it must never throw and must never block forever: the writer is
 * asked to stop on its own thread and given a bounded amount of time to drain its queue.</p>
 */
final class RwxReplayShutdown {
    private static final long TIMEOUT_MILLIS = 5000L;

    private RwxReplayShutdown() {
    }

    /** Stops and closes the replay writer when a recording is still open. Never throws. */
    static void finalizeQuietly() {
        try {
            GameEngine engine = GameEngine.getInstance();
            if (engine == null) return;
            ReplayEngine replay = engine.replayEngine;
            if (replay == null || !replay.isRecording()) return;
            String path = replay.getReplayFilePath();
            Thread worker = new Thread(replay::e, "rwx-replay-finalize");
            worker.setDaemon(true);
            worker.start();
            worker.join(TIMEOUT_MILLIS);
            if (worker.isAlive()) {
                System.err.println("Replay finalize timed out after " + TIMEOUT_MILLIS
                        + " ms; " + path + " stays truncated");
            } else {
                System.out.println("RWX_REPLAY_FINALIZED " + path);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            // The shutdown hook still has to close the evidence, so a failure here is reported and
            // swallowed rather than aborting the remaining work.
            System.err.println("Replay finalize failed: " + error);
        }
    }
}
