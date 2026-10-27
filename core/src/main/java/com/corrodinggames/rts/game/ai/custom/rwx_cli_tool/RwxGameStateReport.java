package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.*;
import static com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxStatusReports.*;

/** A single report model supplies both output formats. */
final class RwxGameStateReport {
    private RwxGameStateReport() { }

    private static List<Object> typeCounts(List<UnitSnapshot> units) {
        List<Object> result = new ArrayList<>();
        types(units).forEach((type, count) -> result.add(object("type", type, "count", count)));
        return result;
    }

    private static Map<String, Object> force(List<UnitSnapshot> units) {
        Map<String, Object> result = side(units);
        result.put("typeCounts", typeCounts(units));
        return result;
    }

    static String render(GameStateSnapshot snapshot, PendingPage pending, EventPage events, double seconds,
                         boolean json, boolean summary, boolean unitsActing, boolean unitsDamaged,
                         boolean buildingActing, boolean buildingActionable) {
        GameSnapshot game = snapshot.game;
        Map<String, Object> battle = game(game);
        battle.remove("credits"); battle.remove("incomePerSecond");
        Map<String, Object> economy = object("credits", game.credits, "incomePerSecond", game.incomePerSecond);
        Map<String, Object> enemies = force(snapshot.visibleEnemies);
        enemies.remove("idleUnitCount");
        Map<String, Object> forces = object("self", force(snapshot.units), "allies", force(snapshot.allies),
                "visibleEnemies", enemies);
        Map<String, Object> pendingCounts = null;
        if (pending != null) {
            int credits = 0, upgrade = 0;
            for (PendingOrderSnapshot order : pending.orders) {
                if ("upgrade".equals(order.waitFor)) upgrade++;
                else if ("credits".equals(order.waitFor)) credits++;
            }
            pendingCounts = object("pendingOrders", pending.orders.size(), "waitingForCreditsCount", credits,
                    "waitingForUpgradeCount", upgrade);
        }
        List<Object> units = new ArrayList<>(), buildings = new ArrayList<>();
        if (!summary) for (UnitSnapshot unit : snapshot.units) {
            if (!"self".equals(unit.relation)) continue;
            boolean building = isBuilding(unit), damaged = hp(unit) < 70;
            List<String> statuses = acting(unit);
            boolean selected = building ? (buildingActing && !statuses.isEmpty())
                    || (buildingActionable && (unit.canProduce || unit.canUpgrade))
                    : (unitsActing && !statuses.isEmpty()) || (unitsDamaged && damaged);
            if (!selected) continue;
            if (!building && damaged && !statuses.contains("damaged")) statuses.add("damaged");
            if (building && buildingActionable) {
                if (unit.canUpgrade) statuses.add("upgradeable");
                if (unit.canProduce) statuses.add("producible");
            }
            Map<String, Object> row = object("id", unit.id, "type", unit.unitTypeId,
                    "x", unit.x, "y", unit.y, "health", unit.health, "maxHealth", unit.maxHealth,
                    "hpPercent", unit.maxHealth > 0 ? hp(unit) : null, "statuses", statuses,
                    "currentOrder", unit.currentOrder, "targetId", unit.targetUnitId,
                    "waypointX", unit.waypointX, "waypointY", unit.waypointY);
            if (building) {
                row.put("techLevel", unit.techLevel); row.put("upgradeLevel", unit.upgradeLevel);
                List<Object> queue = new ArrayList<>();
                if (!unit.productionQueue.isEmpty()) {
                    QueueItem item = unit.productionQueue.get(0);
                    queue.add(object("command", item.command, "type", item.unitTypeId,
                            "name", item.unitTypeName, "progress", item.progress));
                }
                row.put("queueEntryCount", unit.productionQueue.size());
                row.put("queue", queue);
            }
            (building ? buildings : units).add(row);
        }
        Map<String, Object> model = object("battle", battle, "economy", economy, "forces", forces,
                "production", production(snapshot.units), "upgrades", upgrades(snapshot.units),
                "healthStatus", object("units", healthGroups(snapshot.units, false),
                        "buildings", healthGroups(snapshot.units, true)),
                "pending", pendingCounts, "notableUnits", units, "notableBuildings", buildings);
        if (events != null) {
            List<Object> items = new ArrayList<>();
            for (EventRecord event : events.items) items.add(object("gameTimeMillis", event.gameTimeMillis,
                    "type", event.type.name().toLowerCase(Locale.ROOT), "description", event.description));
            model.put("events", object("windowSeconds", seconds, "items", items));
        }
        return json ? jsonValue(model) : text(model, summary);
    }

