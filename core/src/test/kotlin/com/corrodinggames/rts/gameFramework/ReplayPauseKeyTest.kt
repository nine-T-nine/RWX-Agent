package com.corrodinggames.rts.gameFramework

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.utility.SlickToAndroidKeycodes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Q toggles the replay pause from the render-loop input path, so it also works while paused
 * (the stock pause key lives inside gameLoop, which a paused frame never drives).
 */
class ReplayPauseKeyTest {
    private lateinit var engine: GameEngine
    private lateinit var replay: ReplayEngine
    private var previousReplay: ReplayEngine? = null

    @BeforeTest
    fun setup() {
        engine = GameEngine.getInstance() ?: GameLogic()
        previousReplay = engine.replayEngine
        replay = ReplayEngine()
        engine.replayEngine = replay
        engine.gameSpeed = 1f
    }

    @AfterTest
    fun cleanup() {
        replay.P = false
        replay.isReplaying = false
        engine.gameSpeed = 1f
        engine.replayEngine = previousReplay
    }

    @Test
    fun `Q is ignored when no replay is loaded`() {
        replay.P = false
        replay.isReplaying = false
        assertFalse(ReplayEngine.togglePauseForKey(SlickToAndroidKeycodes.AndroidCodes.KEYCODE_Q))
        assertEquals(1f, engine.gameSpeed)
    }

    @Test
    fun `Q toggles the replay pause in both directions`() {
        replay.P = true
        replay.isReplaying = true
        assertTrue(ReplayEngine.togglePauseForKey(SlickToAndroidKeycodes.AndroidCodes.KEYCODE_Q))
        assertEquals(0f, engine.gameSpeed, "Q must pause a running replay")
        assertTrue(ReplayEngine.togglePauseForKey(SlickToAndroidKeycodes.AndroidCodes.KEYCODE_Q))
        assertEquals(1f, engine.gameSpeed, "Q must resume a paused replay")
    }

    @Test
    fun `other keys and recording mode are not consumed`() {
        replay.P = true
        replay.isReplaying = true
        assertFalse(ReplayEngine.togglePauseForKey(SlickToAndroidKeycodes.AndroidCodes.KEYCODE_W))
        assertEquals(1f, engine.gameSpeed)

        replay.isReplaying = false
        assertFalse(ReplayEngine.togglePauseForKey(SlickToAndroidKeycodes.AndroidCodes.KEYCODE_Q),
            "a recording session must keep Q for the patrol hotkey")
        assertEquals(1f, engine.gameSpeed)
    }
}
