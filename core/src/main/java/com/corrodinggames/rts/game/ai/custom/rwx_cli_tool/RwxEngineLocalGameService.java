package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.custom.ai_automate.AiAutomate;
import com.corrodinggames.rts.game.ai.custom.ai_automate.AiAutomateReport;
import com.corrodinggames.rts.game.ai.custom.ai_automate.AiAutomateRuleId;
import com.corrodinggames.rts.game.ai.custom.ai_automate.AiAutomateRuleOptions;
import com.corrodinggames.rts.game.ai.custom.util.AiMapGraph;
import com.corrodinggames.rts.game.map.MapTile;
import com.corrodinggames.rts.game.map.TileMap;
import com.corrodinggames.rts.game.units.AttackMode;
import com.corrodinggames.rts.game.units.BaseUnit;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.UnitCommand;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeEnum;
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction;
import com.corrodinggames.rts.game.units.actions.ActionDisplayType;
import com.corrodinggames.rts.game.units.actions.ActionId;
import com.corrodinggames.rts.game.units.actions.ActionType;
import com.corrodinggames.rts.game.units.buildings.BaseBuilding;
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface;
import com.corrodinggames.rts.game.units.buildings.Projectile;
import com.corrodinggames.rts.game.units.custom.CustomUnit;
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig;
import com.corrodinggames.rts.game.units.custom.CustomProjectileTemplate;
import com.corrodinggames.rts.game.units.custom.TurretConfig;
import com.corrodinggames.rts.game.units.custom.condition.resources.Resource;
import com.corrodinggames.rts.game.units.custom.logic.CustomAction;
import com.corrodinggames.rts.game.units.g.SpecialActionBlockEffect;
import com.corrodinggames.rts.gameFramework.Command;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.ReplayEngine;
import com.corrodinggames.rts.gameFramework.Utility;
import com.corrodinggames.rts.gameFramework.file.FileHelper;
import io.github.rwx.geometry.PointF;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Engine-backed implementation of the local-game service.
 *
 * <p>CLI requests arrive on a socket worker thread. This adapter moves every engine read or
 * mutation to {@link GameEngine#queueGameThreadTask(Runnable)}, which is drained before the game
 * simulation update.</p>
 *
 * <p>Engine callbacks freeze damage, death and lifecycle facts. The existing observation
 * sampler tracks visibility transitions; event queries only read immutable snapshots.
 * Unit-list delta queries maintain their own baseline.</p>
 */
public final class RwxEngineLocalGameService implements RwxLocalGameService {
    private static final long GAME_THREAD_TIMEOUT_MILLIS = 20_000L;
    private static final long GAME_THREAD_POLL_MILLIS = 100L;
    private static final int PENDING_RETENTION_MILLIS = 10 * 60 * 1000;
    private static final int MAX_EVER_SEEN = 20000;
    /**
     * Per-read wait inside {@code step}: a stalled queue must fail fast, not eat the 20 s budget.
     * 300 ms turned out to trip on ordinary render hitches (terrain buffer creation is 130-230 ms
     * and can overlap a frame), so the read is allowed a full second before the step gives up.
     */
    private static final long STEP_POLL_TIMEOUT_MILLIS = 1_000L;
    /** Baseline read: one second, because the match is still running while we wait for it. */
    private static final long STEP_BASELINE_TIMEOUT_MILLIS = 1_000L;
    /** The closing pause gets a little longer: a missed pause is worse than 700 extra ms. */
    private static final long STEP_PAUSE_TIMEOUT_MILLIS = 1_000L;
    private static final long STEP_POLL_INTERVAL_MILLIS = 10L;
    /** Wall-clock interval of the background observation sampler. */
    private static final long OBSERVATION_SAMPLE_INTERVAL_MILLIS = 250L;
    /**
     * The sampler must not park for the full 20 s command timeout: while the map loads nothing
     * consumes the game-thread queue, and a parked sample also leaves a stale task behind.
     */
    private static final long SAMPLER_TIMEOUT_MILLIS = 3_000L;
    /** Back-off after a failed sample, so a loading map is retried a few times per minute. */
    private static final long SAMPLER_RETRY_MILLIS = 2_000L;
    private static final int MAX_PLACEMENT_SUGGESTIONS = 3;
    private static final int MAX_NUKE_LAUNCHES = 100;
    private static final int MAX_PENDING_ORDERS = 20;
    /** Production rules a single player can keep maintained; each is one factory action. */
    private static final int MAX_KEEP_RULES = 32;
    /** Minimum game-time gap between two top-up attempts for the same rule. */
    private static final int KEEP_RETRY_MILLIS = 800;
    /** Consecutive non-credit failures after which a rule is dropped instead of retried forever. */
    private static final int KEEP_MAX_FAILURES = 5;
    /** Group count and per-group size caps; both are unbounded in the engine otherwise. */
    private static final int MAX_GROUPS = 64;
    private static final int MAX_GROUP_UNITS = 500;

    private final RwxEventCollector eventCollector = new RwxEventCollector();
    private final Map<Long, RwxLocalGameTypes.UnitSnapshot> unitListBaseline = new LinkedHashMap<>();
    /** Saved unit groups ({@code group-save}); process-local, cleared when the map changes. */
    private final Map<String, List<Long>> groups = new LinkedHashMap<>();
    /** Nuclear launches issued through the CLI, newest last; used for per-launch attribution. */
    private final ArrayDeque<NukeLaunch> nukeLaunches = new ArrayDeque<>();
    /** Orders held until affordable ({@code order-action --allow-unaffordable}), oldest first. */
    private final Map<Long, PendingAction> pendingActions = new LinkedHashMap<>();
    /** Newest-first human-readable notes about pending items (issued/dropped/why). */
    private final ArrayDeque<String> pendingRecent = new ArrayDeque<>();
    private long nextPendingId = 1;
    /** Factory production rules ({@code rwx-game-keep-set}), insertion ordered. */
    private final Map<Long, KeepRule> keepRules = new LinkedHashMap<>();
    private final Map<Command, KeepSubmission> keepCommands = new HashMap<>();

    private static final class KeepSubmission {
        final long unitId;
        final long credits;
        final int tick;

        KeepSubmission(long unitId, long credits, int tick) {
            this.unitId = unitId;
            this.credits = credits;
            this.tick = tick;
        }
    }
    private final AiAutomate automate = new AiAutomate();
    {
        // 自动化规则用 --group 时按编组当前成员筛选，编组由 group-save 维护。
        automate.setGroupLookup(groups::get);
    }
    private final ArrayDeque<String> keepRecent = new ArrayDeque<>();
    private long nextKeepRuleId = 1;
    /** First place each team was ever seen; used as a spawn fallback while its base is fogged. */
    private final Map<Integer, float[]> teamFirstSeen = new HashMap<>();
    /** Insertion-ordered and hard-capped; the oldest id is evicted first. */
    private final RwxBoundedIdSet everSeenUnitIds = new RwxBoundedIdSet(MAX_EVER_SEEN);
    /**
     * Units that already received a default-stance command. Without it every sample (4x per
     * second, and repeatedly while paused) re-issued the command until the unit showed up in
     * everSeenUnitIds, which produced ~400 identical commands on one tick in the 2v2 test.
     */
    private final Set<Long> stanceIssuedUnitIds = new HashSet<>();
    private int lastSampleTick = -1;
    private String lastSampleMap = null;
    /** Last raw engine clock, used to spot the int wrap (time ran backwards inside one match). */
    private int lastObservedMillis = Integer.MIN_VALUE;
    private boolean clockOverflowed = false;
    /** The speed to restore after a CLI pause; the engine represents pause as speed zero. */
    private float speedBeforePause = 1.0f;
    /** Credits reserved by action commands submitted during the current simulation tick. */
    private int reservedActionTick = -1;
    private long reservedActionCredits;
    /** Team id -> stance automatically applied to newly produced (non-building) units. */
    private final Map<Integer, AttackMode> defaultAttackModes = new HashMap<>();
    private Thread observationSampler;

    @Override
    public RwxLocalGameTypes.GameSnapshot game() {
        return gameSnapshot(GAME_THREAD_TIMEOUT_MILLIS);
    }

    @Override
    public RwxLocalGameTypes.GameSnapshot setPaused(boolean paused) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            requireLocalTimeControl(engine);
            // The engine represents pause as speed zero. Keep the previous speed so resuming a
            // fast-forwarded game does not silently reset it to 1x.
            if (paused) {
                if (engine.gameSpeed > 0.0f) speedBeforePause = engine.gameSpeed;
                engine.gameSpeed = 0.0f;
            } else {
                engine.gameSpeed = speedBeforePause > 0.0f ? speedBeforePause : 1.0f;
            }
            return snapshotGame(engine, controlled);
        });
    }

    /** Pause for step(): never throws, so it is safe inside finally. */
    private RwxLocalGameTypes.GameSnapshot pauseQuietly() {
        return pauseQuietly(GAME_THREAD_TIMEOUT_MILLIS);
    }

    /** Pause with a short wait, then fall back to writing the speed directly. */
    private RwxLocalGameTypes.GameSnapshot pauseQuietly(long timeoutMillis) {
        try {
            return onGameThread(() -> {
                GameEngine engine = GameEngine.getInstance();
                requireActiveGame(engine);
                PlayerTeam controlled = requireControlledTeam(engine);
                requireLocalTimeControl(engine);
                if (engine.gameSpeed > 0.0f) speedBeforePause = engine.gameSpeed;
                engine.gameSpeed = 0.0f;
                return snapshotGame(engine, controlled);
            }, timeoutMillis);
        } catch (RuntimeException error) {
            forcePause();
            return null;
        }
    }

    /**
     * Stops the simulation without waiting on the game-thread queue: the only path that still
     * works when that queue is not being drained.
     */
    private void forcePause() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) return;
        synchronized (engine.gameStateLock) {
            if (engine.networkEngine.networkGameActive) return;
            if (engine.gameSpeed > 0.0f) speedBeforePause = engine.gameSpeed;
            engine.gameSpeed = 0.0f;
        }
    }

    @Override
    public RwxLocalGameTypes.GameSnapshot setSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0.0f || speed > 64.0f) {
            throw new IllegalArgumentException("Speed must be a finite value from 0 to 64");
        }
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            requireLocalTimeControl(engine);
            engine.gameSpeed = speed;
            if (speed > 0.0f) speedBeforePause = speed;
            return snapshotGame(engine, controlled);
        });
    }

    /** 允许的最大缩放；与游戏内滚轮/双指缩放的上限保持一致（GameInterfaceRenderer.getMaxZoomLevel）。 */
    private static final float CAMERA_MAX_ZOOM = 4.6f;
    private static final float CAMERA_MIN_ZOOM = 0.05f;

    @Override
    public RwxLocalGameTypes.CameraSnapshot camera() {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            requireControlledTeam(engine);
            return cameraSnapshot(engine, null);
        });
    }

    @Override
    public RwxLocalGameTypes.CameraSnapshot moveCamera(Float centerX, Float centerY, Float left,
                                                       Float top, Float zoom) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            requireControlledTeam(engine);
            List<String> notes = new ArrayList<>();

            boolean movesView = (left != null && top != null) || (centerX != null && centerY != null);
            if (zoom != null) {
                if (!Float.isFinite(zoom) || zoom <= 0.0f) {
                    throw new IllegalArgumentException("--zoom must be a positive number");
                }
                // 缩放围绕当前视野中心进行，除非调用方同时指定了新的位置。
                float centerBeforeX = engine.viewpointX + engine.halfVisibleWorldWidth;
                float centerBeforeY = engine.viewpointY + engine.halfVisibleWorldHeight;
                float clamped = clampZoom(engine, zoom, notes);
                applyZoom(engine, clamped);
                if (!movesView) {
                    engine.setViewpoint(centerBeforeX - engine.halfVisibleWorldWidth,
                            centerBeforeY - engine.halfVisibleWorldHeight);
                }
            }

            if (left != null && top != null) {
                engine.setViewpoint(left, top);
            } else if (centerX != null && centerY != null) {
                engine.setViewpoint(centerX - engine.halfVisibleWorldWidth,
                        centerY - engine.halfVisibleWorldHeight);
            }

            // 缩放也需要钳制：缩到“整图铺满”时画面会比地图的一边还宽，必须贴边/居中，
            // 否则视野会跑到地图外面去。
            if (movesView || zoom != null) {
                engine.clampCameraPosition();
                if (clampToVisibleWorld(engine)) {
                    notes.add("视野已按地图边界修正（贴边或居中）");
                }
            }
            return cameraSnapshot(engine, notes);
        });
    }

    /**
     * Reloads the current local match from tick 0 without touching the renderer or the process.
     *
     * <p>{@code GameLogic.loadGame} is the same path the in-game "restart mission" menu uses:
     * it reloads the map, re-runs room setup (teams, AI, starting units, credits, income) and
     * resets the tick clock. Because the map path and room settings remain loaded, a skirmish
     * restarts with exactly the same configuration instead of dropping to a default room.</p>
     */
    @Override
    public String restart() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            throw new IllegalStateException("No active game; start a battle room game first");
        }
        requireActiveGame(engine);
        requireLocalTimeControl(engine);
        float previousSpeed = engine.gameSpeed;
        // loadGame clears the tile atlas, which issues OpenGL calls. It must therefore run on the
        // render/game thread, never inline on the CLI thread (a paused game used to take the
        // "simulation idle, run now" shortcut and aborted the JVM with "No context is current").
        // Un-pausing first makes the frame loop run again, so the queued task is drained on the
        // render thread where the GL context is current.
        if (engine.isPaused || engine.gameSpeed == 0.0f) {
            engine.isPaused = false;
            if (engine.gameSpeed == 0.0f) engine.gameSpeed = 1.0f;
        }
        final float restoreSpeed = previousSpeed > 0.0f ? previousSpeed : 1.0f;
        return onGameThread(() -> {
            String map = engine.getCurrentMapName();
            // The observation ledgers describe the old units; ids are reused after the reload.
            resetObservation(false);
            clockOverflowed = false;
            lastObservedMillis = Integer.MIN_VALUE;
            engine.loadGame(true, com.corrodinggames.rts.gameFramework.GameMode.normal);
            // A restart is a fresh run: never leave the new match paused because the old one was.
            engine.isPaused = false;
            engine.gameSpeed = restoreSpeed;
            return "已重开: " + map + "（tick " + engine.currentTick + "，运行中）";
        });
    }

    /** Submits the engine surrender action; local matches cannot use network chat votes. */
    @Override
    public String surrender() {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            if (engine.networkEngine == null) {
                throw new IllegalStateException("The engine has no network/game-command channel");
            }
            automate.reset(engine, requireControlledTeam(engine));
            pendingActions.clear();
            keepRules.clear();
            // Queue surrender last so releasing micro-control cannot restore orders after it.
            engine.networkEngine.requestSurrender();
            return "已提交投降命令（本地对局在下一模拟 tick 生效；联机对局发送投降投票）";
        });
    }

    /**
     * 让“屏幕上看到的那块世界”落在[0,world]内：比引擎自己的钳制更严格一点——引擎按去掉侧栏的
     * viewpoint 宽度算，画面右下角会露出地图外的一截；这里按实际可见宽度算，地图比画面窄时居中。
     */
    private static boolean clampToVisibleWorld(GameEngine engine) {
        TileMap map = engine.tileMap;
        if (map == null) return false;
        float worldWidth = map.getWorldWidth();
        float worldHeight = map.getWorldHeight();
        float viewWidth = engine.visibleWorldWidth;
        float viewHeight = engine.visibleWorldHeight;
        float x = engine.viewpointX;
        float y = engine.viewpointY;

        if (viewWidth >= worldWidth) {
            x = (worldWidth - viewWidth) / 2.0f;
        } else {
            x = Math.max(0.0f, Math.min(x, worldWidth - viewWidth));
        }
        if (viewHeight >= worldHeight) {
            y = (worldHeight - viewHeight) / 2.0f;
        } else {
            y = Math.max(0.0f, Math.min(y, worldHeight - viewHeight));
        }
        if (Math.abs(x - engine.viewpointX) < 0.01f && Math.abs(y - engine.viewpointY) < 0.01f) {
            return false;
        }
        engine.setViewpoint(x, y);
        return true;
    }

    /** 与游戏内缩放一致：{@code zoom = targetZoom * densityZoomScale}。 */
    private static void applyZoom(GameEngine engine, float zoom) {
        float density = engine.densityZoomScale <= 0.0f ? 1.0f : engine.densityZoomScale;
        float target = zoom / density;
        engine.targetZoom = target;
        float effective = target * density;
        engine.zoom = effective;
        engine.visibleWorldWidth = engine.currentScreenWidthPixels / effective;
        engine.visibleWorldHeight = engine.currentScreenHeightPixels / effective;
        engine.viewpointWidth = engine.currentViewpointWidthPixels / effective;
        engine.halfVisibleWorldWidth = engine.visibleWorldWidth / 2.0f;
        engine.halfVisibleWorldHeight = engine.visibleWorldHeight / 2.0f;
    }

    private static float clampZoom(GameEngine engine, float requested, List<String> notes) {
        float min = cameraMinZoom(engine);
        float max = cameraMaxZoom(engine);
        float value = Math.max(min, Math.min(requested, max));
        if (Math.abs(value - requested) > 0.0001f) {
            notes.add("zoom " + RwxCliText.number(requested) + " → " + RwxCliText.number(value)
                    + "（允许 " + RwxCliText.number(min) + "–" + RwxCliText.number(max) + "）");
        }
        return value;
    }

    /** 缩放下限：把整张地图放进屏幕的那个比例（再缩小只会露出地图外的空白）。 */
    private static float cameraMinZoom(GameEngine engine) {
        TileMap map = engine.tileMap;
        float max = cameraMaxZoom(engine);
        if (map == null || map.getWorldWidth() <= 0.0f || map.getWorldHeight() <= 0.0f) {
            return Math.min(CAMERA_MIN_ZOOM, max);
        }
        float fit = Math.min(engine.currentScreenWidthPixels / map.getWorldWidth(),
                engine.currentScreenHeightPixels / map.getWorldHeight());
        return Math.min(max, Math.max(CAMERA_MIN_ZOOM, fit));
    }

    private static float cameraMaxZoom(GameEngine engine) {
        float density = engine.densityZoomScale <= 0.0f ? 1.0f : engine.densityZoomScale;
        return CAMERA_MAX_ZOOM * Math.min(1.0f, density);
    }

    private static RwxLocalGameTypes.CameraSnapshot cameraSnapshot(GameEngine engine, List<String> notes) {
        float left = engine.viewpointX;
        float top = engine.viewpointY;
        TileMap map = engine.tileMap;
        return new RwxLocalGameTypes.CameraSnapshot(
                left, top,
                left + engine.visibleWorldWidth, top + engine.visibleWorldHeight,
                engine.zoom, cameraMinZoom(engine), cameraMaxZoom(engine),
                engine.currentScreenWidthPixels, engine.currentScreenHeightPixels,
                map == null ? 0 : Math.round(map.getWorldWidth()),
                map == null ? 0 : Math.round(map.getWorldHeight()),
                notes);
    }

    @Override
    public List<RwxLocalGameTypes.TeamSnapshot> teams() {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            // PlayerTeam.k(i) 里的 i 是“颜色槽”，不等于 teamId；AI 队伍的颜色槽与 teamId
            // 可能错开，按 teamId 取会漏队。刷新后遍历真实队伍实例更稳。
            PlayerTeam.refreshTeamInstances();
            List<RwxLocalGameTypes.TeamSnapshot> result = new ArrayList<>();
            for (PlayerTeam team : PlayerTeam.getTeamInstances()) {
                if (team == null || team.teamId < 0 || team.teamId >= PlayerTeam.TEAM_NEUTRAL) continue;
                boolean self = team == controlled;
                String relation = relation(controlled, team);
                boolean ai = !self && (team.isTeamControlledByAI || team.isTeamSpectator);
                result.add(new RwxLocalGameTypes.TeamSnapshot(
                        team.teamId,
                        team.teamName,
                        relation,
                        team.getTeamColorIndex(),
                        ai,
                        isDefeated(team) ? "defeated" : "active",
                        self ? team.credits : null,
                        self ? netIncomeRate(team) : null,
                        self ? team.getUnitCount(true, false) : null
                ));
            }
            return result;
        });
    }

    @Override
    public RwxLocalGameTypes.UnitPage units(RwxLocalGameTypes.UnitQuery query) {
        if (query == null) throw new IllegalArgumentException("Unit query is required");
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            List<Long> groupIds = null;
            if (query.groupName != null) {
                groupIds = groups.get(requireGroupName(query.groupName));
                if (groupIds == null) throw new IllegalArgumentException("Unknown unit group '" + query.groupName + "'");
            }
            // area:/rect: selectors only ever resolve our own controllable units; they are an
            // intersection filter on top of the normal owner/type/radius filters.
            List<Long> selectorIds = query.selector == null ? null
                    : resolveUnitSelectorIds(query.selector);
            sample(engine, controlled);
            List<RwxLocalGameTypes.UnitSnapshot> result = new ArrayList<>();
            BaseUnit[] units = BaseUnit.bE.a();
            for (int index = 0; index < BaseUnit.bE.size(); index++) {
                BaseUnit unit = units[index];
                if (unit == null || unit.isDead || !isObservable(unit, controlled)) continue;
                if (groupIds != null && !groupIds.contains(unit.objectId)) continue;
                if (selectorIds != null && !selectorIds.contains(unit.objectId)) continue;
                if (!matchesOwner(unit, controlled, query.owner)) continue;
                if (!query.includeTypes.isEmpty() && !matchesAnyType(unit, query.includeTypes)) continue;
                if (!query.excludeTypes.isEmpty() && matchesAnyType(unit, query.excludeTypes)) continue;
                if (query.hpBelowPercent != null && healthPercent(unit) >= query.hpBelowPercent) continue;
                if (!withinRadius(unit, query)) continue;
                if (query.idleOnly && !isIdle(unit)) continue;
                if (query.excludeBuilders && isBuilder(unit)) continue;
                result.add(snapshot(unit, controlled));
            }
            List<RwxLocalGameTypes.UnitChange> changes = new ArrayList<>();
            if (!query.summaryOnly) {
                Map<Long, RwxLocalGameTypes.UnitSnapshot> current = new LinkedHashMap<>();
                for (RwxLocalGameTypes.UnitSnapshot unit : result) {
                    current.put(unit.id, unit);
                    RwxLocalGameTypes.UnitSnapshot previous = unitListBaseline.get(unit.id);
                    if (previous == null) changes.add(new RwxLocalGameTypes.UnitChange(unit, "now listed"));
                    else if (unit.health < previous.health) changes.add(new RwxLocalGameTypes.UnitChange(unit, "health decreased"));
                    else if (unit.techLevel > previous.techLevel) changes.add(new RwxLocalGameTypes.UnitChange(unit, "upgraded"));
                }
                for (RwxLocalGameTypes.UnitSnapshot previous : unitListBaseline.values()) {
                    if (!current.containsKey(previous.id)) changes.add(new RwxLocalGameTypes.UnitChange(previous, "no longer listed"));
                }
                unitListBaseline.clear(); unitListBaseline.putAll(current);
            }
            return new RwxLocalGameTypes.UnitPage(
                    engine.currentTick, engine.gameTimeMillis, result, changes);
        });
    }

    @Override
    public RwxLocalGameTypes.UnitSnapshot unit(long unitId) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            sample(engine, controlled);
            BaseUnit unit;
            try {
                unit = findLiveUnit(unitId);
            } catch (IllegalArgumentException ignored) {
                // Do not let a caller distinguish a fogged enemy from a dead one.
                throw new IllegalArgumentException("Unit is not currently observable: " + unitId);
            }
            if (!isObservable(unit, controlled)) {
                throw new IllegalArgumentException("Unit is not currently observable: " + unitId);
            }
            return snapshot(unit, controlled);
        });
    }

    @Override
    public List<RwxLocalGameTypes.ActionSnapshot> actions(long unitId) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            BaseUnit unit = findLiveUnit(unitId);
            if (!(unit instanceof OrderableUnit) || unit.team != controlled || unit.canNotBeGivenOrdersByPlayer()) {
                throw new IllegalArgumentException("Unit is not controllable: " + unitId);
            }
            return actionsFor(unit, availableCredits(engine, controlled));
        });
    }

    /**
     * Available actions of one controllable unit, shared by {@code actions} and {@code state}.
     *
     * @param credits what the team can still spend right now (pending CLI orders already reserved);
     *                an action the team cannot pay for at least once is reported unavailable.
     */
    private static List<RwxLocalGameTypes.ActionSnapshot> actionsFor(BaseUnit unit, long credits) {
        List<RwxLocalGameTypes.ActionSnapshot> result = new ArrayList<>();
        // 注意：OrderableUnit.cm() 是“AI 建议的下一个升级”，不是“正在执行的动作”。之前拿它
        // 判 in progress，导致第 0 号升级在什么都没做时也显示 already in progress。
        for (AbstractUnitAction action : unit.getAvailableActions()) {
            UnitType actionUnitType = action.getUnitType();
            int queuedCount = queuedCount(unit, action, actionUnitType);
            // 与原版按钮一致：b() 表示当前可用，canAfford() 才把价格和锁定算进去。
            boolean enabled = action.b(unit);
            int cost = Math.max(0, action.getCostAmount());
            // 价格要真能付得起：canAfford() 之外再和当前可用资金对一遍，避免“显示可用、
            // 下单才报资金不足”。
            boolean affordable = action.canAfford(unit, false) && cost <= credits;
            String displayName = action.getDisplayName(unit);
            // 升级完成后（如超频）原版会留下一个无名、不可用的占位按钮，界面上不显示，这里也跳过。
            if (!enabled && queuedCount == 0
                    && (displayName == null || displayName.trim().isEmpty())) {
                continue;
            }
            // 生产单位的动作允许多次排队（造 5 辆坦克），所以不能因为 queuedCount>0 就判不可用。
            // 判据不是“有没有 unitType”：机甲工厂/炮塔的升级动作也带 unitType（升级后的建筑），
            // 用它当依据会把已排队的升级显示成“资金不足”。真正可重复的是产出“非建筑单位”的动作。
            boolean repeatable = actionUnitType != null && !actionUnitType.isBuildingUnit();
            String reason = null;
            if (!repeatable && queuedCount > 0) {
                reason = "already queued or in progress (x" + queuedCount + ")";
            } else if (!enabled) {
                reason = "locked or unavailable for this unit state";
            } else if (!affordable) {
                reason = unaffordableReason(action, unit);
            }
            result.add(new RwxLocalGameTypes.ActionSnapshot(
                    action.getActionIdString(),
                    action.getDisplayName(unit),
                    reason == null,
                    queuedCount,
                    needsPointTarget(action),
                    needsUnitTarget(action),
                    actionUnitType == null ? null : actionUnitType.v(),
                    Integer.toString(action.getCostAmount()),
                    reason
            ));
        }
        return result;
    }

    /**
     * How many of this action are already in the factory queue, including the one being built
     * right now ({@code --count 3} is one queue entry holding three items).
     */
    private static int queuedCount(BaseUnit unit, AbstractUnitAction action, UnitType actionUnitType) {
        if (!(unit instanceof FactoryQueueInterface)) return 0;
        FactoryQueueInterface queue = (FactoryQueueInterface) unit;
        if (queue.dx() == null) return 0;
        String actionId = action.getActionId() == null ? null : action.getActionId().getId();
        int count = 0;
        for (Object entry : queue.dx()) {
            if (!(entry instanceof Projectile)) continue;
            Projectile item = (Projectile) entry;
            boolean matches = item.j != null && actionId != null
                    ? actionId.equals(item.j.getId())
                    : actionUnitType != null && item.unitType == actionUnitType;
            if (matches) count += Math.max(1, item.launchDelay);
        }
        return count;
    }

    @Override
    public RwxLocalGameTypes.GameStateSnapshot state(RwxLocalGameTypes.UnitOwner owner) {
        final RwxLocalGameTypes.UnitOwner requested = owner == null
                ? RwxLocalGameTypes.UnitOwner.SELF : owner;
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            List<RwxLocalGameTypes.UnitSnapshot> units = new ArrayList<>();
            List<RwxLocalGameTypes.UnitSnapshot> allies = new ArrayList<>();
            List<RwxLocalGameTypes.UnitSnapshot> enemies = new ArrayList<>();
            Map<Long, List<RwxLocalGameTypes.ActionSnapshot>> actions = new LinkedHashMap<>();
            int factories = 0;
            int idleFactories = 0;
            BaseUnit[] all = BaseUnit.bE.a();
            for (int index = 0; index < BaseUnit.bE.size(); index++) {
                BaseUnit unit = all[index];
                if (unit == null || unit.isDead || !isObservable(unit, controlled)) continue;
                RwxLocalGameTypes.UnitSnapshot row = snapshot(unit, controlled);
                if ("ally".equals(row.relation)) allies.add(row);
                else if ("enemy".equals(row.relation)) enemies.add(row);
                if (!matchesOwner(unit, controlled, requested)) continue;
                units.add(row);
                if (row.productionCapable && unit instanceof FactoryQueueInterface && unit.team == controlled
                        && unit instanceof OrderableUnit && !unit.canNotBeGivenOrdersByPlayer()) {
                    FactoryQueueInterface queue = (FactoryQueueInterface) unit;
                    int queued = queue.dx() == null ? 0 : queue.dx().size();
                    factories++;
                    if (queued == 0) idleFactories++;
                }
            }
            return new RwxLocalGameTypes.GameStateSnapshot(
                    snapshotGame(engine, controlled), requested, units, allies, enemies, actions,
                    factories, idleFactories);
        });
    }

    /**
     * canAfford() is false for three different reasons; only the last one is money. Nuke launch
     * used to report "insufficient resources" with 20k credits because no warhead was stocked.
     */
    private static String unaffordableReason(AbstractUnitAction action, BaseUnit unit) {
        if (action.isNotAvailable(unit)) {
            return "requirement not met right now (already completed, prerequisite missing, or nothing stocked — e.g. no nuke built yet)";
        }
        if (com.corrodinggames.rts.game.units.g.SpecialActionBlockEffect.a(unit, action.getActionId()) > 0) {
            return "temporarily blocked by an effect or cooldown";
        }
        return "insufficient resources (cost " + action.getCostAmount() + ")";
    }

    @Override
    public RwxLocalGameTypes.MapSnapshot map(RwxLocalGameTypes.MapQuery query) {
        if (query == null) query = new RwxLocalGameTypes.MapQuery(false, false);
        RwxLocalGameTypes.MapQuery effectiveQuery = query;
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            TileMap map = engine.tileMap;
            if (map == null) {
                throw new IllegalStateException("The map is not loaded yet");
            }
            List<RwxLocalGameTypes.ResourcePool> pools = new ArrayList<>();
            // Anchors point one tile below the pool. Return the actual building centre.
            // BaseBuilding.b queries live building occupancy without a visibility filter.
            for (PointF anchor : AiMapGraph.resourcePoolAnchors()) {
                float x = anchor.x + map.halfTileWorldSizeX;
                float y = anchor.y - map.halfTileWorldSizeY;
                int tileX = (int) Math.floor(x / map.tileWorldSizeX);
                int tileY = (int) Math.floor(y / map.tileWorldSizeY);
                BaseUnit occupant = BaseBuilding.b(tileX, tileY);
                pools.add(new RwxLocalGameTypes.ResourcePool(x, y,
                        occupant == null ? null : occupant.objectId,
                        occupant == null || occupant.team == null ? null : occupant.team.teamId,
                        occupant == null ? null : occupant.r().v()));
            }
            List<RwxLocalGameTypes.TeamSpawn> spawns = new ArrayList<>();
            if (effectiveQuery.includeSpawns) {
                sample(engine, controlled);
                PlayerTeam.refreshTeamInstances();
                for (PlayerTeam team : PlayerTeam.getTeamInstances()) {
                    if (team == null || team.teamId < 0 || team.teamId >= PlayerTeam.TEAM_NEUTRAL) continue;
                    BaseUnit center = findCommandCenter(team, controlled);
                    if (center != null) {
                        spawns.add(new RwxLocalGameTypes.TeamSpawn(
                                team.teamId, team.teamName, relation(controlled, team), center.objectId,
                                center.r().v(), center.posX, center.posY, false));
                        continue;
                    }
                    float[] seen = teamFirstSeen.get(team.teamId);
                    if (seen == null) {
                        spawns.add(new RwxLocalGameTypes.TeamSpawn(
                                team.teamId, team.teamName, relation(controlled, team), null, null,
                                -1.0f, -1.0f, false));
                    } else {
                        spawns.add(new RwxLocalGameTypes.TeamSpawn(
                                team.teamId, team.teamName, relation(controlled, team), null, null,
                                seen[0], seen[1], true));
                    }
                }
            }
            return new RwxLocalGameTypes.MapSnapshot(
                    map.tileCountX * map.tileWorldSizeX,
                    map.tileCountY * map.tileWorldSizeY,
                    map.tileWorldSizeX,
                    map.tileWorldSizeY,
                    map.fogEnabled,
                    pools,
                    spawns,
                    effectiveQuery.includeTerrain ? terrainWindow(map, controlled, effectiveQuery, pools) : null
            );
        });
    }

    /** Samples only the requested rectangle; no map-wide fog allocation is needed. */
    private static RwxLocalGameTypes.TerrainWindow terrainWindow(TileMap map, PlayerTeam controlled,
            RwxLocalGameTypes.MapQuery query, List<RwxLocalGameTypes.ResourcePool> pools) {
        int centerTileX = query.terrainX == null
                ? map.tileCountX / 2 : AiMapGraph.worldToTileX(query.terrainX);
        int centerTileY = query.terrainY == null
                ? map.tileCountY / 2 : AiMapGraph.worldToTileY(query.terrainY);
        int halfWidth = Math.max(0, query.terrainHalfWidthTiles);
        int halfHeight = Math.max(0, query.terrainHalfHeightTiles);
        int left = centerTileX - halfWidth;
        int top = centerTileY - halfHeight;
        int right = centerTileX + halfWidth;
        int bottom = centerTileY + halfHeight;
        boolean clipped = left < 0 || top < 0 || right >= map.tileCountX || bottom >= map.tileCountY;
        left = Math.max(0, Math.min(map.tileCountX - 1, left));
        top = Math.max(0, Math.min(map.tileCountY - 1, top));
        right = Math.max(0, Math.min(map.tileCountX - 1, right));
        bottom = Math.max(0, Math.min(map.tileCountY - 1, bottom));
        int width = Math.max(1, right - left + 1);
        int height = Math.max(1, bottom - top + 1);
        int[][] codes = new int[height][width];
        String[][] buildingLabels = query.terrainAscii ? null : new String[height][width];
        Map<Long, RwxLocalGameTypes.ResourcePool> resourceTiles = new HashMap<>();
        for (RwxLocalGameTypes.ResourcePool pool : pools) {
            int x = (int) Math.floor(pool.x / map.tileWorldSizeX);
            int y = (int) Math.floor(pool.y / map.tileWorldSizeY);
            resourceTiles.put(terrainTileKey(x, y), pool);
        }
        byte[][] fog = query.includeFog && map.fogEnabled ? controlled.fogOfWarData : null;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int tileX = left + x;
                int tileY = top + y;
                MapTile tile = map.getTileAt(tileX, tileY);
                // 10 = unexplored, 5..9 = explored but outside current sight, <5 = visible.
                codes[y][x] = RwxTerrainCodes.classify(tile,
                        resourceTiles.get(terrainTileKey(tileX, tileY)), RwxTerrainCodes.fogged(fog, tileX, tileY));
            }
        }
        if (buildingLabels != null) {
            BaseUnit[] units = BaseUnit.bE.a();
            io.github.rwx.geometry.Point origin = new io.github.rwx.geometry.Point();
            for (int i = 0; i < BaseUnit.bE.size(); i++) {
                BaseUnit unit = units[i];
                if (unit == null || unit.isDead || !unit.bI() || unit.team == null || unit.team.teamId < 0) continue;
                if (controlled.c(unit.team) && !isObservable(unit, controlled)) continue;
                String label = unit.team == controlled ? "#" + unit.objectId : controlled.c(unit.team) ? "-4" : "4";
                io.github.rwx.geometry.Rect footprint = unit.cc();
                unit.a(map, origin);
                int originX = origin.worldX;
                int originY = origin.worldY;
                for (int y = Math.max(top, originY + footprint.b); y <= Math.min(bottom, originY + footprint.d); y++) {
                    for (int x = Math.max(left, originX + footprint.a); x <= Math.min(right, originX + footprint.c); x++) {
                        if (!RwxTerrainCodes.fogged(fog, x, y)) buildingLabels[y - top][x - left] = label;
                    }
                }
            }
        }
        return new RwxLocalGameTypes.TerrainWindow(
                left, top, width, height,
                left * map.tileWorldSizeX, top * map.tileWorldSizeY,
                map.tileWorldSizeX, map.tileWorldSizeY,
                codes, query.terrainAscii, clipped, buildingLabels);
    }

    private static long terrainTileKey(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /** The team's live, currently observable command centre; null when fogged or destroyed. */
    private static BaseUnit findCommandCenter(PlayerTeam team, PlayerTeam controlled) {
        BaseUnit[] units = BaseUnit.bE.a();
        for (int index = 0; index < BaseUnit.bE.size(); index++) {
            BaseUnit unit = units[index];
            if (unit == null || unit.isDead || unit.team != team) continue;
            if (!(unit instanceof com.corrodinggames.rts.game.units.buildings.CommandCenter)) continue;
            if (!isObservable(unit, controlled)) continue;
            return unit;
        }
        return null;
    }

    @Override
    public RwxLocalGameTypes.ReachabilitySnapshot reach(long unitId, float x, float y) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            BaseUnit unit = findLiveUnit(unitId);
            if (unit.team != controlled) {
                throw new IllegalArgumentException(
                        "map-reach works on your own units only: " + unitId);
            }
            requireMapPoint(engine, x, y);
            UnitMovementType movement = unit.getMovementType();
            float straight = distance(unit.posX, unit.posY, x, y);
            if (movement == UnitMovementType.AIR) {
                int tiles = Math.max(1, (int) Math.ceil(straight / averageTileSize(engine)));
                List<RwxLocalGameTypes.Point> waypoints = new ArrayList<>();
                waypoints.add(new RwxLocalGameTypes.Point(unit.posX, unit.posY));
                waypoints.add(new RwxLocalGameTypes.Point(x, y));
                return new RwxLocalGameTypes.ReachabilitySnapshot(
                        true, "air unit: flies straight over terrain", movement.name(),
                        AiMapGraph.worldToTileX(unit.posX), AiMapGraph.worldToTileY(unit.posY),
                        AiMapGraph.worldToTileX(x), AiMapGraph.worldToTileY(y),
                        false, x, y, tiles, straight, straight, 1.0f, waypoints);
            }
            if (movement == UnitMovementType.NONE || movement == UnitMovementType.BUILDING) {
                boolean sameTile = AiMapGraph.worldToTileX(unit.posX) == AiMapGraph.worldToTileX(x)
                        && AiMapGraph.worldToTileY(unit.posY) == AiMapGraph.worldToTileY(y);
                return new RwxLocalGameTypes.ReachabilitySnapshot(
                        sameTile, sameTile ? "building is already on that tile"
                        : "this unit cannot move (building/NONE movement type)", movement.name(),
                        AiMapGraph.worldToTileX(unit.posX), AiMapGraph.worldToTileY(unit.posY),
                        AiMapGraph.worldToTileX(x), AiMapGraph.worldToTileY(y),
                        false, x, y, 0, straight, 0.0f, 0.0f,
                        Collections.<RwxLocalGameTypes.Point>emptyList());
            }
            AiMapGraph graph = AiMapGraph.of(movement);
            if (graph == null) {
                return new RwxLocalGameTypes.ReachabilitySnapshot(
                        false, "no terrain cost map for movement type " + movement
                        + "; cannot analyse a route", movement.name(),
                        AiMapGraph.worldToTileX(unit.posX), AiMapGraph.worldToTileY(unit.posY),
                        AiMapGraph.worldToTileX(x), AiMapGraph.worldToTileY(y),
                        false, x, y, 0, straight, 0.0f, 0.0f,
                        Collections.<RwxLocalGameTypes.Point>emptyList());
            }
            // Straight-line terrain scan: distinguishes "直线不可达但能绕路" from
            // "完全不可达", and names the blocker (mountain / river / lava).
            StraightScan lineScan = scanStraightLine(unit.posX, unit.posY, x, y, graph);

            int startX = AiMapGraph.worldToTileX(unit.posX);
            int startY = AiMapGraph.worldToTileY(unit.posY);
            int goalX = AiMapGraph.worldToTileX(x);
            int goalY = AiMapGraph.worldToTileY(y);
            boolean goalSnapped = false;
            if (!graph.passable(startX, startY)) {
                int[] fixed = nearestPassableTile(graph, startX, startY, 6);
                if (fixed == null) {
                    return new RwxLocalGameTypes.ReachabilitySnapshot(
                            false, unreachableReason("the unit is standing on terrain it cannot traverse",
                            lineScan), movement.name(), startX, startY, goalX, goalY, false,
                            x, y, 0, straight, 0.0f, 0.0f,
                            lineScan.clear, lineScan.blockedTiles, lineScan.blockers,
                            lineScan.summary, Collections.<RwxLocalGameTypes.Point>emptyList());
                }
                startX = fixed[0];
                startY = fixed[1];
            }
            int requestedGoalX = goalX;
            int requestedGoalY = goalY;
            if (!graph.passable(goalX, goalY)) {
                int[] fixed = nearestPassableTile(graph, goalX, goalY, 8);
                if (fixed == null) {
                    return new RwxLocalGameTypes.ReachabilitySnapshot(
                            false, unreachableReason(
                            "the target is not on passable terrain and no nearby passable tile exists",
                            lineScan), movement.name(), startX, startY, goalX, goalY, true,
                            x, y, 0, straight, 0.0f, 0.0f,
                            lineScan.clear, lineScan.blockedTiles, lineScan.blockers,
                            lineScan.summary, Collections.<RwxLocalGameTypes.Point>emptyList());
                }
                goalX = fixed[0];
                goalY = fixed[1];
                goalSnapped = true;
            }
            float goalWorldX = AiMapGraph.tileCenterX(goalX);
            float goalWorldY = AiMapGraph.tileCenterY(goalY);
            float goalDistance = distance(unit.posX, unit.posY, goalWorldX, goalWorldY);

            if (startX == goalX && startY == goalY) {
                List<RwxLocalGameTypes.Point> waypoints = new ArrayList<>();
                waypoints.add(new RwxLocalGameTypes.Point(unit.posX, unit.posY));
                waypoints.add(new RwxLocalGameTypes.Point(goalWorldX, goalWorldY));
                return new RwxLocalGameTypes.ReachabilitySnapshot(
                        true, goalSnapped ? "already at the nearest passable tile to the target"
                                : "already at the target tile", movement.name(),
                        startX, startY, goalX, goalY, goalSnapped, goalWorldX, goalWorldY,
                        0, goalDistance, 0.0f, 0.0f,
                        lineScan.clear, lineScan.blockedTiles, lineScan.blockers,
                        lineScan.summary, waypoints);
            }

            short startRegion = AiMapGraph.regionId(unit.posX, unit.posY, movement);
            short goalRegion = AiMapGraph.regionId(x, y, movement);
            if (startRegion > 0 && goalRegion > 0 && startRegion != goalRegion) {
                return new RwxLocalGameTypes.ReachabilitySnapshot(
                        false, unreachableReason("目标与单位不在同一静态连通区域（region " + startRegion
                        + " vs " + goalRegion + "）：水域、高地或悬崖把两地隔开，需要绕行、运输或空运",
                        lineScan), movement.name(), startX, startY, goalX, goalY, goalSnapped,
                        goalWorldX, goalWorldY, 0, goalDistance, 0.0f, 0.0f,
                        lineScan.clear, lineScan.blockedTiles, lineScan.blockers,
                        lineScan.summary, Collections.<RwxLocalGameTypes.Point>emptyList());
            }

            AiMapGraph.DistanceField field = graph.distanceFieldFrom(startX, startY);
            if (!field.reached(goalX, goalY)) {
                String reason = "静态地形上没有可通行的路线";
                if (requestedGoalX != goalX || requestedGoalY != goalY) {
                    reason += "（目标格被阻挡，已尝试最近可通行格）";
                }
                if (goalRegion > 0 && AiMapGraph.regionSize(x, y, movement) > 0) {
                    reason += "；目标所在连通区域大小 " + AiMapGraph.regionSize(x, y, movement) + " 格";
                }
                return new RwxLocalGameTypes.ReachabilitySnapshot(
                        false, unreachableReason(reason, lineScan), movement.name(),
                        startX, startY, goalX, goalY, goalSnapped,
                        goalWorldX, goalWorldY, 0, goalDistance, 0.0f, 0.0f,
                        lineScan.clear, lineScan.blockedTiles, lineScan.blockers,
                        lineScan.summary, Collections.<RwxLocalGameTypes.Point>emptyList());
            }

            List<io.github.rwx.geometry.Point> tiles = field.pathTo(goalX, goalY);
            float pathDistance = 0.0f;
            for (int index = 1; index < tiles.size(); index++) {
                io.github.rwx.geometry.Point previous = tiles.get(index - 1);
                io.github.rwx.geometry.Point current = tiles.get(index);
                pathDistance += distance(
                        AiMapGraph.tileCenterX(previous.worldX), AiMapGraph.tileCenterY(previous.worldY),
                        AiMapGraph.tileCenterX(current.worldX), AiMapGraph.tileCenterY(current.worldY));
            }
            List<RwxLocalGameTypes.Point> waypoints = sampleWaypoints(tiles);
            // BFS is 4-directional, so an unobstructed diagonal route already costs the
            // Manhattan distance. Comparing against the straight line would always report
            // ~1.41 on open ground and cry "detour" at nothing. Compare against the
            // Manhattan lower bound instead; >1 then really means terrain forced a detour.
            int manhattanTiles = Math.abs(goalX - startX) + Math.abs(goalY - startY);
            int traversedTiles = Math.max(1, tiles.size() - 1);
            float detour = manhattanTiles <= 0 ? 1.0f
                    : (float) traversedTiles / (float) manhattanTiles;
            String reason;
            if (lineScan.clear) {
                reason = "直线可达，route found for " + movement;
            } else {
                reason = "直线不可达（有" + lineScan.summary + "），已找到绕行路线（"
                        + movement + "）";
            }
            if (goalSnapped) reason += "；目标已吸附到最近可通行格";
            return new RwxLocalGameTypes.ReachabilitySnapshot(
                    true, reason, movement.name(), startX, startY, goalX, goalY, goalSnapped,
                    goalWorldX, goalWorldY, traversedTiles,
                    goalDistance, pathDistance, detour,
                    lineScan.clear, lineScan.blockedTiles, lineScan.blockers,
                    lineScan.summary, waypoints);
        });
    }

    @Override
    public RwxLocalGameTypes.PlacementSnapshot canPlace(long builderId, String unitTypeId, float x, float y,
                                                        int searchRadiusTiles) {
        if (searchRadiusTiles < 0 || searchRadiusTiles > 10) {
            throw new IllegalArgumentException("--radius must be an integer from 0 to 10");
        }
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            BaseUnit builder = findLiveUnit(builderId);
            if (!(builder instanceof OrderableUnit)
                    || builder.team != controlled
                    || builder.canNotBeGivenOrdersByPlayer()) {
                throw new IllegalArgumentException("Builder is not controllable: " + builderId);
            }
            UnitType type = requireUnitType(unitTypeId);
            if (!type.isBuildingUnit()) {
                throw new IllegalArgumentException("Not a building type: " + unitTypeId);
            }
            OrderableUnit orderableBuilder = (OrderableUnit) builder;
            // 与 order-build 保持一致：先确认这个建造者真的能造这种建筑，再谈地形。
            // 否则 --type seaFactory 配一个不会造船的建造者时，这里会误报“可放置”。
            AbstractUnitAction buildAction = orderableBuilder.findActionForUnitType(type, false);
            boolean canBuild = buildAction != null && !buildAction.isNotAvailable(orderableBuilder)
                    && buildAction.b(orderableBuilder);
            float[] snapped = snapBuildingPosition(type, x, y);
            BaseUnit prototype = BaseUnit.findTurretPosition(type);
            float oldX = prototype == null ? 0.0f : prototype.posX;
            float oldY = prototype == null ? 0.0f : prototype.posY;
            PlayerTeam oldTeam = prototype == null ? null : prototype.team;
            try {
                // --x/--y 表示“建筑中心”。引擎的放置校验把入参当“footprint 原点”，
                // 而实际建造把入参当中心，两者正好差一个 offset；这里按中心语义统一。
                boolean placeable = canBuild && canPlaceBuildingAt(type, x, y, controlled);
                boolean reachable = canBuild
                        && canReachBuildSite(orderableBuilder, type, snapped[0], snapped[1]);
                ArrayList<RwxLocalGameTypes.PlacementSuggestion> suggestions = new ArrayList<>();
                String reason;
                if (!canBuild) {
                    reason = buildAction == null
                            ? "该建造者不能建造 " + type.v() + "（" + orderableBuilder.r().v()
                            + " 的建造列表里没有它）"
                            : "该建造者现在不能建造 " + type.v()
                            + "（科技/前置未满足，或正在升级）";
                } else if (!placeable) {
                    reason = placementFailureReason(type, snapped, controlled);
                } else if (!reachable) {
                    reason = "地形可放，但建造者到不了该点（隔水/隔崖或另一座岛）";
                } else {
                    reason = "ok at " + (int) snapped[0] + "," + (int) snapped[1];
                }
                if (canBuild && (!placeable || !reachable) && searchRadiusTiles > 0) {
                    suggestions.addAll(findNearestPlacements(type, x, y, controlled,
                            searchRadiusTiles, snapped[0], snapped[1], orderableBuilder));
                }
                return new RwxLocalGameTypes.PlacementSnapshot(
                        canBuild && placeable && reachable, reason, snapped[0], snapped[1],
                        suggestions, searchRadiusTiles, reachable);
            } finally {
                if (prototype != null) {
                    prototype.posX = oldX;
                    prototype.posY = oldY;
                    if (oldTeam != null) prototype.setUnitTeam(oldTeam);
                }
            }
        });
    }

    @Override
    public RwxLocalGameTypes.CommandReceipt issue(RwxLocalGameTypes.Order order) {
        if (order == null || order.type == null) {
            throw new IllegalArgumentException("Order type is required");
        }
        return onGameThread(() -> issueOnGameThread(order));
    }

    @Override
    public RwxLocalGameTypes.CommandReceipt cancel(long unitId, String actionId, int count, boolean all) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            BaseUnit found = findLiveUnit(unitId);
            if (!(found instanceof OrderableUnit) || found.team != controlled
                    || !(found instanceof FactoryQueueInterface)) {
                throw new IllegalArgumentException("Unit " + unitId + " is not a controllable factory");
            }
            OrderableUnit factory = (OrderableUnit) found;
            FactoryQueueInterface queue = (FactoryQueueInterface) found;
            List<Projectile> matching = new ArrayList<>();
            if (queue.dx() != null) {
                for (Object entry : queue.dx()) {
                    if (!(entry instanceof Projectile)) continue;
                    Projectile item = (Projectile) entry;
                    if (item.j == null) continue;
                    if (actionId == null || actionId.equals(item.j.getId())) matching.add(item);
                }
            }
            if (matching.isEmpty()) {
                throw new IllegalArgumentException("No queued "
                        + (actionId == null ? "production or upgrade" : "action '" + actionId + "'")
                        + " on unit " + unitId);
            }
            int removeCount = all ? matching.size() : count;
            if (removeCount > matching.size()) {
                throw new IllegalArgumentException("Only " + matching.size()
                        + " matching queue entries exist; requested " + removeCount);
            }
            List<String> removed = new ArrayList<>();
            for (int index = 0; index < removeCount; index++) {
                Projectile item = matching.get(index);
                AbstractUnitAction action = factory.validateActionId(item.j);
                if (action == null || !action.canPlayerCancel(factory, true) || !action.b(factory)) {
                    throw new IllegalArgumentException("Queue item '" + item.j.getId()
                            + "' cannot be cancelled by the player or is currently unavailable");
                }
                removed.add(item.j.getId());
            }
            double creditsBefore = controlled.credits;
            boolean immediate = canApplyImmediately(engine);
            yieldAutomation(engine, Collections.singletonList(factory), true, immediate, null);
            suspendKeep(engine, Collections.singletonList(factory));
            discardPendingForUnits(Collections.singletonList(unitId), actionId, true);
            for (int index = 0; index < removeCount; index++) {
                Command command = engine.commandController.createCommandForTeam(controlled);
                command.addUnitToCommand(factory);
                command.stopCurrentAction = true;
                command.setActionId(matching.get(index).j);
                if (immediate) applyCommandNow(engine, command);
            }
            String message = "cancelled queue entries: " + String.join(", ", removed)
                    + (immediate
                    ? " (credits refunded now: " + (int) Math.max(0, controlled.credits - creditsBefore) + ")"
                    : " (refund pending until the command executes)" + queuedNote(engine));
            return new RwxLocalGameTypes.CommandReceipt(engine.currentTick,
                    Collections.singletonList(unitId), message, immediate);
        });
    }

    @Override
    public String nukeReport(long unitId, Float x, Float y) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            sample(engine, controlled);
            BaseUnit found = findLiveUnit(unitId);
            if (!(found instanceof CustomUnit) || found.team != controlled) {
                throw new IllegalArgumentException("Unit " + unitId + " is not an owned nuclear launcher");
            }
            CustomUnit launcher = (CustomUnit) found;
            NuclearAction nuclear = findNuclearAction(launcher);
            if (nuclear == null) {
                throw new IllegalArgumentException("Unit " + unitId + " has no nuclear launch action");
            }
            CustomAction launchAction = nuclear.action;
            TurretConfig turret = nuclear.turret;
            CustomProjectileTemplate projectile = nuclear.projectile;
            int cooldownMillis = Math.max(0, SpecialActionBlockEffect.a(launcher, launchAction.getActionId()));
            StringBuilder output = new StringBuilder("Nuclear launcher #").append(unitId)
                    .append(" (tick ").append(engine.currentTick).append(')')
                    .append("\nstockpile: ").append(launcher.ammo)
                    .append("\nqueued: ").append(launcher instanceof FactoryQueueInterface
                            && ((FactoryQueueInterface) launcher).dx() != null
                            ? ((FactoryQueueInterface) launcher).dx().size() : 0)
                    .append("\nlaunchAction: ").append(launchAction.getActionIdString())
                    .append("\nlaunchAvailable: ").append(launchAction.b(launcher)
                            && launchAction.canAfford(launcher, false) && cooldownMillis == 0)
                    .append("\ncooldownSeconds: ")
                    .append(String.format(Locale.ROOT, "%.1f", cooldownMillis / 1000.0))
                    .append("\nrange: ").append((int) Math.max(0, turret.limitingMinRange))
                    .append("..").append((int) turret.effectiveRange).append(" world")
                    .append("\nimpactRadius: ").append(projectile.i).append(" world");
            if (x != null && y != null) {
                requireMapPoint(engine, x, y);
                float launchDistance = distance(launcher.posX, launcher.posY, x, y);
                output.append("\npoint: ").append((int) (float) x).append(',').append((int) (float) y)
                        .append(" | distance: ").append((int) launchDistance)
                        .append(" | inRange: ").append(launchDistance >= Math.max(0, turret.limitingMinRange)
                                && launchDistance <= turret.effectiveRange);
                int self = 0, ally = 0, enemy = 0;
                Map<String, Integer> visibleTypes = new LinkedHashMap<>();
                BaseUnit[] units = BaseUnit.bE.a();
                for (int index = 0; index < BaseUnit.bE.size(); index++) {
                    BaseUnit unit = units[index];
                    if (unit == null || unit.isDead || !isObservable(unit, controlled)
                            || distance(unit.posX, unit.posY, x, y) > projectile.i) continue;
                    String relation = relationOf(unit.team, controlled);
                    if ("self".equals(relation)) self++;
                    if ("ally".equals(relation)) ally++;
                    if ("enemy".equals(relation)) enemy++;
                    mergeCount(visibleTypes, relation + " " + unit.r().v());
                    output.append("\n  visible ").append(relation).append(' ')
                            .append(unit.r().v()).append(" #").append(unit.objectId);
                }
                output.append("\nvisibleInRadius: self=").append(self)
                        .append(", ally=").append(ally).append(", enemy=").append(enemy)
                        .append("\nvisibleByType: ").append(tallyText(visibleTypes));

            }
            appendNukeLaunchHistory(output, unitId, x, y);
            return output.toString();
        });
    }

    /** One nuclear launch issued through {@code order-action}, for per-launch attribution. */
    private static final class NukeLaunch {
        final long launcherId;
        final float x;
        final float y;
        final int gameTimeMillis;
        final int tick;
        final int radius;
        final String actionId;

        NukeLaunch(long launcherId, float x, float y, int gameTimeMillis, int tick, int radius,
                   String actionId) {
            this.launcherId = launcherId;
            this.x = x;
            this.y = y;
            this.gameTimeMillis = gameTimeMillis;
            this.tick = tick;
            this.radius = Math.max(1, radius);
            this.actionId = actionId;
        }
    }

    /** The nuclear launch action of a launcher, with its turret and projectile template. */
    private static final class NuclearAction {
        final CustomAction action;
        final TurretConfig turret;
        final CustomProjectileTemplate projectile;

        NuclearAction(CustomAction action, TurretConfig turret, CustomProjectileTemplate projectile) {
            this.action = action;
            this.turret = turret;
            this.projectile = projectile;
        }
    }

    private static NuclearAction findNuclearAction(CustomUnit launcher) {
        for (Object candidate : launcher.getAvailableActions()) {
            if (!(candidate instanceof CustomAction)) continue;
            CustomAction customAction = (CustomAction) candidate;
            Integer turretIndex = customAction.actionDef.fireTurretAtGroundIndex;
            if (turretIndex == null || turretIndex < 0
                    || turretIndex >= launcher.unitConfig.turrets.length) continue;
            TurretConfig configured = launcher.unitConfig.turrets[turretIndex];
            int projectileId = configured.projectileId;
            if (projectileId < 0 || projectileId >= launcher.unitConfig.projectileTemplatesById.length) continue;
            CustomProjectileTemplate template = launcher.unitConfig.projectileTemplatesById[projectileId];
            if (template != null && template.p) {
                return new NuclearAction(customAction, configured, template);
            }
        }
        return null;
    }

    /**
     * Remembers which launcher fired at which point. Only a launch issued through the CLI gets
     * an entry, so the report can say "this launch" instead of "this area, some time".
     */
    private void recordNuclearLaunches(GameEngine engine, List<OrderableUnit> selected,
                                       RwxLocalGameTypes.Order order) {
        if (order.x == null || order.y == null) return;
        for (OrderableUnit unit : selected) {
            if (!(unit instanceof CustomUnit)) continue;
            NuclearAction nuclear = findNuclearAction((CustomUnit) unit);
            if (nuclear == null) continue;
            AbstractUnitAction requested = unit.validateActionId(ActionId.intern(order.actionId));
            if (requested != nuclear.action) continue;
            nukeLaunches.addLast(new NukeLaunch(unit.objectId, order.x, order.y,
                    engine.gameTimeMillis, engine.currentTick, nuclear.projectile.i,
                    nuclear.action.getActionIdString()));
        }
        while (nukeLaunches.size() > MAX_NUKE_LAUNCHES
                || (!nukeLaunches.isEmpty() && nukeLaunches.peekFirst().gameTimeMillis
                < engine.gameTimeMillis - PENDING_RETENTION_MILLIS)) {
            nukeLaunches.removeFirst();
        }
    }

    /** Per-launch damage/death attribution from observed events only. */
    private void appendNukeLaunchHistory(StringBuilder output, long launcherId, Float x, Float y) {
        output.append("\nlaunchHistory:");
        for (NukeLaunch launch : nukeLaunches) {
            if (launch.launcherId != launcherId) continue;
            output.append("\n  [").append(RwxCliText.time(launch.gameTimeMillis)).append("] target ")
                    .append(RwxEventProcessor.coordinates(launch.x, launch.y)).append(" radius ").append(launch.radius);
        }
    }

    private static void mergeCount(Map<String, Integer> counts, String key) {
        if (key == null) return;
        Integer previous = counts.get(key);
        counts.put(key, previous == null ? 1 : previous + 1);
    }

    private static String tallyText(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "none";
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (text.length() > 0) text.append(", ");
            text.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return text.toString();
    }

    @Override
    public RwxLocalGameTypes.CommandReceipt assist(RwxLocalGameTypes.Order order) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            if (order == null || order.type != RwxLocalGameTypes.OrderType.REPAIR
                    || order.targetUnitId == null) {
                throw new IllegalArgumentException("order-assist requires --units and --target");
            }
            BaseUnit target;
            try {
                target = findLiveUnit(order.targetUnitId);
            } catch (IllegalArgumentException missing) {
                throw new IllegalArgumentException("Target is not currently observable (dead or hidden): "
                        + order.targetUnitId);
            }
            if (!isObservable(target, controlled)) {
                throw new IllegalArgumentException("Target is not currently observable (dead or hidden): "
                        + order.targetUnitId);
            }
            requireFriendlyTarget(target, controlled, "assist");
            if (!target.r().isBuildingUnit() || target.buildProgress >= 1.0f) {
                throw new IllegalArgumentException("Target " + target.objectId
                        + " is not an unfinished building; the engine has no order that assists a completed factory's production queue");
            }
            List<OrderableUnit> builders = requireControllableUnits(resolveOrderUnitIds(order), controlled);
            for (OrderableUnit builder : builders) {
                if (!builder.canRepairTarget(target)) {
                    throw new IllegalArgumentException("Unit " + builder.objectId
                            + " cannot assist construction of " + target.objectId);
                }
                if (!canReachBuildSite(builder, target.r(), target.posX, target.posY)) {
                    throw new IllegalArgumentException("Unit " + builder.objectId
                            + " cannot reach construction site " + target.objectId);
                }
            }
            return issueOnGameThread(order);
        });
    }

    @Override
    public RwxLocalGameTypes.EventPage events(RwxLocalGameTypes.EventQuery query) {
        RwxLocalGameTypes.EventQuery effective = query == null ? new RwxLocalGameTypes.EventQuery(null, 30, false) : query;
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            requireControlledTeam(engine);
            if (observationSampler == null || !observationSampler.isAlive()) {
                throw new IllegalStateException("Event service unavailable: observation sampler is not running");
            }
            if (observationFailure != null) {
                throw new IllegalStateException("Event service unavailable: " + observationFailure);
            }
            // No sampling, trimming or cursor advancement inside a query.
            return RwxEventProcessor.query(eventCollector.history(engine, requireControlledTeam(engine)),
                    engine.gameTimeMillis, effective);
        });
    }

    @Override
    public RwxLocalGameTypes.ThreatSnapshot threats(RwxLocalGameTypes.ThreatQuery query) {
        if (query == null) throw new IllegalArgumentException("Threat query is required");
        if (!Float.isFinite(query.radius) || query.radius <= 0.0f) {
            throw new IllegalArgumentException("--radius must be a positive number");
        }
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            requireMapPoint(engine, query.x, query.y);
            sample(engine, controlled);
            List<RwxLocalGameTypes.ThreatUnit> units = new ArrayList<>();
            int enemyCount = 0;
            int airCount = 0;
            int groundCount = 0;
            int buildingCount = 0;
            int antiAirCount = 0;
            int antiGroundCount = 0;
            int antiSubmergedCount = 0;
            float nearest = Float.MAX_VALUE;
            BaseUnit[] all = BaseUnit.bE.a();
            for (int index = 0; index < BaseUnit.bE.size(); index++) {
                BaseUnit unit = all[index];
                if (unit == null || unit.isDead || !isObservable(unit, controlled)) continue;
                if (!matchesOwner(unit, controlled, RwxLocalGameTypes.UnitOwner.ENEMY)) continue;
                float distance = distance(query.x, query.y, unit.posX, unit.posY);
                if (distance > query.radius) continue;
                OrderableUnit orderable = unit instanceof OrderableUnit ? (OrderableUnit) unit : null;
                // canAttackFlyingUnits()/canAttackSurfaceUnits() default to true on OrderableUnit,
                // so an unarmed builder/factory would otherwise be tagged as an AA/AT threat.
                boolean armed = orderable != null && hasWeapon(orderable) && orderable.m() > 0.0f;
                boolean antiAir = armed && orderable.canAttackFlyingUnits();
                boolean antiGround = armed && orderable.canAttackSurfaceUnits();
                boolean antiSubmerged = armed && orderable.canAttackSubmergedUnits();
                float range = armed ? orderable.m() : 0.0f;
                boolean flying = unit.i();
                boolean building = unit.r().isBuildingUnit();
                enemyCount++;
                if (flying) airCount++;
                if (building) buildingCount++;
                if (!flying && !building) groundCount++;
                if (antiAir) antiAirCount++;
                if (antiGround) antiGroundCount++;
                if (antiSubmerged) antiSubmergedCount++;
                if (distance < nearest) nearest = distance;
                units.add(new RwxLocalGameTypes.ThreatUnit(
                        unit.objectId, unit.r().v(), unit.r().getUnitTypeDescriptionShort(),
                        unit.team == null ? -1 : unit.team.teamId, unit.posX, unit.posY, distance,
                        unit.currentHealth, unit.maxHealth, flying, building,
                        antiAir, antiGround, antiSubmerged, range));
            }
            units.sort((left, right) -> Float.compare(left.distance, right.distance));
            if (units.size() > 100) units = new ArrayList<>(units.subList(0, 100));
            List<RwxLocalGameTypes.ThreatCluster> clusters = query.cluster
                    ? RwxThreatClusters.cluster(units, query.clusterRadius)
                    : Collections.emptyList();
            return new RwxLocalGameTypes.ThreatSnapshot(
                    query.x, query.y, query.radius, enemyCount, airCount, groundCount,
                    buildingCount, antiAirCount, antiGroundCount, antiSubmergedCount,
                    nearest == Float.MAX_VALUE ? -1.0f : nearest, units, clusters,
                    query.cluster ? query.clusterRadius : 0.0f);
        });
    }

    @Override
    public RwxLocalGameTypes.ReplaySnapshot armReplay() {
        ReplayEngine.requestLocalRecording();
        GameEngine engine = GameEngine.getInstance();
        if (engine != null && engine.hasLoadedLevel && !engine.isStopped) {
            return replaySnapshot(engine, null,
                    "armed, but this match is already running: the arm applies to the NEXT local match. "
                            + "Use " + RwxCliCommands.Command.REPLAY_START.commandName()
                            + " to record this one from its current state.");
        }
        return new RwxLocalGameTypes.ReplaySnapshot(
                false, false, false, true, null, null, 0L, 0, -1,
                "armed; the next local match will start recording at tick 0");
    }

    @Override
    public RwxLocalGameTypes.ReplaySnapshot startReplay(String fileName) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            ReplayEngine replay = engine.replayEngine;
            if (replay == null) {
                throw new IllegalStateException("Replay engine is not available");
            }
            if (replay.isRecording()) {
                throw new IllegalStateException(
                        "A replay is already being recorded: " + replay.getReplayFilePath());
            }
            if (replay.isReplaying()) {
                throw new IllegalStateException("A replay is currently being played back");
            }
            String name = fileName == null ? "" : fileName.trim();
            if (name.isEmpty()) name = defaultLocalReplayName(engine);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".replay")) name += ".replay";
            ReplayEngine.clearLocalRecordingRequest();
            replay.d(name);
            if (!replay.isRecording()) {
                throw new IllegalStateException(
                        "Failed to start replay recording (see the game log); the replay file was not created");
            }
            return replaySnapshot(engine, null,
                    "recording started at the current state (tick " + engine.currentTick
                            + "); commands before this point are NOT included. To capture a full match, "
                            + "arm before starting the next one.");
        });
    }

    @Override
    public RwxLocalGameTypes.ReplaySnapshot stopReplay() {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            ReplayEngine replay = engine.replayEngine;
            if (replay == null) {
                throw new IllegalStateException("Replay engine is not available");
            }
            if (!replay.isRecording()) {
                throw new IllegalStateException("No replay is currently being recorded");
            }
            String file = replay.getReplayFilePath();
            int commands = replay.getRecordedCommandCount();
            int tick = engine.currentTick;
            replay.e();
            RwxLocalGameTypes.ReplaySnapshot status = replaySnapshot(engine, file,
                    "recording stopped and finalized at tick " + tick);
            return new RwxLocalGameTypes.ReplaySnapshot(
                    status.gameActive, false, status.replaying,
                    ReplayEngine.isLocalRecordingRequested(), file, status.absolutePath,
                    status.fileSizeBytes, commands, tick, status.message);
        });
    }

    @Override
    public RwxLocalGameTypes.ReplaySnapshot replayStatus() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || !engine.hasLoadedLevel || engine.isStopped) {
            return new RwxLocalGameTypes.ReplaySnapshot(
                    false, false, false, ReplayEngine.isLocalRecordingRequested(),
                    null, null, 0L, 0, -1,
                    ReplayEngine.isLocalRecordingRequested()
                            ? "armed for the next local match"
                            : "no active game; nothing is being recorded");
        }
        return onGameThread(() -> replaySnapshot(engine, null, null));
    }

    @Override
    public RwxLocalGameTypes.StepSnapshot step(double seconds, int timeoutMillis) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            throw new IllegalArgumentException("--seconds must be a positive number");
        }
        // Reject before step's local recovery/finally paths can interpret a permission
        // failure as a stalled game thread and pause a multiplayer simulation.
        GameEngine engine = GameEngine.getInstance();
        if (engine != null) synchronized (engine.gameStateLock) {
            requireLocalTimeControl(engine);
        }
        int timeout = Math.max(1_000, Math.min(timeoutMillis, 600_000));
        RwxLocalGameTypes.GameSnapshot before;
        try {
            before = gameSnapshot(STEP_BASELINE_TIMEOUT_MILLIS);
        } catch (IllegalStateException stalled) {
            // Never leave the match running when the state cannot even be read: the caller would
            // see the result minutes later with no way to know it was still simulating.
            forcePause();
            throw new IllegalStateException("step could not read the current state ("
                    + stalled.getMessage() + "); the match was paused, retry step", stalled);
        }
        boolean wasPaused = before.paused || before.speed <= 0.0f;
        float previousSpeed = before.speed;
        long targetMillis = before.gameTimeMillis + Math.round(seconds * 1000.0);
        // Continue at the caller's speed; a paused game (speed 0) steps at 1x.
        float runSpeed = previousSpeed > 0.0f ? previousSpeed : 1.0f;
        long deadline = System.currentTimeMillis() + timeout;
        RwxLocalGameTypes.GameSnapshot after = before;
        boolean timedOut = false;
        boolean stalled = false;
        boolean pausedDirectly = false;
        RwxLocalGameTypes.GameSnapshot paused = null;
        boolean resumed = false;
        try {
            try {
                setSpeed(runSpeed);
                resumed = true;
            } catch (IllegalStateException noAnswer) {
                requireLocalTimeControl(GameEngine.getInstance());
                // Same rule as a stalled read: never spend the 20 s command timeout here, the
                // match would keep running at the old speed for all of it.
                timedOut = true;
                stalled = true;
                forcePause();
                pausedDirectly = true;
            }
            while (resumed) {
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true;
                    break;
                }
                try {
                    // A read must answer quickly: waiting the full 20 s command timeout here is
                    // exactly what let a stalled queue overrun the target by ~170 game seconds.
                    after = gameSnapshot(STEP_POLL_TIMEOUT_MILLIS);
                } catch (IllegalStateException noAnswer) {
                    // The queue is not being drained: stop the match right here instead of
                    // spending another round-trip to ask for a pause.
                    timedOut = true;
                    stalled = true;
                    forcePause();
                    pausedDirectly = true;
                    break;
                }
                if (after.gameTimeMillis >= targetMillis) break;
                if (!"active".equals(after.outcome)) break;
                if (after.paused) {
                    // Something else paused the game (defeat, map end); stop instead of spinning.
                    timedOut = true;
                    break;
                }
                // Poll tighter near the target so a fast game does not overshoot by many ticks.
                long remaining = targetMillis - after.gameTimeMillis;
                long sleep = remaining > 2_000L * runSpeed ? STEP_POLL_INTERVAL_MILLIS : 3L;
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    timedOut = true;
                    break;
                }
            }
        } finally {
            // Always hand the game back paused, even when a read timed out or threw.
            if (!pausedDirectly) paused = pauseQuietly(STEP_PAUSE_TIMEOUT_MILLIS);
        }
        if (paused == null) {
            // The game thread never answered; the pause was applied directly (forcePause), so the
            // last good reading is the best snapshot we can offer.
            paused = new RwxLocalGameTypes.GameSnapshot(after.tick, after.gameTimeMillis, true,
                    0.0f, after.mapName, after.controlledTeamId, after.outcome, after.credits,
                    after.incomePerSecond, after.unitCount, after.clockOverflowed);
        }
        float advanced = Math.max(0, paused.gameTimeMillis - before.gameTimeMillis) / 1000.0f;
        return new RwxLocalGameTypes.StepSnapshot(paused, seconds, advanced, wasPaused, timedOut,
                stalled);
    }

    /** {@code game()} with a caller-chosen wait, so a stalled queue fails fast instead of hanging. */
    private RwxLocalGameTypes.GameSnapshot gameSnapshot(long timeoutMillis) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            return snapshotGame(engine, requireControlledTeam(engine));
        }, timeoutMillis);
    }

    @Override
    public List<RwxLocalGameTypes.GroupSnapshot> groups() {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            if (engine == null || !engine.hasLoadedLevel || engine.isStopped) {
                List<RwxLocalGameTypes.GroupSnapshot> idle = new ArrayList<>();
                for (Map.Entry<String, List<Long>> entry : groups.entrySet()) {
                    idle.add(new RwxLocalGameTypes.GroupSnapshot(entry.getKey(), entry.getValue(), 0));
                }
                return idle;
            }
            PlayerTeam controlled = requireControlledTeam(engine);
            List<RwxLocalGameTypes.GroupSnapshot> result = new ArrayList<>();
            for (Map.Entry<String, List<Long>> entry : groups.entrySet()) {
                result.add(new RwxLocalGameTypes.GroupSnapshot(
                        entry.getKey(), entry.getValue(),
                        countLiveUnits(entry.getValue(), controlled),
                        liveComposition(entry.getValue(), controlled)));
            }
            return result;
        });
    }

    @Override
    public RwxLocalGameTypes.GroupSnapshot saveGroup(String name, String unitsOption,
                                                     List<Long> excludedIds, List<String> includeTypes,
                                                     List<String> excludeTypes, Float hpBelowPercent,
                                                     boolean idleOnly, boolean excludeBuilders) {
        String groupName = requireGroupName(name);
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            List<Long> ids = resolveUnitOption(unitsOption);
            ids = excludeIds(ids, excludedIds);
            ids = applyTypeFilters(ids, includeTypes, excludeTypes);
            ids = applyHpFilter(ids, hpBelowPercent);
            ids = filterSelection(ids, idleOnly, excludeBuilders);
            List<OrderableUnit> live = requireControllableUnits(ids, controlled);
            List<Long> saved = idsOf(live);
            if (saved.size() > MAX_GROUP_UNITS) {
                throw new IllegalArgumentException("A group holds at most " + MAX_GROUP_UNITS
                        + " units; this selection has " + saved.size()
                        + ". Narrow it with --type/--hp/area/rect.");
            }
            if (!groups.containsKey(groupName) && groups.size() >= MAX_GROUPS) {
                throw new IllegalArgumentException("At most " + MAX_GROUPS
                        + " groups can be saved; delete one with group-delete first");
            }
            groups.put(groupName, saved);
            return new RwxLocalGameTypes.GroupSnapshot(groupName, saved, saved.size(),
                    compositionOf(live));
        });
    }

    @Override
    public RwxLocalGameTypes.GroupSnapshot deleteGroup(String name) {
        String groupName = requireGroupName(name);
        return onGameThread(() -> {
            List<Long> removed = groups.remove(groupName);
            if (removed == null) {
                throw new IllegalArgumentException("No saved group named '" + groupName + "'");
            }
            return new RwxLocalGameTypes.GroupSnapshot(groupName, removed, 0);
        });
    }

    @Override
    public RwxLocalGameTypes.ObserveResetSnapshot observeReset(boolean keepGroups) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            requireControlledTeam(engine);
            int events = eventCollector.store.size();
            int clearedGroups = keepGroups ? 0 : groups.size();
            // 只丢“观察账本”。defaultAttackModes / stanceIssuedUnitIds 是下发过命令的记录，
            // 清掉会让所有单位在新单位采样时再收一次姿态命令（之前就产生过几百条重复命令）。

            eventCollector.clear();
            unitListBaseline.clear();
            teamFirstSeen.clear();
            everSeenUnitIds.clear();

            if (!keepGroups) groups.clear();
            // 下一次采样走 initialSample 分支：只重建基线，不会把全图单位记成“新建”。
            lastSampleTick = -1;
            return new RwxLocalGameTypes.ObserveResetSnapshot(
                    engine.currentTick, engine.gameTimeMillis, events, clearedGroups,
                    keepGroups, true);
        });
    }

    private static String requireGroupName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("--name is required");
        if (!trimmed.matches("[A-Za-z0-9_.-]{1,32}")) {
            throw new IllegalArgumentException(
                    "--name must be 1-32 characters of letters, digits, '_', '-' or '.'");
        }
        return trimmed;
    }

    private static int countLiveUnits(List<Long> ids, PlayerTeam controlled) {
        int live = 0;
        for (Long id : ids) {
            try {
                BaseUnit unit = findLiveUnit(id);
                if (unit.team == controlled && unit instanceof OrderableUnit) live++;
            } catch (IllegalArgumentException gone) {
                // Dead units stay in the group; they are just not counted as live.
            }
        }
        return live;
    }

    /** Live members of a saved group, grouped by unit type and ordered biggest stack first. */
    private static List<RwxLocalGameTypes.GroupTypeCount> liveComposition(
            List<Long> ids, PlayerTeam controlled) {
        List<OrderableUnit> live = new ArrayList<>();
        for (Long id : ids) {
            try {
                BaseUnit unit = findLiveUnit(id);
                if (unit.team == controlled && unit instanceof OrderableUnit) {
                    live.add((OrderableUnit) unit);
                }
            } catch (IllegalArgumentException gone) {
                // Dead members simply drop out of the composition.
            }
        }
        return compositionOf(live);
    }

    private static List<RwxLocalGameTypes.GroupTypeCount> compositionOf(List<OrderableUnit> units) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (OrderableUnit unit : units) {
            String typeId = unit.r().v();
            counts.put(typeId, (counts.get(typeId) == null ? 0 : counts.get(typeId)) + 1);
            names.put(typeId, unit.r().getUnitTypeDescriptionShort());
        }
        List<RwxLocalGameTypes.GroupTypeCount> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            result.add(new RwxLocalGameTypes.GroupTypeCount(
                    entry.getKey(), names.get(entry.getKey()), entry.getValue()));
        }
        result.sort((left, right) -> {
            if (left.count != right.count) return right.count - left.count;
            return left.unitTypeId.compareTo(right.unitTypeId);
        });
        return result;
    }

    private static String defaultLocalReplayName(GameEngine engine) {        return "[local-live] " + engine.getCurrentMapName()
                + " [v" + engine.getVersionString() + "] ("
                + Utility.formatCurrentDate("d MMM yyyy HH.mm.ss") + ").replay";
    }

    private static RwxLocalGameTypes.ReplaySnapshot replaySnapshot(GameEngine engine, String fileOverride,
                                                                   String message) {
        ReplayEngine replay = engine == null ? null : engine.replayEngine;
        String file = fileOverride != null ? fileOverride
                : replay == null ? null : replay.getReplayFilePath();
        String absolute = null;
        long size = 0L;
        if (file != null) {
            try {
                File resolved = FileHelper.getRWFile(file, "replays/", false);
                absolute = resolved.getAbsolutePath();
                if (resolved.isFile()) size = resolved.length();
            } catch (RuntimeException ignored) {
                // Path resolution is informational; never fail a status/stop because of it.
            }
        }
        boolean gameActive = engine != null && engine.hasLoadedLevel && !engine.isStopped;
        boolean recording = replay != null && replay.isRecording();
        boolean replaying = replay != null && replay.isReplaying();
        return new RwxLocalGameTypes.ReplaySnapshot(
                gameActive, recording, replaying, ReplayEngine.isLocalRecordingRequested(),
                file, absolute, size, replay == null ? 0 : replay.getRecordedCommandCount(),
                engine == null ? -1 : engine.currentTick,
                message != null ? message
                        : recording ? "recording" : "not recording");
    }

    private static void requireActiveGame(GameEngine engine) {
        // 注意：桌面版启动对局后 GameEngine.isGameStarted 可能一直是 false
        // （它只在暂停热键/回放/编辑器路径里被置 true），真正的“地图已加载并
        // 在跑”标志是 hasLoadedLevel + 未被 stop。用 isGameStarted 判定会把
        // 正在进行的本地对局误判成“没有对局”。
        if (!engine.hasLoadedLevel || engine.isStopped) {
            throw new IllegalStateException("No active game; start a battle room game first");
        }
    }

    private static PlayerTeam requireControlledTeam(GameEngine engine) {
        if (engine.playerTeam == null || engine.playerTeam.isTeamSpectator) {
            throw new IllegalStateException("The local player does not control a team");
        }
        return engine.playerTeam;
    }

    private static String outcome(PlayerTeam team) {
        if (isDefeated(team)) return "defeat";
        // Despite its legacy decompiled name, isTeamVictory is set by system action 100
        // (surrender), not by winning. Victory is determined from the surviving hostile teams.
        PlayerTeam.refreshTeamInstances();
        Set<Integer> teamsWithUnits = teamIdsWithUnits();
        boolean hasEnemy = false;
        for (PlayerTeam candidate : PlayerTeam.getTeamInstances()) {
            if (candidate == null || candidate.teamId < 0 || candidate.teamId >= PlayerTeam.TEAM_NEUTRAL) continue;
            if (!team.c(candidate)) continue;
            hasEnemy = true;
            boolean defeated = isDefeated(candidate);
            if (!defeated && teamsWithUnits.contains(candidate.teamId)) return "active";
        }
        if (hasEnemy) return "victory";
        return "active";
    }

    private static boolean isDefeated(PlayerTeam team) {
        return team.isTeamVictory || team.isTeamDefeated || team.isTeamDefeatedTech || team.isTeamWipedOut;
    }

    private RwxLocalGameTypes.GameSnapshot snapshotGame(GameEngine engine, PlayerTeam controlled) {
        return new RwxLocalGameTypes.GameSnapshot(
                engine.currentTick,
                engine.gameTimeMillis,
                engine.isGamePaused() || engine.gameSpeed == 0.0f,
                engine.gameSpeed,
                engine.getCurrentMapName(),
                controlled.teamId,
                outcome(controlled),
                controlled.credits,
                netIncomeRate(controlled),
                controlled.getUnitCount(true, false),
                clockOverflowed
        );
    }

    private static void requireLocalTimeControl(GameEngine engine) {
        if (engine.networkEngine.networkGameActive) {
            throw new IllegalStateException("Pause and speed are available only for a local single-player game");
        }
    }

    private static double netIncomeRate(PlayerTeam team) {
        return team.b(Resource.D) - team.getResourceDrainRate(Resource.D);
    }

    private static String relation(PlayerTeam controlled, PlayerTeam candidate) {
        if (candidate == controlled) return "self";
        return controlled.c(candidate) ? "enemy" : "ally";
    }

    private static boolean isObservable(BaseUnit unit, PlayerTeam controlled) {
        return unit.team == controlled || unit.d(controlled);
    }

    private static Set<Integer> teamIdsWithUnits() {
        Set<Integer> ids = new HashSet<>();
        BaseUnit[] units = BaseUnit.bE.a();
        for (int index = 0; index < BaseUnit.bE.size(); index++) {
            BaseUnit unit = units[index];
            if (unit != null && !unit.isDead && unit.team != null) {
                ids.add(unit.team.teamId);
            }
        }
        return ids;
    }

    private static BaseUnit findLiveUnit(long unitId) {
        BaseUnit[] units = BaseUnit.bE.a();
        for (int index = 0; index < BaseUnit.bE.size(); index++) {
            BaseUnit unit = units[index];
            if (unit != null && !unit.isDead && unit.objectId == unitId) return unit;
        }
        throw new IllegalArgumentException("Unit not found: " + unitId);
    }

    private static boolean matchesOwner(BaseUnit unit, PlayerTeam controlled, RwxLocalGameTypes.UnitOwner owner) {
        return matchesOwnerRelation(relationOf(unit.team, controlled), owner);
    }

    /** Shared by the visible entity list and its event delta, so their default scope agrees. */
    static boolean matchesOwnerRelation(String relation, RwxLocalGameTypes.UnitOwner owner) {
        if (owner == null) return "self".equals(relation) || "enemy".equals(relation);
        switch (owner) {
            case SELF: return "self".equals(relation);
            case ALLY: return "ally".equals(relation);
            case ENEMY: return "enemy".equals(relation);
            case NEUTRAL: return "neutral".equals(relation);
            default: return false;
        }
    }

    /** True when a unit has nothing to do: no waypoint and no factory queue. */
    private static boolean isIdle(BaseUnit unit) {
        if (unit instanceof OrderableUnit && ((OrderableUnit) unit).getCurrentWaypoint() != null) {
            return false;
        }
        if (unit instanceof FactoryQueueInterface) {
            if (!((FactoryQueueInterface) unit).dy()) return false;
        }
        return true;
    }

    private static boolean withinRadius(BaseUnit unit, RwxLocalGameTypes.UnitQuery query) {
        if (query.radius == null) return true;
        if (query.x == null || query.y == null) {
            throw new IllegalArgumentException("Unit radius requires x and y");
        }
        float dx = unit.posX - query.x;
        float dy = unit.posY - query.y;
        return dx * dx + dy * dy <= query.radius * query.radius;
    }

    /** Applies the same owner/type/area scope to unit-list changes as to its snapshot rows. */

    private static RwxLocalGameTypes.UnitSnapshot snapshot(BaseUnit unit, PlayerTeam controlled) {
        OrderableUnit orderable = unit instanceof OrderableUnit ? (OrderableUnit) unit : null;
        // Orders, waypoints, targets and factory queues are private tactical state. Enemy unit
        // snapshots may expose only what a player can see on the map.
        boolean exposeCommandState = unit.team == controlled;
        UnitCommand waypoint = !exposeCommandState || orderable == null ? null : orderable.getCurrentWaypoint();
        BaseUnit target = !exposeCommandState ? null
                : waypoint == null ? unit.attackTargetUnit : waypoint.getTargetUnit();
        BaseUnit transport = null;
        if (exposeCommandState) {
            transport = unit.transportContainer != null ? unit.transportContainer
                    : orderable == null ? null : orderable.transportedBy;
        }
        String attackMode = !exposeCommandState || orderable == null || orderable.attackMode == null || !hasWeapon(orderable)
                ? null : orderable.attackMode.name();
        Integer queueSize = null;
        Float rallyX = null;
        Float rallyY = null;
        List<RwxLocalGameTypes.QueueItem> productionQueue = new ArrayList<>();
        if (exposeCommandState && unit instanceof FactoryQueueInterface) {
            FactoryQueueInterface queue = (FactoryQueueInterface) unit;
            queueSize = queue.dx() == null ? 0 : queue.dx().size();
            PointF rally = queue.getRallyPoint();
            if (rally != null) {
                rallyX = rally.x;
                rallyY = rally.y;
            }
            if (queue.dx() != null) {
                for (Object entry : queue.dx()) {
                    if (!(entry instanceof Projectile)) continue;
                    Projectile item = (Projectile) entry;
                    String command = "produce";
                    String typeId = item.unitType == null ? null : item.unitType.v();
                    String typeName = item.unitType == null ? null : item.unitType.getUnitTypeDescriptionShort();
                    AbstractUnitAction queuedAction = item.j == null || orderable == null
                            ? null : orderable.validateActionId(item.j);
                    if ((item.unitType == null || item.unitType.isBuildingUnit()
                            || (queuedAction != null && queuedAction.getActionDisplayType() == ActionDisplayType.upgrade))
                            && item.j != null) {
                        // Factory upgrades are queued as actions with no unitType; show their real
                        // action name instead of an empty "produce" row.
                        AbstractUnitAction action = queuedAction;
                        command = action == null ? "action"
                                : action.getActionDisplayType() == ActionDisplayType.upgrade
                                || (item.unitType != null && item.unitType.isBuildingUnit())
                                ? "upgrade" : action.getActionType().name();
                        typeId = item.j.getId();
                        typeName = action == null ? typeId : action.getDisplayName(unit);
                    }
                    productionQueue.add(new RwxLocalGameTypes.QueueItem(
                            command, typeId, typeName,
                            item.targetPoint == null ? null : item.targetPoint.x,
                            item.targetPoint == null ? null : item.targetPoint.y,
                            item.targetUnit == null ? null : item.targetUnit.objectId,
                            item.m < 0.0f ? null : Math.min(1.0f, item.m)));
                }
            }
        }
        List<RwxLocalGameTypes.QueueItem> waypointQueue = new ArrayList<>();
        if (exposeCommandState && orderable != null) {
            int count = Math.min(orderable.getWaypointCount(), 30);
            for (int index = 0; index < count; index++) {
                UnitCommand command = orderable.getWaypointAt(index);
                if (command == null) continue;
                UnitType buildType = command.getBuildUnitType();
                BaseUnit buildTarget = command.getTargetUnit();
                Float buildProgress = buildType != null && buildTarget != null
                        ? Float.valueOf(Math.min(1.0f, buildTarget.buildProgress)) : null;
                waypointQueue.add(new RwxLocalGameTypes.QueueItem(
                        command.getCommandType() == null ? "unknown" : command.getCommandType().name(),
                        buildType == null ? null : buildType.v(),
                        buildType == null ? null : buildType.getUnitTypeDescriptionShort(),
                        command.getTargetX(),
                        command.getTargetY(),
                        buildTarget == null ? null : buildTarget.objectId,
                        buildProgress));
            }
        }
        PlayerTeam team = unit.team;
        int tier = techTier(unit);
        RwxLocalGameTypes.UnitSnapshot result = new RwxLocalGameTypes.UnitSnapshot(
                unit.objectId,
                unit.r().v(),
                unit.r().getUnitTypeDescriptionShort(),
                team == null ? -1 : team.teamId,
                relationOf(team, controlled),
                unit.posX,
                unit.posY,
                unit.currentHealth,
                unit.maxHealth,
                unit.buildProgress,
                tier,
                Math.max(1, unit.getUpgradeLevel()),
                orderable != null && team == controlled && !unit.canNotBeGivenOrdersByPlayer(),
                unit.getMovementType().name(),
                waypoint == null ? null : waypoint.getCommandType().name(),
                target != null && isObservable(target, controlled) ? target.objectId : null,
                transport != null && isObservable(transport, controlled) ? transport.objectId : null,
                waypoint == null ? null : Float.valueOf(waypoint.getTargetX()),
                waypoint == null ? null : Float.valueOf(waypoint.getTargetY()),
                attackMode,
                queueSize,
                rallyX,
                rallyY,
                productionQueue,
                waypointQueue,
                GameEngine.getInstance().currentTick
        );
        result.building = unit.r().isBuildingUnit();
        if (orderable != null) {
            result.idle = orderable.getCurrentWaypoint() == null && orderable.attackTarget == null
                    && unit.attackTargetUnit == null
                    && (!(unit instanceof FactoryQueueInterface)
                        || ((FactoryQueueInterface) unit).dx() == null
                        || ((FactoryQueueInterface) unit).dx().isEmpty());
        }
        if (exposeCommandState && orderable != null) {
            List<String> statuses = new ArrayList<>();
            if (unit.buildProgress < 1.0f) statuses.add("constructing");
            if (waypoint != null) {
                String command = waypoint.getCommandType().name();
                BaseUnit repairTarget = waypoint.getTargetUnit();
                if ("repair".equals(command)) {
                    statuses.add(repairTarget != null && repairTarget.buildProgress < 1.0f
                            ? "constructing" : "repairing");
                } else {
                    statuses.add(activityLabel(command));
                }
            }
            for (RwxLocalGameTypes.QueueItem item : productionQueue.subList(0, Math.min(1, productionQueue.size()))) {
                String status = "produce".equals(item.command) ? "producing"
                        : "upgrade".equals(item.command) ? "upgrading" : "executing";
                if (!statuses.contains(status)) statuses.add(status);
            }
            int now = GameEngine.getInstance().gameTimeMillis;
            if (orderable.lastWeaponFireMillis >= 0 && now >= orderable.lastWeaponFireMillis
                    && now - orderable.lastWeaponFireMillis <= 1000) statuses.add("attacking");
            if (orderable.lastInterceptionMillis >= 0 && now >= orderable.lastInterceptionMillis
                    && now - orderable.lastInterceptionMillis <= 1000) statuses.add("defending");
            result.statuses = java.util.Collections.unmodifiableList(new ArrayList<>(new java.util.LinkedHashSet<>(statuses)));
            if (RwxStatusReports.isBuilding(result)) {
                for (AbstractUnitAction action : unit.getAvailableActions()) {
                    UnitType produced = action.getUnitType();
                    boolean produces = produced != null && !produced.isBuildingUnit()
                            && action.getActionDisplayType() != ActionDisplayType.upgrade
                            && action.getActionType() == ActionType.popupQueue;
                    boolean upgrades = action.getActionDisplayType() == ActionDisplayType.upgrade
                            || (produced != null && produced.isBuildingUnit()
                                && action.getActionType() == ActionType.popupQueue);
                    if (produces) result.productionCapable = true;
                    boolean available = unit.buildProgress >= 1.0f && result.controllable
                            && action.b(unit) && action.canAfford(unit, false)
                            && !action.isNotAvailable(unit)
                            && com.corrodinggames.rts.game.units.g.SpecialActionBlockEffect.a(unit, action.getActionId()) <= 0;
                    if (available && produces) result.canProduce = true;
                    if (available && upgrades && (!(unit instanceof FactoryQueueInterface)
                            || ((FactoryQueueInterface) unit).a(action.getActionId(), true) == 0)) result.canUpgrade = true;
                }
            }
        }
        return result;
    }

    private static String activityLabel(String command) {
        switch (command) {
            case "move": return "moving";
            case "attackMove": return "advancing";
            case "attack": return "engaging";
            case "build": return "constructing";
            case "patrol": return "patrolling";
            case "guard": case "guardAt": case "guardUnit": return "guarding";
            case "follow": return "following";
            case "reclaim": return "reclaiming";
            case "loadInto": case "loadUp": return "loading";
            default: return command;
        }
    }

    private static String relationOf(PlayerTeam team, PlayerTeam controlled) {
        // 中立单位（树等）挂的是 teamId < 0 的系统队伍（TEAM_ALL/TEAM_UNKNOWN），不是 null。
        if (team == null || team.teamId < 0) return "neutral";
        if (team == controlled) return "self";
        return controlled.c(team) ? "enemy" : "ally";
    }

    /**
     * One "tech tier" for every display path.
     *
     * <p>Custom units carry the tier in their config ({@code UnitType.g()}), while
     * {@code CustomUnit.getTechLevel()} returns the <em>turret slot count</em> — that is why an
     * upgraded T2 anti-air turret used to be announced as "T1 -> T3" and a T2 factory as "T1".
     * Native T2/T3 classes override {@code getTechLevel()} instead and return 1 from {@code g()},
     * so they still need the engine value.</p>
     */
    private static int techTier(BaseUnit unit) {
        try {
            if (unit.r() instanceof CustomUnitConfig) return Math.max(1, unit.r().g());
            // Native factories report their tier through getUpgradeLevel() (1 -> 2), turrets
            // through getTechLevel() (GunTurretT2 -> 2).
            return Math.max(1, Math.max(unit.getTechLevel(), unit.getUpgradeLevel()));
        } catch (RuntimeException error) {
            return 1;
        }
    }

    /**
     * Constructs the same native command object that the game UI creates for selected units.
     * CommandController owns queueing, formation assignment, pathing and network serialization;
     * this adapter validates the caller's visible unit references and fills the command.
     */
    private RwxLocalGameTypes.CommandReceipt issueOnGameThread(RwxLocalGameTypes.Order order) {
        return issueOnGameThread(order, OrderOrigin.MANUAL);
    }

    private enum OrderOrigin { MANUAL, PENDING, KEEP }

    private RwxLocalGameTypes.CommandReceipt issueOnGameThread(RwxLocalGameTypes.Order order,
                                                               OrderOrigin origin) {
        GameEngine engine = GameEngine.getInstance();
        requireActiveGame(engine);
        PlayerTeam controlled = requireControlledTeam(engine);
        if (controlled.isTeamDefeated || controlled.isTeamDefeatedTech || controlled.isTeamWipedOut) {
            throw new IllegalStateException("The local team was defeated; orders are refused");
        }

        // Attack stance is pure per-unit state, not a waypoint. Apply it immediately (so a
        // paused game shows the new stance right away) and never report a fake "replaced order".
        if (order.type == RwxLocalGameTypes.OrderType.ATTACK_MODE) {
            return applyAttackMode(engine, controlled, order);
        }

        List<Long> skippedUnitIds = new ArrayList<>();
        List<OrderableUnit> selected = requireControllableUnits(
                resolveOrderUnitIds(order), controlled, skippedUnitIds);
        String skippedNote = skippedUnitIds.isEmpty() ? ""
                : " (skipped dead/missing units: " + RwxCliText.joinIds(skippedUnitIds) + ")";
        BaseUnit target = null;
        if (order.targetUnitId != null) {
            try {
                target = findLiveUnit(order.targetUnitId);
            } catch (IllegalArgumentException missing) {
                // Dead and fogged targets are indistinguishable to the client by design. Use one
                // message so a caller cannot probe enemy positions by reading the error text.
                throw new IllegalArgumentException(
                        "Target is not currently observable (dead or hidden): " + order.targetUnitId);
            }
            if (!isObservable(target, controlled)) {
                throw new IllegalArgumentException(
                        "Target is not currently observable (dead or hidden): " + order.targetUnitId);
            }
        }
        if (origin == OrderOrigin.MANUAL && order.type == RwxLocalGameTypes.OrderType.ACTION) {
            // 未执行的自动补产不能先占住手动动作需要的资金。
            cancelKeepCommands(engine, idsOf(selected), null);
        }
        try {
            validateOrder(engine, order, selected, target, controlled);
        } catch (IllegalArgumentException rejected) {
            // 升级/生产类动作在 validateOrder 里就会因资金不足抛错，早于下面的
            // reserveActionCredits；这里必须走同一条待发路径，否则
            // --allow-unaffordable 对升级完全不生效（order-build 走 reserve，所以正常）。
            PendingKind hold = pendingKindForFailure(order, rejected);
            if (hold != null) {
                return holdPendingOrder(engine, order, selected, rejected.getMessage(), hold);
            }
            throw rejected;
        }
        long reservedActionCost;
        try {
            reservedActionCost = reserveActionCredits(engine, order, selected, controlled);
        } catch (IllegalArgumentException shortage) {
            PendingKind hold = pendingKindForFailure(order, shortage);
            if (hold != null) {
                return holdPendingOrder(engine, order, selected, shortage.getMessage(), hold);
            }
            throw shortage;
        }

        // 非 --queue 命令会清空这些单位已有的 waypoint；提前统计，回执里明说，
        // 否则连续两条不带 --queue 的建造/移动命令会出现“后一条悄悄顶掉前一条”。
        int replacedOrders = 0;
        if (!order.queued && clearsExistingOrders(order.type)) {
            for (OrderableUnit unit : selected) {
                if (unit.getCurrentWaypoint() != null) replacedOrders++;
            }
        }
        String replaceNote = replacedOrders == 0 ? ""
                : " (replaced the previous order on " + replacedOrders
                + (replacedOrders == 1 ? " unit" : " units") + "; add --queue to append)";

        Command command = engine.commandController.createCommandForTeam(controlled);
        String acceptedMessage;
        try {
            if (engine.networkEngine.networkGameActive) command.sourceTeam = controlled;
            command.isQueued = order.queued;
            command.isHighPriority = order.type == RwxLocalGameTypes.OrderType.MOVE
                    || order.type == RwxLocalGameTypes.OrderType.ATTACK_MOVE;
            for (OrderableUnit unit : selected) command.addUnitToCommand(unit);

            switch (order.type) {
                case MOVE:
                    command.setMoveTarget(requireCoordinate(order.x, "--x"), requireCoordinate(order.y, "--y"));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case ATTACK_MOVE:
                    command.setAttackMoveTarget(requireCoordinate(order.x, "--x"), requireCoordinate(order.y, "--y"));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case ATTACK:
                    command.setAttackTarget(requireTarget(target));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case STOP:
                    if (order.queued) throw new IllegalArgumentException("--queue is not supported for stop");
                    command.setClearExistingOrders();
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case PATROL:
                    command.setPatrolTarget(requireCoordinate(order.x, "--x"), requireCoordinate(order.y, "--y"));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case GUARD:
                    command.setGuardTarget(requireTarget(target));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case GUARD_AT:
                    command.unitCommand = new UnitCommand();
                    command.unitCommand.setGuardAt(
                            requireCoordinate(order.x, "--x"), requireCoordinate(order.y, "--y"));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case FOLLOW:
                    command.unitCommand = new UnitCommand();
                    command.unitCommand.setFollowTargetUnit(requireTarget(target));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case REPAIR:
                    command.setRepairTarget(requireTarget(target));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case RECLAIM:
                    command.setReclaimTarget(requireTarget(target));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case LOAD_INTO:
                    command.setLoadIntoTarget(requireTarget(target));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case LOAD_UP:
                    command.setLoadUpTarget(requireTarget(target));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case BUILD: {
                    UnitType buildType = requireUnitType(order.unitTypeId);
                    float buildX = requireCoordinate(order.x, "--x");
                    float buildY = requireCoordinate(order.y, "--y");
                    float[] snapped = snapBuildingPosition(buildType, buildX, buildY);
                    String autoNote = "";
                    // 提前复用原版放置校验，避免把明显不可放的点位排进队列后静默失败。
                    if (!canPlaceBuildingAt(buildType, buildX, buildY, controlled)) {
                        if (!order.autoPlace) {
                            throw new IllegalArgumentException(
                                    "Cannot place " + buildType.v() + ": "
                                            + placementFailureReason(buildType, snapped, controlled)
                                            + "；可先用 " + RwxCliCommands.Command.MAP_PLACE.commandName()
                                            + " 查找最近的可放置点");
                        }
                        int radius = Math.max(1, Math.min(order.placementRadius, 10));
                        List<RwxLocalGameTypes.PlacementSuggestion> suggestions =
                                findNearestPlacements(buildType, buildX, buildY, controlled, radius,
                                        snapped[0], snapped[1], null);
                        if (suggestions.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "Cannot place " + buildType.v() + " anywhere within " + radius
                                            + " tiles of " + (int) buildX + "," + (int) buildY + ": "
                                            + placementFailureReason(buildType, snapped, controlled));
                        }
                        RwxLocalGameTypes.PlacementSuggestion best = suggestions.get(0);
                        autoNote = " (auto-placed at " + (int) best.x + "," + (int) best.y
                                + ", " + (int) best.distance + " world from the requested spot)";
                        buildX = best.x;
                        buildY = best.y;
                        snapped = new float[] {best.snappedX, best.snappedY};
                    }
                    // 原版接受任何可放置点；若工程车走不到（隔河、另一座岛），它会直接放弃并闲置，
                    // 回执却是 accepted。这里先用静态连通域判断，全部走不到就拒绝。
                    List<Long> unreachableBuilders = new ArrayList<>();
                    for (OrderableUnit builder : selected) {
                        if (!canReachBuildSite(builder, buildType, snapped[0], snapped[1])) {
                            unreachableBuilders.add(builder.objectId);
                        }
                    }
                    if (!selected.isEmpty() && unreachableBuilders.size() == selected.size()) {
                        throw new IllegalArgumentException(
                                "Builder cannot reach " + buildType.v() + " at " + (int) snapped[0] + ","
                                        + (int) snapped[1] + " (different terrain region; it would go idle)"
                                        + "；用 " + RwxCliCommands.Command.MAP_REACH.commandName()
                                        + " 检查路线，或换一个同岸/同岛的点位、用运输/空中建造者");
                    }
                    if (!unreachableBuilders.isEmpty()) {
                        autoNote += " (cannot reach the site, will idle: "
                                + RwxCliText.joinIds(unreachableBuilders) + ")";
                    }
                    int buildCount = Math.max(1, Math.min(order.repeatCount, 20));
                    command.setBuildTarget(snapped[0], snapped[1], buildType, buildCount);
                    // 资金校验放在 reserveActionCredits（与动作共用同一份预留），这样同一暂停批里
                    // 先升级、后建造时，建造看到的是“可用资金”而不是账面资金。
                    long buildCost = (long) buildType.c() * buildCount;
                    acceptedMessage = "accepted build of " + buildType.v()
                            + " at " + (int) snapped[0] + "," + (int) snapped[1]
                            + (buildCount > 1 ? " x" + buildCount : "")
                            + " (cost " + buildCost + ", available " + availableCredits(engine, controlled)
                            + autoNote + ")";
                    break;
                }
                case RALLY:
                    command.setRallyPoint(new PointF(
                            requireCoordinate(order.x, "--x"), requireCoordinate(order.y, "--y")));
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                case ACTION:
                    command.setActionTarget(
                            ActionId.intern(requireActionId(order.actionId)),
                            order.x == null ? null : new PointF(
                                    requireCoordinate(order.x, "--x"), requireCoordinate(order.y, "--y")),
                            target);
                    acceptedMessage = "accepted " + describeOrderType(order.type);
                    break;
                default:
                    throw notImplemented("order-" + order.type.name().toLowerCase().replace('_', '-'));
            }

            boolean localIdle = canApplyImmediately(engine);
            if (origin != OrderOrigin.KEEP) {
                yieldAutomation(engine, selected, order.queued || !isWaypointOrder(order.type),
                        localIdle, command);
                if (origin == OrderOrigin.MANUAL && isWaypointOrder(order.type) && !order.queued) {
                    discardPendingForUnits(idsOf(selected), null, false);
                }
                if (order.type == RwxLocalGameTypes.OrderType.ACTION) suspendKeep(engine, selected);
            }
            String pausedNote;
            boolean appliedImmediately = false;
            if (localIdle) {
                // The desktop loop stops draining commandController.pendingCommands while paused,
                // so an accepted command would only appear after "pause false". Applying it now
                // keeps paused planning honest and makes unit-show reflect the order at once.
                applyCommandNow(engine, command);
                // The engine has already charged controlled.credits; keeping the reservation would
                // count the cost twice for every later order while the tick stays frozen.
                reservedActionCredits -= reservedActionCost;
                reservedActionCost = 0L;
                appliedImmediately = true;
                pausedNote = " (applied immediately; the simulation is paused)";
            } else {
                if (origin == OrderOrigin.KEEP) {
                    keepCommands.put(command, new KeepSubmission(selected.get(0).objectId,
                            reservedActionCost, engine.currentTick));
                }
                pausedNote = queuedNote(engine);
            }
            if (order.type == RwxLocalGameTypes.OrderType.ACTION) {
                // Recorded only after the command applied cleanly, so a rejected launch is not
                // attributed later as if it had fired.
                recordNuclearLaunches(engine, selected, order);
            }
            return new RwxLocalGameTypes.CommandReceipt(
                    engine.currentTick, idsOf(selected),
                    acceptedMessage + replaceNote + pausedNote + skippedNote
                            + (reservedActionCost == 0 ? "" : " (reserved credits: " + reservedActionCost + ")"),
                    appliedImmediately);
        } catch (RuntimeException error) {
            // A half-built command must never stay queued and execute later.
            engine.commandController.pendingCommands.remove(command);
            reservedActionCredits -= reservedActionCost;
            throw error;
        }
    }

    /** Why an order is held: waiting for credits, or for a factory upgrade to finish. */
    enum PendingKind {
        CREDITS("等待资金"),
        UPGRADE("等待升级完成");

        final String label;

        PendingKind(String label) {
            this.label = label;
        }
    }

    /**
     * The unit is mid-upgrade, so the requested production action (or the next tier's actions)
     * does not exist yet. It is temporary: {@code --after-upgrade} holds the order until it does.
     */
    static final class UpgradeInProgressException extends IllegalArgumentException {
        UpgradeInProgressException(String message) {
            super(message);
        }
    }

    static boolean isCreditShortage(Throwable error) {
        return RwxLocalGameTypes.isCreditShortage(error);
    }

    /**
     * 这次下单失败是该直接报错，还是转入待发队列。
     *
     * <p>{@code validateOrder} 与 {@code reserveActionCredits} 都可能抛出“资金不足”，
     * 两处必须用同一套规则，否则 {@code --allow-unaffordable} 会在其中一条路径上失效
     * （升级动作正是只在 validate 阶段扣款检查）。</p>
     *
     * @return 转入待发时的等待类型；{@code null} 表示照原样抛错
     */
    static PendingKind pendingKindForFailure(RwxLocalGameTypes.Order order,
                                             IllegalArgumentException failure) {
        if (order == null || failure == null) return null;
        if (failure instanceof UpgradeInProgressException) {
            return order.afterUpgrade ? PendingKind.UPGRADE : null;
        }
        if ((order.allowUnaffordable || order.afterUpgrade) && isCreditShortage(failure)) {
            return order.afterUpgrade ? PendingKind.UPGRADE : PendingKind.CREDITS;
        }
        return null;
    }

    /**
     * {@code "升级至 T2 42%"} for the upgrade this unit is currently working on, or null.
     *
     * <p>Upgrade actions are {@link ActionDisplayType#upgrade}; during the upgrade the factory
     * keeps the previous tier's action list, which is why queuing a T2 unit then fails with
     * "no such action" instead of something actionable.</p>
     */
    private static String queuedUpgradeLabel(BaseUnit unit, OrderableUnit orderable) {
        if (unit == null || orderable == null) return null;
        for (AbstractUnitAction action : unit.getAvailableActions()) {
            if (action.getActionDisplayType() != ActionDisplayType.upgrade) continue;
            int queued = unit instanceof FactoryQueueInterface
                    ? ((FactoryQueueInterface) unit).a(action.getActionId(), true) : 0;
            if (queued <= 0) continue;
            float progress = action.getProgress(unit);
            String name = action.getDisplayName(unit);
            if (name == null || name.trim().isEmpty()) name = action.getActionIdString();
            return name + (progress < 0.0f ? "" : " " + Math.round(Math.min(1.0f, progress) * 100.0f) + "%");
        }
        return null;
    }

    /** A few action ids the unit offers right now, to save an {@code actions} round trip. */
    private static String availableActionIds(BaseUnit unit, int limit) {
        StringBuilder ids = new StringBuilder();
        int shown = 0;
        for (AbstractUnitAction action : unit.getAvailableActions()) {
            if (shown >= limit) break;
            if (ids.length() > 0) ids.append(',');
            ids.append(action.getActionIdString());
            shown++;
        }
        return ids.length() == 0 ? "none" : ids.toString();
    }

    /** One order held by the CLI until it can be issued. Stored without the retry flags. */
    private static final class PendingAction {
        final long id;
        RwxLocalGameTypes.Order order;
        final int createdTick;
        final int createdTimeMillis;
        final PendingKind kind;
        String reason;

        PendingAction(long id, RwxLocalGameTypes.Order order, int createdTick,
                      int createdTimeMillis, String reason, PendingKind kind) {
            this.id = id;
            this.createdTick = createdTick;
            this.createdTimeMillis = createdTimeMillis;
            this.reason = reason;
            this.kind = kind;
            // Retry copies must not re-enter the hold path, or a failing retry would create a
            // duplicate pending item on every sampler pass.
            this.order = order.withRetryFlags(false, false);
        }
    }

    private RwxLocalGameTypes.CommandReceipt holdPendingOrder(GameEngine engine,
                                                              RwxLocalGameTypes.Order order,
                                                              List<OrderableUnit> selected,
                                                              String reason, PendingKind kind) {
        if (pendingActions.size() >= MAX_PENDING_ORDERS) {
            throw new IllegalArgumentException("Too many pending orders (" + MAX_PENDING_ORDERS
                    + "); wait for credits or drop them with "
                    + RwxCliCommands.Command.PENDING_CLEAR.commandName());
        }
        yieldAutomation(engine, selected, true, canApplyImmediately(engine), null);
        if (isWaypointOrder(order.type) && !order.queued) {
            discardPendingForUnits(idsOf(selected), null, false);
        }
        if (order.type == RwxLocalGameTypes.OrderType.ACTION) suspendKeep(engine, selected);
        long id = nextPendingId++;
        pendingActions.put(id, new PendingAction(id, order.withUnitIds(idsOf(selected)), engine.currentTick,
                engine.gameTimeMillis, reason, kind));
        notePending("新建 #" + id + " " + describeOrderType(order.type)
                + (order.actionId == null ? "" : " " + order.actionId) + "：" + kind.label);
        return new RwxLocalGameTypes.CommandReceipt(engine.currentTick, idsOf(selected),
                "held as pending #" + id + " (" + kind.label + "：" + reason + "). The CLI re-issues"
                        + " it automatically; list with "
                        + RwxCliCommands.Command.PENDING_LIST.commandName()
                        + ", cancel with " + RwxCliCommands.Command.PENDING_CLEAR.commandName(),
                false, RwxLocalGameTypes.ReceiptStatus.PENDING);
    }

    /** Re-issues held orders that have become issuable. Runs on the game thread from the sampler. */
    private void processPendingOrders(GameEngine engine, PlayerTeam controlled) {
        if (pendingActions.isEmpty()) return;
        // Never inject commands while a replay is playing back: they are not part of the
        // recorded stream and would desync the playback.
        if (engine.replayEngine != null && engine.replayEngine.j()) return;
        List<Long> finished = new ArrayList<>();
        for (PendingAction pending : new ArrayList<>(pendingActions.values())) {
            if (engine.gameTimeMillis - pending.createdTimeMillis > PENDING_RETENTION_MILLIS) {
                finished.add(pending.id);
                notePending("丢弃 #" + pending.id + "：超过 10 分钟仍无法发出（" + pending.kind.label + "）");
                continue;
            }
            try {
                issueOnGameThread(pending.order, OrderOrigin.PENDING);
                finished.add(pending.id);
                notePending("已发出 #" + pending.id + " "
                        + describeOrderType(pending.order.type)
                        + (pending.order.actionId == null ? "" : " " + pending.order.actionId));
            } catch (IllegalArgumentException failure) {
                String reason = failure.getMessage();
                if (isCreditShortage(failure)) {
                    pending.reason = reason;
                    continue;
                }
                if (pending.kind == PendingKind.UPGRADE && failure instanceof UpgradeInProgressException) {
                    // Still upgrading: keep waiting, refreshed with the current progress.
                    pending.reason = reason;
                    continue;
                }
                // The unit died, the action disappeared, the factory was captured, ...
                finished.add(pending.id);
                notePending("丢弃 #" + pending.id + "：" + reason);
            } catch (RuntimeException other) {
                pending.reason = String.valueOf(other.getMessage());
            }
        }
        for (Long id : finished) pendingActions.remove(id);
    }

    private void notePending(String note) {
        pendingRecent.addFirst(note);
        while (pendingRecent.size() > 20) pendingRecent.removeLast();
    }

    private List<String> pendingRecentSnapshot() {
        return new ArrayList<>(pendingRecent);
    }

    @Override
    public RwxLocalGameTypes.PendingPage pending() {
        return onGameThread(() -> pendingPage(GameEngine.getInstance()));
    }

    private RwxLocalGameTypes.PendingPage pendingPage(GameEngine engine) {
        List<RwxLocalGameTypes.PendingOrderSnapshot> rows = new ArrayList<>();
        for (PendingAction pending : pendingActions.values()) {
            rows.add(new RwxLocalGameTypes.PendingOrderSnapshot(
                    pending.id, pending.createdTick, pending.createdTimeMillis,
                    pending.order.type.name().toLowerCase(Locale.ROOT),
                    pending.order.actionId, pending.order.unitIds, pending.order.x,
                    pending.order.y, pending.order.targetUnitId, pending.reason,
                    pending.kind.name().toLowerCase(Locale.ROOT)));
        }
        int tick = engine == null ? -1 : engine.currentTick;
        int time = engine == null ? -1 : engine.gameTimeMillis;
        return new RwxLocalGameTypes.PendingPage(tick, time, rows, pendingRecentSnapshot(),
                "pending orders: " + rows.size());
    }

    @Override
    public RwxLocalGameTypes.PendingPage clearPending(Long id) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            if (id == null) {
                int removed = pendingActions.size();
                pendingActions.clear();
                notePending("清空全部待发（" + removed + " 项）");
            } else if (pendingActions.remove(id) == null) {
                throw new IllegalArgumentException("No pending order #" + id);
            } else {
                notePending("手动取消 #" + id);
            }
            return pendingPage(engine);
        });
    }

    /** One "keep this factory producing N of an action" rule. */
    private static final class KeepRule {
        final long id;
        final long unitId;
        final String actionId;
        final int count;
        int issued;
        int queued;
        String state = "排队中";
        int lastAttemptTimeMillis = Integer.MIN_VALUE;
        int failures;
        boolean suspendedByManualOrder;

        KeepRule(long id, long unitId, String actionId, int count) {
            this.id = id;
            this.unitId = unitId;
            this.actionId = actionId;
            this.count = count;
        }
    }

    @Override
    public RwxLocalGameTypes.KeepPage keep(long unitId, String actionId, int count) {
        if (actionId == null || actionId.trim().isEmpty()) {
            throw new IllegalArgumentException("--action is required");
        }
        if (count < 1 || count > 20) {
            throw new IllegalArgumentException("--count must be an integer from 1 to 20");
        }
        String normalized = actionId.trim();
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            BaseUnit unit = findLiveUnit(unitId);
            if (!(unit instanceof OrderableUnit) || unit.team != controlled) {
                throw new IllegalArgumentException("Unit " + unitId + " is not a controllable unit");
            }
            OrderableUnit orderable = (OrderableUnit) unit;
            AbstractUnitAction action = orderable.validateActionId(ActionId.intern(normalized));
            if (action == null) {
                String upgrade = queuedUpgradeLabel(unit, orderable);
                throw new IllegalArgumentException("Unit " + unitId + " (" + unit.r().v()
                        + ") has no action '" + normalized + "' right now"
                        + (upgrade == null ? "" : "（正在升级：" + upgrade + "，升级完成后再建规则）")
                        + "; available now: " + availableActionIds(unit, 6));
            }
            if (action.getUnitType() == null || action.getUnitType().isBuildingUnit()) {
                throw new IllegalArgumentException("Action '" + normalized
                        + "' does not produce a mobile unit; keep only maintains production actions");
            }
            KeepRule existing = null;
            for (KeepRule rule : keepRules.values()) {
                if (rule.unitId == unitId && rule.actionId.equals(normalized)) existing = rule;
            }
            if (existing != null) {
                cancelKeepCommands(engine, Collections.singletonList(unitId), normalized);
                keepRules.remove(existing.id);
                noteKeep("#" + existing.id + " 更新为 x" + count);
            }
            if (keepRules.size() >= MAX_KEEP_RULES) {
                throw new IllegalArgumentException("Too many keep rules (" + MAX_KEEP_RULES
                        + "); remove one with " + RwxCliCommands.Command.KEEP_CLEAR.commandName());
            }
            KeepRule rule = new KeepRule(nextKeepRuleId++, unitId, normalized, count);
            keepRules.put(rule.id, rule);
            noteKeep("新建 #" + rule.id + " 单位 " + unitId + " 保持 " + normalized + " x" + count);
            return keepPage(engine);
        });
    }

    @Override
    public RwxLocalGameTypes.KeepPage keeps() {
        return onGameThread(() -> keepPage(GameEngine.getInstance()));
    }

    @Override
    public RwxLocalGameTypes.KeepPage clearKeep(Long id) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            if (id == null) {
                int removed = keepRules.size();
                List<Long> units = new ArrayList<>();
                for (KeepRule rule : keepRules.values()) units.add(rule.unitId);
                cancelKeepCommands(engine, units, null);
                keepRules.clear();
                noteKeep("清空全部维持规则（" + removed + " 项）");
            } else if (!keepRules.containsKey(id)) {
                throw new IllegalArgumentException("No keep rule #" + id);
            } else {
                KeepRule rule = keepRules.remove(id);
                cancelKeepCommands(engine, Collections.singletonList(rule.unitId), rule.actionId);
                noteKeep("手动取消 #" + id);
            }
            return keepPage(engine);
        });
    }

    private RwxLocalGameTypes.KeepPage keepPage(GameEngine engine) {
        List<RwxLocalGameTypes.KeepRuleSnapshot> rows = new ArrayList<>();
        for (KeepRule rule : keepRules.values()) {
            rows.add(new RwxLocalGameTypes.KeepRuleSnapshot(rule.id, rule.unitId, rule.actionId,
                    rule.count, rule.issued, rule.queued, rule.state));
        }
        int tick = engine == null ? -1 : engine.currentTick;
        int time = engine == null ? -1 : engine.gameTimeMillis;
        return new RwxLocalGameTypes.KeepPage(tick, time, rows, new ArrayList<>(keepRecent),
                "keep rules: " + rows.size());
    }

    private void noteKeep(String note) {
        keepRecent.addFirst(note);
        while (keepRecent.size() > 20) keepRecent.removeLast();
    }

    /**
     * Tops up every maintain rule. Runs on the game thread from the sampler, so a rule survives
     * across commands and the factory never sits idle waiting for the next {@code order-action}.
     *
     * <p>One issue per rule per pass, and only when the action is currently unlocked and
     * affordable: credits spent here are real credits, so a rule must never queue more than it can
     * pay for. A factory that is upgrading keeps its rule (waiting) instead of dropping it.</p>
     */
    private void processKeepRules(GameEngine engine, PlayerTeam controlled) {
        if (keepRules.isEmpty()) return;
        if (engine.replayEngine != null && engine.replayEngine.j()) return;
        keepCommands.keySet().removeIf(command -> !engine.commandController.pendingCommands.contains(command)
                && !engine.commandController.queuedCommands.contains(command));
        List<Long> finished = new ArrayList<>();
        for (KeepRule rule : new ArrayList<>(keepRules.values())) {
            BaseUnit unit;
            try {
                unit = findLiveUnit(rule.unitId);
            } catch (IllegalArgumentException gone) {
                unit = null;
            }
            if (unit == null || unit.isDead) {
                finished.add(rule.id);
                noteKeep("丢弃 #" + rule.id + "：单位 " + rule.unitId + " 已不存在");
                continue;
            }
            if (unit.team != controlled) {
                finished.add(rule.id);
                noteKeep("丢弃 #" + rule.id + "：单位 " + rule.unitId + " 已不属于我方");
                continue;
            }
            OrderableUnit orderable = unit instanceof OrderableUnit ? (OrderableUnit) unit : null;
            if (orderable == null || !(unit instanceof FactoryQueueInterface)) {
                finished.add(rule.id);
                noteKeep("丢弃 #" + rule.id + "：单位 " + rule.unitId + " 不是工厂");
                continue;
            }
            if (rule.suspendedByManualOrder) {
                rule.state = "手动命令优先，已暂停；keep-set 重新授权";
                continue;
            }
            String upgrade = queuedUpgradeLabel(unit, orderable);
            if (upgrade != null) {
                rule.state = "等待升级（" + upgrade + "）";
                continue;
            }
            AbstractUnitAction action = orderable.validateActionId(ActionId.intern(rule.actionId));
            if (action == null) {
                rule.failures++;
                rule.state = "动作已不可用";
                if (rule.failures >= KEEP_MAX_FAILURES) {
                    finished.add(rule.id);
                    noteKeep("丢弃 #" + rule.id + "：动作 " + rule.actionId + " 不再可用");
                }
                continue;
            }
            rule.queued = ((FactoryQueueInterface) unit).a(action.getActionId(), true);
            if (rule.queued >= rule.count) {
                rule.state = "已满（" + rule.queued + "/" + rule.count + "）";
                rule.failures = 0;
                continue;
            }
            if (rule.lastAttemptTimeMillis != Integer.MIN_VALUE
                    && engine.gameTimeMillis - rule.lastAttemptTimeMillis < KEEP_RETRY_MILLIS) {
                continue;
            }
            if (!action.b(unit)) {
                rule.state = "动作暂不可用（" + rule.queued + "/" + rule.count + "）";
                continue;
            }
            if (!action.canAfford(unit, false)) {
                rule.state = "等资金（" + rule.queued + "/" + rule.count + "）";
                continue;
            }
            rule.lastAttemptTimeMillis = engine.gameTimeMillis;
            try {
                issueOnGameThread(new RwxLocalGameTypes.Order(RwxLocalGameTypes.OrderType.ACTION,
                        Collections.singletonList(rule.unitId), null, null, null, rule.actionId,
                        null, null, null, false, false), OrderOrigin.KEEP);
                rule.issued++;
                rule.failures = 0;
                rule.state = "已补到 " + (rule.queued + 1) + "/" + rule.count;
                noteKeep("#" + rule.id + " 单位 " + rule.unitId + " 补充 " + rule.actionId);
            } catch (IllegalArgumentException failure) {
                if (isCreditShortage(failure)) {
                    rule.state = "等资金（" + rule.queued + "/" + rule.count + "）";
                    continue;
                }
                rule.failures++;
                rule.state = "失败：" + failure.getMessage();
                if (rule.failures >= KEEP_MAX_FAILURES) {
                    finished.add(rule.id);
                    noteKeep("丢弃 #" + rule.id + "：" + failure.getMessage());
                }
            }
        }
        for (Long id : finished) keepRules.remove(id);
    }

    @Override
    public RwxLocalGameTypes.AutoPage autoRules() {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            sample(engine, controlled);
            return autoPage(automate.list(engine, controlled));
        });
    }

    @Override
    public RwxLocalGameTypes.AutoPage autoSet(RwxLocalGameTypes.AutoRuleOptions options) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            if (options == null) throw new IllegalArgumentException("auto-set requires --rule");
            AiAutomateRuleId rule = AiAutomateRuleId.byId(options.rule);
            if (rule == null) {
                throw new IllegalArgumentException("Unknown --rule '" + options.rule
                        + "'; use one of: " + AiAutomateRuleId.listIds());
            }
            AiAutomateRuleOptions patch = new AiAutomateRuleOptions();
            patch.rule = rule;
            patch.enabled = options.enabled;
            patch.units = options.units;
            patch.group = options.group;
            patch.excludedIds = options.excludedIds;
            patch.includeTypes = options.includeTypes;
            patch.excludeTypes = options.excludeTypes;
            patch.hpPercent = options.hpPercent;
            patch.idleOnly = options.idleOnly;
            patch.excludeBuilders = options.excludeBuilders;
            patch.includeUnits = options.includeUnits;
            patch.x = options.x;
            patch.y = options.y;
            patch.radius = options.radius;
            sample(engine, controlled);
            return autoPage(automate.set(engine, controlled, patch));
        });
    }

    @Override
    public RwxLocalGameTypes.AutoPage autoShow(String rule) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            sample(engine, controlled);
            return autoPage(automate.show(engine, controlled, requireAutomationRule(rule)));
        });
    }

    @Override
    public RwxLocalGameTypes.AutoPage autoClear(String rule) {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            AiAutomateRuleId id = requireAutomationRule(rule);
            sample(engine, controlled);
            return autoPage(automate.clear(engine, controlled, id));
        });
    }

    @Override
    public RwxLocalGameTypes.AutoPage autoReset() {
        return onGameThread(() -> {
            GameEngine engine = GameEngine.getInstance();
            requireActiveGame(engine);
            PlayerTeam controlled = requireControlledTeam(engine);
            sample(engine, controlled);
            return autoPage(automate.reset(engine, controlled));
        });
    }

    private static AiAutomateRuleId requireAutomationRule(String text) {
        AiAutomateRuleId rule = AiAutomateRuleId.byId(text);
        if (rule == null) {
            throw new IllegalArgumentException("Unknown --rule '" + text
                    + "'; use one of: " + AiAutomateRuleId.listIds());
        }
        return rule;
    }

    /** 把自动化模块的结构化结果搬成 CLI 类型；两边字段一一对应，不做二次解释。 */
    private static RwxLocalGameTypes.AutoPage autoPage(AiAutomateReport report) {
        List<RwxLocalGameTypes.AutoRuleSnapshot> rules = new ArrayList<>();
        for (AiAutomateReport.RuleView view : report.rules) {
            rules.add(new RwxLocalGameTypes.AutoRuleSnapshot(
                    view.id, view.label, view.summary, view.enabled, view.priority,
                    view.cooldownMillis, view.resume, view.hpPercent, view.x, view.y, view.radius,
                    view.units, view.includeTypes, view.excludeTypes, view.excludedIds,
                    view.idleOnly, view.excludeBuilders, view.includeUnits, view.eligible, view.engaged,
                    view.triggers, view.state, view.engagedUnitIds));
        }
        return new RwxLocalGameTypes.AutoPage(report.tick, report.gameTimeMillis, rules,
                report.recent, report.message);
    }

    /** Attack stance is a stance-only Command: it is not a waypoint and must not clear orders. */
    private RwxLocalGameTypes.CommandReceipt applyAttackMode(GameEngine engine, PlayerTeam controlled,
                                                             RwxLocalGameTypes.Order order) {
        String raw = order.attackMode == null ? "" : order.attackMode.trim();
        boolean clearDefault = raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("none")
                || raw.equalsIgnoreCase("clear");
        if (clearDefault) {
            if (!order.applyToNew) {
                throw new IllegalArgumentException(
                        "--mode off is only valid together with --apply-to-new (it clears the default stance)");
            }
            defaultAttackModes.remove(controlled.teamId);
            return new RwxLocalGameTypes.CommandReceipt(
                    engine.currentTick, Collections.<Long>emptyList(),
                    "default attack mode for newly produced units cleared", true);
        }
        AttackMode mode = requireAttackMode(raw);
        List<OrderableUnit> selected;
        if (order.unitIds.isEmpty() && (order.unitSelector == null || order.unitSelector.trim().isEmpty())) {
            if (!order.applyToNew) {
                throw new IllegalArgumentException(
                        "--units is required unless --apply-to-new is used to set the default stance");
            }
            selected = Collections.emptyList();
        } else {
            boolean explicitIds = !order.unitIds.isEmpty();
            List<OrderableUnit> resolved = requireControllableUnits(resolveOrderUnitIds(order), controlled);
            selected = new ArrayList<>(resolved.size());
            for (OrderableUnit unit : resolved) {
                // 建造者/指挥中心这类没有武器的单位，攻击模式纯属误导。显式 id 直接报错，
                // 选择器（all-army 等）则跳过无武器单位，否则一条 all-army 命令会因为工程车而失败。
                if (!hasWeapon(unit)) {
                    if (explicitIds) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v()
                                        + ") has no weapon; attack mode would do nothing");
                    }
                    continue;
                }
                selected.add(unit);
            }
        }
        boolean appliedImmediately = true;
        if (!selected.isEmpty()) {
            // Same path as AIController's stance changes: a stance-only Command has no waypoint,
            // so it keeps existing orders, and it is written to the replay. A direct
            // unit.attackMode write is invisible to the replay and desyncs playback.
            Command command = engine.commandController.newCommandForTeam(controlled);
            try {
                for (OrderableUnit unit : selected) command.addUnitToCommand(unit);
                command.setAttackMode(mode);
                yieldAutomation(engine, selected, true, canApplyImmediately(engine), command);
                // 显式姿态也优先于尚未被采样器应用的新单位默认姿态。
                stanceIssuedUnitIds.addAll(idsOf(selected));
            } catch (RuntimeException error) {
                engine.commandController.pendingCommands.remove(command);
                throw error;
            }
            appliedImmediately = canApplyImmediately(engine);
            if (appliedImmediately) applyCommandNow(engine, command);
        }
        if (order.applyToNew) defaultAttackModes.put(controlled.teamId, mode);
        StringBuilder message = new StringBuilder();
        if (!selected.isEmpty()) {
            message.append("attack mode set to ").append(mode.name())
                    .append(appliedImmediately ? " immediately" : queuedNote(engine))
                    .append(" on ").append(selected.size())
                    .append(selected.size() == 1 ? " unit" : " units");
            if (order.applyToNew) message.append("; ");
        } else {
            // Nothing was ordered now; claiming "set immediately" would read as if units changed.
            appliedImmediately = false;
        }
        if (order.applyToNew) {
            message.append("newly produced units of team ").append(controlled.teamId)
                    .append(" will start with ").append(mode.name())
                    .append(" (existing units unchanged unless listed with --units)");
        }
        return new RwxLocalGameTypes.CommandReceipt(
                engine.currentTick, idsOf(selected), message.toString(), appliedImmediately);
    }

    /** Executes a fully filled command immediately (only safe while the simulation is idle). */
    private static void applyCommandNow(GameEngine engine, Command command) {
        try {
            engine.replayEngine.a(command, engine.currentTick);
        } catch (RuntimeException ignored) {
            // Replay recording is optional; a recording failure must not lose the order.
        }
        command.executeCommand();
        engine.commandController.pendingCommands.remove(command);
        try {
            engine.replayEngine.c();
        } catch (IOException ignored) {
            // Same as above: never fail the order because replay flush failed.
        }
    }

    private static boolean clearsExistingOrders(RwxLocalGameTypes.OrderType type) {
        switch (type) {
            case MOVE:
            case ATTACK_MOVE:
            case ATTACK:
            case PATROL:
            case GUARD:
            case GUARD_AT:
            case FOLLOW:
            case REPAIR:
            case RECLAIM:
            case LOAD_INTO:
            case LOAD_UP:
            case BUILD:
            case ACTION:
                return true;
            default:
                // STOP clears on purpose (setClearExistingOrders); rally/attack-mode touch no orders.
                return false;
        }
    }

    private static boolean isWaypointOrder(RwxLocalGameTypes.OrderType type) {
        return type == RwxLocalGameTypes.OrderType.STOP
                || type != RwxLocalGameTypes.OrderType.ACTION && clearsExistingOrders(type);
    }

    private void yieldAutomation(GameEngine engine, List<OrderableUnit> selected, boolean restore,
                                 boolean immediate, Command manual) {
        List<Command> prefix = automate.yieldToOrders(engine, selected, restore);
        if (immediate) {
            for (Command command : prefix) applyCommandNow(engine, command);
        } else if (manual != null && !prefix.isEmpty()) {
            // createCommandForTeam 已将手动命令入队；恢复原任务必须排在它前面。
            if (engine.commandController.pendingCommands.remove(manual)) {
                engine.commandController.pendingCommands.add(manual);
            }
            if (engine.commandController.queuedCommands.remove(manual)) {
                engine.commandController.queuedCommands.add(manual);
            }
        }
    }

    private void suspendKeep(GameEngine engine, List<OrderableUnit> selected) {
        Set<Long> ids = new HashSet<>(idsOf(selected));
        for (KeepRule rule : keepRules.values()) {
            if (!ids.contains(rule.unitId)) continue;
            rule.suspendedByManualOrder = true;
            rule.state = "手动命令优先，已暂停；keep-set 重新授权";
        }
        cancelKeepCommands(engine, new ArrayList<>(ids), null);
    }

    private void cancelKeepCommands(GameEngine engine, List<Long> ids, String actionId) {
        keepCommands.entrySet().removeIf(entry -> {
            if (!ids.contains(entry.getValue().unitId)
                    || actionId != null && !actionId.equals(entry.getKey().actionId.getId())) return false;
            boolean removed = engine.commandController.pendingCommands.remove(entry.getKey());
            removed |= engine.commandController.queuedCommands.remove(entry.getKey());
            if (removed && entry.getValue().tick == reservedActionTick) {
                reservedActionCredits = Math.max(0, reservedActionCredits - entry.getValue().credits);
            }
            return true;
        });
    }

    /** 新的替换任务/取消生产使旧待发意图失效；多单位请求只删除冲突的成员。 */
    private void discardPendingForUnits(List<Long> ids, String actionId, boolean actionsOnly) {
        List<Long> discarded = new ArrayList<>();
        for (PendingAction pending : pendingActions.values()) {
            if (actionsOnly && (pending.order.type != RwxLocalGameTypes.OrderType.ACTION
                    || actionId != null && !actionId.equals(pending.order.actionId))) continue;
            List<Long> remaining = new ArrayList<>(pending.order.unitIds);
            if (!remaining.removeAll(ids)) continue;
            notePending("手动命令取代待发 #" + pending.id + " 中的单位 " + RwxCliText.joinIds(ids));
            if (remaining.isEmpty()) discarded.add(pending.id);
            else pending.order = pending.order.withUnitIds(remaining);
        }
        for (Long id : discarded) pendingActions.remove(id);
    }

    private static List<Long> idsOf(List<OrderableUnit> units) {
        List<Long> ids = new ArrayList<>(units.size());
        for (OrderableUnit unit : units) ids.add(unit.objectId);
        return ids;
    }

    /**
     * Native commands are applied after submission. Reserve their credit cost for the current
     * tick so a burst of CLI production/build orders cannot all report success against the same
     * money, and so a build sees what earlier actions in the same tick already reserved.
     */
    private long reserveActionCredits(GameEngine engine, RwxLocalGameTypes.Order order,
                                      List<OrderableUnit> selected, PlayerTeam controlled) {
        if (order.type != RwxLocalGameTypes.OrderType.ACTION
                && order.type != RwxLocalGameTypes.OrderType.BUILD) {
            return 0L;
        }
        if (reservedActionTick != engine.currentTick) {
            reservedActionTick = engine.currentTick;
            reservedActionCredits = 0L;
        }
        long total = 0L;
        if (order.type == RwxLocalGameTypes.OrderType.ACTION) {
            ActionId actionId = ActionId.intern(requireActionId(order.actionId));
            for (OrderableUnit unit : selected) {
                AbstractUnitAction action = unit.validateActionId(actionId);
                if (action != null) total += Math.max(0, action.getCostAmount());
            }
        } else {
            UnitType buildType = requireUnitType(order.unitTypeId);
            int buildCount = Math.max(1, Math.min(order.repeatCount, 20));
            total = (long) buildType.c() * buildCount;
        }
        if (total == 0L) return 0L;
        long available = availableCredits(engine, controlled);
        if (available < total) {
            throw new RwxLocalGameTypes.CreditShortageException((order.type == RwxLocalGameTypes.OrderType.BUILD
                    ? "Cannot build " + order.unitTypeId : "Insufficient credits for action '"
                    + order.actionId + "'")
                    + ": need " + total + ", available " + available
                    + (reservedActionCredits > 0 && reservedActionTick == engine.currentTick
                    ? " (" + (long) controlled.credits + " on hand, "
                    + reservedActionCredits + " already reserved this tick)" : ""));
        }
        reservedActionCredits += total;
        return total;
    }

    /** Credits that are still unspoken for: on-hand minus what this tick's commands reserved. */
    private long availableCredits(GameEngine engine, PlayerTeam controlled) {
        long reserved = reservedActionTick == engine.currentTick ? reservedActionCredits : 0L;
        return Math.max(0L, (long) controlled.credits - reserved);
    }

    /** Rejects commands the engine would silently swallow, so a successful receipt means something. */
    private void validateOrder(GameEngine engine, RwxLocalGameTypes.Order order,
                                      List<OrderableUnit> selected, BaseUnit target, PlayerTeam controlled) {
        switch (order.type) {
            case MOVE:
            case ATTACK_MOVE:
            case PATROL:
                requireMapPoint(engine, order.x, order.y);
                for (OrderableUnit unit : selected) {
                    if (unit.getMovementType() == UnitMovementType.NONE) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v() + ") cannot move");
                    }
                }
                break;
            case ATTACK: {
                if (target == null) throw new IllegalArgumentException("--target is required");
                if (target.team == null || !controlled.c(target.team)) {
                    throw new IllegalArgumentException(
                            "Attack target must be an enemy unit: " + target.objectId
                                    + " (" + target.r().v() + ")");
                }
                break;
            }
            case GUARD:
                requireFriendlyTarget(target, controlled, "guard");
                break;
            case GUARD_AT:
                requireMapPoint(engine, order.x, order.y);
                for (OrderableUnit unit : selected) {
                    if (unit.getMovementType() == UnitMovementType.NONE) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v() + ") cannot move");
                    }
                }
                break;
            case FOLLOW:
                requireFriendlyTarget(target, controlled, "follow");
                for (OrderableUnit unit : selected) {
                    if (unit.objectId == target.objectId) {
                        throw new IllegalArgumentException("A unit cannot follow itself");
                    }
                    if (unit.getMovementType() == UnitMovementType.NONE) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v() + ") cannot move");
                    }
                }
                break;
            case REPAIR:
                if (target == null) throw new IllegalArgumentException("--target is required");
                if (target.team == null || controlled.c(target.team)) {
                    throw new IllegalArgumentException("Cannot repair enemy or neutral target "
                            + target.objectId);
                }
                if (target.currentHealth >= target.maxHealth && target.buildProgress >= 1.0f) {
                    throw new IllegalArgumentException(
                            "Repair target is already fully repaired: " + target.objectId);
                }
                for (OrderableUnit unit : selected) {
                    if (!unit.canRepairTarget(target)) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v()
                                        + ") cannot repair " + target.objectId);
                    }
                }
                break;
            case RECLAIM:
                if (target == null) throw new IllegalArgumentException("--target is required");
                if (target.team == controlled && !order.force) {
                    throw new IllegalArgumentException("Reclaiming your own unit " + target.objectId
                            + " requires --force");
                }
                for (OrderableUnit unit : selected) {
                    if (!unit.canReclaimTarget(target)) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v()
                                        + ") cannot reclaim " + target.objectId);
                    }
                }
                break;
            case LOAD_INTO:
                if (target == null) throw new IllegalArgumentException("--target is required");
                if (!target.canTransportUnits()) {
                    throw new IllegalArgumentException("Target cannot transport units: " + target.objectId);
                }
                if (target.team != null && controlled.c(target.team)) {
                    throw new IllegalArgumentException("Cannot load into an enemy transport: " + target.objectId);
                }
                for (OrderableUnit unit : selected) {
                    if (unit.objectId == target.objectId) {
                        throw new IllegalArgumentException("A unit cannot load into itself");
                    }
                }
                break;
            case LOAD_UP:
                for (OrderableUnit unit : selected) {
                    if (!unit.canTransportUnits()) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v() + ") is not a transport");
                    }
                }
                if (target != null && target.team != null && controlled.c(target.team)) {
                    throw new IllegalArgumentException("Cannot load an enemy unit: " + target.objectId);
                }
                break;
            case BUILD: {
                requireMapPoint(engine, order.x, order.y);
                UnitType buildType = requireUnitType(order.unitTypeId);
                if (!buildType.isBuildingUnit()) {
                    throw new IllegalArgumentException("Not a building type: " + order.unitTypeId);
                }
                for (OrderableUnit unit : selected) {
                    AbstractUnitAction buildAction = unit.findActionForUnitType(buildType, false);
                    if (buildAction == null) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v()
                                        + ") cannot build " + buildType.v());
                    }
                    if (buildAction.isNotAvailable(unit) || !buildAction.b(unit)) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v()
                                        + ") cannot build " + buildType.v()
                                        + " right now (locked, missing tech or unavailable)");
                    }
                }
                break;
            }
            case RALLY:
                requireMapPoint(engine, order.x, order.y);
                for (OrderableUnit unit : selected) {
                    if (!hasActionType(unit, ActionType.setRally)) {
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v()
                                        + ") cannot set a rally point");
                    }
                }
                break;
            case ACTION: {
                String actionIdText = requireActionId(order.actionId);
                ActionId actionId = ActionId.intern(actionIdText);
                for (OrderableUnit unit : selected) {
                    String upgrade = queuedUpgradeLabel(unit, unit);
                    AbstractUnitAction action = unit.validateActionId(actionId);
                    if (action == null) {
                        // "no such action" during an upgrade is the confusing case: the unit is
                        // busy, and the next tier's actions only appear when it finishes.
                        if (upgrade != null) {
                            throw new UpgradeInProgressException(
                                    "Unit " + unit.objectId + " (" + unit.r().v()
                                            + ") is still upgrading (" + upgrade + "), so action '"
                                            + actionIdText + "' does not exist yet. Wait for the"
                                            + " upgrade, or add --after-upgrade to hold this order"
                                            + " and issue it automatically when the upgrade finishes"
                                            + " (available now: "
                                            + availableActionIds(unit, 6) + ")");
                        }
                        throw new IllegalArgumentException(
                                "Unit " + unit.objectId + " (" + unit.r().v() + ") has no action '"
                                        + actionIdText + "'; available now: "
                                        + availableActionIds(unit, 6) + ". List them with "
                                        + RwxCliCommands.Command.ACTIONS.commandName()
                                        + " --unit " + unit.objectId);
                    }
                    if (!action.b(unit)) {
                        throw new IllegalArgumentException(
                                "Action '" + actionIdText + "' is not available on unit "
                                        + unit.objectId + " (" + unit.r().v()
                                        + ") right now (locked, missing tech or already active"
                                        + (upgrade == null ? "" : "; unit is upgrading: " + upgrade)
                                        + ")"
                                        + (upgrade == null || order.afterUpgrade ? "" : "; add"
                                        + " --after-upgrade to retry it when the upgrade finishes"));
                    }
                    if (!action.canAfford(unit, false)) {
                        if (action.isNotAvailable(unit)) {
                            throw new IllegalArgumentException(
                                    "Action '" + actionIdText + "' is not available on unit "
                                            + unit.objectId + " (missing prerequisite or nothing stocked)");
                        }
                        throw new RwxLocalGameTypes.CreditShortageException(
                                "Action '" + actionIdText + "' cannot be afforded by unit "
                                        + unit.objectId + " right now (need "
                                        + action.getCostAmount() + ", available "
                                        + availableCredits(engine, controlled) + ")");
                    }
                    if (action.getActionType() == ActionType.placeBuilding) {
                        // 建造在引擎里走 setBuildTarget 专用路径；用 setActionTarget 会静默什么都不做。
                        throw new IllegalArgumentException(
                                "Action '" + actionIdText + "' places a building; use "
                                        + RwxCliCommands.Command.ORDER_BUILD.commandName()
                                        + " --builder " + unit.objectId
                                        + " --type " + (action.getUnitType() == null
                                        ? "<typeId>" : action.getUnitType().v())
                                        + " --x <x> --y <y> instead");
                    }
                    if (action.getActionType() == ActionType.setRally) {
                        // 集结点走 Command.rallyPoint，setActionTarget 同样不会生效。
                        throw new IllegalArgumentException(
                                "Action '" + actionIdText + "' sets a rally point; use "
                                        + RwxCliCommands.Command.ORDER_RALLY.commandName()
                                        + " --units " + unit.objectId + " --x <x> --y <y> instead");
                    }
                    if (needsUnitTarget(action) && order.targetUnitId == null) {
                        throw new IllegalArgumentException(
                                "Action '" + actionIdText + "' requires --target <unitId>");
                    }
                    if (needsPointTarget(action) && (order.x == null || order.y == null)) {
                        throw new IllegalArgumentException(
                                "Action '" + actionIdText + "' requires --x and --y");
                    }
                }
                break;
            }
            case ATTACK_MODE:
                // Handled by applyAttackMode before this method; nothing to validate here.
                break;
            case STOP:
            default:
                break;
        }
    }

    private static void requireFriendlyTarget(BaseUnit target, PlayerTeam controlled, String verb) {
        if (target == null) throw new IllegalArgumentException("--target is required");
        if (target.team == null || controlled.c(target.team)) {
            throw new IllegalArgumentException(
                    "Target for " + verb + " must be a friendly unit: " + target.objectId);
        }
    }

    private static void requireMapPoint(GameEngine engine, Float x, Float y) {
        float worldX = requireCoordinate(x, "--x");
        float worldY = requireCoordinate(y, "--y");
        TileMap map = engine.tileMap;
        if (map == null) return;
        if (worldX < 0.0f || worldY < 0.0f
                || worldX > map.getWorldWidth() || worldY > map.getWorldHeight()) {
            throw new IllegalArgumentException(
                    "--x/--y are outside the map: " + worldX + "," + worldY
                            + " (0.." + (int) map.getWorldWidth() + ", 0.." + (int) map.getWorldHeight() + ")");
        }
    }

    private static boolean hasActionType(OrderableUnit unit, ActionType type) {
        for (AbstractUnitAction action : unit.getAvailableActions()) {
            if (action.getActionType() == type) return true;
        }
        return false;
    }

    /**
     * 该单位是否真的能开火。
     *
     * <p>自定义单位的 {@code m()}（最大攻击距离）会从模板继承，建造者/指挥中心也能拿到非 0 值，
     * 所以要看 {@code CustomUnit.canAttack()}；内置单位用攻击距离判断即可。</p>
     */
    private static boolean hasWeapon(OrderableUnit unit) {
        if (unit instanceof CustomUnit) {
            return ((CustomUnit) unit).canAttack();
        }
        return unit.m() > 0.0f;
    }

    private static String describeOrderType(RwxLocalGameTypes.OrderType type) {
        return type.name().toLowerCase().replace('_', '-');
    }

    /** Resolves {@code --units} ids or a selector such as {@code all-army} / {@code group:main}. */
    private List<Long> resolveOrderUnitIds(RwxLocalGameTypes.Order order) {
        List<Long> resolved = order.unitIds.isEmpty()
                ? resolveSelectorIds(order.unitSelector)
                : new ArrayList<>(order.unitIds);
        if (order.unitIds.isEmpty() && order.targetUnitId != null
                && (order.type == RwxLocalGameTypes.OrderType.FOLLOW
                || order.type == RwxLocalGameTypes.OrderType.GUARD)) {
            resolved.remove(order.targetUnitId);
            if (resolved.isEmpty()) throw new IllegalArgumentException(
                    "Selector contains only the target; no other units to order");
        }
        List<Long> filtered = excludeIds(resolved, order.excludedUnitIds);
        filtered = applyTypeFilters(filtered, order.includeTypes, order.excludeTypes);
        filtered = applyHpFilter(filtered, order.hpBelowPercent);
        return filterSelection(filtered, order.idleOnly, order.excludeBuilders);
    }

    private static List<Long> filterSelection(List<Long> ids, boolean idleOnly,
                                              boolean excludeBuilders) {
        if (!idleOnly && !excludeBuilders) return ids;
        List<Long> kept = new ArrayList<>();
        for (Long id : ids) {
            BaseUnit unit;
            try {
                unit = findLiveUnit(id);
            } catch (IllegalArgumentException missing) {
                continue;
            }
            if (idleOnly && !isIdle(unit)) continue;
            if (excludeBuilders && isBuilder(unit)) continue;
            kept.add(id);
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("Selection filters matched no live units");
        }
        return kept;
    }

    /**
     * Drops {@code --exclude} ids. Dead/missing excluded ids are ignored (same rule as an id list);
     * excluding every selected unit is an error, because the order would have nowhere to go.
     */
    private static List<Long> excludeIds(List<Long> ids, List<Long> excluded) {
        if (excluded == null || excluded.isEmpty()) return ids;
        List<Long> kept = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (!excluded.contains(id)) kept.add(id);
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException(
                    "--exclude removed every selected unit (all " + ids.size() + " excluded)");
        }
        return kept;
    }

    private List<Long> resolveSelectorIds(String selectorRaw) {
        return resolveSelectorIds(selectorRaw, false);
    }

    /**
     * Resolves a {@code --units} selector. {@code allowEmpty} is for read-only queries such as
     * {@code unit-list}, where "the area contains nothing" is a valid answer rather than an error.
     */
    private List<Long> resolveSelectorIds(String selectorRaw, boolean allowEmpty) {
        String selector = selectorRaw == null ? "" : selectorRaw.trim();
        if (selector.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one unit id is required (--units id1,id2 | all-army | all-units |"
                            + " group:<name> | area:x,y,r | rect:x,y,halfH,halfW)");
        }
        if (selector.regionMatches(true, 0, "area:", 0, 5)) {
            return resolveAreaSelector(selector.substring(5), false, allowEmpty);
        }
        if (selector.regionMatches(true, 0, "rect:", 0, 5)) {
            return resolveAreaSelector(selector.substring(5), true, allowEmpty);
        }
        String normalized = selector.toLowerCase(Locale.ROOT);
        boolean armyOnly = normalized.equals("all-army") || normalized.equals("army") || normalized.equals("all");
        boolean includeBuildings;
        if (normalized.equals("all-army") || normalized.equals("army") || normalized.equals("all")) {
            includeBuildings = false;
        } else if (normalized.equals("all-units") || normalized.equals("everything")) {
            includeBuildings = true;
        } else if (normalized.startsWith("group:") || normalized.startsWith("group=")) {
            String name = selector.substring(6).trim();
            List<Long> saved = groups.get(name);
            if (saved == null) {
                throw new IllegalArgumentException("Unknown unit group '" + name + "'; save it with "
                        + RwxCliCommands.Command.GROUP_SAVE.commandName()
                        + " --name " + name + " --units <ids|selector>");
            }
            return new ArrayList<>(saved);
        } else {
            throw new IllegalArgumentException(
                    "Unknown --units selector '" + selector
                            + "' (use a comma-separated id list, all-army, all-units,"
                            + " group:<name>, area:x,y,r or rect:x,y,halfH,halfW)");
        }
        GameEngine engine = GameEngine.getInstance();
        PlayerTeam controlled = requireControlledTeam(engine);
        List<Long> ids = new ArrayList<>();
        BaseUnit[] units = BaseUnit.bE.a();
        for (int index = 0; index < BaseUnit.bE.size(); index++) {
            BaseUnit unit = units[index];
            if (unit == null || unit.isDead || unit.team != controlled) continue;
            if (!(unit instanceof OrderableUnit) || unit.canNotBeGivenOrdersByPlayer()) continue;
            if (unit.r().isBuildingUnit() && !includeBuildings) continue;
            if (armyOnly && isBuilder(unit)) continue;
            ids.add(unit.objectId);
        }
        if (ids.isEmpty() && !allowEmpty) {
            throw new IllegalArgumentException("--units " + selector + " matched no controllable units");
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * {@code area:x,y[,radiusTiles]} / {@code rect:x,y[,halfHeightTiles[,halfWidthTiles]]}, with
     * {@code area:unit:<id>[,radiusTiles]} to centre on one of our own units.
     *
     * <p>Only our own controllable non-building units are selected: area selection is the army
     * brush, and a move order that includes a building fails because it cannot move. To pick
     * buildings use {@code --units all-units --type <id>} or explicit ids.</p>
     */
    private List<Long> resolveAreaSelector(String body, boolean rectangle, boolean allowEmpty) {
        GameEngine engine = GameEngine.getInstance();
        PlayerTeam controlled = requireControlledTeam(engine);
        float tile = averageTileSize(engine);
        if (!(tile > 0.0f)) tile = 32.0f;
        List<String> values = new ArrayList<>();
        for (String part : body.split(",", -1)) values.add(part.trim());
        if (values.isEmpty() || values.get(0).isEmpty()) {
            throw new IllegalArgumentException(
                    "区域选择器需要坐标：area:<x>,<y>[,<半径格>] | area:unit:<id>[,<半径格>]"
                            + " | rect:<x>,<y>[,<H/2>,<W/2>] | rect:unit:<id>[,<H/2>,<W/2>]");
        }
        float centerX;
        float centerY;
        int index;
        String first = values.get(0).toLowerCase(Locale.ROOT);
        if (first.startsWith("unit:") || first.startsWith("unit=")) {
            String rawId = values.get(0).substring(5).trim();
            long centerId;
            try {
                centerId = Long.parseLong(rawId);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("区域选择器的中心单位 id 不是整数: " + rawId);
            }
            BaseUnit center;
            try {
                center = findLiveUnit(centerId);
            } catch (IllegalArgumentException missing) {
                throw new IllegalArgumentException("区域选择器：中心单位 " + centerId + " 已不存在或不可见");
            }
            if (center.team != controlled) {
                throw new IllegalArgumentException("区域选择器只能以我方单位为圆心：单位 " + centerId + " 不属于你");
            }
            centerX = center.posX;
            centerY = center.posY;
            index = 1;
        } else {
            if (values.size() < 2 || values.get(1).isEmpty()) {
                throw new IllegalArgumentException(
                        "区域选择器需要中心坐标：area:<x>,<y>[,<半径格>] 或 rect:<x>,<y>[,<H/2>,<W/2>]");
            }
            centerX = areaNumber(values.get(0), "中心 x");
            centerY = areaNumber(values.get(1), "中心 y");
            index = 2;
        }
        // Reject trailing components instead of silently dropping them: "area:1,2,3,4" used to
        // ignore the 4th value, so a mistyped selector looked accepted but selected something else.
        int maxParts = index + (rectangle ? 2 : 1);
        if (values.size() > maxParts) {
            StringBuilder extra = new StringBuilder();
            for (int part = maxParts; part < values.size(); part++) {
                if (extra.length() > 0) extra.append(',');
                extra.append(values.get(part));
            }
            throw new IllegalArgumentException("区域选择器有无法识别的内容: " + extra
                    + "（" + (rectangle ? "rect" : "area") + " 选择器最多 " + maxParts + " 段）");
        }
        List<Long> ids = new ArrayList<>();
        if (rectangle) {
            float halfHeightTiles = index < values.size() && !values.get(index).isEmpty()
                    ? areaNumber(values.get(index), "H/2") : 1.0f;
            float halfWidthTiles = index + 1 < values.size() && !values.get(index + 1).isEmpty()
                    ? areaNumber(values.get(index + 1), "W/2") : halfHeightTiles;
            if (halfHeightTiles <= 0.0f || halfWidthTiles <= 0.0f) {
                throw new IllegalArgumentException("矩形选择器的 H/2 和 W/2 必须大于 0");
            }
            float halfHeight = halfHeightTiles * tile;
            float halfWidth = halfWidthTiles * tile;
            for (Long id : controllableIds(controlled)) {
                BaseUnit unit = findLiveUnit(id);
                if (Math.abs(unit.posX - centerX) <= halfWidth && Math.abs(unit.posY - centerY) <= halfHeight) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty() && !allowEmpty) {
                throw new IllegalArgumentException("矩形区域内没有可指挥的我方单位（H/2="
                        + RwxCliText.number(halfHeightTiles) + " 格, W/2=" + RwxCliText.number(halfWidthTiles) + " 格）");
            }
        } else {
            float radiusTiles = index < values.size() && !values.get(index).isEmpty()
                    ? areaNumber(values.get(index), "半径") : 2.0f;
            if (radiusTiles <= 0.0f) throw new IllegalArgumentException("圆形选择器的半径必须大于 0 格");
            float radius = radiusTiles * tile;
            for (Long id : controllableIds(controlled)) {
                BaseUnit unit = findLiveUnit(id);
                if (distance(unit.posX, unit.posY, centerX, centerY) <= radius) ids.add(id);
            }
            if (ids.isEmpty() && !allowEmpty) {
                throw new IllegalArgumentException("圆形区域内没有可指挥的我方单位（半径 "
                        + RwxCliText.number(radiusTiles) + " 格）");
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /** {@code unit-list --units}: an id list or any selector (area/rect included); null when empty. */
    private List<Long> resolveUnitSelectorIds(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        List<Long> ids = new ArrayList<>();
        boolean numeric = true;
        for (String token : value.split(",", -1)) {
            String part = token.trim();
            if (part.isEmpty()) {
                numeric = false;
                break;
            }
            try {
                ids.add(Long.parseLong(part));
            } catch (NumberFormatException notAnId) {
                numeric = false;
                break;
            }
        }
        if (numeric) return ids;
        return resolveSelectorIds(value, true);
    }

    /** Our controllable non-building units, in id order. */
    private List<Long> controllableIds(PlayerTeam controlled) {
        List<Long> ids = new ArrayList<>();
        BaseUnit[] units = BaseUnit.bE.a();
        for (int index = 0; index < BaseUnit.bE.size(); index++) {
            BaseUnit unit = units[index];
            if (unit == null || unit.isDead || unit.team != controlled) continue;
            if (!(unit instanceof OrderableUnit) || unit.canNotBeGivenOrdersByPlayer()) continue;
            if (unit.r().isBuildingUnit()) continue;
            ids.add(unit.objectId);
        }
        Collections.sort(ids);
        return ids;
    }

    private static float areaNumber(String raw, String label) {
        float parsed;
        try {
            parsed = Float.parseFloat(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("区域选择器的" + label + "不是数字: " + raw);
        }
        if (!Float.isFinite(parsed)) {
            throw new IllegalArgumentException("区域选择器的" + label + "不是有限数字: " + raw);
        }
        return parsed;
    }

    /** True when the unit's internal id or display name matches one of the tokens. */
    private static boolean matchesAnyType(BaseUnit unit, List<String> tokens) {
        for (String token : tokens) {
            if (matchesType(unit, token)) return true;
        }
        return false;
    }

    /** Same matching as {@link #matchesType}, for event rows that only carry ids and names. */
    private static boolean matchesAnyTypeName(String typeId, String displayName, List<String> tokens) {
        for (String token : tokens) {
            String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (typeId != null && typeId.toLowerCase(Locale.ROOT).equals(value)) return true;
            String name = displayName == null ? "" : displayName.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && (name.equals(value) || (value.length() >= 2 && name.contains(value)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesType(BaseUnit unit, String token) {
        String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        UnitType type = unit.r();
        String typeId = type.v();
        if (typeId != null && typeId.toLowerCase(Locale.ROOT).equals(value)) return true;
        String name = type.getUnitTypeDescriptionShort();
        if (name == null) return false;
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        if (normalizedName.equals(value)) return true;
        // 允许“重型坦克”这种部分名称，但太短的词（如 t）不参与，否则会匹配到一切。
        return value.length() >= 2 && normalizedName.contains(value);
    }

    private static float healthPercent(BaseUnit unit) {
        if (!(unit.maxHealth > 0.0f)) return 100.0f;
        return Math.max(0.0f, Math.min(100.0f, unit.currentHealth * 100.0f / unit.maxHealth));
    }

    private static List<Long> applyTypeFilters(List<Long> ids, List<String> includeTypes,
                                               List<String> excludeTypes) {
        if (includeTypes.isEmpty() && excludeTypes.isEmpty()) return ids;
        List<Long> kept = new ArrayList<>();
        for (Long id : ids) {
            BaseUnit unit;
            try {
                unit = findLiveUnit(id);
            } catch (IllegalArgumentException missing) {
                continue;
            }
            if (!includeTypes.isEmpty() && !matchesAnyType(unit, includeTypes)) continue;
            if (!excludeTypes.isEmpty() && matchesAnyType(unit, excludeTypes)) continue;
            kept.add(id);
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("--type/--exclude 没有留下任何单位（type="
                    + (includeTypes.isEmpty() ? "any" : String.join(",", includeTypes))
                    + ", exclude=" + (excludeTypes.isEmpty() ? "any" : String.join(",", excludeTypes)) + "）");
        }
        return kept;
    }

    private static List<Long> applyHpFilter(List<Long> ids, Float hpBelowPercent) {
        if (hpBelowPercent == null) return ids;
        List<Long> kept = new ArrayList<>();
        for (Long id : ids) {
            BaseUnit unit;
            try {
                unit = findLiveUnit(id);
            } catch (IllegalArgumentException missing) {
                continue;
            }
            if (healthPercent(unit) < hpBelowPercent) kept.add(id);
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("选中的单位里没有生命值低于 " + RwxCliText.number(hpBelowPercent) + "% 的");
        }
        return kept;
    }

    private static boolean isBuilder(BaseUnit unit) {
        if (!(unit instanceof OrderableUnit)) return false;
        for (AbstractUnitAction action : ((OrderableUnit) unit).getAvailableActions()) {
            if (action.getActionType() == ActionType.placeBuilding) return true;
        }
        return false;
    }

    /** Parses {@code --units} for group management: an id list, or any selector. */
    private List<Long> resolveUnitOption(String unitsOption) {
        String raw = unitsOption == null ? "" : unitsOption.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("--units is required");
        List<Long> ids = new ArrayList<>();
        boolean numeric = true;
        for (String token : raw.split(",", -1)) {
            String value = token.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("--units must not contain empty entries");
            }
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException notAnId) {
                numeric = false;
                break;
            }
        }
        if (numeric) return ids;
        return resolveSelectorIds(raw);
    }

    private static List<OrderableUnit> requireControllableUnits(List<Long> unitIds, PlayerTeam controlled) {
        return requireControllableUnits(unitIds, controlled, new ArrayList<Long>());
    }

    /**
     * Resolves ids to controllable units. Dead/missing ids are skipped (scripted playback often
     * references a unit that just died); only live units that belong to someone else are errors.
     */
    private static List<OrderableUnit> requireControllableUnits(List<Long> unitIds, PlayerTeam controlled,
                                                                List<Long> skipped) {
        if (unitIds == null || unitIds.isEmpty()) {
            throw new IllegalArgumentException("At least one unit id is required");
        }
        List<OrderableUnit> selected = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (Long unitId : unitIds) {
            if (unitId == null || !seen.add(unitId)) {
                throw new IllegalArgumentException("Unit ids must be unique integers");
            }
            BaseUnit unit;
            try {
                unit = findLiveUnit(unitId);
            } catch (IllegalArgumentException dead) {
                if (skipped != null) skipped.add(unitId);
                continue;
            }
            if (!(unit instanceof OrderableUnit) || unit.team != controlled || unit.canNotBeGivenOrdersByPlayer()) {
                throw new IllegalArgumentException("Unit is not controllable: " + unitId);
            }
            selected.add((OrderableUnit) unit);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "No controllable unit remains; skipped dead/missing ids: " + RwxCliText.joinIds(skipped));
        }
        return selected;
    }

    private static float requireCoordinate(Float value, String option) {
        if (value == null || !Float.isFinite(value)) {
            throw new IllegalArgumentException(option + " is required and must be a finite number");
        }
        return value;
    }

    // ---------------------------------------------------------------- placement helpers

    /**
     * 复刻 {@code OrderableUnit} 建造执行时的取格公式，但不再读写 {@link TileMap} 的共享
     * {@code cursorTileX/Y}：那两个字段同时也是 UI 放置预览的状态，CLI 调用与渲染线程
     * 交叉读写会出现“回执坐标跳成 42,65 / 95,112”这类串位。这里用纯算术，结果一致且无副作用。
     */
    private static float[] snapBuildingPosition(UnitType type, float x, float y) {
        GameEngine engine = GameEngine.getInstance();
        BaseUnit prototype = BaseUnit.findTurretPosition(type);
        if (prototype == null || engine.tileMap == null) {
            return new float[] {x, y};
        }
        TileMap map = engine.tileMap;
        float offsetX = prototype.getTileOffsetX();
        float offsetY = prototype.getTileOffsetY();
        // 与 updateCursorTileIndexFromWorldPoint 完全相同的取整方式（int 截断）。
        int tileX = (int) (((x - offsetX) + 1.0f) * map.tileScaleX);
        int tileY = (int) (((y - offsetY) + 1.0f) * map.tileScaleY);
        return new float[] {
                tileX * map.tileWorldSizeX + offsetX,
                tileY * map.tileWorldSizeY + offsetY
        };
    }

    /**
     * 以“建筑中心”为入参的放置校验。
     *
     * <p>{@code BaseBuilding.canBuildingBePlacedAt} 把入参当作 footprint 原点，内部再加
     * {@code getTileOffset()} 回填中心；而 {@code setBuildTarget} 的入参是中心。若把同一个
     * 中心点直接传给校验，校验的实际位置会比建造位置多偏一个 offset——资源池建筑上表现为
     * “校验通过、建好即被移除”。这里先把中心换算回原点再交给引擎。</p>
     */
    private static boolean canPlaceBuildingAt(UnitType type, float centerX, float centerY, PlayerTeam team) {
        BaseUnit prototype = BaseUnit.findTurretPosition(type);
        float offsetX = prototype == null ? 0.0f : prototype.getTileOffsetX();
        float offsetY = prototype == null ? 0.0f : prototype.getTileOffsetY();
        float[] snapped = snapBuildingPosition(type, centerX, centerY);
        float oldX = prototype == null ? 0.0f : prototype.posX;
        float oldY = prototype == null ? 0.0f : prototype.posY;
        PlayerTeam oldTeam = prototype == null ? null : prototype.team;
        try {
            return BaseBuilding.canBuildingBePlacedAt(
                    type, snapped[0] - offsetX, snapped[1] - offsetY, team);
        } finally {
            if (prototype != null) {
                prototype.posX = oldX;
                prototype.posY = oldY;
                if (oldTeam != null) prototype.setUnitTeam(oldTeam);
            }
        }
    }

    /**
     * 把原版放置失败的原因翻译成可读文本。
     *
     * <p>原版 {@code getPlacementFailureReason} 返回的是 {@code {0}/{2}/{3}} 占位符，
     * 直接丢弃会让 agent 只能看到“terrain, resource pool, collision or fog”这种含糊提示。
     * 这里按引擎自己的判定复算一次，并补充“该格当前不可见，可能有敌方单位阻挡”的提示——
     * 迷雾里看不见的敌方单位同样会让放置失败，玩家工具链必须说明这一点。</p>
     */
    private static String placementFailureReason(UnitType type, float[] snapped, PlayerTeam team) {
        GameEngine engine = GameEngine.getInstance();
        BaseUnit prototype = BaseUnit.findTurretPosition(type);
        if (!(prototype instanceof OrderableUnit) || engine.tileMap == null) {
            return "cannot place here at " + (int) snapped[0] + "," + (int) snapped[1];
        }
        TileMap map = engine.tileMap;
        float oldX = prototype.posX;
        float oldY = prototype.posY;
        PlayerTeam oldTeam = prototype.team;
        // Position the shared prototype exactly where the building would land. The engine's
        // own placement helper does the same, so this only reproduces its semantics.
        prototype.posX = snapped[0];
        prototype.posY = snapped[1];
        prototype.setUnitTeam(team);
        String token;
        try {
            token = ((OrderableUnit) prototype).getPlacementFailureReason(false, null);
        } finally {
            prototype.posX = oldX;
            prototype.posY = oldY;
            if (oldTeam != null) prototype.setUnitTeam(oldTeam);
        }
        String reason;
        if (token == null) {
            reason = "unknown rejection";
        } else {
            switch (token) {
                case "{0}":
                    reason = "位置被阻挡（地形、建筑或单位占位）"
                            + blockerDescription(type, snapped, team);
                    break;
                case "{2}":
                    reason = "该格不是资源池（资源提取器必须建在矿上）";
                    break;
                case "{3}":
                    reason = "该建筑需要水面" + waterFootprintDetail(type, snapped);
                    break;
                default:
                    reason = token;
                    break;
            }
        }
        int tileX = (int) ((snapped[0] - prototype.getTileOffsetX()) * map.tileScaleX);
        int tileY = (int) ((snapped[1] - prototype.getTileOffsetY()) * map.tileScaleY);
        boolean visible = map.isInBounds(tileX, tileY) && map.isTileVisibleForTeam(tileX, tileY, team);
        return "cannot place here at " + (int) snapped[0] + "," + (int) snapped[1]
                + ": " + reason
                + (visible ? "" : "；该格当前不可见，可能有未发现的敌方单位占位");
    }

    /**
     * 引擎只回一句“需要水面”，不说是哪一格挡住的。海军基地的占地很大（SeaFactory 实际校验
     * 用的是 5×6 的 velocity 矩形），沿岸差一格就放不下，所以这里按引擎同样的矩形扫描，
     * 把落在陆地上的格子（最多 3 个）指出来；全在水面却仍被拒时，说明是水域本身不可建造。
     */
    private static String waterFootprintDetail(UnitType type, float[] snapped) {
        GameEngine engine = GameEngine.getInstance();
        BaseUnit prototype = BaseUnit.findTurretPosition(type);
        if (engine == null || engine.tileMap == null || prototype == null || prototype.cd() == null) {
            return "";
        }
        TileMap map = engine.tileMap;
        io.github.rwx.geometry.Rect footprint = prototype.cd();
        int anchorX = (int) (((snapped[0] - prototype.getTileOffsetX()) + 1.0f) * map.tileScaleX);
        int anchorY = (int) (((snapped[1] - prototype.getTileOffsetY()) + 1.0f) * map.tileScaleY);
        List<String> land = new ArrayList<>();
        for (int dx = footprint.a; dx <= footprint.c; dx++) {
            for (int dy = footprint.b; dy <= footprint.d; dy++) {
                int tileX = anchorX + dx;
                int tileY = anchorY + dy;
                if (!map.isInBounds(tileX, tileY)) {
                    if (land.size() < 3) land.add("(" + tileX + "," + tileY + ") 地图外");
                    continue;
                }
                MapTile tile = map.getTileAt(tileX, tileY);
                if (tile != null && tile.isWater) continue;
                if (land.size() < 3) {
                    land.add("(" + (int) AiMapGraph.tileCenterX(tileX) + ","
                            + (int) AiMapGraph.tileCenterY(tileY) + ")");
                }
            }
        }
        if (land.isEmpty()) {
            return "：整块占地都在水面，但这片水域当前不能建造（可能有建筑占位或地形阻挡）";
        }
        return "：整块占地必须都是水面，但 " + String.join("、", land) + " 处是陆地";
    }

    /**
     * Whether a builder can stand on or next to the finished footprint.
     *
     * <p>The old check compared the <em>centre</em> tile's terrain region with the builder's. That
     * is right for a land building, but a sea factory's centre is water: a land engineer could
     * never "reach" it even when its own beach tile touches the footprint. {@code map-place} then
     * said "placeable" while {@code order-build} rejected it as a different terrain region — the
     * exact disagreement that made a navy impossible to start.</p>
     *
     * <p>Now the site's footprint (in tiles, from the building prototype) is inflated by one tile
     * and every candidate tile is checked against the builder's static movement region. For a
     * coastal sea factory those candidates include the adjacent land, for a water builder they
     * include the water under the footprint.</p>
     */
    private static boolean canReachBuildSite(OrderableUnit builder, UnitType type, float x, float y) {
        UnitMovementType movement = builder.getMovementType();
        if (movement == null || movement == UnitMovementType.AIR || movement == UnitMovementType.NONE
                || movement == UnitMovementType.BUILDING) {
            return true;
        }
        short builderRegion = AiMapGraph.regionId(builder.posX, builder.posY, movement);
        // No region table / builder standing on odd terrain: do not block the order on a guess.
        if (builderRegion <= 0) return true;
        TileMap map = GameEngine.getInstance().tileMap;
        if (map == null) return true;
        int siteTileX = AiMapGraph.worldToTileX(x);
        int siteTileY = AiMapGraph.worldToTileY(y);
        if (!map.isInBounds(siteTileX, siteTileY)) return false;

        // Footprint rectangles are stored in tile units relative to the site tile. A stock
        // building that leaves the rect empty is a single tile.
        BaseUnit prototype = BaseUnit.findTurretPosition(type);
        int minDX = -1;
        int maxDX = 1;
        int minDY = -1;
        int maxDY = 1;
        if (prototype != null && prototype.cc() != null) {
            io.github.rwx.geometry.Rect footprint = prototype.cc();
            if (footprint.c >= footprint.a && footprint.d >= footprint.b
                    && (footprint.c > footprint.a || footprint.d > footprint.b)) {
                minDX = Math.max(-6, footprint.a - 1);
                maxDX = Math.min(6, footprint.c + 1);
                minDY = Math.max(-6, footprint.b - 1);
                maxDY = Math.min(6, footprint.d + 1);
            }
        }
        for (int dx = minDX; dx <= maxDX; dx++) {
            for (int dy = minDY; dy <= maxDY; dy++) {
                int tileX = siteTileX + dx;
                int tileY = siteTileY + dy;
                if (!map.isInBounds(tileX, tileY)) continue;
                float candidateX = AiMapGraph.tileCenterX(tileX);
                float candidateY = AiMapGraph.tileCenterY(tileY);
                if (AiMapGraph.regionId(candidateX, candidateY, movement) == builderRegion) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Names the nearest unit/building occupying a rejected footprint, if any. */
    private static String blockerDescription(UnitType type, float[] snapped, PlayerTeam team) {
        BaseUnit prototype = BaseUnit.findTurretPosition(type);
        float footprint = prototype == null ? 10.0f : Math.max(10.0f, prototype.radius);
        BaseUnit nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        BaseUnit[] units = BaseUnit.bE.a();
        for (int index = 0; index < BaseUnit.bE.size(); index++) {
            BaseUnit unit = units[index];
            if (unit == null || unit.isDead) continue;
            // Fog: naming a hidden enemy (type, id, position) would scout through the fog.
            // The caller already appends the generic "该格当前不可见" hint for that case.
            if (!isObservable(unit, team)) continue;
            float distance = distance(snapped[0], snapped[1], unit.posX, unit.posY);
            if (distance > footprint + unit.radius + 12.0f) continue;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = unit;
            }
        }
        if (nearest == null) return "";
        return "；blockedBy: " + nearest.r().v() + " id=" + nearest.objectId
                + " team=" + (nearest.team == null ? -1 : nearest.team.teamId)
                + " at " + (int) nearest.posX + "," + (int) nearest.posY;
    }

    /** Finds the nearest placeable centres around a rejected request, ring by ring. */
    private static List<RwxLocalGameTypes.PlacementSuggestion> findNearestPlacements(
            UnitType type, float centerX, float centerY, PlayerTeam team, int maxRadius,
            float snappedX, float snappedY, OrderableUnit builder) {
        GameEngine engine = GameEngine.getInstance();
        TileMap map = engine.tileMap;
        if (map == null) return Collections.emptyList();
        BaseUnit prototype = BaseUnit.findTurretPosition(type);
        if (prototype == null) return Collections.emptyList();
        float offsetX = prototype.getTileOffsetX();
        float offsetY = prototype.getTileOffsetY();
        int baseTileX = (int) (((centerX - offsetX) + 1.0f) * map.tileScaleX);
        int baseTileY = (int) (((centerY - offsetY) + 1.0f) * map.tileScaleY);
        float tileStep = Math.min(map.tileWorldSizeX, map.tileWorldSizeY);
        List<RwxLocalGameTypes.PlacementSuggestion> found = new ArrayList<>();
        float bestDistance = Float.MAX_VALUE;
        for (int radius = 1; radius <= maxRadius; radius++) {
            // Every tile in ring `radius` is at least (radius-1) tile steps away. Once that
            // lower bound exceeds the best candidate we already have, later rings cannot win.
            if (!found.isEmpty() && (radius - 1) * tileStep > bestDistance) break;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                    int tileX = baseTileX + dx;
                    int tileY = baseTileY + dy;
                    if (!map.isInBounds(tileX, tileY)) continue;
                    float candidateX = tileX * map.tileWorldSizeX + offsetX;
                    float candidateY = tileY * map.tileWorldSizeY + offsetY;
                    float[] candidateSnapped = snapBuildingPosition(type, candidateX, candidateY);
                    if (Math.abs(candidateSnapped[0] - snappedX) < 1.0f
                            && Math.abs(candidateSnapped[1] - snappedY) < 1.0f) {
                        continue;
                    }
                    if (!canPlaceBuildingAt(type, candidateX, candidateY, team)) continue;
                    if (builder != null
                            && !canReachBuildSite(builder, type, candidateSnapped[0], candidateSnapped[1])) {
                        continue;
                    }
                    float candidateDistance = distance(centerX, centerY, candidateX, candidateY);
                    if (candidateDistance < bestDistance) bestDistance = candidateDistance;
                    found.add(new RwxLocalGameTypes.PlacementSuggestion(
                            candidateX, candidateY, candidateDistance,
                            candidateSnapped[0], candidateSnapped[1]));
                }
            }
        }
        found.sort((left, right) -> Float.compare(left.distance, right.distance));
        if (found.size() > MAX_PLACEMENT_SUGGESTIONS) {
            return new ArrayList<>(found.subList(0, MAX_PLACEMENT_SUGGESTIONS));
        }
        return found;
    }

    // ---------------------------------------------------------------- reach helpers

    /**
     * Walks the straight line between two world points on the tile grid and reports which kind
     * of terrain blocks it. This is intentionally cheap (Bresenham, one tile per step); it is a
     * diagnosis for "why can't I go straight there", not a second pathfinder.
     */
    private static StraightScan scanStraightLine(float startWorldX, float startWorldY,
                                                 float goalWorldX, float goalWorldY,
                                                 AiMapGraph graph) {
        TileMap map = GameEngine.getInstance().tileMap;
        if (map == null) return new StraightScan(true, 0, Collections.<String>emptyList(), null);
        int startX = AiMapGraph.worldToTileX(startWorldX);
        int startY = AiMapGraph.worldToTileY(startWorldY);
        int goalX = AiMapGraph.worldToTileX(goalWorldX);
        int goalY = AiMapGraph.worldToTileY(goalWorldY);
        int dx = Math.abs(goalX - startX);
        int sx = startX < goalX ? 1 : -1;
        int dy = -Math.abs(goalY - startY);
        int sy = startY < goalY ? 1 : -1;
        int error = dx + dy;
        int tileX = startX;
        int tileY = startY;
        int blocked = 0;
        List<String> kinds = new ArrayList<>();
        while (true) {
            boolean isStart = tileX == startX && tileY == startY;
            if (!isStart && !graph.passable(tileX, tileY)) {
                blocked++;
                String kind = blockerKind(map.getTileAt(tileX, tileY));
                if (!kinds.contains(kind)) kinds.add(kind);
            }
            if (tileX == goalX && tileY == goalY) break;
            int doubled = error * 2;
            if (doubled >= dy) {
                error += dy;
                tileX += sx;
            }
            if (doubled <= dx) {
                error += dx;
                tileY += sy;
            }
        }
        String summary = blocked == 0 ? null : summarizeBlockage(kinds);
        return new StraightScan(blocked == 0, blocked, kinds, summary);
    }

    private static String blockerKind(MapTile tile) {
        if (tile == null) return "地形";
        if (tile.isLava) return "岩浆";
        if (tile.isWater && !tile.isWaterBridge) return "河流";
        if (tile.isCliff) return "山地";
        if (tile.tileset != null && "mountain.png".equals(tile.tileset.imageKey)) return "山地";
        if (tile.hasLargeObject || tile.movementBlockLevel < 0) return "山地/岩石";
        return "地形";
    }

    private static String summarizeBlockage(List<String> kinds) {
        if (kinds.isEmpty()) return "地形阻挡";
        StringBuilder summary = new StringBuilder();
        for (int index = 0; index < kinds.size(); index++) {
            if (index > 0) summary.append('+');
            summary.append(kinds.get(index));
        }
        return summary.append("阻挡").toString();
    }

    private static String unreachableReason(String base, StraightScan scan) {
        if (scan == null || scan.blockedTiles == 0) return "完全不可达：" + base;
        return "完全不可达：" + base + "；直线方向有" + scan.summary;
    }

    private static final class StraightScan {
        final boolean clear;
        final int blockedTiles;
        final List<String> blockers;
        final String summary;

        StraightScan(boolean clear, int blockedTiles, List<String> blockers, String summary) {
            this.clear = clear;
            this.blockedTiles = blockedTiles;
            this.blockers = blockers;
            this.summary = summary;
        }
    }

    private static int[] nearestPassableTile(AiMapGraph graph, int tileX, int tileY, int maxRadius) {
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                    int candidateX = tileX + dx;
                    int candidateY = tileY + dy;
                    if (graph.passable(candidateX, candidateY)) {
                        return new int[] {candidateX, candidateY};
                    }
                }
            }
        }
        return null;
    }

    private static List<RwxLocalGameTypes.Point> sampleWaypoints(
            List<io.github.rwx.geometry.Point> tiles) {
        List<RwxLocalGameTypes.Point> result = new ArrayList<>();
        if (tiles == null || tiles.isEmpty()) return result;
        int maxPoints = 13;
        int count = tiles.size();
        int step = count <= maxPoints ? 1 : (int) Math.ceil((count - 1) / (double) (maxPoints - 1));
        for (int index = 0; index < count; index += step) {
            io.github.rwx.geometry.Point tile = tiles.get(index);
            result.add(new RwxLocalGameTypes.Point(
                    AiMapGraph.tileCenterX(tile.worldX), AiMapGraph.tileCenterY(tile.worldY)));
        }
        io.github.rwx.geometry.Point last = tiles.get(count - 1);
        RwxLocalGameTypes.Point lastPoint = new RwxLocalGameTypes.Point(
                AiMapGraph.tileCenterX(last.worldX), AiMapGraph.tileCenterY(last.worldY));
        if (result.isEmpty() || result.get(result.size() - 1).x != lastPoint.x
                || result.get(result.size() - 1).y != lastPoint.y) {
            result.add(lastPoint);
        }
        return result;
    }

    private static float averageTileSize(GameEngine engine) {
        if (engine.tileMap == null) return 20.0f;
        return (engine.tileMap.tileWorldSizeX + engine.tileMap.tileWorldSizeY) * 0.5f;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static BaseUnit requireTarget(BaseUnit target) {
        if (target == null) throw new IllegalArgumentException("--target is required");
        return target;
    }

    private static UnitType requireUnitType(String typeId) {
        if (typeId == null || typeId.trim().isEmpty()) throw new IllegalArgumentException("--type is required");
        UnitType type = resolveUnitTypeIfKnown(typeId);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Unknown unit type: " + typeId
                            + " (use the internal id, e.g. c_tank; "
                            + RwxCliCommands.Command.QUERY_UNIT.commandName()
                            + " without --name lists every id)");
        }
        return type;
    }

    /** Resolves an exact id, or returns null so fuzzy name matching can handle the token. */
    private static UnitType resolveUnitTypeIfKnown(String typeId) {
        if (typeId == null || typeId.trim().isEmpty()) return null;
        UnitType type = UnitTypeEnum.getUnitTypeByName(typeId);
        if (type == null) type = CustomUnitConfig.getUnitTypeByName(typeId);
        return type;
    }

    private static String requireActionId(String actionId) {
        if (actionId == null || actionId.trim().isEmpty()) throw new IllegalArgumentException("--action is required");
        return actionId;
    }

    private static AttackMode requireAttackMode(String mode) {
        if (mode == null) throw new IllegalArgumentException("--mode is required");
        for (AttackMode candidate : AttackMode.values()) {
            if (candidate.name().equalsIgnoreCase(mode)) return candidate;
        }
        throw new IllegalArgumentException("Unknown attack mode: " + mode
                + " (outOfRange|onlyInRange|returnFire|holdFire|guardArea|aggressive|mixed)");
    }

    /** 该动作是否需要玩家再点一个世界坐标。 */
    private static boolean needsPointTarget(AbstractUnitAction action) {
        switch (action.getActionType()) {
            case placeBuilding:
            case targetGround:
            case attackMove:
            case patrol:
            case setRally:
            case pingMap:
                return true;
            default:
                return action.getActionDisplayType() == com.corrodinggames.rts.game.units.actions.ActionDisplayType.building;
        }
    }

    /** 该动作是否需要玩家再选一个单位目标。 */
    private static boolean needsUnitTarget(AbstractUnitAction action) {
        switch (action.getActionType()) {
            case repairTarget:
            case reclaimTarget:
            case guardUnit:
                return true;
            default:
                return false;
        }
    }

    private static UnsupportedOperationException notImplemented(String operation) {
        return new UnsupportedOperationException("rwx-game-" + operation + " is not implemented yet");
    }

    // ---------------------------------------------------------------- observation ledger

    /** Starts observation independently of CLI polling. */
    public synchronized void startObservationSampler() {
        if (observationSampler != null && observationSampler.isAlive()) return;
        com.corrodinggames.rts.gameFramework.GameStatistics.f.addObserver(eventCollector);
        observationSampler = new Thread(this::observationSampleLoop, "RWX-observation-sampler");
        observationSampler.setDaemon(true);
        observationSampler.start();
    }

    private volatile String lastSamplerError;
    private volatile String observationFailure;

    private void observationSampleLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                GameEngine engine = GameEngine.getInstance();
                if (engine != null && engine.hasLoadedLevel && !engine.isStopped
                        && engine.playerTeam != null && !engine.playerTeam.isTeamSpectator) {
                    onGameThread(() -> {
                        GameEngine current = GameEngine.getInstance();
                        if (!current.hasLoadedLevel || current.isStopped
                                || current.playerTeam == null || current.playerTeam.isTeamSpectator) {
                            return null;
                        }
                        sample(current, current.playerTeam);
                        eventCollector.store.maintain(current.gameTimeMillis);
                        observationFailure = null;
                        return null;
                    }, SAMPLER_TIMEOUT_MILLIS);
                }
                Thread.sleep(OBSERVATION_SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                // A transient game-thread timeout must not stop observation; retry next round.
                // Log real failures (with stack) once per distinct message so a sampler bug is
                // visible in rwx-agent-run.log instead of silently breaking every query.
                String key = error.getClass().getName() + ": " + error.getMessage();
                observationFailure = key;
                if (!key.equals(lastSamplerError)) {
                    lastSamplerError = key;
                    System.err.println("RWX observation sampler failed: " + key);
                    error.printStackTrace();
                }
                try {
                    Thread.sleep(SAMPLER_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Records the currently visible units and turns differences into events. Runs on the game thread. */
    private void sample(GameEngine engine, PlayerTeam controlled) {
        String mapName = engine.getCurrentMapName();
        if (engine.currentTick < lastSampleTick || (lastSampleMap != null && mapName != null
                && !lastSampleMap.equals(mapName))) resetObservation();
        else if (detectClockOverflow(engine)) resetObservation(true);
        lastSampleTick = engine.currentTick;
        lastSampleMap = mapName;
        eventCollector.synchronize(engine, controlled);
        List<BaseUnit> friends = new ArrayList<>(), enemies = new ArrayList<>();
        Set<Long> alive = new HashSet<>();
        List<OrderableUnit> stanceCandidates = new ArrayList<>();
        BaseUnit[] units = BaseUnit.bE.a();
        for (int index = 0; index < BaseUnit.bE.size(); index++) {
            BaseUnit unit = units[index];
            if (unit == null || unit.isDead || unit.team == null || unit.team.teamId < 0) continue;
            String relation = relationOf(unit.team, controlled);
            // Never snapshot hidden enemy positions, health or last-attacker state.
            if ("enemy".equals(relation) && !isObservable(unit, controlled)) continue;
            if ("enemy".equals(relation)) enemies.add(unit);
            else if ("self".equals(relation) || "ally".equals(relation)) friends.add(unit);
            alive.add(unit.objectId);
            teamFirstSeen.putIfAbsent(unit.team.teamId, new float[] {unit.posX, unit.posY});
            if (unit.team == controlled && !everSeenUnitIds.contains(unit.objectId)
                    && !stanceIssuedUnitIds.contains(unit.objectId)) {
                OrderableUnit candidate = defaultStanceCandidate(engine, unit, controlled);
                if (candidate != null) stanceCandidates.add(candidate);
            }
        }
        eventCollector.observe(friends, enemies);
        applyDefaultStance(engine, controlled, stanceCandidates);
        stanceIssuedUnitIds.retainAll(alive);
        for (BaseUnit unit : friends) if (unit.team == controlled) everSeenUnitIds.add(unit.objectId);
        automate.update(engine, controlled);
        try { processPendingOrders(engine, controlled); }
        catch (RuntimeException error) { lastSamplerError = "pending orders: " + error.getMessage(); }
        try { processKeepRules(engine, controlled); }
        catch (RuntimeException error) { lastSamplerError = "keep rules: " + error.getMessage(); }
    }

    /** A new own unit that should get the team's default stance, or null. */
    private OrderableUnit defaultStanceCandidate(GameEngine engine, BaseUnit unit, PlayerTeam controlled) {
        AttackMode mode = defaultAttackModes.get(controlled.teamId);
        if (mode == null) return null;
        if (!(unit instanceof OrderableUnit) || unit.r().isBuildingUnit()) return null;
        OrderableUnit orderable = (OrderableUnit) unit;
        if (!hasWeapon(orderable) || orderable.attackMode == mode) return null;
        // During playback the recorded command already sets the stance.
        if (engine.replayEngine.j()) return null;
        return orderable;
    }

    /** One recorded stance-only Command for all new units of this sample (see applyAttackMode). */
    private void applyDefaultStance(GameEngine engine, PlayerTeam controlled, List<OrderableUnit> units) {
        if (units.isEmpty()) return;
        AttackMode mode = defaultAttackModes.get(controlled.teamId);
        if (mode == null) return;
        Command command = engine.commandController.newCommandForTeam(controlled);
        try {
            for (OrderableUnit unit : units) command.addUnitToCommand(unit);
            command.setAttackMode(mode);
        } catch (RuntimeException error) {
            engine.commandController.pendingCommands.remove(command);
            throw error;
        }
        for (OrderableUnit unit : units) stanceIssuedUnitIds.add(unit.objectId);
    }

    private void resetObservation() {
        resetObservation(false);
    }

    /**
     * Clears the observation ledgers. {@code keepUserState} keeps the saved groups and the default
     * stance settings, which survive a clock wrap inside the same match but not a map reload.
     */
    private void resetObservation(boolean keepUserState) {

        nukeLaunches.clear();
        pendingActions.clear();
        pendingRecent.clear();
        keepRules.clear();
        keepCommands.clear();
        keepRecent.clear();
        eventCollector.clear();
        unitListBaseline.clear();
        teamFirstSeen.clear();
        if (!keepUserState) {
            groups.clear();
            defaultAttackModes.clear();
        }
        everSeenUnitIds.clear();
        stanceIssuedUnitIds.clear();

        lastSampleTick = -1;
        lastSampleMap = null;
    }

    /**
     * Spots the engine's {@code int} game clock wrapping past {@code 2^31} ms (~24.8 game days).
     * A backwards jump that large inside one match means every timestamp already in the ledgers
     * sits in the "future", so the caller resets them; {@code tick} is still monotonic there.
     */
    private boolean detectClockOverflow(GameEngine engine) {
        int now = engine.gameTimeMillis;
        boolean wrapped = lastSampleTick >= 0
                && lastObservedMillis != Integer.MIN_VALUE
                && now < lastObservedMillis - 60_000
                && engine.currentTick >= lastSampleTick;
        lastObservedMillis = now;
        if (!wrapped) return false;
        clockOverflowed = true;
        System.err.println("RWX CLI: the engine game clock wrapped (int overflow at ~24.8 game days);"
                + " observation ledgers were reset and absolute time restarts from 0");
        return true;
    }

    // ---------------------------------------------------------------- game thread plumbing

    /**
     * 把一次引擎访问送到游戏线程执行。
     *
     * <p>暂停/停止时不能只排队：桌面版 Slick 帧驱动在 {@code gameSpeed == 0}
     * 或 {@code isPaused} 时只画暂停帧（{@code drawWorldOnlyThreadSafe}），
     * 根本不会再调用 {@code gameLoop}，于是 {@code pendingGameThreadTasks}
     * 永远不会被消费——连下一条“恢复暂停”的命令都会超时，形成死锁。
     * 这时模拟线程已经停下，直接在状态锁内执行是安全的，也是唯一能解除暂停的路径。</p>
     */
    private static <T> T onGameThread(Callable<T> work) {
        return onGameThread(work, GAME_THREAD_TIMEOUT_MILLIS);
    }

    /** Same hand-off with a caller-chosen deadline; the background sampler uses a short one. */
    private static <T> T onGameThread(Callable<T> work, long timeoutMillis) {
        GameEngine engine = GameEngine.getInstance();
        if (isSimulationIdle(engine)) {
            synchronized (engine.gameStateLock) {
                return callNow(work);
            }
        }
        FutureTask<T> task = new FutureTask<>(work);
        engine.queueGameThreadTask(task);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            try {
                return task.get(GAME_THREAD_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the game thread", error);
            } catch (ExecutionException error) {
                throw rethrow(error);
            } catch (TimeoutException error) {
                // 暂停/速度 0/已停止时模拟线程不会再消费任务队列：此时直接在状态锁内执行。
                if (isSimulationIdle(engine)) {
                    synchronized (engine.gameStateLock) {
                        if (!task.isDone()) task.run();
                    }
                    continue;
                }
                if (System.currentTimeMillis() >= deadline) {
                    // The task is still parked in the engine queue. Cancel it so it cannot run
                    // later against a completely different game state (the queue has no removal
                    // API; a cancelled FutureTask is skipped when the game thread drains it).
                    task.cancel(false);
                    // 开局读图阶段循环还没开始跑，队列自然没人消费；给出可重试的明确提示。
                    throw new IllegalStateException(engine.hasLoadedLevel
                            ? "Timed out waiting for the game thread"
                            : "The game is still loading (map not ready); retry in a moment", error);
                }
            }
        }
    }

    /**
     * Paused orders may only bypass the engine's command queue when nothing records the game.
     * While a replay records, a command executed between ticks runs before that tick's
     * gameTimeMillis advance and its path requests start during the pause, whereas playback
     * executes the same command inside the tick. The drift is invisible at first and surfaced as
     * "checksums don't match" about 350 ticks after a paused order batch (frame 38052). Leaving
     * the command in pendingCommands makes executeLocalCommands record and run it exactly like
     * a click made while paused.
     */
    private static boolean canApplyImmediately(GameEngine engine) {
        return isSimulationIdle(engine)
                && !engine.networkEngine.networkGameActive
                && !engine.replayEngine.i();
    }

    private static String queuedNote(GameEngine engine) {
        if (isSimulationIdle(engine) && engine.replayEngine.k()) {
            return " (queued: a replay is recording, so paused orders take effect on the next tick"
                    + " to keep playback in sync; unit-show will show them after the game resumes)";
        }
        return " (queued for the next simulation tick)";
    }

    /** 模拟线程当前不会推进（暂停、速度 0 或已停止）。 */
    private static boolean isSimulationIdle(GameEngine engine) {
        return engine != null
                && (engine.isStopped || engine.isPaused || engine.gameSpeed == 0.0f);
    }

    private static <T> T callNow(Callable<T> work) {
        try {
            return work.call();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Game-thread operation failed", error);
        }
    }

    private static RuntimeException rethrow(ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        return new IllegalStateException("Game-thread operation failed", cause);
    }
}
