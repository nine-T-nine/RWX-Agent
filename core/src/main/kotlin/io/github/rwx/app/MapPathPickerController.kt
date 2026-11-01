package io.github.rwx.app

import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.CoreUiEvent
import io.github.rwx.ui.host.LevelSelectSceneHost
import io.github.rwx.ui.model.LevelSelectMode

/** Routes a map-path picker request through the level select screen and back to the requester. */
internal class MapPathPickerController(
    private val levelSelectSceneHost: LevelSelectSceneHost,
    private val navigateTo: (AppScreen) -> Unit,
    private val hideDialog: () -> Unit,
) {
    private var pending: CoreUiEvent.MapPathSelectionRequested? = null

    val hasPending: Boolean get() = pending != null

    fun request(event: CoreUiEvent.MapPathSelectionRequested) {
        pending = event
        hideDialog()
        levelSelectSceneHost.updateMaps(LevelSelectMode.Skirmish, TARGET_MODES)
        navigateTo(AppScreen.LevelSelect)
    }

    fun complete(mapPath: String): Boolean {
        val event = pending ?: return false
        pending = null
        levelSelectSceneHost.resetAvailableModes()
        navigateTo(AppScreen.InGame)
        event.handler.onSelected(mapPath)
        return true
    }

    fun cancel(): Boolean {
        if (pending == null) return false
        pending = null
        levelSelectSceneHost.resetAvailableModes()
        navigateTo(AppScreen.InGame)
        return true
    }

    private companion object {
        val TARGET_MODES = listOf(
            LevelSelectMode.Campaign,
            LevelSelectMode.Skirmish,
            LevelSelectMode.Challenge,
            LevelSelectMode.Survival,
            LevelSelectMode.CustomMaps,
        )
    }
}
