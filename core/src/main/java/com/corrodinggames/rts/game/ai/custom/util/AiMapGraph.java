package com.corrodinggames.rts.game.ai.custom.util;

import com.corrodinggames.rts.game.map.MapTile;
import com.corrodinggames.rts.game.map.TileMap;
import com.corrodinggames.rts.game.units.UnitMovementType;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.pathfinding.PathCostMap;
import com.corrodinggames.rts.gameFramework.utility.GameViewUtils;
import io.github.rwx.geometry.Point;
import io.github.rwx.geometry.PointF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 地图骨架：把静态地形通行表当作一张隐式无向图，节点 = 可通行 tile，边 = 上下左右相邻且两端都可通行。
 * 图上提供区域查询、BFS 距离场与最短路（tile 序列）重建，供建筑布局 AI 使用。
 *
 * <p>使用边界：
 * <ul>
 *   <li>只读静态地形（{@link PathCostMap#d}），不含建筑与单位，所以结果整局有效，可以放心缓存；
 *       换图会让引擎重建 {@code PathCostMap}，{@link #of} 会自动丢弃旧缓存。</li>
 *   <li>只有 LAND / WATER / HOVER / OVER_CLIFF / OVER_CLIFF_WATER 有地形表与区域表；
 *       AIR / BUILDING / NONE 不要在 {@link #regionId} / {@link #regionSize} 上使用。</li>
 *   <li>BFS 是 O(tile 数)，只适合开局/换图后算一次并复用，不要放进逐帧热路径。</li>
 *   <li>只在模拟线程调用；本类不做同步。</li>
 * </ul>
 */
public final class AiMapGraph {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DY = {0, 0, 1, -1};

    /** 每个移动类型一份缓存；同槽位换成另一张代价图（换图）时自动失效。 */
    private static final AiMapGraph[] CACHE = new AiMapGraph[UnitMovementType.values().length];

    private final PathCostMap costMap;
    private final int width;
    private final int height;

    private AiMapGraph(PathCostMap costMap) {
        this.costMap = costMap;
        this.width = costMap.width;
        this.height = costMap.height;
    }

    /** 取某个移动类型的地图骨架；没有地图或该类型没有地形表时返回 null。 */
    public static AiMapGraph of(UnitMovementType movementType) {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null || engine.tileMap == null || engine.pathfindingEngine == null) return null;
        PathCostMap costMap = engine.pathfindingEngine.a(movementType);
        if (costMap == null || costMap.d == null) return null;
        int slot = movementType.ordinal();
        AiMapGraph cached = CACHE[slot];
        if (cached != null && cached.costMap == costMap) return cached;
        AiMapGraph graph = new AiMapGraph(costMap);
        CACHE[slot] = graph;
        return graph;
    }

    public int width() { return width; }

    public int height() { return height; }

    public UnitMovementType movementType() { return costMap.a; }

    public boolean inBounds(int tileX, int tileY) {
        return tileX >= 0 && tileY >= 0 && tileX < width && tileY < height;
    }

    /** 静态地形上该 tile 是否可通行（忽略单位与建筑）。 */
    public boolean passable(int tileX, int tileY) {
        return inBounds(tileX, tileY) && costMap.d[(tileX * height) + tileY] != -1;
    }

    // ===== 坐标换算 =====

    public static int worldToTileX(float worldX) {
        TileMap map = GameEngine.getInstance().tileMap;
        return map == null ? 0 : (int) (worldX * map.tileScaleX);
    }

    public static int worldToTileY(float worldY) {
        TileMap map = GameEngine.getInstance().tileMap;
        return map == null ? 0 : (int) (worldY * map.tileScaleY);
    }

    /** tile 索引 → 该 tile 中心的世界坐标（引擎里建筑/资源点锚点的惯例）。 */
    public static float tileCenterX(int tileX) {
        TileMap map = GameEngine.getInstance().tileMap;
        return map == null ? 0f : (tileX * map.tileWorldSizeX) + map.halfTileWorldSizeX;
    }

    public static float tileCenterY(int tileY) {
        TileMap map = GameEngine.getInstance().tileMap;
        return map == null ? 0f : (tileY * map.tileWorldSizeY) + map.halfTileWorldSizeY;
    }

    // ===== 区域（纯静态地形的连通分量）=====

    /** 区域 id：> 0 有效，-1 该点被地形阻挡，-2 越界，-3 该移动类型没有区域表。 */
    public static short regionId(float worldX, float worldY, UnitMovementType movementType) {
        return GameViewUtils.b(worldX, worldY, movementType);
    }

    /** 区域 tile 数；0 表示无效（阻挡/越界/无表）。 */
    public static int regionSize(float worldX, float worldY, UnitMovementType movementType) {
        return GameViewUtils.c(worldX, worldY, movementType);
    }

    /** 两点是否在同一静态连通域（便宜的区域比较，等价于原版 isPathPossibleBetweenPoints）。 */
    public static boolean sameRegion(float x1, float y1, float x2, float y2, UnitMovementType movementType) {
        if (movementType == UnitMovementType.AIR || movementType == UnitMovementType.NONE) return true;
        short first = regionId(x1, y1, movementType);
        short second = regionId(x2, y2, movementType);
        return first == second && first > 0;
    }

    // ===== 地形 =====

    public static MapTile tileAt(float worldX, float worldY) {
        TileMap map = GameEngine.getInstance().tileMap;
        return map == null ? null : map.getTileAtWorldPoint(worldX, worldY);
    }

    /** 液体（水或岩浆）：与原版 {@code GameViewUtils.d} 同口径。 */
    public static boolean isLiquid(float worldX, float worldY) {
        MapTile tile = tileAt(worldX, worldY);
        if (tile != null && (tile.isWater || tile.isLava)) return true;
        return GameViewUtils.d(worldX, worldY);
    }

    public static boolean isLava(float worldX, float worldY) {
        MapTile tile = tileAt(worldX, worldY);
        return tile != null && tile.isLava;
    }

    public static boolean isWater(float worldX, float worldY) {
        MapTile tile = tileAt(worldX, worldY);
        return tile != null && tile.isWater;
    }

    // ===== 资源点 =====

    /**
     * 全部资源池锚点（世界坐标，沿用原版 y 偏移），元素可安全持有。
     * tile 索引可从 {@link #worldToTileX}/{@link #worldToTileY} 反推。
     */
    public static List<PointF> resourcePoolAnchors() {
        GameEngine engine = GameEngine.getInstance();
        List<PointF> result = new ArrayList<>();
        if (engine == null || engine.tileMap == null) return result;
        for (Point pool : engine.tileMap.unitObjects) {
            PointF point = engine.tileMap.tileToWorldPoint(pool); // 共享 temp，必须立刻拷贝
            result.add(new PointF(point.x, point.y + engine.tileMap.tileWorldSizeY));
        }
        return result;
    }

    // ===== 距离场与最短路 =====

    /** 从某个 tile 出发做一次 BFS，返回距离场（含父指针，可重建路径）。 */
    public DistanceField distanceFieldFrom(int startTileX, int startTileY) {
        int total = width * height;
        int[] distance = new int[total];
        int[] parent = new int[total];
        Arrays.fill(distance, -1);
        Arrays.fill(parent, -1);
        if (!passable(startTileX, startTileY)) {
            return new DistanceField(this, distance, parent);
        }
        int[] queue = new int[total];
        int head = 0;
        int tail = 0;
        int start = (startTileX * height) + startTileY;
        distance[start] = 0;
        queue[tail++] = start;
        while (head < tail) {
            int current = queue[head++];
            int currentX = current / height;
            int currentY = current % height;
            int next = distance[current] + 1;
            for (int direction = 0; direction < 4; direction++) {
                int tileX = currentX + DX[direction];
                int tileY = currentY + DY[direction];
                if (!passable(tileX, tileY)) continue;
                int index = (tileX * height) + tileY;
                if (distance[index] != -1) continue;
                distance[index] = next;
                parent[index] = current;
                queue[tail++] = index;
            }
        }
        return new DistanceField(this, distance, parent);
    }

    public DistanceField distanceFieldFrom(float worldX, float worldY) {
        return distanceFieldFrom(worldToTileX(worldX), worldToTileY(worldY));
    }

    /** 一次 BFS 的结果：tile 步数距离 + 父指针。 */
    public static final class DistanceField {
        private final AiMapGraph graph;
        private final int[] distance;
        private final int[] parent;

        private DistanceField(AiMapGraph graph, int[] distance, int[] parent) {
            this.graph = graph;
            this.distance = distance;
            this.parent = parent;
        }

        /** tile 步数；-1 表示不可达。 */
        public int distanceAt(int tileX, int tileY) {
            if (!graph.inBounds(tileX, tileY)) return -1;
            return distance[(tileX * graph.height) + tileY];
        }

        public int distanceAtWorld(float worldX, float worldY) {
            return distanceAt(worldToTileX(worldX), worldToTileY(worldY));
        }

        public boolean reached(int tileX, int tileY) {
            return distanceAt(tileX, tileY) >= 0;
        }

        /** 从起点到目标的最短 tile 序列（含两端）；不可达返回空表。 */
        public List<Point> pathTo(int targetTileX, int targetTileY) {
            if (!reached(targetTileX, targetTileY)) return List.of();
            List<Point> reversed = new ArrayList<>();
            int index = (targetTileX * graph.height) + targetTileY;
            while (index != -1) {
                reversed.add(new Point(index / graph.height, index % graph.height));
                index = parent[index];
            }
            Collections.reverse(reversed);
            return reversed;
        }

        /** 原样返回内部距离表（索引 = tileX * height + tileY），仅供工具类内部批量使用。 */
        public int[] rawDistances() {
            return distance;
        }
    }
}
