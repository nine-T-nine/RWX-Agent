package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-linkage grouping for {@code rwx-game-threat-list --cluster}.
 *
 * <p>Two visible enemies join the same cluster when they are within {@code clusterRadius} world
 * units; chains merge, so a column of tanks spaced 100 apart is one blob rather than twenty
 * one-unit blobs. The result is ordered by each cluster's nearest member to the query centre, so
 * index 1 is always the most immediate threat.</p>
 */
final class RwxThreatClusters {
    private RwxThreatClusters() { }

    static List<RwxLocalGameTypes.ThreatCluster> cluster(
            List<RwxLocalGameTypes.ThreatUnit> units, float clusterRadius) {
        if (units.isEmpty()) return java.util.Collections.emptyList();
        float threshold = Math.max(1.0f, clusterRadius);
        int count = units.size();
        int[] parent = new int[count];
        for (int index = 0; index < count; index++) parent[index] = index;
        for (int left = 0; left < count; left++) {
            for (int right = left + 1; right < count; right++) {
                RwxLocalGameTypes.ThreatUnit a = units.get(left);
                RwxLocalGameTypes.ThreatUnit b = units.get(right);
                if (distance(a.x, a.y, b.x, b.y) <= threshold) union(parent, left, right);
            }
        }
        Map<Integer, List<RwxLocalGameTypes.ThreatUnit>> grouped = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            int root = find(parent, index);
            List<RwxLocalGameTypes.ThreatUnit> group = grouped.get(root);
            if (group == null) {
                group = new ArrayList<>();
                grouped.put(root, group);
            }
            group.add(units.get(index));
        }
        List<RwxLocalGameTypes.ThreatCluster> result = new ArrayList<>();
        for (List<RwxLocalGameTypes.ThreatUnit> group : grouped.values()) {
            float sumX = 0.0f;
            float sumY = 0.0f;
            float health = 0.0f;
            float maxHealth = 0.0f;
            float nearest = Float.MAX_VALUE;
            float maxRange = 0.0f;
            int buildings = 0;
            int air = 0;
            int ground = 0;
            boolean antiAir = false;
            boolean antiGround = false;
            boolean antiSubmerged = false;
            List<Long> ids = new ArrayList<>(group.size());
            Map<String, Integer> typeCounts = new LinkedHashMap<>();
            Map<String, String> typeNames = new LinkedHashMap<>();
            for (RwxLocalGameTypes.ThreatUnit unit : group) {
                sumX += unit.x;
                sumY += unit.y;
                health += unit.health;
                maxHealth += unit.maxHealth;
                nearest = Math.min(nearest, unit.distance);
                maxRange = Math.max(maxRange, unit.range);
                if (unit.flying) air++;
                if (unit.building) buildings++;
                if (!unit.flying && !unit.building) ground++;
                antiAir |= unit.antiAir;
                antiGround |= unit.antiGround;
                antiSubmerged |= unit.antiSubmerged;
                ids.add(unit.id);
                Integer previous = typeCounts.get(unit.unitTypeId);
                typeCounts.put(unit.unitTypeId, previous == null ? 1 : previous + 1);
                typeNames.put(unit.unitTypeId, unit.displayName);
            }
            float centerX = sumX / group.size();
            float centerY = sumY / group.size();
            float radius = 0.0f;
            for (RwxLocalGameTypes.ThreatUnit unit : group) {
                radius = Math.max(radius, distance(centerX, centerY, unit.x, unit.y));
            }
            List<RwxLocalGameTypes.GroupTypeCount> composition = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
                composition.add(new RwxLocalGameTypes.GroupTypeCount(
                        entry.getKey(), typeNames.get(entry.getKey()), entry.getValue()));
            }
            composition.sort((left, right) -> Integer.compare(right.count, left.count));
            result.add(new RwxLocalGameTypes.ThreatCluster(
                    0, centerX, centerY, radius, group.size(), buildings, air, ground,
                    health, maxHealth, nearest, maxRange, antiAir, antiGround, antiSubmerged,
                    composition, ids));
        }
        result.sort((left, right) -> Float.compare(left.nearestDistance, right.nearestDistance));
        List<RwxLocalGameTypes.ThreatCluster> ordered = new ArrayList<>(result.size());
        for (int index = 0; index < result.size(); index++) {
            RwxLocalGameTypes.ThreatCluster cluster = result.get(index);
            ordered.add(new RwxLocalGameTypes.ThreatCluster(
                    index + 1, cluster.centerX, cluster.centerY, cluster.radius,
                    cluster.unitCount, cluster.buildingCount, cluster.airCount, cluster.groundCount,
                    cluster.health, cluster.maxHealth, cluster.nearestDistance, cluster.maxRange,
                    cluster.hasAntiAir, cluster.hasAntiGround, cluster.hasAntiSubmerged,
                    cluster.composition, cluster.unitIds));
        }
        return ordered;
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static void union(int[] parent, int left, int right) {
        int leftRoot = find(parent, left);
        int rightRoot = find(parent, right);
        if (leftRoot != rightRoot) parent[rightRoot] = leftRoot;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
