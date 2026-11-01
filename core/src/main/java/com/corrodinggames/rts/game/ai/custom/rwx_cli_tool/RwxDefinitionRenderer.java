package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 把 {@link RwxUnitDefinition} 渲染成对齐键值文本或 JSON；不判断具体单位类型。 */
public final class RwxDefinitionRenderer {
    private static final int KEY_WIDTH = 10;
    private static final int VALUE_COLUMN = KEY_WIDTH + 2;
    private static final int WRAP_WIDTH = 88;

    private RwxDefinitionRenderer() { }

    public static String text(RwxUnitDefinition definition, boolean detail) {
        StringBuilder output = new StringBuilder();
        append(output, "id", definition.id);
        append(output, "name", definition.name);
        append(output, "name_zh", definition.nameZh);
        append(output, "kind", kind(definition));
        append(output, "cost", cost(definition.cost));
        append(output, "hp", hp(definition.hp));
        if (definition.speed != null) {
            append(output, "speed", number(definition.speed) + " tiles/s");
        }
        if (definition.vision != null) {
            append(output, "vision", definition.vision + " tiles");
        }
        append(output, "weapon", weapon(definition.weapon));
        if (!definition.abilities.isEmpty()) {
            append(output, "ability", String.join(", ", definition.abilities));
        }
        appendActions(output, definition, detail);
        appendList(output, "produces", definition.produces);
        appendList(output, "builds", definition.builds);
        appendUpgrades(output, definition, detail);
        if (!definition.requires.isEmpty()) {
            append(output, "requires", String.join(", ", definition.requires));
        }
        RwxUnitDefinition.ProducerDef primary = primaryProducer(definition);
        if (primary != null) append(output, "from", primary.name);
        if (definition.placement != null) append(output, "placement", definition.placement);
        if (!definition.features.isEmpty() && detail) {
            appendList(output, "features", definition.features);
            appendList(output, "description", definition.description);
        } else if (!definition.features.isEmpty()) {
            appendList(output, "features", definition.features);
        }
        if (definition.about != null) append(output, "about", definition.about);
        return output.toString().stripTrailing();
    }

    public static String json(RwxUnitDefinition definition, boolean detail) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", definition.id);
        value.put("name", definition.name);
        if (definition.nameZh != null) value.put("name_zh", definition.nameZh);
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("role", definition.role);
        kind.put("tier", definition.tier);
        if (!"building".equals(definition.role)) kind.put("movement", definition.movement);
        value.put("kind", kind);

        if (definition.cost != null) {
            Map<String, Object> cost = new LinkedHashMap<>();
            if (definition.cost.credits != null) cost.put("credits", definition.cost.credits);
            if (definition.cost.seconds != null) cost.put("seconds", round1(definition.cost.seconds));
            if (!cost.isEmpty()) value.put("cost", cost);
        }
        Map<String, Object> hp = new LinkedHashMap<>();
        hp.put("max", round1(definition.hp.max));
        if (definition.hp.shield > 0) hp.put("shield", round1(definition.hp.shield));
        if (definition.hp.armor > 0) hp.put("armor", round1(definition.hp.armor));
        value.put("hp", hp);

        if (definition.speed != null) value.put("speed", round1(definition.speed));
        if (definition.vision != null) value.put("vision", definition.vision);
        if (definition.weapon != null) {
            Map<String, Object> weapon = new LinkedHashMap<>();
            if (definition.weapon.damageText != null) {
                weapon.put("damageText", definition.weapon.damageText);
            } else if (definition.weapon.damage != null) {
                weapon.put("damage", round1(definition.weapon.damage));
                if (definition.weapon.damageKind != null) {
                    weapon.put("damageKind", definition.weapon.damageKind);
                }
            }
            if (definition.weapon.rangeTiles != null) weapon.put("range", round1(definition.weapon.rangeTiles));
            if (definition.weapon.reloadSeconds != null) weapon.put("reload", round1(definition.weapon.reloadSeconds));
            if (!definition.weapon.targets.isEmpty()) weapon.put("targets", definition.weapon.targets);
            value.put("weapon", weapon);
        }
        if (!definition.abilities.isEmpty()) value.put("abilities", definition.abilities);

