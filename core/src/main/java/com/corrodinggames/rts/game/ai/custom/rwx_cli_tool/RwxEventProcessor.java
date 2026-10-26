package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.*;

/** Pure filtering/aggregation/formatting over immutable occurrence-time snapshots. */
final class RwxEventProcessor {
    private record Indexed(GameEvent event, int order) { }
    private record Line(int time, int order, EventType type, String description) {
        String text() {
            int seconds = Math.max(0, time) / 1000;
            return String.format(Locale.ROOT, "[%02d:%02d][%s] %s", seconds / 60, seconds % 60,
                    type.name().toLowerCase(Locale.ROOT), description);
        }
    }

    static EventPage query(List<GameEvent> raw, int now, EventQuery query) {
        List<Indexed> filtered = new ArrayList<>();
        long cutoff = (long) now - Math.round(query.seconds * 1000);
        for (int i = 0; i < raw.size(); i++) {
            GameEvent event = raw.get(i);
            if (event.gameTimeMillis < cutoff || event.gameTimeMillis > now) continue;
            if (!query.types.contains(event.type) || !matchesSide(event, query.allies)) continue;
            filtered.add(new Indexed(event, i));
        }
        filtered.sort(Comparator.comparingInt((Indexed row) -> row.event.gameTimeMillis)
                .thenComparingInt(Indexed::order));
        Map<Long, Indexed> discoveries = new LinkedHashMap<>();
        Set<Long> killed = new HashSet<>(), lost = new HashSet<>();
        List<Indexed> unique = new ArrayList<>();
        for (Indexed row : filtered) {
            GameEvent event = row.event;
            if (event.type == EventType.DESTROYED && !killed.add(event.subject.id)) continue;
            if (event.type == EventType.LOSSES && !lost.add(event.subject.id)) continue;
            if (event.type == EventType.NEW) discoveries.put(event.subject.id, row);
            else unique.add(row);
        }
        unique.addAll(discoveries.values());
        unique.sort(Comparator.comparingInt((Indexed row) -> row.event.gameTimeMillis)
                .thenComparingInt(Indexed::order));
        List<Line> lines = new ArrayList<>();
        Map<String, Group> losses = new LinkedHashMap<>();
        Group found = null;
        for (Indexed row : unique) {
            GameEvent event = row.event;
            if (event.type == EventType.LOSSES) {
                String key = event.subject.teamId + "|" + event.subject.typeId;
                Group group = losses.get(key);
                if (group == null || (long) event.gameTimeMillis - group.lastTime > 3000) {
                    if (group != null) lines.add(lossLine(group));
                    group = new Group(row);
                    losses.put(key, group);
                } else group.add(row);
            } else if (event.type == EventType.NEW) {
                if (found == null || (long) event.gameTimeMillis - found.lastTime > 3000) {
                    if (found != null) lines.add(newLine(found));
                    found = new Group(row);
                } else found.add(row);
            } else {
                String description;
                if (event.type == EventType.DAMAGED) {
                    description = entity(event.subject, true, true) + " was damaged by " + attacker(event.attacker);
                } else if (event.type == EventType.DESTROYED) {
                    boolean ours = "self".equals(event.attacker.relation);
                    description = (ours ? "our " : "allied-" + event.attacker.teamId + " ")
                            + entity(event.attacker, ours, false) + " has destroyed "
                            + entity(event.subject, false, true);
                } else description = event.detail;
                lines.add(new Line(event.gameTimeMillis, row.order, event.type, description));
            }
        }
        for (Group group : losses.values()) lines.add(lossLine(group));
        if (found != null) lines.add(newLine(found));
        lines.sort(Comparator.comparingInt(Line::time).thenComparingInt(Line::order));
        int start = Math.max(0, lines.size() - 50);
        List<String> output = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) output.add(lines.get(i).text());
        return new EventPage(output);
    }

    private static boolean matchesSide(GameEvent event, boolean allies) {
        return switch (event.type) {
            case DAMAGED, INFO -> "self".equals(event.subject.relation);
            case NEW -> "enemy".equals(event.subject.relation) && event.subject.x != null;
            case LOSSES -> (allies ? "ally" : "self").equals(event.subject.relation);
            case DESTROYED -> "enemy".equals(event.subject.relation) && event.attacker != null
                    && (allies ? "ally" : "self").equals(event.attacker.relation);
        };
    }

    private static final class Group {
        final Indexed first;
        final List<GameEvent> events = new ArrayList<>();
        int lastTime;
        Group(Indexed row) { first = row; add(row); }
        void add(Indexed row) { events.add(row.event); lastTime = row.event.gameTimeMillis; }
    }

    private static final class Killer {
        final EventEntity first;
        int count;
        Killer(EventEntity first) { this.first = first; }
    }

    private static Line lossLine(Group group) {
        GameEvent first = group.first.event;
        boolean ours = "self".equals(first.subject.relation);
        String prefix = ours ? "we lost " : "allied-" + first.subject.teamId + " lost ";
        String description;
        if (group.events.size() == 1) {
            description = prefix + entity(first.subject, ours, true) + " by " + attacker(first.attacker);
        } else {
            Map<String, Killer> counts = new LinkedHashMap<>();
            for (GameEvent event : group.events) {
                String key = event.attacker == null ? "unknown" : event.attacker.typeId;
                counts.computeIfAbsent(key, ignored -> new Killer(event.attacker)).count++;
            }
            List<Killer> killers = new ArrayList<>(counts.values());
            killers.sort(Comparator.comparingInt((Killer value) -> value.count).reversed());
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < Math.min(2, killers.size()); i++) {
                Killer killer = killers.get(i);
                labels.add(attacker(killer.first) + (killer.count > 1
                        ? (killer.first == null || killer.first.x == null ? " x" : "x") + killer.count : ""));
            }
            if (killers.size() > 2) labels.add("etc.");
            description = prefix + first.subject.name + " x" + group.events.size()
                    + " by " + String.join(", ", labels);
        }
        return new Line(first.gameTimeMillis, group.first.order, EventType.LOSSES, description);
    }

    private static Line newLine(Group group) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        double x = 0, y = 0;
        for (GameEvent event : group.events) {
            counts.merge(event.subject.typeId, 1, Integer::sum);
            names.putIfAbsent(event.subject.typeId, event.subject.name);
            x += event.subject.x;
            y += event.subject.y;
        }
        String description;
        if (group.events.size() == 1) {
            description = "found " + group.first.event.subject.name + " at " + coordinates(x, y);
        } else {
            List<String> labels = new ArrayList<>();
            counts.forEach((type, count) -> labels.add(names.get(type) + " x" + count));
            description = "found " + String.join(", ", labels) + " around "
                    + coordinates(x / group.events.size(), y / group.events.size());
        }
        return new Line(group.first.event.gameTimeMillis, group.first.order, EventType.NEW, description);
    }

    static String coordinates(double x, double y) {
        return "(" + RwxCliText.number(x) + "," + RwxCliText.number(y) + ")";
    }

    static String entity(EventEntity unit, boolean id, boolean position) {
        return unit.name + (id ? "," + unit.id : "")
                + (position && unit.x != null && unit.y != null ? coordinates(unit.x, unit.y) : "");
    }

    private static String attacker(EventEntity unit) {
        return unit == null ? "unknown" : entity(unit, false, true);
    }
}
