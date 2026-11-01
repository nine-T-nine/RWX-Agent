package io.github.rwx.headless

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.PlatformStorage
import io.github.rwx.input.MultiTouchPointerState
import io.github.rwx.platform.CoreGameView
import io.github.rwx.render.RenderBackend
import io.github.rwx.render.frame.GameFrame
import io.github.rwx.render.frame.GameViewport
import io.github.rwx.session.GameSession
import io.github.rwx.session.GameSessionRendererProfile
import io.github.rwx.ui.BattleRoomUiBridge

/** Runs the original simulation and network scheduler without a window or render context. */
class HeadlessGameSession(storage: PlatformStorage) : GameSession() {
    override val renderBackend = object : RenderBackend { override val id = "headless" }
    private val graphics = HeadlessGraphics(storage)
    private val view = object : CoreGameView {
        private val pointer = MultiTouchPointerState()
        override fun pause() = Unit
        override fun isActive() = true
        override fun isContinuousRendering() = false
        override fun isRendering() = false
        override fun getInGameMenuController(): io.github.rwx.ui.InGameMenuController =
            this@HeadlessGameSession.inGameMenuController
        override fun onResume() = Unit
        override fun getSettings() = pointer
        override fun onSizeChanged() = Unit
        override fun stopRender() = Unit
    }
    private var lastNanos = System.nanoTime()

    init {
        configureRendererProfile(GameSessionRendererProfile(
            usesFrameCommandRendering = false, canStartNewSessionInPlace = true,
            usesNativeSurfaceForResumeBackground = false))
    }

    override fun ensureStarted(viewport: GameViewport): GameEngine = synchronized(gameLock) {
        activeEngineLocked() ?: ensureRendererEngine(viewport, graphics, view)
    }

    override fun applyViewport(engine: GameEngine, viewport: GameViewport) {
        lastViewport = viewport
        engine.updateWindowResolution(viewport.width, viewport.height)
    }

    /** Called every scheduler frame, including while in the lobby or while locally paused. */
    fun pump() {
        val now = System.nanoTime()
        val seconds = ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        if (isEngineBusyForUiReads()) return
        synchronized(gameLock) {
            if (isEngineBusyForUiReads()) return
            val engine = activeEngineLocked() ?: return
            synchronized(engine.gameStateLock) {
                if (engine.hasLoadedLevel) {
                    engine.gameLoop(seconds * 60f, (seconds * 1000).toInt())
                } else {
                    // A lobby has no level. The normal frame loop deliberately refuses that state.
                    engine.recurringGameThreadTasks.a()
                    engine.pendingGameThreadTasks.b()
                    // a(delta) is the in-game lockstep scheduler. Calling it in the lobby
                    // consumes the one-shot client-ready packet before a level is loaded.
                    engine.networkEngine.b(seconds * 60f)
                }
            }
        }
    }

    override fun updateFrame(viewport: GameViewport, deltaSeconds: Float,
                             drainVisibleLayerBuffers: Boolean): GameFrame = synchronized(gameLock) {
        val engine = ensureStarted(viewport)
        applyViewport(engine, viewport)
        loadPendingMap(engine)
        GameFrame(viewport, emptyList()).also { lastFrame = it }
    }

    override fun loadPendingMapNow() = updateFrame(lastViewport, 0f)

    override fun adoptStartedGameFromEngine(viewport: GameViewport): Boolean = synchronized(gameLock) {
        val engine = activeEngineLocked() ?: return false
        if (!engine.networkEngine.gameHasBeenStarted) return false
        if (!engine.hasLoadedLevel) BattleRoomUiBridge.setupGame()
        val path = engine.currentMapPath ?: engine.networkEngine.selectedMapPath ?: return false
        updateLoadState { it.copy(runningMapPath = path, menuBackgroundActive = false,
            pendingMapPath = null, pendingRendererBattleRoomConfig = null) }
        engine.isStopped = false
        engine.isPaused = false
        engine.hasLoadedLevel
    }
}
