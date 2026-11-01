package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameModeType
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.logger
import io.github.rwx.session.BattleRoomCoreConfig
import io.github.rwx.session.BattleRoomLaunchConfig
import io.github.rwx.session.BattleRoomSnapshot
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.host.BattleRoomSceneHost
import io.github.rwx.ui.model.BattleRoomAction
import io.github.rwx.ui.model.BattleRoomChatLine
import io.github.rwx.ui.model.LevelSelectMode
import io.github.rwx.ui.model.LevelSelectViewModelFactory
import io.github.rwx.ui.model.MapEntry

internal class BattleRoomController(
    private val gameSession: GameSession,
    private val levelSelectViewModelFactory: LevelSelectViewModelFactory,
    private val sceneHost: BattleRoomSceneHost,
    initialMode: LevelSelectMode,
    private val showUnavailableDialog: (String) -> Unit,
    private val onRoomClosed: () -> Unit = {},
    private val previewForMap: (String, LevelSelectMode) -> String? = { path, mode ->
        levelSelectViewModelFactory.create(mode).mapEntry(path).previewAssetPath
    },
) {
    var selectedMode: LevelSelectMode = initialMode
    var selectedMap: MapEntry? = null
        private set
    var isSelectingMapForBattleRoom: Boolean = false
    private var returnScreen: AppScreen = AppScreen.LevelSelect
    private val chatLines = mutableListOf<BattleRoomChatLine>()
    private var roomOpen = false
    private var previewMapPath: String? = null
    private var cachedPreview: String? = null
    /** Map display name -> preview asset, used because a client's engine map path stays stale in the lobby. */
    private val previewByName = HashMap<String, String?>()

    private fun beginRoom() {
        roomOpen = true
        isSelectingMapForBattleRoom = false
        chatLines.clear()
        previewMapPath = null
        cachedPreview = null
        previewByName.clear()
        sceneHost.beginRoom()
    }

    /** Recheck authority against the live session before the router executes a UI action. */
    fun resolveCurrentAction(action: BattleRoomAction): BattleRoomAction? {
        if (action == BattleRoomAction.Back) return action
        updateFromNetwork(refreshNetworkStatus = false)
        val state = sceneHost.snapshot()
        return state.resolveAction(state.revision, action)
    }

    fun selectedOrDefaultMap(): MapEntry? =
        selectedMap ?: selectDefaultMap()

    fun selectDefaultMap(): MapEntry? {
        selectedMap?.let { return it }
        return runCatching {
            levelSelectViewModelFactory.create(LevelSelectMode.Skirmish).items().firstOrNull()
        }.onFailure { error ->
            logger.warn(error) { "Unable to select default RW map for ${selectedMode.label}" }
        }.getOrNull()?.also { map ->
            selectedMap = map
            logger.info { "Selected default RW map: ${map.mapAssetPath}" }
        }
    }

    /** Default map for the no-argument local-control smoke command. */
    fun selectCommandSkirmishMap(): MapEntry? = runCatching {
        levelSelectViewModelFactory.create(LevelSelectMode.Skirmish).items()
            .firstOrNull { it.fileName.contains("King of the Middle", ignoreCase = true) }
            ?: selectDefaultMap()
    }.onFailure { error ->
        logger.warn(error) { "Unable to select the command skirmish map" }
    }.getOrNull()?.also { map ->
        selectedMap = map
        logger.info { "Selected command skirmish map: ${map.mapAssetPath}" }
    }

    fun currentSnapshot(refreshNetworkStatus: Boolean = true): BattleRoomSnapshot? =
        gameSession.currentBattleRoom(refreshNetworkStatus)

    fun prepareForMap(
        map: MapEntry,
        sandbox: Boolean = false,
        returnScreen: AppScreen = AppScreen.LevelSelect,
    ) {
        beginRoom()
        selectedMap = map
        this.returnScreen = returnScreen
        val newConfig = BattleRoomLaunchConfig(
            sandbox = sandbox,
            aiPlayerCount = defaultBattleRoomAiPlayerCount(map.playerCount),
            room = BattleRoomCoreConfig(
                mapPath = map.mapAssetPath,
                options = GameRoomSettings(),
            ),
        )
        newConfig.room.options.apply {
            aiDifficulty = GameEngine.getInstance()?.settingsEngine?.aiDifficulty ?: 1
            if (map.isSavedGame) gameModeType = GameModeType.savedGame
            if (sandbox) {
                fogMode = 0
                revealedMap = true
            }
        }
        if (prepareLocalSinglePlayerRoom(newConfig)) {
            return
        }
        roomOpen = false
        showUnavailableDialog("Unable to prepare battle room")
    }

    fun prepareSandboxGame(): Boolean {
        val map = selectSandboxMap() ?: selectDefaultMap()
        if (map == null) {
            showUnavailableDialog("No sandbox map is available")
            return false
        }
        selectedMode = LevelSelectMode.Skirmish
        prepareForMap(
            map = map,
            sandbox = true,
            returnScreen = AppScreen.MainMenu,
        )
        return true
    }

    fun closeRoom(): AppScreen {
        isSelectingMapForBattleRoom = false
        roomOpen = false
        chatLines.clear()
        previewMapPath = null
        cachedPreview = null
        sceneHost.beginRoom()
        onRoomClosed()
        gameSession.leaveBattleRoom()
        return returnScreen
    }

    fun selectBattleRoomMap(map: MapEntry) {
        selectedMap = map
        runCatching {
            check(gameSession.setBattleRoomMap(map.mapAssetPath, gameModeTypeFor(map))) {
                "Game session rejected map change"
            }
        }.onFailure { error ->
            logger.warn(error) { "Unable to set battle room map" }
            showUnavailableDialog("Unable to set map: ${error.message ?: error.javaClass.simpleName}")
        }
        updateFromNetwork()
        isSelectingMapForBattleRoom = false
    }

    fun prepareHostRoom(map: MapEntry) {
        beginRoom()
        returnScreen = AppScreen.Multiplayer
        selectedMap = map
    }

    fun markJoinedRoomStarted() {
        beginRoom()
        returnScreen = AppScreen.Multiplayer
    }

    fun updateConnectedRoom(snapshot: BattleRoomSnapshot?): Boolean {
        if (!roomOpen) return false
        if (snapshot != null) publishRoom(snapshot) else updateFromNetwork()
        return sceneHost.snapshot().isAvailable
    }

    fun isMultiplayerRoom(): Boolean =
        runCatching { currentSnapshot(refreshNetworkStatus = false)?.isNetworkMultiplayer }
            .getOrNull() ?: (returnScreen == AppScreen.Multiplayer)

    fun allowedMapSelectModes(): List<LevelSelectMode> =
        if (isMultiplayerRoom()) MULTIPLAYER_MAP_MODES else LevelSelectMode.entries

    /** Maps which the current room is allowed to select, independent of the map-selection UI. */
    fun enabledMaps(): List<MapEntry> = allowedMapSelectModes()
        .flatMap { mode -> levelSelectViewModelFactory.create(mode).items() }
        .distinctBy { it.mapAssetPath }

    fun openMapSelect(): LevelSelectMode {
        if (isMultiplayerRoom() && selectedMode !in MULTIPLAYER_MAP_MODES) {
            selectedMode = LevelSelectMode.Skirmish
        }
        isSelectingMapForBattleRoom = true
        return selectedMode
    }

    fun returnToRoom() {
        val snapshot = currentSnapshot()
        if (!roomOpen) {
            beginRoom()
            returnScreen = if (snapshot?.isNetworkMultiplayer == true) AppScreen.Multiplayer else AppScreen.LevelSelect
        }
        if (snapshot == null) sceneHost.markUnavailable() else publishRoom(snapshot)
    }

    fun updateFromNetwork(refreshNetworkStatus: Boolean = true) {
        if (!roomOpen) return
        val snapshot = currentSnapshot(refreshNetworkStatus)
        if (snapshot == null) {
            sceneHost.markUnavailable()
        } else {
            publishRoom(snapshot)
        }
    }

    private fun publishRoom(snapshot: BattleRoomSnapshot) {
        if (!roomOpen) return
        sceneHost.updateRoom(snapshot.toBattleRoomModel(previewFor(snapshot), chatLines))
    }

    private fun previewFor(snapshot: BattleRoomSnapshot): String? {
        val path = snapshot.room.mapPath
        if (snapshot.room.isSavedGame) return null
        val name = snapshot.mapDisplayName
        selectedMap?.takeIf { it.mapAssetPath == path || it.displayName.equals(name, ignoreCase = true) }
            ?.previewAssetPath?.let { return it }
        // A client's room.mapPath (networkEngine.selectedMapPath / engine.currentMapPath) is only refreshed when a
        // round starts, so it keeps pointing at the previous map in the lobby. The synced settings carry the current
        // map *name*, so resolve the preview from that name first and fall back to the path lookup.
        previewForName(name)?.let { return it }
        if (path.isBlank()) return null
        // A remote map/preview may arrive after the first room snapshot. Cache successful
        // lookups, but keep retrying a missing preview on the existing network refresh cadence.
        if (previewMapPath != path || cachedPreview == null) {
            previewMapPath = path
            cachedPreview = runCatching { previewForMap(path, selectedMode) }.getOrNull()
        }
        return cachedPreview
    }

    /** Resolves a preview by the map's display name, covering built-in and custom/mod maps. */
    private fun previewForName(displayName: String): String? {
        if (displayName.isBlank()) return null
        if (previewByName.containsKey(displayName)) return previewByName[displayName]
        val preview = runCatching {
            enabledMaps().firstOrNull { map ->
                map.displayName.equals(displayName, ignoreCase = true) ||
                    map.fileName.equals(displayName, ignoreCase = true) ||
                    map.fileName.removeSuffix(".tmx").equals(displayName, ignoreCase = true)
            }
        }.getOrNull()?.previewAssetPath
        previewByName[displayName] = preview
        logger.info { "BattleRoom preview name=$displayName preview=$preview" }
        return preview
    }

    fun appendChat(text: String, teamColorIndex: Int = -1) {
        if (!roomOpen) return
        val line = BattleRoomChatLine(text, teamColorIndex)
        chatLines += line
        sceneHost.appendChat(line)
    }

    private fun prepareLocalSinglePlayerRoom(config: BattleRoomLaunchConfig): Boolean {
        if (!gameSession.enterLocalBattleRoomLive(config)) {
            return false
        }
        gameSession.currentBattleRoom()?.let(::publishRoom)
        return true
    }

    private fun selectSandboxMap(): MapEntry? =
        runCatching {
            levelSelectViewModelFactory.create(LevelSelectMode.Skirmish).items()
                .firstOrNull { it.fileName.contains("Crossing Large", ignoreCase = true) }
                ?: levelSelectViewModelFactory.create(LevelSelectMode.Skirmish).items().firstOrNull()
        }.onFailure { error ->
            logger.warn(error) { "Unable to select sandbox map" }
        }.getOrNull()

    companion object {
        val MULTIPLAYER_MAP_MODES: List<LevelSelectMode> =
            listOf(LevelSelectMode.Skirmish, LevelSelectMode.CustomMaps, LevelSelectMode.SavedGames)

        fun gameModeTypeFor(map: MapEntry): GameModeType = when (map.type) {
            LevelSelectMode.SavedGames -> GameModeType.savedGame
            LevelSelectMode.CustomMaps -> GameModeType.customMap
            else -> GameModeType.skirmishMap
        }
    }
}
