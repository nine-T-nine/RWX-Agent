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
    final RwxEventStore store = new RwxEventStore();
    private GameEngine world;
    private Object map;
    private PlayerTeam owner;
    private int lastTime = -1;
    private int lastTick = -1;
    private boolean initialized;
    private Set<Long> visibleEnemies = new HashSet<>();
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
        store.clear(); names.clear(); visibleEnemies.clear(); ownUnits.clear(); initialized = false;
        lastTime = -1; lastTick = -1;
    }

    List<GameEvent> history(GameEngine engine, PlayerTeam team) {
        // A query observes the current perspective but never resets or mutates the old store.
        if (world != engine || map != engine.tileMap || owner != team
                || engine.gameTimeMillis < lastTime || engine.currentTick < lastTick) return List.of();
        return store.snapshot();
    }

    @Override public void a(BaseUnit attacker, BaseUnit victim, Object unused) { }

    @Override public void damaged(BaseUnit unit, BaseUnit attacker) {
        if (!active() || unit.team != owner) return;
        store.append(new GameEvent(world.gameTimeMillis, EventType.DAMAGED,
                snapshot(unit), snapshot(attacker), null));
    }

    @Override public void removed(BaseUnit unit, BaseUnit attacker) {
        if (!active() || unit.team == null || unit.team.teamId < 0) return;
        EventEntity subject = snapshot(unit), killer = snapshot(attacker);
        if ("self".equals(subject.relation) || "ally".equals(subject.relation)) {
            store.append(new GameEvent(world.gameTimeMillis, EventType.LOSSES, subject, killer, null));
        } else if ("enemy".equals(subject.relation) && killer != null
                && ("self".equals(killer.relation) || "ally".equals(killer.relation))) {
            store.append(new GameEvent(world.gameTimeMillis, EventType.DESTROYED, subject, killer, null));
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
        store.append(new GameEvent(world.gameTimeMillis, EventType.INFO, self, null, detail));
    }

    void observe(List<BaseUnit> friends, List<BaseUnit> enemies) {
        if (!active()) return;
        Set<Long> current = new HashSet<>();
        for (BaseUnit enemy : enemies) {
            if (!enemy.d(owner)) continue;
            current.add(enemy.objectId);
            if (!visibleEnemies.contains(enemy.objectId)) {
                store.append(new GameEvent(world.gameTimeMillis, EventType.NEW, snapshot(enemy), null, null));
            }
        }
        Set<Long> currentOwn = new HashSet<>();
        for (BaseUnit unit : friends) {
            if (unit.team != owner) continue;
            currentOwn.add(unit.objectId);
            if (initialized && !ownUnits.contains(unit.objectId) && unit.unitTarget2 == null) {
                info(unit, null, unit.buildProgress < 1 ? "construction-started" : "created");
            }
        }
        visibleEnemies = current;
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

    private static int tier(BaseUnit unit) {
        return unit instanceof CustomUnit ? ((CustomUnit) unit).unitConfig.techLevel : unit.getTechLevel();
    }
}
