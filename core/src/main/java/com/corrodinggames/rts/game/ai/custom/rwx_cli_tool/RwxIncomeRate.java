package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.gameFramework.GameEngine;

/**
 * Converts the engine's income figure into credits per <em>game second</em>.
 *
 * <p>{@link PlayerTeam#getScaledIncomeRate()} and {@link PlayerTeam#b} are expressed per
 * {@link PlayerTeam#resourceIncomeRatePeriod} ticks, and the engine pays one
 * {@code resourceIncomeUpdateInterval} slice of that per tick batch. {@code GameLogic.update}
 * advances the clock by {@code lastDelta * 16.666666} ms per tick, so a game second holds
 * {@code 1000 / step} ticks and the per-second rate is
 * {@code rate * (1000 / step) / resourceIncomeRatePeriod}.</p>
 *
 * <p>On a 1x run the clock advances 16 ms per tick, so the factor is 1.5625. Reporting the raw
 * field as "/s" understated every income estimate by that factor.</p>
 */
public final class RwxIncomeRate {
    private RwxIncomeRate() { }

    /** Milliseconds of game time per tick, averaged since the match started. */
    public static double stepMillis(GameEngine engine) {
        if (engine == null || engine.currentTick <= 0 || engine.gameTimeMillis <= 0) return 16.666666;
        return Math.max(1.0, (double) engine.gameTimeMillis / engine.currentTick);
    }

    /** Credits per game second for one team. */
    public static double perSecond(PlayerTeam team, GameEngine engine) {
        if (team == null) return 0.0;
        double perPeriod = team.b(com.corrodinggames.rts.game.units.custom.condition.resources.Resource.D)
                - team.getResourceDrainRate(
                        com.corrodinggames.rts.game.units.custom.condition.resources.Resource.D);
        return perSecond(perPeriod, engine);
    }

    /** Converts an already-read per-period figure. */
    public static double perSecond(double perPeriod, GameEngine engine) {
        return perPeriod * (1000.0 / stepMillis(engine)) / PlayerTeam.resourceIncomeRatePeriod;
    }
}
