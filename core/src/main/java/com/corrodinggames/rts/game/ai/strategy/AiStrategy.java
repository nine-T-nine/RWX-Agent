package com.corrodinggames.rts.game.ai.strategy;


import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** One strategy instance per custom AI team. All callbacks run on the simulation thread.
 * Time arguments are simulation frames (60 frames = one second), not milliseconds.
 * Issue orders through AiContext.command; do not mutate unit orders or resources directly.
 */
public interface AiStrategy {
    /** Stable save identifier. Change this when replacing the strategy with an incompatible one. */
    String id();

    /** Called on the first active update, after readState when restoring a save. */
    default void onStart(AiContext context, boolean restored) { }

    /** Fine-grained targeting, attack modes, ability use, and movement decisions. */
    default void onFrame(AiContext context, float deltaFrames) { }

    /** Economy, construction, production, repairs, reclaiming, and upgrades (~25 frames). */
    default void onEconomy(AiContext context, float elapsedFrames) { }

    /** Replaces the original base AI's non-building production when enabled below. */
    default boolean overridesUnitProduction() { return false; }

    /** Decide when and which non-building units to queue; called on each economy cadence. */
    default void onUnitProduction(AiContext context, float elapsedFrames) { }

    /** Replaces the original base AI's building choice, placement and construction when enabled below. */
    default boolean overridesBuildingProduction() { return false; }

    /** Decide which buildings to place, where, and which builder places them; called on each economy cadence. */
    default void onBuildingProduction(AiContext context, float elapsedFrames) { }

    /** Replaces the original AI's upgrade draw (the random cm() / high-priority action lottery). */
    default boolean overridesUpgrades() { return false; }

    /** Changes legacy land-reinforcement transport staging without replacing transport execution. */
    default boolean overridesTransportWaiting() { return false; }

    /** Decide which buildings upgrade and in which order; called on each economy cadence. */
    default void onUpgrades(AiContext context, float elapsedFrames) { }

    /** Recruitment, grouping, attack, retreat, scouting, and transport (~80 frames). */
    default void onTactics(AiContext context, float elapsedFrames) { }

    /** Expansion, tech choices, army composition, and long-term goals (~250 frames). */
    default void onStrategy(AiContext context, float elapsedFrames) { }

    /** Releases transient resources when the team leaves this custom policy. Do not issue commands here. */
    default void onStop(AiContext context) { }

    /** Save only your strategy's state; add your own payload version if the layout may change. */
    default void writeState(DataOutputStream output) throws IOException { }

    /** Called before onStart. Resolve saved unit IDs in onStart, after the world has loaded. */
    default void readState(DataInputStream input) throws IOException { }
}
