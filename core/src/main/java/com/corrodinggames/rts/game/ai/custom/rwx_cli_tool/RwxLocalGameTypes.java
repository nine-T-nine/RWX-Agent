package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable transport models for {@link RwxLocalGameService}. */
public final class RwxLocalGameTypes {
    private RwxLocalGameTypes() { }

    public enum UnitOwner { SELF, ALLY, ENEMY, NEUTRAL }

    public enum OrderType {
        MOVE, ATTACK_MOVE, ATTACK, STOP, PATROL, GUARD, GUARD_AT, FOLLOW,
        REPAIR, RECLAIM, LOAD_INTO, LOAD_UP, BUILD, ACTION, ATTACK_MODE, RALLY
    }

    public enum EventType { DAMAGED, DESTROYED, LOSSES, INFO, NEW }

    public static final class GameSnapshot {
        public final int tick;
        public final int gameTimeMillis;
        public final boolean paused;
        public final float speed;
        public final String mapName;
        public final int controlledTeamId;
        public final String outcome;
        /** Own credits, or null when the game exposes no economy to the controlled team. */
        public final Double credits;
        /** Own credits per second, as reported by the engine's economy model. */
        public final Double incomePerSecond;
        /** Own live units/buildings, excluding queued production. */
        public final Integer unitCount;
        /**
         * True once the engine's {@code int} game clock wrapped past {@code 2^31} ms (~24.8 game
         * days, ~9 h wall clock at 64x). Absolute {@code time} restarts from 0 and only the data
         * observed after the wrap is windowed; the flag lets the reply say so instead of silently
         * reporting a clock that jumped backwards.
         */
        public final boolean clockOverflowed;

        public GameSnapshot(int tick, int gameTimeMillis, boolean paused, float speed,
                            String mapName, int controlledTeamId, String outcome,
                            Double credits, Double incomePerSecond, Integer unitCount,
                            boolean clockOverflowed) {
            this.tick = tick;
            this.gameTimeMillis = gameTimeMillis;
            this.paused = paused;
            this.speed = speed;
            this.mapName = mapName;
            this.controlledTeamId = controlledTeamId;
            this.outcome = outcome;
            this.credits = credits;
            this.incomePerSecond = incomePerSecond;
            this.unitCount = unitCount;
            this.clockOverflowed = clockOverflowed;
        }
    }

    public static final class TeamSnapshot {
        public final int teamId;
        public final String name;
        public final String relation;
        public final int teamColor;
        /** True for an AI slot (including spectating AI slots) rather than a human player. */
        public final boolean ai;
        /** active / defeated. Available under fog so victory state remains auditable. */
        public final String status;
        /** Null when the player UI has no right to inspect this team's economy. */
        public final Double credits;
        /** Null when the player UI has no right to inspect this team's economy. */
        public final Double incomePerSecond;
        /** Null when the player UI has no right to inspect this team's population. */
        public final Integer unitCount;

        public TeamSnapshot(int teamId, String name, String relation, int teamColor, boolean ai,
                            String status, Double credits, Double incomePerSecond, Integer unitCount) {
            this.teamId = teamId;
            this.name = name;
            this.relation = relation;
            this.teamColor = teamColor;
            this.ai = ai;
            this.status = status;
            this.credits = credits;
            this.incomePerSecond = incomePerSecond;
            this.unitCount = unitCount;
        }
    }

    public static final class UnitQuery {
        public final UnitOwner owner;
        /** Multiple type ids are accepted; empty means no type filter. */
        public final List<String> unitTypeIds;
        public final Float x;
        public final Float y;
        public final Float radius;
        /** Only units with nothing to do: no waypoint, no factory queue. */
        public final boolean idleOnly;
        /** Saved group membership filter, applied to visible units only. */
        public final String groupName;
        public final boolean summaryOnly;
        public final boolean excludeBuilders;
        /** Selector such as {@code area:unit:123,5}; null when plain filters are used. */
        public final String selector;
        /** Only units of these types (internal id or display name). */
        public final List<String> includeTypes;
        /** Never units of these types. */
        public final List<String> excludeTypes;
        /** Only units below this percentage of max health. */
        public final Float hpBelowPercent;

        public UnitQuery(UnitOwner owner, String unitTypeId, Float x, Float y, Float radius) {
            this(owner, unitTypeId == null ? Collections.<String>emptyList()
                    : Collections.singletonList(unitTypeId), x, y, radius);
        }

        public UnitQuery(UnitOwner owner, List<String> unitTypeIds, Float x, Float y, Float radius) {
            this(owner, unitTypeIds, x, y, radius, false);
        }

        public UnitQuery(UnitOwner owner, List<String> unitTypeIds, Float x, Float y, Float radius,
                         boolean idleOnly) {
            this(owner, unitTypeIds, x, y, radius, idleOnly, null);
        }

        public UnitQuery(UnitOwner owner, List<String> unitTypeIds, Float x, Float y, Float radius,
                         boolean idleOnly, String groupName) {
            this(owner, unitTypeIds, x, y, radius, idleOnly, groupName, false);
        }

        public UnitQuery(UnitOwner owner, List<String> unitTypeIds, Float x, Float y, Float radius,
                         boolean idleOnly, String groupName, boolean summaryOnly) {
            this(owner, unitTypeIds, x, y, radius, idleOnly, groupName, summaryOnly, false);
        }

        public UnitQuery(UnitOwner owner, List<String> unitTypeIds, Float x, Float y, Float radius,
                         boolean idleOnly, String groupName,
                         boolean summaryOnly, boolean excludeBuilders) {
            this(owner, unitTypeIds, x, y, radius, idleOnly, groupName, summaryOnly,
                    excludeBuilders, null, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), null);
        }

