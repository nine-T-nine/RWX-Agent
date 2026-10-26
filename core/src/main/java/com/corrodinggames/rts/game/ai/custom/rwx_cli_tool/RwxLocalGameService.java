package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.List;

/**
 * Game-thread API behind the {@code rwx-game-*} commands.
 *
 * <p>An implementation is the sole place allowed to read mutable engine state or create an
 * engine {@code Command}. Every read is evaluated from the controlled player's perspective:
 * unavailable fogged enemy state is omitted rather than represented with hidden values.</p>
 */
public interface RwxLocalGameService {
    RwxLocalGameTypes.GameSnapshot setPaused(boolean paused);

    RwxLocalGameTypes.GameSnapshot setSpeed(float speed);

    RwxLocalGameTypes.GameSnapshot game();

    List<RwxLocalGameTypes.TeamSnapshot> teams();

    RwxLocalGameTypes.UnitPage units(RwxLocalGameTypes.UnitQuery query);

    /**
     * One-shot global state: economy plus every selected unit's order/queues and every factory's
     * available actions, gathered in a single game-thread pass.
     */
    default RwxLocalGameTypes.GameStateSnapshot state(RwxLocalGameTypes.UnitOwner owner) {
        throw new UnsupportedOperationException("game-state is not supported by this service");
    }

    RwxLocalGameTypes.UnitSnapshot unit(long unitId);

    List<RwxLocalGameTypes.ActionSnapshot> actions(long unitId);

    RwxLocalGameTypes.MapSnapshot map(RwxLocalGameTypes.MapQuery query);

    RwxLocalGameTypes.ReachabilitySnapshot reach(long unitId, float x, float y);

    RwxLocalGameTypes.PlacementSnapshot canPlace(long builderId, String unitTypeId, float x, float y,
                                                 int searchRadiusTiles);

    RwxLocalGameTypes.CommandReceipt issue(RwxLocalGameTypes.Order order);

    default RwxLocalGameTypes.CommandReceipt cancel(long unitId, String actionId, int count,
                                                    boolean all) {
        throw new UnsupportedOperationException("order-cancel is not supported by this service");
    }

    default String nukeReport(long unitId, Float x, Float y) {
        throw new UnsupportedOperationException("nuke-report is not supported by this service");
    }

    default RwxLocalGameTypes.CommandReceipt assist(RwxLocalGameTypes.Order order) {
        throw new UnsupportedOperationException("order-assist is not supported by this service");
    }

    RwxLocalGameTypes.EventPage events(RwxLocalGameTypes.EventQuery query);

    /** Visible enemy units around a point, classified by what they can engage. */
    RwxLocalGameTypes.ThreatSnapshot threats(RwxLocalGameTypes.ThreatQuery query);

    /** Arm recording for the next local match; takes effect when the map finishes loading. */
    RwxLocalGameTypes.ReplaySnapshot armReplay();

    /** Start recording the currently loaded local match from its current state. */
    RwxLocalGameTypes.ReplaySnapshot startReplay(String fileName);

    /** Stop and finalize the active replay recording. */
    RwxLocalGameTypes.ReplaySnapshot stopReplay();

    RwxLocalGameTypes.ReplaySnapshot replayStatus();

    /**
     * Runs the simulation for {@code seconds} of game time, then pauses again.
     *
     * <p>Uses only the public pause/speed and {@code game()} reads, so it works the same for a
     * paused or a running game. A timeout bounds the wall-clock wait.</p>
     */
    RwxLocalGameTypes.StepSnapshot step(double seconds, int timeoutMillis);

    /** Current GUI view: world rect, center, zoom. */
    RwxLocalGameTypes.CameraSnapshot camera();
    /**
     * Moves/zooms the GUI view. Either the center or the top-left corner (world coordinates),
     * plus an optional zoom; everything is clamped to the map and to the allowed zoom range.
     */
    RwxLocalGameTypes.CameraSnapshot moveCamera(Float centerX, Float centerY, Float left, Float top,
                                                Float zoom);

