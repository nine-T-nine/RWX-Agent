package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulation-thread raw history with a monotonic delivery sequence.
 *
 * <p>Events are produced on the game thread (engine callbacks and the observation sampler) and
 * read from CLI threads. Appending assigns the sequence and the production wall clock; reading
 * copies a slice under the same short lock, so a reader can never observe a half-written ring and
 * the game thread never waits for anything but a {@code List} copy.</p>
 *
 * <p>Queries never prune, aggregate or consume this store.</p>
 */
final class RwxEventStore {
    /**
     * Retention is a delivery window, not a display window: an agent that thinks for 10 game
     * minutes must still be able to ask "what happened since seq N". A 30-minute window covers the
     * longest evaluation case; {@link #CAPACITY} is the hard bound and evictions are reported as a
     * gap instead of being dropped silently.
     */
    static final int RETENTION_MILLIS = 1_800_000;
    /**
     * 8192 events ≈ 2 MB. At the worst observed rate (damage throttled to one per 5 game seconds
     * per unit, ~100 damaged units in a large fight = ~20 events/game-second) that is ~7 game
     * minutes of a maximum-intensity fight, far longer than any realistic agent polling interval.
     */
    static final int CAPACITY = 8192;
    private final ArrayDeque<RwxLocalGameTypes.GameEvent> events = new ArrayDeque<>();
    private final Map<Long, Integer> lastDamage = new HashMap<>();
    private int lastThrottlePrune = Integer.MIN_VALUE;
    private int lastStreamTime = Integer.MIN_VALUE;
    /** Sequence the next accepted event receives. Starts at 1 so 0 always means "before anything". */
    private long nextSeq = 1;
    /** Oldest retained sequence; equals {@link #nextSeq} when the ring is empty. */
    private long firstSeq = 1;
    /** Events evicted by capacity/retention since the last {@link #clear()}. */
    private long evicted;

    synchronized boolean append(RwxLocalGameTypes.GameEvent event) {
        if (event.type == RwxLocalGameTypes.EventType.DAMAGED) {
            if (!"self".equals(event.subject.relation)) return false;
            Integer previous = lastDamage.get(event.subject.id);
            if (previous != null && (long) event.gameTimeMillis - previous < 5000) return false;
            lastDamage.put(event.subject.id, event.gameTimeMillis);
        }
        event.seq = nextSeq++;
        event.wallTimeMillis = System.currentTimeMillis();
        events.addLast(event);
        maintain(event.gameTimeMillis);
        publish(event.gameTimeMillis);
        return true;
    }

    synchronized void maintain(int now) {
        while (!events.isEmpty() && (events.size() > CAPACITY
                || (long) now - events.peekFirst().gameTimeMillis > RETENTION_MILLIS)) {
            events.removeFirst();
            evicted++;
        }
        firstSeq = events.isEmpty() ? nextSeq : events.peekFirst().seq;
        if ((long) now - lastThrottlePrune >= 1000) {
            lastDamage.values().removeIf(time -> (long) now - time >= 5000);
            lastThrottlePrune = now;
        }
        if ((long) now - lastStreamTime >= 1000) publish(now);
    }

    private void publish(int now) {
        if (RwxEventStream.isRunning()) {
            RwxEventStream.publish(snapshot(), now);
            lastStreamTime = now;
        }
    }

    /** Highest sequence assigned so far; 0 before the first accepted event. */
    synchronized long nextSeq() { return nextSeq - 1; }

    /** Oldest retained sequence (equals {@link #nextSeq()} + 1 when the ring is empty). */
    synchronized long firstSeq() { return firstSeq; }

    /** True when {@code seq} can no longer be reached because older events were evicted. */
    synchronized boolean evictedBefore(long seq) { return seq + 1 < firstSeq; }

    synchronized long evicted() { return evicted; }

    synchronized int size() { return events.size(); }

    /** Everything strictly newer than {@code seq}, oldest first. */
    synchronized List<RwxLocalGameTypes.GameEvent> since(long seq) {
        if (events.isEmpty()) return List.of();
        List<RwxLocalGameTypes.GameEvent> result = new ArrayList<>();
        for (RwxLocalGameTypes.GameEvent event : events) {
            if (event.seq > seq) result.add(event);
        }
        return result;
    }

    synchronized List<RwxLocalGameTypes.GameEvent> snapshot() { return List.copyOf(events); }

    synchronized void clear() {
        events.clear(); lastDamage.clear(); lastThrottlePrune = Integer.MIN_VALUE;
        lastStreamTime = Integer.MIN_VALUE;
        nextSeq = 1; firstSeq = 1; evicted = 0;
        if (RwxEventStream.isRunning()) RwxEventStream.reset();
    }
}
