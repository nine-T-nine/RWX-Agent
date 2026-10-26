package com.corrodinggames.rts.game.ai.strategy;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Immutable metadata and execution policy. This is the single definition of a selectable AI. */
public final class AiDefinition {
    private final int code;
    private final String id;
    private final Map<String, String> names;
    private final Supplier<? extends AiStrategy> factory;
    private final float incomeMultiplier;
    private final int originalDifficulty;
    private final Set<AiPhase> originalPhases;
    private final AiCadence cadence;

    private AiDefinition(int code, String id, Map<String, String> names,
                         Supplier<? extends AiStrategy> factory, float incomeMultiplier,
                         int originalDifficulty, Set<AiPhase> originalPhases, AiCadence cadence) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("AI id must not be blank");
        if (!Float.isFinite(incomeMultiplier) || incomeMultiplier <= 0) {
            throw new IllegalArgumentException("AI income multiplier must be finite and positive");
        }
        if (originalDifficulty < -2 || originalDifficulty > 3) {
            throw new IllegalArgumentException("Original difficulty must be between -2 and 3");
        }
        this.code = code;
        this.id = id;
        this.names = Map.copyOf(names);
        if (this.names.get("en") == null || this.names.get("en").isBlank()) {
            throw new IllegalArgumentException("AI requires an English fallback name");
        }
        this.factory = factory;
        this.incomeMultiplier = incomeMultiplier;
        this.originalDifficulty = originalDifficulty;
        this.originalPhases = Set.copyOf(originalPhases);
        this.cadence = Objects.requireNonNull(cadence, "cadence");
    }

    static AiDefinition original(int code, String id, String english, String chinese, float income) {
        return new AiDefinition(code, id, Map.of("en", english, "zh-CN", chinese), null,
                income, code, EnumSet.allOf(AiPhase.class), AiCadence.STANDARD);
    }

    /** Custom codes >= 4 are stable persisted IDs: never renumber or reuse them for another AI. */
    public static AiDefinition custom(int code, String id, String english, String chinese,
                                      Supplier<? extends AiStrategy> factory) {
        if (code < 4) throw new IllegalArgumentException("Custom AI code must be >= 4");
        return new AiDefinition(code, id, Map.of("en", english, "zh-CN", chinese),
                Objects.requireNonNull(factory, "factory"), 1, 0, Set.of(), AiCadence.STANDARD);
    }

    public AiDefinition withIncomeMultiplier(float multiplier) {
        return new AiDefinition(code, id, names, factory, multiplier, originalDifficulty, originalPhases, cadence);
    }

    public AiDefinition withCadence(AiCadence periods) {
        return new AiDefinition(code, id, names, factory, incomeMultiplier, originalDifficulty, originalPhases, periods);
    }

    /** Run the entire original AI alongside custom callbacks. Avoid competing orders. */
    public AiDefinition extendOriginal(int difficulty) {
        return withOriginalPhases(difficulty, AiPhase.values());
    }

    /** Retain selected original phases; the custom callbacks remain independently scheduled. */
    public AiDefinition withOriginalPhases(int difficulty, AiPhase... phases) {
        if (!isCustom()) throw new IllegalStateException("Original presets cannot be reconfigured");
        return new AiDefinition(code, id, names, factory, incomeMultiplier, difficulty,
                Set.of(phases), cadence);
    }

    public int code() { return code; }
    public String id() { return id; }
    public boolean isCustom() { return factory != null; }
    public float incomeMultiplier() { return incomeMultiplier; }
    public int originalDifficulty() { return originalDifficulty; }
    public boolean runsOriginal(AiPhase phase) { return originalPhases.contains(phase); }
    public boolean runsAnyOriginal() { return !originalPhases.isEmpty(); }
    public AiCadence cadence() { return cadence; }
    public String displayName() { return names.get("en"); }

    public String displayName(Locale locale) {
        String exact = names.get(locale.toLanguageTag());
        if (exact != null) return exact;
        if (locale.getLanguage().equals("zh")) return names.getOrDefault("zh-CN", displayName());
        return names.getOrDefault(locale.getLanguage(), displayName());
    }

    AiStrategy create() {
        if (factory == null) throw new IllegalStateException("Original AI uses the native controller");
        AiStrategy strategy = Objects.requireNonNull(factory.get(), "AI factory result");
        if (strategy.id() == null || strategy.id().isBlank()) {
            throw new IllegalArgumentException("AI strategy state id must not be blank");
        }
        return strategy;
    }
}
