package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.custom.CustomUnit;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.UnitEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.*;

/** Freezes facts and visibility on the simulation thread, before the involved objects disappear. */
final class RwxEventCollector extends UnitEventListener {
    /** A contact must stay observable this long before it is announced, so fog flicker is not a discovery. */
    private static final int DISCOVERY_CONFIRM_MILLIS = 1000;
    final RwxEventStore store = new RwxEventStore();
    private GameEngine world;
    private Object map;
    private PlayerTeam owner;
    private int lastTime = -1;
    private int lastTick = -1;
    private boolean initialized;
    /** Game time each enemy first became continuously observable in the current streak. */
    private final Map<Long, Integer> visibleSince = new HashMap<>();
    /** Ids already reported once; a unit that leaves and re-enters the fog is never re-announced. */
    private final Set<Long> reportedEnemies = new HashSet<>();
    private Set<Long> ownUnits = new HashSet<>();
    private final Map<UnitType, String> names = new HashMap<>();

    boolean synchronize(GameEngine engine, PlayerTeam team) {
        if (world != engine || map != engine.tileMap || owner != team
                || engine.gameTimeMillis < lastTime || engine.currentTick < lastTick) {
            clear();
            world = engine; map = engine.tileMap; owner = team;
        }
        lastTime = engine.gameTimeMillis;
        lastTick = engine.currentTick;
        return engine.hasLoadedLevel && !engine.isStopped && team != null && !team.isTeamSpectator;
    }

    private boolean active() {
        GameEngine engine = GameEngine.getInstance();
        return engine != null && synchronize(engine, engine.playerTeam);
    }

    void clear() {
        store.clear(); names.clear(); visibleSince.clear(); reportedEnemies.clear(); ownUnits.clear(); initialized = false;
        lastTime = -1; lastTick = -1;
    }

    List<GameEvent> history(GameEngine engine, PlayerTeam team) {
        // A query observes the current perspective but never resets or mutates the old store.
        if (world != engine || map != engine.tileMap || owner != team
                || engine.gameTimeMillis < lastTime || engine.currentTick < lastTick) return List.of();
        return store.snapshot();
    }

    /**
     * Events newer than {@code seq} for the current perspective only. Same staleness guard as
     * {@link #history}: after a map reload or a perspective change the old events must not be
     * delivered as if they described the new match.
     */
    List<GameEvent> recent(GameEngine engine, PlayerTeam team, long seq) {
        if (world != engine || map != engine.tileMap || owner != team
                || engine.gameTimeMillis < lastTime || engine.currentTick < lastTick) return List.of();
        return store.since(seq);
    }

    @Override public void a(BaseUnit attacker, BaseUnit victim, Object unused) { }

    @Override public void damaged(BaseUnit unit, BaseUnit attacker) {
        if (!active() || unit.team != owner) return;
        store.append(event(EventType.DAMAGED,
                snapshot(unit), snapshot(attacker), null));
    }

    @Override public void removed(BaseUnit unit, BaseUnit attacker) {
        if (!active() || unit.team == null || unit.team.teamId < 0) return;
        EventEntity subject = snapshot(unit), killer = snapshot(attacker);
        if ("self".equals(subject.relation) || "ally".equals(subject.relation)) {
            store.append(event(EventType.LOSSES, subject, killer, null));
        } else if ("enemy".equals(subject.relation) && killer != null
                && ("self".equals(killer.relation) || "ally".equals(killer.relation))) {
            store.append(event(EventType.DESTROYED, subject, killer, null));
        }
    }

    @Override public void info(BaseUnit unit, BaseUnit related, String phase) {
        if (!active() || unit.team != owner) return;
        EventEntity self = snapshot(unit), other = snapshot(related);
        String label = RwxEventProcessor.entity(self, true, false);
        String detail = switch (phase) {
            case "produced" -> other == null ? null : label + " produced "
                    + RwxEventProcessor.entity(other, true, false);
            case "construction-started" -> other == null ? label + " construction started"
                    : RwxEventProcessor.entity(other, true, false) + " started constructing " + label;
            case "construction-finished" -> other == null ? label + " construction completed"
                    : RwxEventProcessor.entity(other, true, false) + " completed " + label;
            case "upgrade-started" -> label + " started upgrading";
            case "upgrade-finished" -> label + " upgraded to T" + tier(unit);
            case "created" -> label + " was created";
            default -> null;
        };
        if (detail == null) return;
        ownUnits.add(unit.objectId);
        if (other != null && "produced".equals(phase)) ownUnits.add(other.id);
        // The produced unit travels in `attacker` so the renderer can aggregate a batch of production
        // notices without parsing prose: subject is always the producer.
        store.append(event(EventType.INFO, self, "produced".equals(phase) ? other : null, detail));
    }

    void observe(List<BaseUnit> friends, List<BaseUnit> enemies) {
        if (!active()) return;
        Set<Long> current = new HashSet<>();
        for (BaseUnit enemy : enemies) {
            if (!enemy.d(owner)) continue;
            current.add(enemy.objectId);
            Integer since = visibleSince.get(enemy.objectId);
            if (since == null) { visibleSince.put(enemy.objectId, world.gameTimeMillis); continue; }
            // A unit that flickers at the edge of the fog is not a discovery: it must stay observable
            // for a full confirmation window, and each id is reported at most once per match.
            if (world.gameTimeMillis - since >= DISCOVERY_CONFIRM_MILLIS
                    && reportedEnemies.add(enemy.objectId)) {
                store.append(event(EventType.NEW, snapshot(enemy), null, null));
            }
        }
        visibleSince.keySet().retainAll(current);
        Set<Long> currentOwn = new HashSet<>();
        for (BaseUnit unit : friends) {
            if (unit.team != owner) continue;
            currentOwn.add(unit.objectId);
            if (initialized && !ownUnits.contains(unit.objectId) && unit.unitTarget2 == null) {
                info(unit, null, unit.buildProgress < 1 ? "construction-started" : "created");
            }
        }
        ownUnits = currentOwn;
        initialized = true;
        store.maintain(world.gameTimeMillis);
    }

    EventEntity snapshot(BaseUnit unit) {
        if (unit == null || unit.team == null) return null;
        String relation = unit.team.teamId < 0 ? "neutral" : unit.team == owner ? "self"
                : owner.c(unit.team) ? "enemy" : "ally";
        boolean visible = !"enemy".equals(relation) || unit.d(owner);
        String name = names.computeIfAbsent(unit.r(), type -> RwxUnitNames.english(type, type.v()));
        return new EventEntity(unit.objectId, unit.r().v(), name, unit.team.teamId, relation,
                visible ? unit.posX : null, visible ? unit.posY : null);
    }

    private GameEvent event(EventType type, EventEntity subject, EventEntity attacker, String detail) {
        return new GameEvent(world.gameTimeMillis, type, subject, attacker, detail, world.currentTick);
    }

    private static int tier(BaseUnit unit) {
        return unit instanceof CustomUnit ? ((CustomUnit) unit).unitConfig.techLevel : unit.getTechLevel();
    }
}
