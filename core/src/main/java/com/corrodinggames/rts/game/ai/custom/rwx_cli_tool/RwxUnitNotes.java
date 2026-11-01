package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工覆盖层：为单个单位/建筑补 about 与 features，并覆盖自动提取的结果。
 *
 * <p>格式：{@code [id]} 开头，下面可选 {@code about=...} 与 {@code features=a, b, c}
 * （{@code feature=} 也接受）。先读工作目录的 {@code tmp/unit-notes.txt}（便于开发时热改），
 * 再读 classpath 里的同名资源；同 id 以后读到的为准。</p>
 */
final class RwxUnitNotes {
    static final class Note {
        String about;
        String name;
        String nameZh;
        String weaponDamage;
        Integer tier;
        final List<String> features = new ArrayList<>();
        final List<String> actionsExtra = new ArrayList<>();
    }

    private static final Map<String, Note> NOTES = load();
    private static final long LOADED_AT = System.currentTimeMillis();

    private RwxUnitNotes() { }

    static Note find(String id) {
        return id == null ? null : NOTES.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    private static Map<String, Note> load() {
        Map<String, Note> notes = new LinkedHashMap<>();
        File override = new File("tmp/unit-notes.txt");
        if (override.isFile()) {
            try {
                parse(Files.readAllLines(override.toPath(), StandardCharsets.UTF_8), notes);
            } catch (Exception ignored) {
                // 文件损坏时退回 classpath 版本
            }
        }
        try (InputStream stream = RwxUnitNotes.class.getResourceAsStream("unit-notes.txt")) {
            if (stream != null) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
                parse(lines, notes);
            }
        } catch (Exception ignored) {
            // 没有资源时只用 override
        }
        return notes;
    }

    private static void parse(List<String> lines, Map<String, Note> notes) {
        Note current = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                String id = line.substring(1, line.length() - 1).trim();
                current = new Note();
                notes.put(id.toLowerCase(java.util.Locale.ROOT), current);
                continue;
            }
            if (current == null) continue;
            int separator = line.indexOf('=');
            if (separator < 0) continue;
            String key = line.substring(0, separator).trim().toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if ("about".equals(key)) {
                current.about = value;
            } else if ("name".equals(key)) {
                current.name = value;
            } else if ("name_zh".equals(key)) {
                current.nameZh = value;
            } else if ("tier".equals(key)) {
                try {
                    current.tier = Integer.valueOf(value);
                } catch (NumberFormatException ignored) {
                    // 非法 tier 忽略，保留自动值
                }
            } else if ("weapon_damage".equals(key)) {
                current.weaponDamage = value;
            } else if ("actions_extra".equals(key) || "actions".equals(key)) {
                for (String item : value.split("[,，]")) {
                    String action = item.trim();
                    if (!action.isEmpty()) current.actionsExtra.add(action);
                }
            } else if ("features".equals(key) || "feature".equals(key)) {
                for (String item : value.split("[,，]")) {
                    String feature = item.trim();
                    if (!feature.isEmpty()) current.features.add(feature);
                }
            }
        }
    }

    static String stripDash(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        while (trimmed.startsWith("-") || trimmed.startsWith("•")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
