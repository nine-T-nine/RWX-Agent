package io.github.rwx.app

import io.github.rwx.render.frame.GameFrame
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.model.LevelSelectAction
import io.github.rwx.ui.model.MainMenuAction
import io.github.rwx.ui.model.SettingsModel
import io.github.rwx.ui.host.ReplaySelectSceneHost

internal class ScreenLifecycleController(
    private val screenPresenter: ScreenPresenter,
    private val externalFrame: () -> GameFrame?,
    private val actions: ActionHandlers,
    private val battleRoomController: BattleRoomController,
    private val battleRoomLaunchController: BattleRoomLaunchController,
    private val replaySelectSceneHost: ReplaySelectSceneHost,
    private val multiplayerLobbyController: MultiplayerLobbyController,
    private val modsController: ModsController,
    private val resourceBrowserController: ResourceBrowserController,
    private val settingsRepository: GameSettingsRepository,
    private val settingsModel: SettingsModel,
    private val refreshMainMenu: () -> Unit,
    autoStartSinglePlayerFromUi: Boolean,
    autoStartBattleRoom: Boolean,
    startupSandboxMapPath: String? = null,
    private val navigateTo: (AppScreen) -> Unit = {},
) {
    private var pendingAutoStartSinglePlayerFromUi = autoStartSinglePlayerFromUi
    private var pendingAutoSelectSinglePlayerMap = false
    private var pendingAutoStartBattleRoom = autoStartBattleRoom

    /** `--sandbox-map=<path>`, consumed on the first main menu: the room needs a started session. */
    private var pendingStartupSandboxMap = startupSandboxMapPath

    fun onScreenChanged(screen: AppScreen) {
        if (screen != AppScreen.ResourceBrowser) resourceBrowserController.leaveScreen()
        screenPresenter.apply(screen, externalFrame())
        if (screen == AppScreen.MainMenu) {
            refreshMainMenu()
            val sandboxMap = pendingStartupSandboxMap
            if (sandboxMap != null) {
                pendingStartupSandboxMap = null
                if (battleRoomController.prepareSandboxGame(sandboxMap)) {
                    navigateTo(AppScreen.BattleRoom)
                    return
                }
            }
            if (pendingAutoStartSinglePlayerFromUi) {
                pendingAutoStartSinglePlayerFromUi = false
                pendingAutoSelectSinglePlayerMap = true
                actions.menu(MainMenuAction.SinglePlayer)
            }
        }
        if (screen == AppScreen.LevelSelect && pendingAutoSelectSinglePlayerMap) {
            pendingAutoSelectSinglePlayerMap = false
            pendingAutoStartBattleRoom = true
            battleRoomController.selectDefaultMap()?.let { map ->
                actions.levelSelect(LevelSelectAction.SelectMap(map))
            }
        }
        if (screen == AppScreen.ReplaySelect) replaySelectSceneHost.refresh()
        if (screen == AppScreen.Multiplayer) multiplayerLobbyController.requestRefresh()
        if (screen == AppScreen.Mods) modsController.refresh()
        if (screen == AppScreen.ResourceBrowser) {
            resourceBrowserController.beginVisit()
            resourceBrowserController.requestInitialSearchIfEmpty()
        }
        if (screen == AppScreen.Settings) settingsRepository.loadInto(settingsModel)
        if (screen == AppScreen.BattleRoom && pendingAutoStartBattleRoom) {
            pendingAutoStartBattleRoom = false
            battleRoomLaunchController.startBattleRoomGame()
        }
    }

}
