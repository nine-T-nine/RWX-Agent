package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluation tier: which environment capabilities a run is given.
 *
 * <p>A tier is chosen by the evaluator at launch ({@code --evaluate <n>} / {@code --tier <n>}) and
 * recorded in the evidence, so two results are only comparable when they were produced at the same
 * tier. The point of the tier is that the <em>evaluation</em> owns the switches: in the scored
 * tiers an agent cannot raise or lower its own assistance.</p>
 *
 * <table>
 *   <caption>tiers</caption>
 *   <tr><th>tier</th><th>delivery</th><th>suggestions</th><th>agent may switch</th><th>{@code --since}</th></tr>
 *   <tr><td>1 (easy)</td><td>L1</td><td>yes</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>2 (default)</td><td>L1</td><td>no</td><td>no</td><td>yes</td></tr>
 *   <tr><td>3 (hard)</td><td>L1</td><td>no</td><td>no</td><td>no</td></tr>
 * </table>
 *
 * <p>Tier 1 is the existence proof: everything the environment can hand over, including a suggested
 * next command, and the agent may still turn assistance off itself. Tier 2 is the standard run.
 * Tier 3 keeps the passive channel (the agent is still told what happened) but removes the
 * incremental pull, so digging through history is not part of the interface.</p>
 */
public final class RwxTier {
    /** Tier used when nothing is specified. */
    public static final int DEFAULT = 2;
    public static final int MIN = 1;
    public static final int MAX = 3;
    /** {@code -Drwx.tier=3}; the launch argument installs the same value. */
    public static final String PROPERTY = "rwx.tier";

    private static final RwxTier[] TIERS = {
            null,
            new RwxTier(1, RwxDelivery.Level.L1, true, true),
            new RwxTier(2, RwxDelivery.Level.L1, false, false),
            new RwxTier(3, RwxDelivery.Level.L1, false, false),
    };
    private static volatile RwxTier active = TIERS[DEFAULT];

    public final int level;
    /** Delivery level this tier hands out unless the evaluator overrides it explicitly. */
    public final RwxDelivery.Level alertLevel;
    /** Whether replies carry {@code check=} / {@code suggested_check}. */
    public final boolean suggestions;
    /** Whether the agent itself may change the delivery level at runtime. */
    public final boolean agentMaySwitchAlerts;

    private RwxTier(int level, RwxDelivery.Level alertLevel, boolean suggestions,
                    boolean agentMaySwitchAlerts) {
        this.level = level;
        this.alertLevel = alertLevel;
        this.suggestions = suggestions;
        this.agentMaySwitchAlerts = agentMaySwitchAlerts;
    }

    /** Tier {@code level}, or an exception naming the allowed range. */
    public static RwxTier of(int level) {
        if (level < MIN || level > MAX) {
            throw new IllegalArgumentException("tier must be " + MIN + ".." + MAX + " (got " + level + ")");
        }
        return TIERS[level];
    }

    /** Parses {@code 1} / {@code 2} / {@code 3}; blank means {@link #DEFAULT}. */
    public static RwxTier parse(String text) {
        if (text == null || text.isBlank()) return of(DEFAULT);
        try {
            return of(Integer.parseInt(text.trim()));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("tier must be " + MIN + ".." + MAX
                    + " (got '" + text.trim() + "')");
        }
    }

    public static RwxTier active() {
        return active;
    }

    /** Installs the tier for this process; null restores the default. */
    public static void install(RwxTier tier) {
        active = tier == null ? TIERS[DEFAULT] : tier;
    }

    /** Reads {@code -Drwx.tier} when the launch arguments did not set one. */
    public static void installFromProperty() {
        String value = System.getProperty(PROPERTY);
        if (value != null && !value.isBlank()) install(parse(value));
    }

    /**
     * What this tier changes, written into the evidence so a reader can tell two tiers apart
     * without reading this class.
     */
    public Map<String, Object> describe() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("level", level);
        row.put("alertLevel", alertLevel.id);
        row.put("suggestions", suggestions);
        row.put("agentMaySwitchAlerts", agentMaySwitchAlerts);
        return row;
    }

    /** One-line summary for logs: {@code tier=2 suggestions=false agentSwitch=false}. */
    public String summary() {
        return "tier=" + level
                + " suggestions=" + suggestions
                + " agentSwitch=" + agentMaySwitchAlerts;
    }

    @Override public String toString() {
        return "tier-" + level;
    }

    /** True when {@code text} looks like a tier number rather than a micro case id. */
    public static boolean looksLikeTier(String text) {
        if (text == null) return false;
        String value = text.trim().toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("2") || value.equals("3");
    }
}
