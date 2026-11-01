package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.gameFramework.GameEngine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The game-thread hand-off must recognise the thread that actually runs the loop and then run the
 * work inline, without queueing it back to itself. Identity comes from the recorded loop thread and
 * the GUI render thread, never from "this looks like the Swing EDT".
 */
class GameThreadHandoffTest {
    private var savedLoopThread: Thread? = null
    private var savedRenderThread: Thread? = null

    @BeforeTest fun remember() {
        savedLoopThread = GameEngine.gameLoopThread
        savedRenderThread = GameEngine.guiRenderThread
    }

    @AfterTest fun restore() {
        GameEngine.gameLoopThread = savedLoopThread
        GameEngine.guiRenderThread = savedRenderThread
    }

    @Test fun `the recorded loop thread counts as the game thread`() {
        GameEngine.guiRenderThread = null
        GameEngine.gameLoopThread = Thread.currentThread()
        assertTrue(RwxEngineLocalGameService.isOnGameThread())
    }

    @Test fun `the GUI render thread counts as the game thread`() {
        GameEngine.gameLoopThread = null
        GameEngine.guiRenderThread = Thread.currentThread()
        assertTrue(RwxEngineLocalGameService.isOnGameThread())
    }

    @Test fun `another thread is never the game thread`() {
        GameEngine.gameLoopThread = Thread.currentThread()
        GameEngine.guiRenderThread = null
        var observed = true
        val other = Thread { observed = RwxEngineLocalGameService.isOnGameThread() }
        other.start()
        other.join()
        assertFalse(observed, "a background thread must not be treated as the game thread")
    }

    @Test fun `a nested hand-off runs inline on the game thread exactly once`() {
        GameEngine.guiRenderThread = null
        GameEngine.gameLoopThread = Thread.currentThread()
        var runs = 0
        var thread: Thread? = null
        val value = RwxEngineLocalGameService.onGameThread {
            runs++
            thread = Thread.currentThread()
            "inline"
        }
        assertEquals("inline", value)
        assertEquals(1, runs, "the work must run exactly once")
        assertEquals(Thread.currentThread(), thread, "the work must run on the calling (game) thread")
    }
}
