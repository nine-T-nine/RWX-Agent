package com.corrodinggames.rts.game.ai.strategy;

import com.corrodinggames.rts.game.ai.custom.CustomAiCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** All selectable AI definitions, shared by UI, naming, economy, dispatch, and saves. */
public final class AiRegistry {
    private static final Map<Integer, AiDefinition> BY_CODE = new LinkedHashMap<>();
    private static final Map<String, AiDefinition> BY_ID = new LinkedHashMap<>();

    static {
        original(-2, "very-easy", "Very Easy", "非常简单");
        original(-1, "easy", "Easy", "简单");
        original(0, "medium", "Medium", "中等");
        original(1, "hard", "Hard", "困难");
        original(2, "very-hard", "Very Hard", "非常困难");
        original(3, "impossible", "Impossible", "不可能");
        CustomAiCatalog.definitions().forEach(AiRegistry::register);
    }

    private AiRegistry() { }

    private static void original(int code, String id, String english, String chinese) {
        add(AiDefinition.original(code, "rwx.original." + id, english, chinese, legacyIncome(code)));
    }

    /** Startup registration. Duplicate numeric codes or stable IDs are errors, never replacements. */
    public static synchronized void register(AiDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (!definition.isCustom()) throw new IllegalArgumentException("Register a custom AI definition");
        add(definition);
    }

    private static void add(AiDefinition definition) {
        if (BY_CODE.containsKey(definition.code()) || BY_ID.containsKey(definition.id())) {
            throw new IllegalArgumentException("Duplicate AI code or id: " + definition.code() + "/" + definition.id());
        }
        BY_CODE.put(definition.code(), definition);
        BY_ID.put(definition.id(), definition);
    }

    /** Startup removal only; never alter registrations while a match is running. */
    public static synchronized void unregister(int code) {
        AiDefinition definition = BY_CODE.get(code);
        if (definition == null) return;
        if (!definition.isCustom()) throw new IllegalArgumentException("Cannot unregister original AI");
        BY_CODE.remove(code);
        BY_ID.remove(definition.id());
    }

    public static synchronized List<AiDefinition> definitions() { return List.copyOf(BY_CODE.values()); }
    public static synchronized AiDefinition find(int code) { return BY_CODE.get(code); }
    public static synchronized AiDefinition find(String id) { return BY_ID.get(id); }

    public static String displayName(int code) {
        AiDefinition definition = find(code);
        return definition == null ? "Unknown" : definition.displayName();
    }

    public static float incomeMultiplier(int code) {
        AiDefinition definition = find(code);
        // Unknown legacy difficulty values retain the original engine's arithmetic and behaviour.
        return definition == null ? legacyIncome(code) : definition.incomeMultiplier();
    }

    private static float legacyIncome(int code) {
        float value = code > 0 ? 1.0f + code * 0.4f : 1.0f + code * 0.3f;
        if (code == 3) value += 1.5f;
        return Math.max(0.1f, value);
    }
}
