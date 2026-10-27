package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.*;

/** Presentation of owned activity and battle summaries; never reads live engine objects. */
final class RwxStatusReports {
    private RwxStatusReports() { }

    static boolean isBuilding(UnitSnapshot unit) {
        if (unit.building != null) return unit.building;
        return "NONE".equals(unit.movementType) || "BUILDING".equals(unit.movementType);
    }

    static double hp(UnitSnapshot unit) {
        return unit.maxHealth > 0 ? 100.0 * unit.health / unit.maxHealth : 100.0;
    }

    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static Map<String, Object> game(GameSnapshot game) {
        Map<String, Object> result = object("tick", game.tick, "time", RwxCliText.time(game.gameTimeMillis),
                "gameTimeMillis", game.gameTimeMillis, "paused", game.paused, "speed", game.speed,
                "map", game.mapName, "controlledTeam", game.controlledTeamId, "outcome", game.outcome,
                "credits", game.credits, "incomePerSecond", game.incomePerSecond);
        if (game.clockOverflowed) result.put("clockOverflowed", true);
        return result;
    }

    static Map<String, Integer> types(List<UnitSnapshot> units) {        Map<String, Integer> result = new TreeMap<>();
        for (UnitSnapshot unit : units) result.merge(unit.unitTypeId, 1, Integer::sum);
        return result;
    }

    static List<String> acting(UnitSnapshot unit) {
        LinkedHashSet<String> statuses = new LinkedHashSet<>(unit.statuses);
        if (unit.buildProgress < 1) statuses.add("constructing");
        // Legacy/test snapshots can still describe orders and queues without engine telemetry.
        if (statuses.isEmpty() && unit.currentOrder != null) statuses.add(unit.currentOrder);
        for (QueueItem item : unit.productionQueue.subList(0, Math.min(1, unit.productionQueue.size()))) {
            statuses.add("produce".equals(item.command) ? "producing"
                    : "upgrade".equals(item.command) ? "upgrading" : "executing");
        }
        return new ArrayList<>(statuses);
    }

    static Map<String, Object> side(List<UnitSnapshot> units) {
        int buildings = 0, idle = 0;
        for (UnitSnapshot unit : units) {
            if (isBuilding(unit)) buildings++;
            else if (unit.idle != null ? unit.idle : acting(unit).isEmpty()) idle++;
        }
        return object("unitCount", units.size() - buildings, "buildingCount", buildings,
                "idleUnitCount", idle, "typeCounts", types(units));
    }

    static Map<String, Object> production(List<UnitSnapshot> units) {
        int factories = 0, busy = 0, idle = 0, entries = 0;
        for (UnitSnapshot unit : units) {
            if (!isBuilding(unit) || !unit.productionCapable || unit.buildProgress < 1) continue;
            factories++;
            if (unit.productionQueue.isEmpty()) idle++; else busy++;
            for (QueueItem item : unit.productionQueue) if ("produce".equals(item.command)) entries++;
        }
        return object("factoryCount", factories, "busyFactoryCount", busy,
                "idleFactoryCount", idle, "productionQueueEntryCount", entries);
    }

    static List<Object> upgrades(List<UnitSnapshot> units) {
        List<Object> result = new ArrayList<>();
        for (UnitSnapshot unit : units) {
            for (QueueItem item : unit.productionQueue) {
                if (!"upgrade".equals(item.command)) continue;
                result.add(object("id", unit.id, "type", unit.unitTypeId,
                        "upgrade", item.unitTypeName, "progress", item.progress));
            }
        }
        return result;
    }

    /** Buckets: [0,10), [10,50), [50,80]; counts sorted lexicographically by severity. */
    static List<Object> healthGroups(List<UnitSnapshot> units, boolean buildings) {
        Map<String, int[]> groups = new TreeMap<>();
        for (UnitSnapshot unit : units) {
            double percent = hp(unit);
            if (isBuilding(unit) != buildings || percent > 80) continue;
            int[] counts = groups.computeIfAbsent(unit.unitTypeId, ignored -> new int[3]);
            counts[percent < 10 ? 0 : percent < 50 ? 1 : 2]++;
        }
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort((left, right) -> {
            for (int i = 0; i < 3; i++) {
                int difference = Integer.compare(right.getValue()[i], left.getValue()[i]);
                if (difference != 0) return difference;
            }
            return left.getKey().compareTo(right.getKey());
        });
        List<Object> result = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : sorted) {
            int[] count = entry.getValue();
            result.add(object("type", entry.getKey(), "criticalCount", count[0],
                    "lowHealthCount", count[1], "injuredCount", count[2]));
        }
        return result;
    }

    /** Small deterministic JSON writer for report values only. */
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Long || value instanceof Integer) return value.toString();
        if (value instanceof Number) return RwxCliText.number(((Number) value).doubleValue());
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            List<String> fields = new ArrayList<>();
            for (Object raw : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
                fields.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
            }
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : (Iterable<?>) value) items.add(json(item));
            return "[" + String.join(",", items) + "]";
        }
        return "\"" + RwxCliText.json(value.toString()) + "\"";
    }
}