        public UnitQuery(UnitOwner owner, List<String> unitTypeIds, Float x, Float y, Float radius,
                         boolean idleOnly, String groupName,
                         boolean summaryOnly, boolean excludeBuilders, String selector,
                         List<String> includeTypes, List<String> excludeTypes, Float hpBelowPercent) {
            this.owner = owner;
            this.unitTypeIds = immutable(unitTypeIds);
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.idleOnly = idleOnly;
            this.groupName = groupName;
            this.summaryOnly = summaryOnly;
            this.excludeBuilders = excludeBuilders;
            this.selector = selector;
            this.includeTypes = immutable(includeTypes);
            this.excludeTypes = immutable(excludeTypes);
            this.hpBelowPercent = hpBelowPercent;
        }
    }

    /** One entry of a builder's waypoint queue or a factory's production queue. */
    public static final class QueueItem {
        /** Waypoint command type (build/move/attack/...) or "produce" for a factory queue. */
        public final String command;
        /** Produced/built unit type id; null for non-build commands. */
        public final String unitTypeId;
        /** Localized display name of {@link #unitTypeId}; null when not a build command. */
        public final String unitTypeName;
        public final Float x;
        public final Float y;
        public final Long targetUnitId;
        /** 0..1 build/production progress; null for non-progress entries. */
        public final Float progress;

        public QueueItem(String command, String unitTypeId, String unitTypeName,
                         Float x, Float y, Long targetUnitId) {
            this(command, unitTypeId, unitTypeName, x, y, targetUnitId, null);
        }

        public QueueItem(String command, String unitTypeId, String unitTypeName,
                         Float x, Float y, Long targetUnitId, Float progress) {
            this.command = command;
            this.unitTypeId = unitTypeId;
            this.unitTypeName = unitTypeName;
            this.x = x;
            this.y = y;
            this.targetUnitId = targetUnitId;
            this.progress = progress;
        }
    }

    public static final class UnitSnapshot {
        public final long id;
        public final String unitTypeId;
        public final String displayName;
        public final int teamId;
        /** self / ally / enemy / neutral, from the controlled player's point of view. */
        public final String relation;
        public final float x;
        public final float y;
        public final float health;
        public final float maxHealth;
        public final float buildProgress;
        /** Unit type tech tier (UnitType.g(), normally 1..3), i.e. T1/T2/T3. */
        public final int techLevel;
        /** Instance upgrade level (BaseUnit.getTechLevel()); 1 when the unit has no upgrades. */
        public final int upgradeLevel;
        public final boolean controllable;
        public final String movementType;
        public final String currentOrder;
        public final Long targetUnitId;
        public final Long transportId;
        /** 当前 waypoint 的目标坐标（仅当有 waypoint 时非空）。 */
        public final Float waypointX;
        public final Float waypointY;
        /** Engine attack stance; null for units that cannot take orders. */
        public final String attackMode;
        /** Factory build queue length; null when the unit is not a factory. */
        public final Integer queueSize;
        /** Rally point of a factory; null when unset or not a factory. */
        public final Float rallyX;
        public final Float rallyY;
        /** Full production queue of a factory (empty for non-factories). */
        public final List<QueueItem> productionQueue;
        /** Full waypoint queue of a controllable unit (empty for buildings/enemies). */
        public final List<QueueItem> waypointQueue;
        /** Tick at which this snapshot was taken. */
        public final int tick;

        /** Engine-derived activity, separate from the available-action menu. */
        public List<String> statuses = Collections.emptyList();
        public boolean productionCapable;
        public boolean canProduce;
        public boolean canUpgrade;
        /** Idle computed on the game thread, including allied activity without exposing orders. */
        public Boolean idle;
        public Boolean building;

        public UnitSnapshot(long id, String unitTypeId, String displayName, int teamId,
                            String relation, float x, float y, float health, float maxHealth,
                            float buildProgress, int techLevel, int upgradeLevel, boolean controllable,
                            String movementType, String currentOrder, Long targetUnitId, Long transportId,
                            Float waypointX, Float waypointY, String attackMode, Integer queueSize,
                            Float rallyX, Float rallyY, List<QueueItem> productionQueue,
                            List<QueueItem> waypointQueue, int tick) {
            this.id = id;
            this.unitTypeId = unitTypeId;
            this.displayName = displayName;
            this.teamId = teamId;
            this.relation = relation;
            this.x = x;
            this.y = y;
            this.health = health;
            this.maxHealth = maxHealth;
            this.buildProgress = buildProgress;
            this.techLevel = techLevel;
            this.upgradeLevel = upgradeLevel;
            this.controllable = controllable;
            this.movementType = movementType;
            this.currentOrder = currentOrder;
            this.targetUnitId = targetUnitId;
            this.transportId = transportId;
            this.waypointX = waypointX;
            this.waypointY = waypointY;
            this.attackMode = attackMode;
            this.queueSize = queueSize;
            this.rallyX = rallyX;
            this.rallyY = rallyY;
            this.productionQueue = immutable(productionQueue);
            this.waypointQueue = immutable(waypointQueue);
            this.tick = tick;
        }
    }

    /** All values are frozen when the event occurs. Hidden coordinates are null. */
    public static final class EventEntity {
        public final long id;
        public final String typeId, name, relation;
        public final int teamId;
        public final Float x, y;
        public EventEntity(long id, String typeId, String name, int teamId, String relation, Float x, Float y) {
            this.id = id; this.typeId = typeId; this.name = name; this.teamId = teamId;
            this.relation = relation; this.x = x; this.y = y;
        }
    }

    public static final class GameEvent {
        public final int gameTimeMillis;
        public final EventType type;
        public final EventEntity subject, attacker;
        public final String detail;
        public GameEvent(int gameTimeMillis, EventType type, EventEntity subject, EventEntity attacker, String detail) {
            this.gameTimeMillis = gameTimeMillis; this.type = type;
            this.subject = subject; this.attacker = attacker; this.detail = detail;
        }
    }

    public static final class EventQuery {
        public final List<EventType> types;
        public final double seconds;
        public final boolean allies;
        public EventQuery(List<EventType> types, double seconds, boolean allies) {
            if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 180) {
                throw new IllegalArgumentException("--seconds must be greater than 0 and at most 180");
            }
            this.types = types == null ? List.of(EventType.DAMAGED, EventType.DESTROYED, EventType.LOSSES, EventType.NEW)
                    : List.copyOf(types);
            this.seconds = seconds; this.allies = allies;
        }
    }

    public static final class EventPage {
        public final List<String> lines;
        /** Processed, merged events in the same order as lines. */
        public final List<EventRecord> items;
        public EventPage(List<String> lines) { this(lines, Collections.emptyList()); }
        public EventPage(List<String> lines, List<EventRecord> items) {
            this.lines = List.copyOf(lines);
            this.items = List.copyOf(items);
        }
    }

    /** A query result after the shared event processor's filtering and aggregation. */
    public static final class EventRecord {
        public final int gameTimeMillis;
        public final EventType type;
        public final String description;
        public EventRecord(int gameTimeMillis, EventType type, String description) {
            this.gameTimeMillis = gameTimeMillis;
            this.type = type;
            this.description = description;
        }
    }

    /** A separate unit-list snapshot comparison, without an event cursor or retained event history. */
    public static final class UnitChange {
        public final UnitSnapshot unit;
        public final String description;
        public UnitChange(UnitSnapshot unit, String description) { this.unit = unit; this.description = description; }
    }

    /** Filtered units and changes since the previous delta query. */
    public static final class UnitPage {
        public final int tick;
        public final int gameTimeMillis;
        public final List<UnitSnapshot> units;
        public final List<UnitChange> changes;

        public UnitPage(int tick, int gameTimeMillis, List<UnitSnapshot> units, List<UnitChange> changes) {
            this.tick = tick;
            this.gameTimeMillis = gameTimeMillis;
            this.units = immutable(units);
            this.changes = immutable(changes);
        }
    }

    public static final class ActionSnapshot {
        public final String actionId;
        public final String name;
        public final boolean available;
        public final int queuedCount;
        public final boolean needsPointTarget;
        public final boolean needsUnitTarget;
        public final String producedUnitTypeId;
        public final String price;
        /** Null when available; otherwise a user-facing reason the action cannot be issued now. */
        public final String unavailableReason;

        public ActionSnapshot(String actionId, String name, boolean available, int queuedCount,
                              boolean needsPointTarget, boolean needsUnitTarget,
                              String producedUnitTypeId, String price, String unavailableReason) {
            this.actionId = actionId;
            this.name = name;
            this.available = available;
            this.queuedCount = queuedCount;
            this.needsPointTarget = needsPointTarget;
            this.needsUnitTarget = needsUnitTarget;
            this.producedUnitTypeId = producedUnitTypeId;
            this.price = price;
            this.unavailableReason = unavailableReason;
        }
    }

    /**
     * Everything a control loop needs for one decision, in one engine-thread pass: the economy
     * snapshot and owned activity/capabilities. Avoids N+1 reads of individual units.
     */
    public static final class GameStateSnapshot {
        public final GameSnapshot game;
        public final UnitOwner owner;
        public final List<UnitSnapshot> units;
        public final List<UnitSnapshot> allies;
        public final List<UnitSnapshot> visibleEnemies;
        /** Legacy action-menu slot; engine state reads leave it empty. Use actions(unitId) for menus. */
        public final java.util.Map<Long, List<ActionSnapshot>> actions;
        public final int factoryCount;
        public final int idleFactoryCount;

        public GameStateSnapshot(GameSnapshot game, UnitOwner owner, List<UnitSnapshot> units,
                                 java.util.Map<Long, List<ActionSnapshot>> actions,
                                 int factoryCount, int idleFactoryCount) {
            this(game, owner, units, Collections.emptyList(), Collections.emptyList(), actions, factoryCount, idleFactoryCount);
        }

        public GameStateSnapshot(GameSnapshot game, UnitOwner owner, List<UnitSnapshot> units,
                                 List<UnitSnapshot> allies, List<UnitSnapshot> visibleEnemies,
                                 java.util.Map<Long, List<ActionSnapshot>> actions,
                                 int factoryCount, int idleFactoryCount) {
            this.game = game;
            this.owner = owner;
            this.units = immutable(units);
            this.allies = immutable(allies);
            this.visibleEnemies = immutable(visibleEnemies);
            this.actions = Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<Long, List<ActionSnapshot>>(actions));
            this.factoryCount = factoryCount;
            this.idleFactoryCount = idleFactoryCount;
        }
    }

    public static final class MapQuery {
        public final boolean includeFog;
        /** Include one starting position per team (from its command centre). */
        public final boolean includeSpawns;
        /** Include a sampled terrain window ({@code map-show --terrain}). */
        public final boolean includeTerrain;
        /** World-space centre of the terrain window; null means the map centre. */
        public final Float terrainX;
        public final Float terrainY;
        /** Half extents of the rectangular window in tiles (default 8, giving 17x17 cells). */
        public final int terrainHalfWidthTiles;
        public final int terrainHalfHeightTiles;
        /** Emit category characters instead of the default semantic numeric codes. */
        public final boolean terrainAscii;

        public MapQuery(boolean includeFog, boolean includeSpawns) {
            this(includeFog, includeSpawns, false, null, null, 8, 8, false);
        }

        public MapQuery(boolean includeFog, boolean includeSpawns,
                        boolean includeTerrain, Float terrainX, Float terrainY,
                        int terrainHalfWidthTiles, int terrainHalfHeightTiles, boolean terrainAscii) {
            this.includeFog = includeFog;
            this.includeSpawns = includeSpawns;
            this.includeTerrain = includeTerrain;
            this.terrainX = terrainX;
            this.terrainY = terrainY;
            this.terrainHalfWidthTiles = terrainHalfWidthTiles;
            this.terrainHalfHeightTiles = terrainHalfHeightTiles;
            this.terrainAscii = terrainAscii;
        }
    }

    public static final class MapSnapshot {
        public final float width;
        public final float height;
        public final float tileWidth;
        public final float tileHeight;
        public final boolean fogEnabled;
        /** All resource pools and their live building occupancy, independent of visibility. */
        public final List<ResourcePool> resourcePools;
        /** Requested with {@code --spawns}: one entry per team, from its command centre. */
        public final List<TeamSpawn> spawns;
        /** Requested with {@code --terrain}: the sampled window, otherwise null. */
        public final TerrainWindow terrain;

        public MapSnapshot(float width, float height, float tileWidth, float tileHeight,
                           boolean fogEnabled, List<ResourcePool> resourcePools,
                           List<TeamSpawn> spawns, TerrainWindow terrain) {
            this.width = width;
            this.height = height;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.fogEnabled = fogEnabled;
            this.resourcePools = immutable(resourcePools);
            this.spawns = immutable(spawns);
            this.terrain = terrain;
        }
    }

    /** A rectangular slice of semantic terrain codes, with resource and optional fog overlays. */
    public static final class TerrainWindow {
        /** First tile column/row covered by the window (inclusive). */
        public final int tileX;
        public final int tileY;
        public final int widthTiles;
        public final int heightTiles;
        /** World position of the window's top-left tile corner. */
        public final float worldX;
        public final float worldY;
        public final float tileWidth;
        public final float tileHeight;
        /** Row-major values from {@link RwxTerrainCodes}, never raw tileset GIDs. */
        public final int[][] codes;
        public final boolean asciiRequested;
        /** Optional numeric-mode labels; terrain categories remain available for ASCII output. */
        public final String[][] buildingLabels;
        /** True when the requested rectangle was clipped at the map edge. */
        public final boolean clipped;

        public TerrainWindow(int tileX, int tileY, int widthTiles, int heightTiles,
                             float worldX, float worldY, float tileWidth, float tileHeight,
                             int[][] codes, boolean asciiRequested, boolean clipped) {
            this(tileX, tileY, widthTiles, heightTiles, worldX, worldY, tileWidth, tileHeight,
                    codes, asciiRequested, clipped, null);
        }

        public TerrainWindow(int tileX, int tileY, int widthTiles, int heightTiles,
                              float worldX, float worldY, float tileWidth, float tileHeight,
                              int[][] codes, boolean asciiRequested, boolean clipped, String[][] buildingLabels) {
            this.tileX = tileX;
            this.tileY = tileY;
            this.widthTiles = widthTiles;
            this.heightTiles = heightTiles;
            this.worldX = worldX;
            this.worldY = worldY;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.codes = codes;
            this.asciiRequested = asciiRequested;
            this.buildingLabels = buildingLabels;
            this.clipped = clipped;
        }
    }

    public static final class ResourcePool {
        public final float x;
        public final float y;
        /** Any live building covering the pool, including unfinished buildings; null if free. */
        public final Long occupantId;
        public final Integer occupantTeamId;
        public final String occupantType;

        public ResourcePool(float x, float y, Long occupantId, Integer occupantTeamId,
                            String occupantType) {
            this.x = x;
            this.y = y;
            this.occupantId = occupantId;
            this.occupantTeamId = occupantTeamId;
            this.occupantType = occupantType;
        }

        public boolean occupied() {
            return occupantId != null;
        }
    }

    /**
     * One team's starting position, derived from its command centre.
     *
     * <p>Only the {@code teamId}/{@code relation}/position are exposed; the building id follows
     * the same visibility rules as {@code unit-show} (null when it is a fogged enemy building).</p>
     */
    public static final class TeamSpawn {
        public final int teamId;
        public final String teamName;
        public final String relation;
        /** Null for a team whose command centre is not currently observable. */
        public final Long buildingId;
        public final String buildingTypeId;
        /** World position; -1/-1 when the team has never been scouted. */
        public final float x;
        public final float y;
        /** True when the position is the first place this team was seen, not its command centre. */
        public final boolean approximate;

        public TeamSpawn(int teamId, String teamName, String relation, Long buildingId,
                         String buildingTypeId, float x, float y, boolean approximate) {
            this.teamId = teamId;
            this.teamName = teamName;
            this.relation = relation;
            this.buildingId = buildingId;
            this.buildingTypeId = buildingTypeId;
            this.x = x;
            this.y = y;
            this.approximate = approximate;
        }
    }

    public static final class Point {
        public final float x;
        public final float y;

        public Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class FogCell {
        public final int tileX;
        public final int tileY;
        public final boolean explored;
        public final boolean visible;

        public FogCell(int tileX, int tileY, boolean explored, boolean visible) {
            this.tileX = tileX;
            this.tileY = tileY;
            this.explored = explored;
            this.visible = visible;
        }
    }

    /**
     * Result of {@code map-reach}.
     *
     * <p>{@code reachable} means a static-terrain route exists for the unit's movement type.
     * Dynamic blockers (units/buildings) are not part of the analysis: they move away, and
     * treating them as permanent walls makes the answer stale by the next tick.</p>
     */
    public static final class ReachabilitySnapshot {
        public final boolean reachable;
        public final String reason;
        public final String movementType;
        public final int startTileX;
        public final int startTileY;
        public final int goalTileX;
        public final int goalTileY;
        /** True when the requested point was blocked and the nearest passable tile was used. */
        public final boolean goalSnapped;
        public final float goalX;
        public final float goalY;
        /** Tiles traversed by the returned route; 0 when no route exists. */
        public final int pathTiles;
        /** Straight-line world distance between start and goal. */
        public final float straightDistance;
        /** World distance along the found route (N/S/E/W tile steps). */
        public final float pathDistance;
        /** pathDistance / manhattanTiles; 1.0 means no detour beyond the 4-directional bound. */
        public final float detourRatio;
        /** True when the straight line between unit and target crosses no blocking terrain. */
        public final boolean straightReachable;
        /** Number of blocking tiles the straight line crosses. */
        public final int straightBlockedTiles;
        /** Blocking terrain kinds crossed by the straight line: 山地 / 河流 / 岩浆 / 地形. */
        public final List<String> straightBlockers;
        /** Short human-readable summary such as "山地+河流阻挡"; null when the line is clear. */
        public final String straightBlockage;
        /** Sampled route points (tile centers), including start and goal. */
        public final List<Point> waypoints;

        public ReachabilitySnapshot(boolean reachable, String reason, String movementType,
                                    int startTileX, int startTileY, int goalTileX, int goalTileY,
                                    boolean goalSnapped, float goalX, float goalY, int pathTiles,
                                    float straightDistance, float pathDistance, float detourRatio,
                                    List<Point> waypoints) {
            this(reachable, reason, movementType, startTileX, startTileY, goalTileX, goalTileY,
                    goalSnapped, goalX, goalY, pathTiles, straightDistance, pathDistance,
                    detourRatio, true, 0, Collections.<String>emptyList(), null, waypoints);
        }

        public ReachabilitySnapshot(boolean reachable, String reason, String movementType,
                                    int startTileX, int startTileY, int goalTileX, int goalTileY,
                                    boolean goalSnapped, float goalX, float goalY, int pathTiles,
                                    float straightDistance, float pathDistance, float detourRatio,
                                    boolean straightReachable, int straightBlockedTiles,
                                    List<String> straightBlockers, String straightBlockage,
                                    List<Point> waypoints) {
            this.reachable = reachable;
            this.reason = reason;
            this.movementType = movementType;
            this.startTileX = startTileX;
            this.startTileY = startTileY;
            this.goalTileX = goalTileX;
            this.goalTileY = goalTileY;
            this.goalSnapped = goalSnapped;
            this.goalX = goalX;
            this.goalY = goalY;
            this.pathTiles = pathTiles;
            this.straightDistance = straightDistance;
            this.pathDistance = pathDistance;
            this.detourRatio = detourRatio;
            this.straightReachable = straightReachable;
            this.straightBlockedTiles = straightBlockedTiles;
            this.straightBlockers = immutable(straightBlockers);
            this.straightBlockage = straightBlockage;
            this.waypoints = immutable(waypoints);
        }
    }

    /** A nearby alternative for a rejected building placement. */
    public static final class PlacementSuggestion {
        public final float x;
        public final float y;
        /** Distance from the requested centre, in world units. */
        public final float distance;
        public final float snappedX;
        public final float snappedY;

        public PlacementSuggestion(float x, float y, float distance, float snappedX, float snappedY) {
            this.x = x;
            this.y = y;
            this.distance = distance;
            this.snappedX = snappedX;
            this.snappedY = snappedY;
        }
    }

    public static final class PlacementSnapshot {
        public final boolean placeable;
        public final String reason;
        /** Requested centre after footprint snapping; the value order-build would really use. */
        public final float snappedX;
        public final float snappedY;
        /** Up to three nearest placeable positions when {@link #placeable} is false. */
        public final List<PlacementSuggestion> suggestions;
        /** Tile search radius used for {@link #suggestions}. */
        public final int searchRadiusTiles;
        /**
         * True when the supplied builder can stand next to the finished footprint. A water
         * building is reachable from the adjacent shore, even though its centre tile is water.
         */
        public final boolean builderReachable;

        public PlacementSnapshot(boolean placeable, String reason, float snappedX, float snappedY,
                                 List<PlacementSuggestion> suggestions, int searchRadiusTiles) {
            this(placeable, reason, snappedX, snappedY, suggestions, searchRadiusTiles, true);
        }

        public PlacementSnapshot(boolean placeable, String reason, float snappedX, float snappedY,
                                 List<PlacementSuggestion> suggestions, int searchRadiusTiles,
                                 boolean builderReachable) {
            this.placeable = placeable;
            this.reason = reason;
            this.snappedX = snappedX;
            this.snappedY = snappedY;
            this.suggestions = immutable(suggestions);
            this.searchRadiusTiles = searchRadiusTiles;
            this.builderReachable = builderReachable;
        }
    }

    public static final class Order {
        public final OrderType type;
        public final List<Long> unitIds;
        /** Selector such as {@code all-army} or {@code group:main}; ids win when set. */
        public final String unitSelector;
        public final Long targetUnitId;
        public final String unitTypeId;
        public final String actionId;
        public final String attackMode;
        public final Float x;
        public final Float y;
        public final boolean queued;
        /** For {@code order-attack-mode}: also make this the stance for newly produced units. */
        public final boolean applyToNew;
        /** Unit ids the caller explicitly wants left out of this order ({@code --exclude}). */
        public final List<Long> excludedUnitIds;
        /** How many times to repeat the command ({@code --count}); 1 means once. */
        public final int repeatCount;
        /** For {@code order-build}: pick the nearest free spot when the requested one is blocked. */
        public final boolean autoPlace;
        /** Tile radius used by {@link #autoPlace}; 0 disables the search. */
        public final int placementRadius;
        /** Explicit acknowledgement required before reclaiming an owned unit. */
        public final boolean force;
        public final boolean idleOnly;
        public final boolean excludeBuilders;
        /** {@code --type}: only these unit types (internal id or display name, any match). */
        public final List<String> includeTypes;
        /** {@code --exclude} entries that are unit type names rather than ids. */
        public final List<String> excludeTypes;
        /** {@code --hp}: keep only units whose health is below this percentage of max health. */
        public final Float hpBelowPercent;
        /** {@code --allow-unaffordable}: hold the order until credits are available. */
        public final boolean allowUnaffordable;
        /**
         * {@code --after-upgrade}: hold the order while the target factory is still upgrading,
         * then issue it as soon as the upgrade finishes (and its T2/T3 actions exist).
         */
        public final boolean afterUpgrade;

        public Order(OrderType type, List<Long> unitIds, String unitSelector, Long targetUnitId,
                     String unitTypeId, String actionId, String attackMode, Float x, Float y,
                     boolean queued, boolean applyToNew) {
            this(type, unitIds, unitSelector, targetUnitId, unitTypeId, actionId, attackMode, x, y,
                    queued, applyToNew, Collections.<Long>emptyList(), 1, false, 0);
        }

        public Order(OrderType type, List<Long> unitIds, String unitSelector, Long targetUnitId,
                     String unitTypeId, String actionId, String attackMode, Float x, Float y,
                     boolean queued, boolean applyToNew, List<Long> excludedUnitIds,
                     int repeatCount, boolean autoPlace, int placementRadius) {
            this(type, unitIds, unitSelector, targetUnitId, unitTypeId, actionId, attackMode, x, y,
                    queued, applyToNew, excludedUnitIds, repeatCount, autoPlace, placementRadius, false);
        }

        public Order(OrderType type, List<Long> unitIds, String unitSelector, Long targetUnitId,
                     String unitTypeId, String actionId, String attackMode, Float x, Float y,
                     boolean queued, boolean applyToNew, List<Long> excludedUnitIds,
                     int repeatCount, boolean autoPlace, int placementRadius, boolean force) {
            this(type, unitIds, unitSelector, targetUnitId, unitTypeId, actionId, attackMode, x, y,
                    queued, applyToNew, excludedUnitIds, repeatCount, autoPlace, placementRadius,
                    force, false, false);
        }

        public Order(OrderType type, List<Long> unitIds, String unitSelector, Long targetUnitId,
                     String unitTypeId, String actionId, String attackMode, Float x, Float y,
                     boolean queued, boolean applyToNew, List<Long> excludedUnitIds,
                     int repeatCount, boolean autoPlace, int placementRadius, boolean force,
                     boolean idleOnly, boolean excludeBuilders) {
            this(type, unitIds, unitSelector, targetUnitId, unitTypeId, actionId, attackMode, x, y,
                    queued, applyToNew, excludedUnitIds, repeatCount, autoPlace, placementRadius,
                    force, idleOnly, excludeBuilders, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), null, false);
        }

        public Order(OrderType type, List<Long> unitIds, String unitSelector, Long targetUnitId,
                     String unitTypeId, String actionId, String attackMode, Float x, Float y,
                     boolean queued, boolean applyToNew, List<Long> excludedUnitIds,
                     int repeatCount, boolean autoPlace, int placementRadius, boolean force,
                     boolean idleOnly, boolean excludeBuilders, List<String> includeTypes,
                     List<String> excludeTypes, Float hpBelowPercent, boolean allowUnaffordable) {
            this(type, unitIds, unitSelector, targetUnitId, unitTypeId, actionId, attackMode, x, y,
                    queued, applyToNew, excludedUnitIds, repeatCount, autoPlace, placementRadius,
                    force, idleOnly, excludeBuilders, includeTypes, excludeTypes, hpBelowPercent,
                    allowUnaffordable, false);
        }

        public Order(OrderType type, List<Long> unitIds, String unitSelector, Long targetUnitId,
                     String unitTypeId, String actionId, String attackMode, Float x, Float y,
                     boolean queued, boolean applyToNew, List<Long> excludedUnitIds,
                     int repeatCount, boolean autoPlace, int placementRadius, boolean force,
                     boolean idleOnly, boolean excludeBuilders, List<String> includeTypes,
                     List<String> excludeTypes, Float hpBelowPercent, boolean allowUnaffordable,
                     boolean afterUpgrade) {
            this.type = type;
            this.unitIds = immutable(unitIds);
            this.unitSelector = unitSelector;
            this.targetUnitId = targetUnitId;
            this.unitTypeId = unitTypeId;
            this.actionId = actionId;
            this.attackMode = attackMode;
            this.x = x;
            this.y = y;
            this.queued = queued;
            this.applyToNew = applyToNew;
            this.excludedUnitIds = immutable(excludedUnitIds);
            this.repeatCount = Math.max(1, repeatCount);
            this.autoPlace = autoPlace;
            this.placementRadius = Math.max(0, placementRadius);
            this.force = force;
            this.idleOnly = idleOnly;
            this.excludeBuilders = excludeBuilders;
            this.includeTypes = immutable(includeTypes);
            this.excludeTypes = immutable(excludeTypes);
            this.hpBelowPercent = hpBelowPercent;
            this.allowUnaffordable = allowUnaffordable;
            this.afterUpgrade = afterUpgrade;
        }

        /**
         * Copy used by the CLI's hold-and-retry queue: retries must not re-enter the "hold this"
         * path, so both hold flags are explicit here instead of being copied blindly.
         */
        public Order withRetryFlags(boolean retryAllowUnaffordable, boolean retryAfterUpgrade) {
            return new Order(type, unitIds, unitSelector, targetUnitId, unitTypeId, actionId,
                    attackMode, x, y, queued, applyToNew, excludedUnitIds, repeatCount,
                    autoPlace, placementRadius, force, idleOnly, excludeBuilders, includeTypes,
                    excludeTypes, hpBelowPercent, retryAllowUnaffordable, retryAfterUpgrade);
        }

        /** Freeze a delayed order to the units selected when it was accepted, not a future selector. */
        public Order withUnitIds(List<Long> ids) {
            return new Order(type, ids, null, targetUnitId, unitTypeId, actionId,
                    attackMode, x, y, queued, applyToNew, Collections.<Long>emptyList(), repeatCount,
                    autoPlace, placementRadius, force, false, false, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), null, allowUnaffordable, afterUpgrade);
        }
    }

    /** What a {@code Command accepted} receipt actually did. */
    public enum ReceiptStatus {
        /** The engine command was created (and executed now or on the next tick). */
        SUBMITTED,
        /** Held by the CLI; it will be issued later (credits / upgrade completion). */
        PENDING,
        /** A repeated order where some copies were submitted, held or failed. */
        PARTIAL
    }

    public static final class CommandReceipt {
        public final int acceptedTick;
        public final List<Long> affectedUnitIds;
        public final String message;
        /** True when the command was applied by this call instead of on the next simulation tick. */
        public final boolean appliedImmediately;
        public final ReceiptStatus status;

        public CommandReceipt(int acceptedTick, List<Long> affectedUnitIds, String message,
                              boolean appliedImmediately) {
            this(acceptedTick, affectedUnitIds, message, appliedImmediately, ReceiptStatus.SUBMITTED);
        }

        public CommandReceipt(int acceptedTick, List<Long> affectedUnitIds, String message,
                              boolean appliedImmediately, ReceiptStatus status) {
            this.acceptedTick = acceptedTick;
            this.affectedUnitIds = immutable(affectedUnitIds);
            this.message = message;
            this.appliedImmediately = appliedImmediately;
            this.status = status == null ? ReceiptStatus.SUBMITTED : status;
        }
    }

    /** Visible enemy units around a point, classified by what they can shoot. */
    public static final class ThreatUnit {
        public final long id;
        public final String unitTypeId;
        public final String displayName;
        public final int teamId;
        public final float x;
        public final float y;
        public final float distance;
        public final float health;
        public final float maxHealth;
        public final boolean flying;
        public final boolean building;
        public final boolean antiAir;
        public final boolean antiGround;
        public final boolean antiSubmerged;
        public final float range;

        public ThreatUnit(long id, String unitTypeId, String displayName, int teamId,
                          float x, float y, float distance, float health, float maxHealth,
                          boolean flying, boolean building, boolean antiAir, boolean antiGround,
                          boolean antiSubmerged, float range) {
            this.id = id;
            this.unitTypeId = unitTypeId;
            this.displayName = displayName;
            this.teamId = teamId;
            this.x = x;
            this.y = y;
            this.distance = distance;
            this.health = health;
            this.maxHealth = maxHealth;
            this.flying = flying;
            this.building = building;
            this.antiAir = antiAir;
            this.antiGround = antiGround;
            this.antiSubmerged = antiSubmerged;
            this.range = range;
        }
    }

    public static final class ThreatQuery {
        public final float x;
        public final float y;
        public final float radius;
        /** Group nearby enemies into clusters ({@code --cluster}). */
        public final boolean cluster;
        /** World distance used to join two units into the same cluster. */
        public final float clusterRadius;

        public ThreatQuery(float x, float y, float radius) {
            this(x, y, radius, false, 120.0f);
        }

        public ThreatQuery(float x, float y, float radius, boolean cluster, float clusterRadius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.cluster = cluster;
            this.clusterRadius = clusterRadius;
        }
    }

    public static final class ThreatSnapshot {
        public final float x;
        public final float y;
        public final float radius;
        public final int enemyCount;
        public final int airCount;
        public final int groundCount;
        public final int buildingCount;
        public final int antiAirCount;
        public final int antiGroundCount;
        public final int antiSubmergedCount;
        public final float nearestDistance;
        public final List<ThreatUnit> units;
        /** Empty unless the query asked for clusters. */
        public final List<ThreatCluster> clusters;
        /** Cluster join distance used, 0 when clusters were not requested. */
        public final float clusterRadius;

        public ThreatSnapshot(float x, float y, float radius, int enemyCount, int airCount,
                              int groundCount, int buildingCount, int antiAirCount,
                              int antiGroundCount, int antiSubmergedCount, float nearestDistance,
                              List<ThreatUnit> units) {
            this(x, y, radius, enemyCount, airCount, groundCount, buildingCount, antiAirCount,
                    antiGroundCount, antiSubmergedCount, nearestDistance, units,
                    Collections.<ThreatCluster>emptyList(), 0.0f);
        }

        public ThreatSnapshot(float x, float y, float radius, int enemyCount, int airCount,
                              int groundCount, int buildingCount, int antiAirCount,
                              int antiGroundCount, int antiSubmergedCount, float nearestDistance,
                              List<ThreatUnit> units, List<ThreatCluster> clusters,
                              float clusterRadius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.enemyCount = enemyCount;
            this.airCount = airCount;
            this.groundCount = groundCount;
            this.buildingCount = buildingCount;
            this.antiAirCount = antiAirCount;
            this.antiGroundCount = antiGroundCount;
            this.antiSubmergedCount = antiSubmergedCount;
            this.nearestDistance = nearestDistance;
            this.units = immutable(units);
            this.clusters = immutable(clusters);
            this.clusterRadius = clusterRadius;
        }
    }

    /**
     * A group of nearby visible enemies: enough to decide "which blob do I hit first" without
     * walking the individual unit list. The centre is the average position, radius the farthest
     * member from it, and health is the sum of current/max health.
     */
    public static final class ThreatCluster {
        /** 1-based, ordered by distance from the query centre (nearest first). */
        public final int index;
        public final float centerX;
        public final float centerY;
        public final float radius;
        public final int unitCount;
        public final int buildingCount;
        public final int airCount;
        public final int groundCount;
        public final float health;
        public final float maxHealth;
        public final float nearestDistance;
        public final float maxRange;
        public final boolean hasAntiAir;
        public final boolean hasAntiGround;
        public final boolean hasAntiSubmerged;
        /** Per-type counts inside the cluster, largest first. */
        public final List<GroupTypeCount> composition;
        public final List<Long> unitIds;

        public ThreatCluster(int index, float centerX, float centerY, float radius,
                             int unitCount, int buildingCount, int airCount, int groundCount,
                             float health, float maxHealth, float nearestDistance, float maxRange,
                             boolean hasAntiAir, boolean hasAntiGround, boolean hasAntiSubmerged,
                             List<GroupTypeCount> composition, List<Long> unitIds) {
            this.index = index;
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
            this.unitCount = unitCount;
            this.buildingCount = buildingCount;
            this.airCount = airCount;
            this.groundCount = groundCount;
            this.health = health;
            this.maxHealth = maxHealth;
            this.nearestDistance = nearestDistance;
            this.maxRange = maxRange;
            this.hasAntiAir = hasAntiAir;
            this.hasAntiGround = hasAntiGround;
            this.hasAntiSubmerged = hasAntiSubmerged;
            this.composition = immutable(composition);
            this.unitIds = immutable(unitIds);
        }
    }

    /** Replay recording state for {@code rwx-game-replay-*}. */
    public static final class ReplaySnapshot {
        public final boolean gameActive;
        public final boolean recording;
        public final boolean replaying;
        /** True when recording was armed for the next local match but the match has not loaded. */
        public final boolean armed;
        public final String filePath;
        public final String absolutePath;
        public final long fileSizeBytes;
        public final int commandCount;
        public final int tick;
        public final String message;

        public ReplaySnapshot(boolean gameActive, boolean recording, boolean replaying, boolean armed,
                              String filePath, String absolutePath, long fileSizeBytes,
                              int commandCount, int tick, String message) {
            this.gameActive = gameActive;
            this.recording = recording;
            this.replaying = replaying;
            this.armed = armed;
            this.filePath = filePath;
            this.absolutePath = absolutePath;
            this.fileSizeBytes = fileSizeBytes;
            this.commandCount = commandCount;
            this.tick = tick;
            this.message = message;
        }
    }

    /** Result of {@code rwx-game-step}: run the simulation for a while, then pause again. */
    public static final class StepSnapshot {
        public final GameSnapshot game;
        public final double requestedSeconds;
        /** Game-time actually advanced by this call. */
        public final float advancedSeconds;
        /** True when the game was already paused before stepping. */
        public final boolean wasPaused;
        /** True when the timeout expired before the requested game time elapsed. */
        public final boolean timedOut;
        /**
         * True when the game thread stopped answering reads mid-step (a queued snapshot did not
         * come back in time). The service then paused the match directly instead of waiting for
         * another full round-trip, so {@code game} may be the last good reading.
         */
        public final boolean stalled;

        public StepSnapshot(GameSnapshot game, double requestedSeconds, float advancedSeconds,
                            boolean wasPaused, boolean timedOut, boolean stalled) {
            this.game = game;
            this.requestedSeconds = requestedSeconds;
            this.advancedSeconds = advancedSeconds;
            this.wasPaused = wasPaused;
            this.timedOut = timedOut;
            this.stalled = stalled;
        }
    }

    /** One unit type inside a saved group, e.g. "tank x3". */
    public static final class GroupTypeCount {
        public final String unitTypeId;
        public final String displayName;
        public final int count;

        public GroupTypeCount(String unitTypeId, String displayName, int count) {
            this.unitTypeId = unitTypeId;
            this.displayName = displayName;
            this.count = count;
        }
    }

    /** A saved unit group ({@code group-save}/{@code group-show}). */
    public static final class GroupSnapshot {
        public final String name;
        /** Ids as saved; some may have died since. */
        public final List<Long> unitIds;
        public final int liveCount;
        /** Live units grouped by type, biggest stack first; empty when nothing is alive. */
        public final List<GroupTypeCount> composition;

        public GroupSnapshot(String name, List<Long> unitIds, int liveCount) {
            this(name, unitIds, liveCount, java.util.Collections.<GroupTypeCount>emptyList());
        }

        public GroupSnapshot(String name, List<Long> unitIds, int liveCount,
                             List<GroupTypeCount> composition) {
            this.name = name;
            this.unitIds = immutable(unitIds);
            this.liveCount = liveCount;
            this.composition = immutable(composition);
        }
    }

    /**
     * Result of {@code observe-reset}: event history and the unit-list delta baseline were cleared.
     */
    public static final class ObserveResetSnapshot {
        public final int tick;
        public final int gameTimeMillis;
        public final int clearedEvents;
        /** Groups removed by this reset; 0 when {@code --keep-groups} was used. */
        public final int clearedGroups;
        public final boolean keptGroups;
        /** Default attack stance and the "already received a stance" set are never cleared. */
        public final boolean keptDefaultStance;

        public ObserveResetSnapshot(int tick, int gameTimeMillis, int clearedEvents,
                                    int clearedGroups,
                                    boolean keptGroups, boolean keptDefaultStance) {
            this.tick = tick;
            this.gameTimeMillis = gameTimeMillis;
            this.clearedEvents = clearedEvents;
            this.clearedGroups = clearedGroups;
            this.keptGroups = keptGroups;
            this.keptDefaultStance = keptDefaultStance;
        }
    }

    /** One order held by the CLI because it could not be afforded yet. */
    public static final class PendingOrderSnapshot {
        public final long id;
        public final int createdTick;
        public final int createdTimeMillis;
        public final String kind;
        public final String actionId;
        public final List<Long> unitIds;
        public final Float x;
        public final Float y;
        public final Long targetUnitId;
        /** Why it is still pending, as last reported by the engine. */
        public final String reason;
        /** What it is waiting for: {@code credits} or {@code upgrade}. */
        public final String waitFor;

        public PendingOrderSnapshot(long id, int createdTick, int createdTimeMillis, String kind,
                                    String actionId, List<Long> unitIds, Float x, Float y,
                                    Long targetUnitId, String reason) {
            this(id, createdTick, createdTimeMillis, kind, actionId, unitIds, x, y, targetUnitId,
                    reason, "credits");
        }

        public PendingOrderSnapshot(long id, int createdTick, int createdTimeMillis, String kind,
                                    String actionId, List<Long> unitIds, Float x, Float y,
                                    Long targetUnitId, String reason, String waitFor) {
            this.id = id;
            this.createdTick = createdTick;
            this.createdTimeMillis = createdTimeMillis;
            this.kind = kind;
            this.actionId = actionId;
            this.unitIds = immutable(unitIds);
            this.x = x;
            this.y = y;
            this.targetUnitId = targetUnitId;
            this.reason = reason;
            this.waitFor = waitFor;
        }
    }

    /** Result of {@code pending-list} / {@code pending-clear}. */
    public static final class PendingPage {
        public final int tick;
        public final int gameTimeMillis;
        public final List<PendingOrderSnapshot> orders;
        /** Newest first: what the CLI did with pending items since the last report. */
        public final List<String> recent;
        public final String message;

        public PendingPage(int tick, int gameTimeMillis, List<PendingOrderSnapshot> orders,
                           List<String> recent, String message) {
            this.tick = tick;
            this.gameTimeMillis = gameTimeMillis;
            this.orders = immutable(orders);
            this.recent = immutable(recent);
            this.message = message;
        }
    }

    /** One factory production rule maintained by {@code rwx-game-keep-set}. */
    public static final class KeepRuleSnapshot {
        public final long id;
        public final long unitId;
        public final String actionId;
        /** How many copies the rule keeps queued or in progress. */
        public final int count;
        /** Copies this rule has submitted since it was created. */
        public final int issued;
        /** Current queue depth for the action, as of the last sampler pass. */
        public final int queued;
        /** Human-readable state: waiting for credits, upgrading, topped up, ... */
        public final String state;

        public KeepRuleSnapshot(long id, long unitId, String actionId, int count, int issued,
                                int queued, String state) {
            this.id = id;
            this.unitId = unitId;
            this.actionId = actionId;
            this.count = count;
            this.issued = issued;
            this.queued = queued;
            this.state = state;
        }
    }

    /** Result of {@code rwx-game-keep-set} and {@code rwx-game-keep-list}. */
    public static final class KeepPage {
        public final int tick;
        public final int gameTimeMillis;
        public final List<KeepRuleSnapshot> rules;
        /** Newest first: actions the CLI took on rules since the last report. */
        public final List<String> recent;
        public final String message;

        public KeepPage(int tick, int gameTimeMillis, List<KeepRuleSnapshot> rules,
                        List<String> recent, String message) {
            this.tick = tick;
            this.gameTimeMillis = gameTimeMillis;
            this.rules = immutable(rules);
            this.recent = immutable(recent);
            this.message = message;
        }
    }

    /** A valid order that the engine refused only because it cannot be paid for right now. */
    public static final class CreditShortageException extends IllegalArgumentException {
        public CreditShortageException(String message) {
            super(message);
        }
    }

    /**
     * True when a failure is "buy it later", not "this can never work": a {@link
     * CreditShortageException} or an engine message about credits. Only these are retried by
     * {@code --allow-unaffordable}; anything else is permanent and must not be retried forever.
     */
    public static boolean isCreditShortage(Throwable error) {
        if (error instanceof CreditShortageException) return true;
        String message = error == null ? null : error.getMessage();
        return message != null && (message.startsWith("Insufficient credits for action")
                || message.contains("cannot be afforded by unit")
                || (message.contains("need ") && message.contains("credits, available")));
    }

    /**
     * 当前 GUI 视野（{@code rwx-game-camera}）：世界坐标下的可见矩形、中心、左上角、尺寸和缩放。
     * 坐标都是“世界坐标”，也就是单位坐标、地图坐标同一套数值。
     */
    public static final class CameraSnapshot {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final float centerX;
        public final float centerY;
        public final float zoom;
        /** 允许的缩放区间（由屏幕像素和地图尺寸推出）。 */
        public final float minZoom;
        public final float maxZoom;
        /** 屏幕像素尺寸：GUI 实际绘制区域。 */
        public final float screenWidth;
        public final float screenHeight;
        public final int worldWidth;
        public final int worldHeight;
        /** 本次请求越界被钳制的说明；空列表表示没有发生钳制。 */
        public final List<String> clampNotes;

        public CameraSnapshot(float left, float top, float right, float bottom,
                              float zoom, float minZoom, float maxZoom,
                              float screenWidth, float screenHeight,
                              int worldWidth, int worldHeight, List<String> clampNotes) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.centerX = (left + right) / 2.0f;
            this.centerY = (top + bottom) / 2.0f;
            this.zoom = zoom;
            this.minZoom = minZoom;
            this.maxZoom = maxZoom;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.worldWidth = worldWidth;
            this.worldHeight = worldHeight;
            this.clampNotes = immutable(clampNotes);
        }

        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * {@code auto-set} 的参数补丁：为 null 的字段保持规则当前值。
     *
     * <p>字段语义与 {@code ai_automate.AiAutomateRuleOptions} 一一对应，服务层只做搬运。</p>
     */
    public static final class AutoRuleOptions {
        public String rule;
        /** null 表示默认开启；显式 false 才关闭。 */
        public Boolean enabled;
        public String units;
        /** {@code --group}：已保存编组的名称（可以是数字编号），与 {@link #units} 互斥。 */
        public String group;
        public List<Long> excludedIds = new ArrayList<>();
        public List<String> includeTypes = new ArrayList<>();
        public List<String> excludeTypes = new ArrayList<>();
        public Float hpPercent;
        public Boolean idleOnly;
        public Boolean excludeBuilders;
        public Boolean includeUnits;
        public Float x;
        public Float y;
        public Float radius;
    }

    /** 一条自动化规则的配置与运行状态（{@code auto-list} / {@code auto-show}）。 */
    public static final class AutoRuleSnapshot {
        public final String id;
        public final String label;
        public final String summary;
        public final boolean enabled;
        public final int priority;
        public final int cooldownMillis;
        public final boolean resume;
        public final Float hpPercent;
        public final Float x;
        public final Float y;
        public final Float radius;
        public final String units;
        public final List<String> includeTypes;
        public final List<String> excludeTypes;
        public final List<Long> excludedIds;
        public final boolean idleOnly;
        public final boolean excludeBuilders;
        public final int eligible;
        public final boolean includeUnits;
        public final int engaged;
        public final int triggers;
        public final String state;
        public final List<Long> engagedUnitIds;

        public AutoRuleSnapshot(String id, String label, String summary, boolean enabled,
                                int priority, int cooldownMillis, boolean resume, Float hpPercent,
                                Float x, Float y, Float radius, String units,
                                List<String> includeTypes, List<String> excludeTypes,
                                List<Long> excludedIds, boolean idleOnly, boolean excludeBuilders, boolean includeUnits,
                                int eligible, int engaged, int triggers, String state,
                                List<Long> engagedUnitIds) {
            this.id = id;
            this.label = label;
            this.summary = summary;
            this.enabled = enabled;
            this.priority = priority;
            this.cooldownMillis = cooldownMillis;
            this.resume = resume;
            this.hpPercent = hpPercent;
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.units = units;
            this.includeTypes = immutable(includeTypes);
            this.excludeTypes = immutable(excludeTypes);
            this.excludedIds = immutable(excludedIds);
            this.idleOnly = idleOnly;
            this.excludeBuilders = excludeBuilders;
            this.includeUnits = includeUnits;
            this.eligible = eligible;
            this.engaged = engaged;
            this.triggers = triggers;
            this.state = state;
            this.engagedUnitIds = immutable(engagedUnitIds);
        }
    }

    /** {@code auto-list} / {@code auto-show} / {@code auto-set} / {@code auto-clear} 的结果。 */
    public static final class AutoPage {
        public final int tick;
        public final int gameTimeMillis;
        public final List<AutoRuleSnapshot> rules;
        /** 最新在前：规则触发、接管、恢复、失败记录。 */
        public final List<String> recent;
        public final String message;

        public AutoPage(int tick, int gameTimeMillis, List<AutoRuleSnapshot> rules,
                        List<String> recent, String message) {
            this.tick = tick;
            this.gameTimeMillis = gameTimeMillis;
            this.rules = immutable(rules);
            this.recent = immutable(recent);
            this.message = message == null ? "" : message;
        }
    }
}