        if (detail) {
            List<Object> actions = new ArrayList<>();
            for (RwxUnitDefinition.ActionDef action : definition.actions) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", action.id);
                row.put("name", action.name);
                if (action.kind != null) row.put("kind", action.kind);
                if (action.cost != null) row.put("cost", action.cost);
                if (action.target != null) row.put("target", action.target);
                if (action.cancel) row.put("cancel", true);
                actions.add(row);
            }
            if (!actions.isEmpty()) value.put("actions", actions);
            List<String> commands = definition.genericCommands();
            if (!commands.isEmpty()) value.put("commands", commands);
        } else {
            List<String> kinds = definition.actionKinds();
            if (!kinds.isEmpty()) value.put("actions", kinds);
        }
        if (!definition.produces.isEmpty()) value.put("produces", definition.produces);
        if (!definition.builds.isEmpty()) value.put("builds", definition.builds);
        if (!definition.upgrades.isEmpty()) {
            List<Object> upgrades = new ArrayList<>();
            for (RwxUnitDefinition.UpgradeDef upgrade : definition.upgrades) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", upgrade.name);
                if (upgrade.to != null) row.put("to", upgrade.to);
                if (upgrade.cost != null) row.put("cost", upgrade.cost);
                upgrades.add(row);
            }
            value.put("upgrade", detail ? upgrades : upgrades.get(0));
        }
        if (!definition.requires.isEmpty()) value.put("requires", definition.requires);
        RwxUnitDefinition.ProducerDef primary = primaryProducer(definition);
        if (primary != null) value.put("from", primary.name);
        if (definition.placement != null) value.put("placement", definition.placement);
        if (!definition.features.isEmpty()) value.put("features", definition.features);
        if (detail) {
            if (!definition.description.isEmpty()) value.put("description", definition.description);
            if (!definition.producers.isEmpty()) {
                List<Object> producers = new ArrayList<>();
                for (RwxUnitDefinition.ProducerDef producer : definition.producers) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", producer.name);
                    row.put("level", producer.level);
                    row.put("factory", producer.factory);
                    producers.add(row);
                }
                value.put("producers", producers);
            }
        }
        if (definition.about != null) value.put("about", definition.about);
        return RwxStatusReports.json(value);
    }

    // ===== 字段格式化 =====

    private static String kind(RwxUnitDefinition definition) {
        String value = definition.role + ", tier " + definition.tier;
        if (!"building".equals(definition.role) && !"none".equals(definition.movement)) {
            value += ", " + definition.movement;
        }
        return value;
    }

    private static String cost(RwxUnitDefinition.Cost cost) {
        if (cost == null) return null;
        List<String> parts = new ArrayList<>();
        if (cost.credits != null) parts.add(cost.credits + " credits");
        if (cost.seconds != null) parts.add(number(cost.seconds) + "s");
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String hp(RwxUnitDefinition.Hp hp) {
        List<String> extras = new ArrayList<>();
        if (hp.shield > 0) extras.add("shield " + number(hp.shield));
        if (hp.armor > 0) extras.add("armor " + number(hp.armor));
        String base = number(hp.max);
        return extras.isEmpty() ? base : base + " (" + String.join(", ", extras) + ")";
    }

    private static String weapon(RwxUnitDefinition.Weapon weapon) {
        if (weapon == null || weapon.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        if (weapon.damageText != null) {
            parts.add("damage " + weapon.damageText);
        } else if (weapon.damage != null) {
            parts.add("damage " + number(weapon.damage)
                    + (weapon.damageKind == null ? "" : " " + weapon.damageKind));
        }
        if (weapon.rangeTiles != null) parts.add("range " + number(weapon.rangeTiles) + " tiles");
        if (weapon.reloadSeconds != null) parts.add("reload " + number(weapon.reloadSeconds) + "s");
        if (!weapon.targets.isEmpty()) parts.add("vs " + String.join(", ", weapon.targets));
        return String.join(", ", parts);
    }

    private static void appendActions(StringBuilder output, RwxUnitDefinition definition, boolean detail) {
        List<String> kinds = definition.actionKinds();
        if (kinds.isEmpty() && !detail) return;
        String first = String.join(", ", kinds);
        if (!detail) {
            append(output, "actions", first);
            return;
        }
        List<String> rows = new ArrayList<>();
        if (!kinds.isEmpty()) rows.add(first);
        for (RwxUnitDefinition.ActionDef action : definition.actions) {
            StringBuilder row = new StringBuilder(action.id);
            if (action.cost != null) row.append("   ").append(action.cost).append(" credits");
            if (action.target != null) row.append("   target");
            rows.add(row.toString());
        }
        if (!rows.isEmpty()) appendRows(output, "actions", rows);
        List<String> commands = definition.genericCommands();
        if (!commands.isEmpty()) append(output, "commands", String.join(", ", commands));
    }

    private static void appendUpgrades(StringBuilder output, RwxUnitDefinition definition, boolean detail) {
        if (definition.upgrades.isEmpty()) return;
        List<String> rows = new ArrayList<>();
        for (RwxUnitDefinition.UpgradeDef upgrade : definition.upgrades) {
            StringBuilder row = new StringBuilder();
            if (upgrade.to != null) {
                row.append("to ").append(upgrade.to);
            } else {
                row.append(upgradeLabel(upgrade.name));
            }
            if (upgrade.cost != null) row.append(row.length() > 0 ? ", " : "").append(upgrade.cost).append(" credits");
            rows.add(row.toString());
            if (!detail) break;
        }
        appendRows(output, "upgrade", rows);
    }

    /** 就地升级没有目标类型，把「升级 - T2」这类本地化名字压成 T2。 */
    private static String upgradeLabel(String name) {
        if (name != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[Tt]([1-3])").matcher(name);
            if (matcher.find()) return "T" + matcher.group(1) + " (in place)";
        }
        return "upgrade";
    }

    private static RwxUnitDefinition.ProducerDef primaryProducer(RwxUnitDefinition definition) {
        RwxUnitDefinition.ProducerDef primary = null;
        for (RwxUnitDefinition.ProducerDef producer : definition.producers) {
            if (primary == null || (producer.factory && !primary.factory)) primary = producer;
        }
        return primary;
    }

    // ===== 文本排版 =====

    private static void append(StringBuilder output, String key, String value) {
        if (value == null || value.isEmpty()) return;
        output.append(String.format(Locale.ROOT, "%-" + KEY_WIDTH + "s  %s", key, value)).append('\n');
    }

    private static void appendList(StringBuilder output, String key, List<String> values) {
        if (values == null || values.isEmpty()) return;
        appendRows(output, key, new ArrayList<>(values));
    }

    /** 键 + 第一行；多行或超宽时按值列折行。 */
    private static void appendRows(StringBuilder output, String key, List<String> rows) {
        if (rows.isEmpty()) return;
        String prefix = String.format(Locale.ROOT, "%-" + KEY_WIDTH + "s  ", key);
        StringBuilder current = new StringBuilder(prefix);
        List<String> lines = new ArrayList<>();
        for (String row : rows) {
            if (current.length() > prefix.length()) {
                if (current.length() + 2 + row.length() > WRAP_WIDTH) {
                    lines.add(current.toString());
                    current = new StringBuilder(" ".repeat(VALUE_COLUMN));
                    current.append(row);
                } else {
                    current.append(", ").append(row);
                }
            } else {
                current.append(row);
            }
        }
        lines.add(current.toString());
        for (String line : lines) output.append(line).append('\n');
    }

    private static String number(float value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) return Integer.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
