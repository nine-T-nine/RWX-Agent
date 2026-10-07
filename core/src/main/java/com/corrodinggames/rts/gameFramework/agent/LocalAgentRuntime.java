package com.corrodinggames.rts.gameFramework.agent;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.gameFramework.Command;
import com.corrodinggames.rts.gameFramework.GameEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Local-only semantic command bridge. Called from the simulation thread by GameLogic. */
public final class LocalAgentRuntime {
    private enum Kind { MOVE, ATTACK_MOVE, ATTACK, STOP }
    private static final class Request {
        final Kind kind; final long[] unitIds; final float x; final float y; final long targetId;
        final CompletableFuture<String> result = new CompletableFuture<>();
        Request(Kind kind, long[] unitIds, float x, float y, long targetId) {
            this.kind = kind; this.unitIds = unitIds; this.x = x; this.y = y; this.targetId = targetId;
        }
    }
    private static final ConcurrentLinkedQueue<Request> pending = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<String> brief = new AtomicReference<>("error: no active single-player match");
    private static final AtomicReference<String> ownUnits = new AtomicReference<>("[]");
    private static final AtomicReference<String> enemyUnits = new AtomicReference<>("[]");

    private LocalAgentRuntime() {}

    public static String brief() { return brief.get(); }
    public static String units(String scope) {
        if ("own".equals(scope)) return ownUnits.get();
        if ("enemy".equals(scope)) return enemyUnits.get();
        if ("all".equals(scope)) return "{\"own\":" + ownUnits.get() + ",\"visibleEnemies\":" + enemyUnits.get() + "}";
        return "error: units scope must be own, enemy, or all";
    }

    public static String submit(String verb, long[] unitIds, float x, float y, long targetId) {
        final Kind kind;
        try { kind = Kind.valueOf(verb.replace('-', '_').toUpperCase()); }
        catch (IllegalArgumentException e) { return "error: unsupported command"; }
        if (unitIds == null || unitIds.length == 0) return "error: at least one unit id is required";
        if (!Float.isFinite(x) || !Float.isFinite(y)) return "error: coordinates must be finite";
        Request request = new Request(kind, unitIds, x, y, targetId);
        pending.add(request);
        try { return request.result.get(2, TimeUnit.SECONDS); }
        catch (Exception e) { pending.remove(request); return "error: game simulation did not process command"; }
    }

    /** Must run before CommandController.executeAllCommands(). */
    public static void applyPending(GameEngine engine) {
        Request request;
        while ((request = pending.poll()) != null) request.result.complete(apply(engine, request));
    }

    /** Must run on the simulation thread after command execution. */
    public static void publish(GameEngine engine) {
        if (!engine.isSinglePlayerGame() || engine.playerTeam == null) {
            brief.set("error: no active single-player match"); ownUnits.set("[]"); enemyUnits.set("[]"); return;
        }
        PlayerTeam player = engine.playerTeam;
        List<BaseUnit> own = new ArrayList<>(), enemies = new ArrayList<>();
        BaseUnit[] units = BaseUnit.bE.a();
        for (int i = 0; i < BaseUnit.bE.size(); i++) {
            BaseUnit unit = units[i];
            if (unit == null || unit.isDestroyed || unit.team == null) continue;
            if (unit.team == player) own.add(unit);
            else if (player.d(unit.team) && unit.isVisibleToLocalPlayer()) enemies.add(unit);
        }
        ownUnits.set(unitsJson(own)); enemyUnits.set(unitsJson(enemies));
        brief.set("{\"phase\":\"playing\",\"tick\":" + engine.currentTick + ",\"map\":\"" + esc(engine.getCurrentMapName()) +
            "\",\"team\":" + player.teamId + ",\"credits\":" + player.credits + ",\"energy\":" + player.energy +
            ",\"units\":" + own.size() + ",\"visibleEnemies\":" + enemies.size() + "}");
    }

    private static String apply(GameEngine engine, Request request) {
        if (!engine.isSinglePlayerGame() || engine.playerTeam == null) return "error: an active single-player match is required";
        PlayerTeam team = engine.playerTeam;
        ArrayList<OrderableUnit> selected = new ArrayList<>();
        for (long id : request.unitIds) {
            BaseUnit unit = find(id);
            if (!(unit instanceof OrderableUnit) || unit.isDestroyed || unit.team != team) return "error: controllable own unit not found: " + id;
            selected.add((OrderableUnit) unit);
        }
        BaseUnit target = request.kind == Kind.ATTACK ? find(request.targetId) : null;
        if (request.kind == Kind.ATTACK && (target == null || target.isDestroyed)) return "error: target unit not found: " + request.targetId;
        Command command = engine.commandController.createCommandForTeam(team);
        for (OrderableUnit unit : selected) command.addUnitToCommand(unit);
        switch (request.kind) {
            case MOVE: command.setMoveTarget(request.x, request.y); break;
            case ATTACK_MOVE: command.setAttackMoveTarget(request.x, request.y); break;
            case ATTACK: command.setAttackTarget(target); break;
            case STOP: command.setClearExistingOrders(); break;
        }
        return "ok";
    }

    private static BaseUnit find(long id) {
        BaseUnit[] units = BaseUnit.bE.a();
        for (int i = 0; i < BaseUnit.bE.size(); i++) if (units[i] != null && units[i].objectId == id) return units[i];
        return null;
    }
    private static String unitsJson(List<BaseUnit> units) {
        StringBuilder out = new StringBuilder("[");
        for (BaseUnit unit : units) {
            if (out.length() > 1) out.append(',');
            out.append("{\"id\":").append(unit.objectId).append(",\"type\":\"").append(esc(unit.r().v()))
                .append("\",\"x\":").append(unit.posX).append(",\"y\":").append(unit.posY)
                .append(",\"hp\":").append(unit.currentHealth).append(",\"maxHp\":").append(unit.maxHealth).append('}');
        }
        return out.append(']').toString();
    }
    private static String esc(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