    /** Preserve snapshot precision instead of the text formatter's one-decimal rounding. */
    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? value.toString() : "null";
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            List<String> fields = new ArrayList<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                fields.add(jsonValue(entry.getKey().toString()) + ":" + jsonValue(entry.getValue()));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : (Iterable<?>) value) items.add(jsonValue(item));
            return "[" + String.join(",", items) + "]";
        }
        return "\"" + RwxCliText.json(value.toString()) + "\"";
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> model, boolean summary) {
        Map<String, Object> battle = (Map<String, Object>) model.get("battle");
        Map<String, Object> economy = (Map<String, Object>) model.get("economy");
        Map<String, Object> forces = (Map<String, Object>) model.get("forces");
        Map<String, Object> production = (Map<String, Object>) model.get("production");
        Map<String, Object> health = (Map<String, Object>) model.get("healthStatus");
        Map<String, Object> pending = (Map<String, Object>) model.get("pending");
        StringBuilder out = new StringBuilder("Battle State (tick ").append(battle.get("tick"))
                .append(", time ").append(battle.get("time")).append(")\nStatus: ")
                .append(Boolean.TRUE.equals(battle.get("paused")) ? "paused" : "running")
                .append(" | speed ").append(number(battle.get("speed"))).append(" | map ").append(battle.get("map"))
                .append("\nTeam: self=").append(battle.get("controlledTeam")).append(" | outcome=").append(battle.get("outcome"))
                .append("\n\nEconomy:\n  Credits: ").append(number(economy.get("credits")))
                .append(" (+").append(number(economy.get("incomePerSecond"))).append("/s)\n\nForces:");
        appendForce(out, "Self", (Map<String, Object>) forces.get("self"), true);
        appendForce(out, "Allies", (Map<String, Object>) forces.get("allies"), true);
        appendForce(out, "Visible Enemies", (Map<String, Object>) forces.get("visibleEnemies"), false);
        out.append("\n\nProduction:\n  Factories: ").append(production.get("factoryCount"))
                .append(" (busy ").append(production.get("busyFactoryCount")).append(", idle ")
                .append(production.get("idleFactoryCount")).append(")\n  Queue entries: ")
                .append(production.get("productionQueueEntryCount")).append("\n\nUpgrades:");
        List<Map<String, Object>> upgrades = (List<Map<String, Object>>) model.get("upgrades");
        if (upgrades.isEmpty()) out.append("\n  none");
        for (Map<String, Object> row : upgrades) out.append("\n  #").append(row.get("id")).append(' ')
                .append(row.get("type")).append(": upgrade=").append(row.get("upgrade"))
                .append(", progress=").append(percent(row.get("progress")));
        out.append("\n\nHealth:\n  Units: ").append(healthText((List<Map<String, Object>>) health.get("units")))
                .append("\n  Buildings: ").append(healthText((List<Map<String, Object>>) health.get("buildings")))
                .append("\n\nPending:\n  Total: ").append(pending == null ? "unknown" : pending.get("pendingOrders"))
                .append("\n  Waiting for credits: ").append(pending == null ? "unknown" : pending.get("waitingForCreditsCount"))
                .append("\n  Waiting for upgrade: ").append(pending == null ? "unknown" : pending.get("waitingForUpgradeCount"));
        if (!summary) {
            appendNotable(out, "Notable Units", (List<Map<String, Object>>) model.get("notableUnits"), false);
            appendNotable(out, "Notable Buildings", (List<Map<String, Object>>) model.get("notableBuildings"), true);
        }
        Map<String, Object> events = (Map<String, Object>) model.get("events");
        if (events != null) {
            out.append("\n\nRecent Events (last ").append(number(events.get("windowSeconds"))).append(" game seconds):");
            List<Map<String, Object>> items = (List<Map<String, Object>>) events.get("items");
            if (items.isEmpty()) out.append("\n  none");
            for (Map<String, Object> item : items) out.append("\n  ").append(RwxEventProcessor.format(
                    ((Number) item.get("gameTimeMillis")).intValue(), (String) item.get("type"),
                    (String) item.get("description")));
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendForce(StringBuilder out, String name, Map<String, Object> force, boolean idle) {
        out.append("\n  ").append(name).append(": ").append(force.get("unitCount")).append(" units, ")
                .append(force.get("buildingCount")).append(" buildings");
        if (idle) out.append(", ").append(force.get("idleUnitCount")).append(" idle");
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) force.get("typeCounts"))
            labels.add(row.get("type") + " x" + row.get("count"));
        out.append("\n    ").append(labels.isEmpty() ? "none" : String.join(", ", labels));
    }

    private static String healthText(List<Map<String, Object>> rows) {
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> row : rows) labels.add(row.get("type") + " critical=" + row.get("criticalCount")
                + " low=" + row.get("lowHealthCount") + " injured=" + row.get("injuredCount"));
        return labels.isEmpty() ? "none" : String.join(", ", labels);
    }

    private static void appendNotable(StringBuilder out, String title, List<Map<String, Object>> rows, boolean building) {
        out.append("\n\n").append(title).append(" (").append(rows.size()).append("):");
        if (rows.isEmpty()) out.append("\n  none");
        for (Map<String, Object> row : rows) {
            out.append("\n  #").append(row.get("id")).append(' ').append(row.get("type")).append(" @")
                    .append(number(row.get("x"))).append(',').append(number(row.get("y"))).append(" hp=")
                    .append(number(row.get("health"))).append('/').append(number(row.get("maxHealth")))
                    .append("\n    status: ").append(String.join(", ", (List<String>) row.get("statuses")));
            if (row.get("currentOrder") != null) out.append("\n    order: ").append(row.get("currentOrder"));
            if (row.get("targetId") != null) out.append(" | target=#").append(row.get("targetId"));
            if (row.get("waypointX") != null && row.get("waypointY") != null) out.append(" | waypoint=")
                    .append(number(row.get("waypointX"))).append(',').append(number(row.get("waypointY")));
            if (building) {
                out.append("\n    tech: T").append(row.get("techLevel")).append(" | upgrade=").append(row.get("upgradeLevel"));
                List<Map<String, Object>> queue = (List<Map<String, Object>>) row.get("queue");
                if (!queue.isEmpty()) {
                    List<String> labels = new ArrayList<>();
                    for (Map<String, Object> item : queue) labels.add(item.get("command") + " "
                            + (item.get("name") == null ? item.get("type") : item.get("name"))
                            + (item.get("progress") == null ? "" : " " + percent(item.get("progress"))));
                    out.append("\n    queue: ").append(row.get("queueEntryCount"))
                            .append(" entries; first ").append(String.join(", ", labels));
                }
            }
        }
    }

    private static String number(Object value) {
        return value == null ? "unknown" : RwxCliText.number(((Number) value).doubleValue());
    }

    private static String percent(Object value) {
        return value == null ? "unknown" : Math.round(((Number) value).doubleValue() * 100) + "%";
    }
}