    /**
     * Restarts the loaded local match from tick 0 with the same map, room settings and teams.
     * This is the in-engine reload path (not a process restart), so the renderer stays alive and
     * the command returns in a second or two.
     */
    default String restart() {
        throw new UnsupportedOperationException("restart is not supported by this service");
    }

    /** Sends the engine's surrender command for the controlled team. */
    default String surrender() {
        throw new UnsupportedOperationException("surrender is not supported by this service");
    }

    /** Saved unit groups, oldest name first. */
    List<RwxLocalGameTypes.GroupSnapshot> groups();

    /** {@code group-save} with the selection filter set shared by the order commands. */
    RwxLocalGameTypes.GroupSnapshot saveGroup(String name, String unitsOption,
                                              java.util.List<Long> excludedIds,
                                              java.util.List<String> includeTypes,
                                              java.util.List<String> excludeTypes,
                                              Float hpBelowPercent, boolean idleOnly,
                                              boolean excludeBuilders);

    RwxLocalGameTypes.GroupSnapshot deleteGroup(String name);

    /**
     * Clears the event history and the independent {@code unit-list} change baseline so later windows start from the current tick.
     *
     * <p>Never clears the default attack stance or its "already ordered" set: re-issuing those
     * would send hundreds of duplicate stance commands. Groups are cleared unless
     * {@code keepGroups} is set.</p>
     */
    RwxLocalGameTypes.ObserveResetSnapshot observeReset(boolean keepGroups);

    /** Orders held by the CLI until they become affordable ({@code --allow-unaffordable}). */
    default RwxLocalGameTypes.PendingPage pending() {
        throw new UnsupportedOperationException("pending-list is not supported by this service");
    }

    /** Drops one pending order, or all of them when {@code id} is null. */
    default RwxLocalGameTypes.PendingPage clearPending(java.lang.Long id) {
        throw new UnsupportedOperationException("pending-clear is not supported by this service");
    }

    /**
     * Adds/updates a production rule that keeps {@code count} copies of {@code actionId} queued on
     * a factory, topping it up whenever the queue drains.
     */
    default RwxLocalGameTypes.KeepPage keep(long unitId, String actionId, int count) {
        throw new UnsupportedOperationException("keep is not supported by this service");
    }

    /** Current production rules. */
    default RwxLocalGameTypes.KeepPage keeps() {
        throw new UnsupportedOperationException("keep is not supported by this service");
    }

    /** Drops one production rule, or all of them when {@code id} is null. */
    default RwxLocalGameTypes.KeepPage clearKeep(java.lang.Long id) {
        throw new UnsupportedOperationException("keep is not supported by this service");
    }

    /** Every automation rule with its configuration and last-pass counters ({@code auto-list}). */
    default RwxLocalGameTypes.AutoPage autoRules() {
        throw new UnsupportedOperationException("auto-list is not supported by this service");
    }

    /** Applies one {@code auto-set} patch: null fields keep the rule's current value. */
    default RwxLocalGameTypes.AutoPage autoSet(RwxLocalGameTypes.AutoRuleOptions options) {
        throw new UnsupportedOperationException("auto-set is not supported by this service");
    }

    /** One rule in detail, including the units it currently owns. */
    default RwxLocalGameTypes.AutoPage autoShow(String rule) {
        throw new UnsupportedOperationException("auto-show is not supported by this service");
    }

    /** Resets one rule to its defaults. */
    default RwxLocalGameTypes.AutoPage autoClear(String rule) {
        throw new UnsupportedOperationException("auto-clear is not supported by this service");
    }

    /** Releases every controlled unit and disables every rule ({@code auto-reset}). */
    default RwxLocalGameTypes.AutoPage autoReset() {
        throw new UnsupportedOperationException("auto-reset is not supported by this service");
    }
}
