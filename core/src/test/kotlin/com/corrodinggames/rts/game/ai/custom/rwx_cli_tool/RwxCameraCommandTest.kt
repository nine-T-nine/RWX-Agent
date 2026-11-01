package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `rwx-game-camera` 的解析与回执格式测试。
 *
 * 用假的 [RwxLocalGameService] 记录调用参数，不加载地图、不启动游戏——
 * 引擎无关的那一半（参数语义、默认值、报错、文本）都能在这里锁住。
 */
class RwxCameraCommandTest {
    private class FakeService : RwxLocalGameService {
        var lastCenterX: Float? = null
        var lastCenterY: Float? = null
        var lastLeft: Float? = null
        var lastTop: Float? = null
        var lastZoom: Float? = null
        var moveCalls = 0

        private val view = RwxLocalGameTypes.CameraSnapshot(
            100f, 200f, 300f, 400f,
            1.5f, 0.5f, 4.6f, 1600f, 900f, 8192, 8192,
            listOf("视野已钳制到地图边界内"),
        )

        override fun camera(): RwxLocalGameTypes.CameraSnapshot = view

        override fun moveCamera(centerX: Float?, centerY: Float?, left: Float?, top: Float?,
                                zoom: Float?): RwxLocalGameTypes.CameraSnapshot {
            moveCalls++
            lastCenterX = centerX
            lastCenterY = centerY
            lastLeft = left
            lastTop = top
            lastZoom = zoom
            return view
        }

        override fun setPaused(paused: Boolean) = throw UnsupportedOperationException()
        override fun setSpeed(speed: Float) = throw UnsupportedOperationException()
        override fun game() = throw UnsupportedOperationException()
        override fun teams() = throw UnsupportedOperationException()
        override fun units(query: RwxLocalGameTypes.UnitQuery) = throw UnsupportedOperationException()
        override fun unit(unitId: Long) = throw UnsupportedOperationException()
        override fun actions(unitId: Long) = throw UnsupportedOperationException()
        override fun map(query: RwxLocalGameTypes.MapQuery) = throw UnsupportedOperationException()
        override fun reach(unitId: Long, x: Float, y: Float) = throw UnsupportedOperationException()
        override fun canPlace(builderId: Long, unitTypeId: String, x: Float, y: Float,
                              searchRadiusTiles: Int) = throw UnsupportedOperationException()
        override fun issue(order: RwxLocalGameTypes.Order) = throw UnsupportedOperationException()
        override fun events(query: RwxLocalGameTypes.EventQuery) = throw UnsupportedOperationException()
        override fun threats(query: RwxLocalGameTypes.ThreatQuery) = throw UnsupportedOperationException()
        override fun armReplay() = throw UnsupportedOperationException()
        override fun startReplay(fileName: String?) = throw UnsupportedOperationException()
        override fun stopReplay() = throw UnsupportedOperationException()
        override fun replayStatus() = throw UnsupportedOperationException()
        override fun step(seconds: Double, timeoutMillis: Int) = throw UnsupportedOperationException()
        override fun groups() = throw UnsupportedOperationException()
        override fun saveGroup(name: String, unitsOption: String, excludedIds: List<Long>,
                               includeTypes: List<String>, excludeTypes: List<String>,
                               hpBelowPercent: Float?, idleOnly: Boolean,
                               excludeBuilders: Boolean) = throw UnsupportedOperationException()
        override fun deleteGroup(name: String) = throw UnsupportedOperationException()
        override fun observeReset(keepGroups: Boolean) = throw UnsupportedOperationException()
    }

    private fun run(service: FakeService, vararg arguments: String): String =
        RwxLocalGameCommands(service).execute(RwxCliCommands.Command.SNAPSHOT, arguments.toList())

    @Test
    fun `query prints the world rect center size and zoom`() {
        val text = run(FakeService())
        assertTrue(text.contains("zoom: 1.5"), text)
        assertTrue(text.contains("rect: (100, 200)-(300, 400)"), text)
        assertTrue(text.contains("center: 200, 300"), text)
        assertTrue(text.contains("top-left: 100, 200"), text)
        assertTrue(text.contains("size: W 200  H 200"), text)
        assertTrue(text.contains("world: 8192 x 8192"), text)
        assertTrue(text.contains("调整: 视野已钳制到地图边界内"), text)
    }

    @Test
    fun `x and y move the center by default`() {
        val service = FakeService()
        run(service, "--x", "1400", "--y", "700")
        assertEquals(1, service.moveCalls)
        assertEquals(1400f, service.lastCenterX)
        assertEquals(700f, service.lastCenterY)
        assertNull(service.lastLeft)
        assertNull(service.lastZoom)
    }

    @Test
    fun `top-left flag moves the corner instead of the center`() {
        val service = FakeService()
        run(service, "--top-left", "--x", "12", "--y", "34")
        assertEquals(12f, service.lastLeft)
        assertEquals(34f, service.lastTop)
        assertNull(service.lastCenterX)
    }

    @Test
    fun `zoom alone keeps the view position`() {
        val service = FakeService()
        run(service, "--zoom", "2")
        assertEquals(2f, service.lastZoom)
        assertNull(service.lastCenterX)
        assertNull(service.lastLeft)
    }

    @Test
    fun `zoom-by multiplies the current zoom`() {
        val service = FakeService()
        run(service, "--zoom-by", "2")
        assertEquals(3f, service.lastZoom)   // current 1.5 * 2
    }

    @Test
    fun `bad combinations fail loudly`() {
        assertFailsWith<IllegalArgumentException> { run(FakeService(), "--x", "1") }
        assertFailsWith<IllegalArgumentException> {
            run(FakeService(), "--center", "--top-left", "--x", "1", "--y", "2")
        }
        assertFailsWith<IllegalArgumentException> { run(FakeService(), "--zoom", "1", "--zoom-by", "2") }
        assertFailsWith<IllegalArgumentException> { run(FakeService(), "--zoom", "-1") }
        assertFailsWith<IllegalArgumentException> { run(FakeService(), "--center") }
    }
}
