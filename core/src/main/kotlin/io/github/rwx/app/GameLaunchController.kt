package io.github.rwx.app

import io.github.rwx.i18n.I18n
import io.github.rwx.logger
import io.github.rwx.render.frame.GameViewport
import io.github.rwx.session.BattleRoomLaunchConfig
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.host.DialogSceneHost
import io.github.rwx.ui.model.Dialog
import io.github.rwx.ui.model.DialogButton
import io.github.rwx.ui.model.ReplayEntry

internal class GameLaunchController(
    private val gameSession: GameSession,
    private val battleRoomController: BattleRoomController,
    private val warmupController: WarmupController,
    private val pendingStartController: PendingStartController,
    private val dialogSceneHost: DialogSceneHost,
    private val viewport: () -> GameViewport,
    private val currentScreen: () -> AppScreen,
    private val navigateTo: (AppScreen) -> Unit,
) {
    fun enterMicro(mapPath: String) {
        warmupController.clear()
        pendingStartController.set(mapPath, AppScreen.MainMenu)
        if (gameSession.usesFrameCommandRendering) gameSession.prepareMapAsync(mapPath, viewport())
        else gameSession.requestMap(mapPath)
        navigateTo(AppScreen.InGame)
    }

    fun enterRwGame(startNew: Boolean = false, launchConfig: BattleRoomLaunchConfig? = null) {
        val mapPath = launchConfig?.room?.mapPath ?: battleRoomController.selectedOrDefaultMap()?.mapAssetPath
        warmupController.clear()
        if (mapPath == null) {
            pendingStartController.clear()
            return
        }
        val shouldDiscardExistingGame = shouldDiscardExistingGameForStart(
            startNew = startNew,
            hasLaunchConfig = launchConfig != null,
            usesFrameCommandRendering = gameSession.usesFrameCommandRendering,
            canResume = gameSession.canResume(),
            canStartNewSessionInPlace = gameSession.canStartNewSessionInPlace,
        )
        if (shouldDiscardExistingGame) {
            gameSession.discardRunningGame()
        }
        if (!startNew && gameSession.isMapLoaded(mapPath)) {
            pendingStartController.clear()
        } else {
            pendingStartController.set(mapPath, mapStartFailureReturnScreen(currentScreen()))
            if (!gameSession.usesFrameCommandRendering) {
                if (launchConfig != null) {
                    check(gameSession.prepareLocalBattleRoom(launchConfig)) {
                        "Game session rejected local battle room launch"
                    }
                } else {
                    gameSession.requestMap(mapPath)
                }
                logger.info { "Queued Slick game launch: $mapPath (startNew=$startNew)" }
            } else if (launchConfig != null) {
                gameSession.prepareBattleRoomAsync(launchConfig, viewport())
            } else {
                gameSession.prepareMapAsync(mapPath, viewport())
            }
            if (gameSession.usesFrameCommandRendering) {
                logger.info { "Entering RW game screen while map prepares: $mapPath (startNew=$startNew)" }
            }
        }
        navigateTo(AppScreen.InGame)
    }

    fun resumeRwGame() {
        warmupController.clear()
        pendingStartController.clear()
        if (gameSession.canResume()) {
            navigateTo(AppScreen.InGame)
        } else {
            enterRwGame(startNew = true)
        }
    }

    fun showStartNewGameDialog(startNewAction: () -> Unit = { enterRwGame(startNew = true) }) {
        dialogSceneHost.show(
            Dialog(
                title = I18n.gamestart.title(),
                message = I18n.gamestart.message(),
                buttons = listOf(
                    DialogButton(I18n.gamestart.startNew()) { startNewAction() },
                    DialogButton(I18n.mainmenu.`continue`()) { resumeRwGame() },
                    DialogButton(I18n.common.cancel()),
                ),
            ),
        )
    }

    fun enterReplay(replay: ReplayEntry, startNew: Boolean = false) {
        warmupController.clear()
        if (!startNew && gameSession.canResume()) {
            showStartNewGameDialog {
                enterReplay(replay, startNew = true)
            }
            return
        }
        discardRunningGameBeforeStandaloneLaunch()
        pendingStartController.set(replay.replayName, mapStartFailureReturnScreen(currentScreen()))
        gameSession.prepareReplayAsync(replay.replayName, viewport())
        logger.info { "Entering RW replay while it prepares: ${replay.replayName}" }
        navigateTo(AppScreen.InGame)
    }

    fun enterSavedGame(saveName: String, startNew: Boolean = false) {
        val requestedSaveName = saveName.takeIf { it.isNotBlank() } ?: return
        warmupController.clear()
        if (!startNew && gameSession.canResume()) {
            showStartNewGameDialog {
                enterSavedGame(requestedSaveName, startNew = true)
            }
            return
        }
        discardRunningGameBeforeStandaloneLaunch()
        pendingStartController.set(requestedSaveName, mapStartFailureReturnScreen(currentScreen()))
        gameSession.prepareSavedGameAsync(requestedSaveName, viewport())
        logger.info { "Entering RW saved game while it prepares: $requestedSaveName" }
        navigateTo(AppScreen.InGame)
    }

    private fun discardRunningGameBeforeStandaloneLaunch() {
        if (!gameSession.canStartNewSessionInPlace) {
            gameSession.discardRunningGame()
        }
    }
}
