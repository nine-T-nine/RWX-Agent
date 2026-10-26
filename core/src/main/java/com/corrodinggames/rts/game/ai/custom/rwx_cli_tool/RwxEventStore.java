package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Simulation-thread raw history. Queries never prune, aggregate or consume this store. */
final class RwxEventStore {
    static final int RETENTION_MILLIS = 180_000;
    static final int CAPACITY = 1000;
    private final ArrayDeque<RwxLocalGameTypes.GameEvent> events = new ArrayDeque<>();
    private final Map<Long, Integer> lastDamage = new HashMap<>();
    private int lastThrottlePrune = Integer.MIN_VALUE;

    boolean append(RwxLocalGameTypes.GameEvent event) {
        if (event.type == RwxLocalGameTypes.EventType.DAMAGED) {
            if (!"self".equals(event.subject.relation)) return false;
            Integer previous = lastDamage.get(event.subject.id);
            if (previous != null && (long) event.gameTimeMillis - previous < 5000) return false;
            lastDamage.put(event.subject.id, event.gameTimeMillis);
        }
        events.addLast(event);
        maintain(event.gameTimeMillis);
        return true;
    }

    void maintain(int now) {
        while (!events.isEmpty() && (events.size() > CAPACITY
                || (long) now - events.peekFirst().gameTimeMillis > RETENTION_MILLIS)) {
            events.removeFirst();
        }
        if ((long) now - lastThrottlePrune >= 1000) {
            lastDamage.values().removeIf(time -> (long) now - time >= 5000);
            lastThrottlePrune = now;
        }
    }

    List<RwxLocalGameTypes.GameEvent> snapshot() { return List.copyOf(events); }
    int size() { return events.size(); }
    void clear() { events.clear(); lastDamage.clear(); lastThrottlePrune = Integer.MIN_VALUE; }
}
