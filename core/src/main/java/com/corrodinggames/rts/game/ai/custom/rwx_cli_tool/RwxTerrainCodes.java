package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import com.corrodinggames.rts.game.map.MapTile;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxLocalGameTypes.ResourcePool;

/** Stable CLI categories, independent of a map's tilesets and decorative variants. */
final class RwxTerrainCodes {
    static final int LAND = 1;
    static final int WATER = -1;
    static final int FOG = -2;
    static final int MOUNTAIN = -7;
    static final int LAVA = -9;
    static final int RESOURCE_FREE = 0;
    static final int RESOURCE_OCCUPIED = 8;

    private RwxTerrainCodes() { }

    static boolean fogged(byte[][] fog, int x, int y) {
        return fog != null && x >= 0 && x < fog.length && fog[x] != null
                && y >= 0 && y < fog[x].length && (fog[x][y] & 0xFF) >= 5;
    }

    static int classify(MapTile tile, ResourcePool pool, boolean fogged) {
        if (fogged) return FOG;
        if (pool != null) return pool.occupied() ? RESOURCE_OCCUPIED : RESOURCE_FREE;
        if (tile == null) return MOUNTAIN;
        if (tile.isResourcePool) return RESOURCE_FREE;
        if (tile.isWaterBridge) return LAND;
        if (tile.isLava) return LAVA;
        if (tile.isWater) return WATER;
        if (tile.isCliff || tile.movementBlockLevel < 0 || tile.blocksBuildingPlacement) return MOUNTAIN;
        return LAND;
    }

    static char symbol(int code) {
        switch (code) {
            case LAND: return '.';
            case WATER: return '~';
            case FOG: return '?';
            case MOUNTAIN: return '^';
            case LAVA: return '*';
            case RESOURCE_FREE: return '0';
            case RESOURCE_OCCUPIED: return '8';
            default: throw new IllegalArgumentException("Unknown terrain code: " + code);
        }
    }
}
