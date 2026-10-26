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

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static Map<String, Object> game(GameSnapshot game) {
        Map<String, Object> result = object("tick", game.tick, "time", RwxCliText.time(game.gameTimeMillis),
                "gameTimeMillis", game.gameTimeMillis, "paused", game.paused, "speed", game.speed,
                "map", game.mapName, "controlledTeam", game.controlledTeamId, "outcome", game.outcome,
                "credits", game.credits, "incomePerSecond", game.incomePerSecond);
        if (game.clockOverflowed) result.put("clockOverflowed", true);
        return result;
    }

    /** Macro-only view of a match (the former game-show): tick, time, pause, speed, map, economy. */
    static String summary(GameSnapshot game, boolean json) {
        if (json) {
            Map<String, Object> result = game(game);
            if (game.unitCount != null) result.put("units", game.unitCount);
            return json(result);
        }
        StringBuilder output = new StringBuilder("游戏状态\n")
                .append("tick: ").append(game.tick).append("\n")
                .append("time: ").append(RwxCliText.time(game.gameTimeMillis))
                .append(" (").append(game.gameTimeMillis).append(" ms)\n")
                .append("paused: ").append(game.paused).append("\n")
                .append("speed: ").append(game.speed).append("\n")
                .append("map: ").append(game.mapName).append("\n")
                .append("controlledTeam: ").append(game.controlledTeamId).append("\n")
                .append("outcome: ").append(game.outcome);
        if (game.credits != null) output.append("\ncredits: ").append(RwxCliText.number(game.credits));
        if (game.incomePerSecond != null) {
            output.append("\nincomePerSecond: ").append(RwxCliText.number(game.incomePerSecond));
        }
        if (game.unitCount != null) output.append("\nunits: ").append(game.unitCount);
        if (game.clockOverflowed) {
            output.append("\nclockOverflowed: true（引擎 int 时钟已越过 2^31 ms ≈ 24.8 游戏天：")
                    .append("time 从 0 重新计，观测账本已重置，窗口类命令只覆盖溢出之后的数据）");
        }
        return output.toString();
    }

    private static Map<String, Integer> types(List<UnitSnapshot> units) {        Map<String, Integer> result = new TreeMap<>();
        for (UnitSnapshot unit : units) result.merge(unit.unitTypeId, 1, Integer::sum);
        return result;
    }

    private static List<String> acting(UnitSnapshot unit) {
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

    private static Map<String, Object> side(List<UnitSnapshot> units) {
        int buildings = 0, idle = 0;
        for (UnitSnapshot unit : units) {
            if (isBuilding(unit)) buildings++;
            else if (unit.idle != null ? unit.idle : acting(unit).isEmpty()) idle++;
        }
        return object("unitCount", units.size() - buildings, "buildingCount", buildings,
                "idleUnitCount", idle, "typeCounts", types(units));
    }

    private static Map<String, Object> production(List<UnitSnapshot> units) {
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

    private static List<Object> upgrades(List<UnitSnapshot> units) {
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
    private static List<Object> healthGroups(List<UnitSnapshot> units, boolean buildings) {
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

    static String brief(GameSnapshot game, List<UnitSnapshot> own, List<UnitSnapshot> allies,
                        List<UnitSnapshot> enemies, double seconds,
                        PendingPage pending, boolean json) {
        Map<String, Object> result = game(game);
        result.put("self", side(own));
        result.put("allies", side(allies));
        result.put("VisibleEnemies", types(enemies));
        result.put("production", production(own));
        result.put("upgrades", upgrades(own));
        result.put("healthStatus", object("units", healthGroups(own, false),
                "building", healthGroups(own, true)));
        result.put("windowSeconds", seconds);
        if (pending == null) result.put("pending", null);
        else {
            int credits = 0, upgrade = 0;
            for (PendingOrderSnapshot order : pending.orders) {
                if ("upgrade".equals(order.waitFor)) upgrade++; else credits++;
            }
            result.put("pending", object("pendingOrders", pending.orders.size(),
                    "waitingForCreditsCount", credits, "waitingForUpgradeCount", upgrade));
        }
        if (json) return json(result);
        StringBuilder text = new StringBuilder("战况 ").append(RwxCliText.time(game.gameTimeMillis))
                .append(" (tick ").append(game.tick).append(game.paused ? ", 暂停" : ", 运行中")
                .append(", speed ").append(RwxCliText.number(game.speed)).append(')')
                .append("\n地图: ").append(game.mapName).append(" | 我方 team ").append(game.controlledTeamId)
                .append(" | 结果: ").append(game.outcome)
                .append("\n经济: 资金 ").append(game.credits == null ? "?" : RwxCliText.number(game.credits))
                .append(" (+").append(game.incomePerSecond == null ? "?" : RwxCliText.number(game.incomePerSecond)).append("/s)");
        Map<String, Object> production = production(own);
        text.append("\n我方: ").append(sideText(own))
                .append("\n友军: ").append(sideText(allies))
                .append("\n视野内敌军: ").append(typeText(types(enemies)))
                .append("\n生产: 工厂 ").append(production.get("factoryCount"))
                .append("（队列占用 ").append(production.get("busyFactoryCount"))
                .append("，空闲 ").append(production.get("idleFactoryCount"))
                .append("） | 生产队列条目 ").append(production.get("productionQueueEntryCount"))
                .append("\n升级: ").append(upgradeText(own))
                .append("\n血量分档 (<10%濒死, 10%–<50%残血, 50%–80%受伤)")
                .append("\n  单位: ").append(healthText(healthGroups(own, false)))
                .append("\n  建筑: ").append(healthText(healthGroups(own, true)))
                .append("\n待发: ");
        if (pending == null) text.append("不支持查询");
        else {
            Map<?, ?> counts = (Map<?, ?>) result.get("pending");
            text.append(counts.get("pendingOrders")).append("（等待资金 ")
                    .append(counts.get("waitingForCreditsCount")).append("，等待升级 ")
                    .append(counts.get("waitingForUpgradeCount")).append('）');
        }
        return text.toString();
    }

    private static String sideText(List<UnitSnapshot> units) {
        Map<String, Object> counts = side(units);
        return "单位 " + counts.get("unitCount") + "，建筑 " + counts.get("buildingCount")
                + "，空闲单位 " + counts.get("idleUnitCount") + " | " + typeText(types(units));
    }

    private static String typeText(Map<String, Integer> counts) {
        List<String> rows = new ArrayList<>();
        counts.forEach((type, count) -> rows.add(type + " x" + count));
        return rows.isEmpty() ? "无" : String.join(", ", rows);
    }

    private static String upgradeText(List<UnitSnapshot> units) {
        List<String> rows = new ArrayList<>();
        for (Object raw : upgrades(units)) {
            Map<?, ?> row = (Map<?, ?>) raw;
            Number progress = (Number) row.get("progress");
            rows.add("#" + row.get("id") + " " + row.get("type") + " " + row.get("upgrade")
                    + (progress == null ? " 等待" : " " + Math.round(progress.doubleValue() * 100) + "%"));
        }
        return rows.isEmpty() ? "无" : String.join(", ", rows);
    }

    private static String healthText(List<Object> groups) {
        List<String> rows = new ArrayList<>();
        for (Object raw : groups) {
            Map<?, ?> row = (Map<?, ?>) raw;
            rows.add(row.get("type") + " 濒死" + row.get("criticalCount")
                    + "/残血" + row.get("lowHealthCount") + "/受伤" + row.get("injuredCount"));
        }
        return rows.isEmpty() ? "无" : String.join(", ", rows);
    }

    static String state(GameStateSnapshot snapshot, boolean json, boolean unitsActing,
                        boolean unitsDamaged, boolean buildingActing, boolean buildingActionable) {
        List<Object> unitRows = new ArrayList<>();
        List<Object> buildingRows = new ArrayList<>();
        List<String> unitTextRows = new ArrayList<>();
        List<String> buildingTextRows = new ArrayList<>();
        for (UnitSnapshot unit : snapshot.units) {
            if (!"self".equals(unit.relation)) continue;
            boolean building = isBuilding(unit);
            List<String> statuses = acting(unit);
            boolean damaged = hp(unit) < 70;
            boolean selected = building
                    ? (buildingActing && !statuses.isEmpty()) || (buildingActionable && (unit.canProduce || unit.canUpgrade))
                    : (unitsActing && !statuses.isEmpty()) || (unitsDamaged && damaged);
            if (!selected) continue;
            if (!building && unitsDamaged && damaged) statuses.add("damaged");
            if (building && buildingActionable) {
                if (unit.canUpgrade) statuses.add("upgradeable");
                if (unit.canProduce) statuses.add("producible");
            }
            Map<String, Object> row = object("id", unit.id, "type", unit.unitTypeId,
                    "x", unit.x, "y", unit.y, "hpPercent", hp(unit), "statuses", statuses);
            StringBuilder line = new StringBuilder("#").append(unit.id).append(' ').append(unit.unitTypeId)
                    .append(" @").append((int) unit.x).append(',').append((int) unit.y)
                    .append(" hp").append(Math.round(hp(unit))).append('%')
                    .append(" | ").append(String.join(",", statuses));
            if (unit.targetUnitId != null && unit.currentOrder != null) {
                row.put("targetId", unit.targetUnitId);
                line.append(" target=#").append(unit.targetUnitId);
            }
            (building ? buildingRows : unitRows).add(row);
            (building ? buildingTextRows : unitTextRows).add(line.toString());
        }
        if (json) {
            Map<String, Object> result = game(snapshot.game);
            result.put("notable Units", unitRows);
            result.put("notable buildings", buildingRows);
            return json(result);
        }
        GameSnapshot game = snapshot.game;
        return "游戏状态 (tick " + game.tick + ", " + RwxCliText.time(game.gameTimeMillis)
                + (game.paused ? ", 暂停" : ", 运行中") + ", speed " + RwxCliText.number(game.speed) + ")"
                + "\n地图: " + game.mapName + " | 我方 team " + game.controlledTeamId + " | 结果: " + game.outcome
                + "\n经济: 资金 " + game.credits + " | 收入 " + game.incomePerSecond + "/s"
                + "\nnotable Units:" + (unitTextRows.isEmpty() ? " 无" : "\n" + String.join("\n", unitTextRows))
                + "\nnotable buildings:" + (buildingTextRows.isEmpty() ? " 无" : "\n" + String.join("\n", buildingTextRows));
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
