package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `threats --cluster` grouping: nearby enemies form blobs ordered by nearest distance. */
class RwxThreatClustersTest {
    private fun threat(
        id: Long,
        x: Float,
        y: Float,
        distance: Float,
        type: String = "c_tank",
        flying: Boolean = false,
        building: Boolean = false,
        antiAir: Boolean = false,
        health: Float = 100f,
        maxHealth: Float = 100f,
        range: Float = 100f,
    ) = RwxLocalGameTypes.ThreatUnit(
        id, type, type, 1, x, y, distance, health, maxHealth, flying, building,
        antiAir, true, false, range,
    )

    @Test
    fun `nearby units merge and clusters are ordered by nearest distance`() {
        val far = threat(1, 900f, 900f, 500f)
        val nearA = threat(2, 100f, 100f, 40f, health = 60f, maxHealth = 100f)
        val nearB = threat(3, 150f, 100f, 80f, type = "c_heavyTank", health = 200f, maxHealth = 250f)
        val clusters = RwxThreatClusters.cluster(listOf(far, nearA, nearB), 120f)
        assertEquals(2, clusters.size)
        assertEquals(1, clusters[0].index)
        assertEquals(2, clusters[0].unitCount)
        assertEquals(260f, clusters[0].health)
        assertEquals(350f, clusters[0].maxHealth)
        assertEquals(setOf(2L, 3L), clusters[0].unitIds.toSet())
        assertEquals(1, clusters[1].unitCount)
        assertEquals(900f, clusters[1].centerX)
    }

    @Test
    fun `chain merges into one cluster`() {
        val a = threat(1, 0f, 0f, 10f)
        val b = threat(2, 100f, 0f, 110f)
        val c = threat(3, 200f, 0f, 210f)
        val clusters = RwxThreatClusters.cluster(listOf(a, b, c), 120f)
        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].unitCount)
        assertEquals(100f, clusters[0].centerX)
        assertEquals(100f, clusters[0].radius)
    }

    @Test
    fun `empty and single input are safe`() {
        assertTrue(RwxThreatClusters.cluster(emptyList(), 120f).isEmpty())
        val one = RwxThreatClusters.cluster(listOf(threat(1, 5f, 5f, 5f)), 120f)
        assertEquals(1, one.size)
        assertEquals(5f, one[0].centerX)
        assertEquals(0f, one[0].radius)
    }
}
