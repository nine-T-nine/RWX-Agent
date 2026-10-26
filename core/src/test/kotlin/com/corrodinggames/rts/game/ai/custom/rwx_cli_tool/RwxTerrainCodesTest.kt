package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.map.MapTile
import kotlin.test.Test
import kotlin.test.assertEquals

class RwxTerrainCodesTest {
    // MapTile's constructor is package-private; fixtures must not change the engine API.
    private fun tile(): MapTile = MapTile::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }.newInstance()

    @Test
    fun `decorative tile ids do not change semantic terrain codes`() {
        for (gid in listOf(36, 37, 477, 494)) {
            val tile = tile().apply { tilesetLocalIndex = gid }
            assertEquals(1, RwxTerrainCodes.classify(tile, null, false))
        }
        assertEquals(-1, RwxTerrainCodes.classify(tile().apply { isWater = true }, null, false))
        assertEquals(1, RwxTerrainCodes.classify(tile().apply {
            isWater = true; isWaterBridge = true
        }, null, false))
        assertEquals(-9, RwxTerrainCodes.classify(tile().apply { isLava = true }, null, false))
        assertEquals(-7, RwxTerrainCodes.classify(tile().apply { isCliff = true }, null, false))
        assertEquals(-7, RwxTerrainCodes.classify(tile().apply { movementBlockLevel = -1 }, null, false))
        assertEquals(-7, RwxTerrainCodes.classify(tile().apply { blocksBuildingPlacement = true }, null, false))
        assertEquals(-7, RwxTerrainCodes.classify(null, null, false))
    }

    @Test
    fun `resource overlay marks occupancy even without a resource flag on the ground tile`() {
        val ground = tile()
        val free = RwxLocalGameTypes.ResourcePool(10f, 10f, null, null, null)
        val occupied = RwxLocalGameTypes.ResourcePool(10f, 10f, 9L, 3, "extractorT1")
        assertEquals(0, RwxTerrainCodes.classify(ground, free, false))
        assertEquals(8, RwxTerrainCodes.classify(ground, occupied, false))
        assertEquals(-2, RwxTerrainCodes.classify(ground, occupied, true))
        assertEquals(-2, RwxTerrainCodes.classify(null, free, true))
        assertEquals(true, occupied.occupied(), "fog masking must not change the pool list's occupancy")
    }

    @Test
    fun `each semantic code has a distinct ascii symbol`() {
        val codes = listOf(1, -1, -7, -9, 0, 8, -2)
        assertEquals(listOf('.', '~', '^', '*', '0', '8', '?'), codes.map { RwxTerrainCodes.symbol(it) })
    }

    @Test
    fun `both explored fog and unexplored fog are masked but visible tiles are not`() {
        val fog = arrayOf(byteArrayOf(0, 4, 5, 9, 10))
        assertEquals(listOf(false, false, true, true, true),
            (0..4).map { RwxTerrainCodes.fogged(fog, 0, it) })
        assertEquals(false, RwxTerrainCodes.fogged(null, 0, 0))
    }
}
