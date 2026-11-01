package io.github.rwx.map.multiplayer

import com.corrodinggames.rts.gameFramework.network.GameModeType
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import kotlinx.serialization.Serializable

/** The map path/type belong to each world; every other engine room rule belongs to the session. */
@Serializable
data class MapRoomRules(
    val fogMode: Int = 2,
    val startingCredits: Int = 0,
    val revealedMap: Boolean = true,
    val aiDifficulty: Int = 1,
    val startingUnits: Int = 1,
    val incomeMultiplier: Float = 1f,
    val noNukes: Boolean = false,
    val legacyRuleJ: Boolean = false,
    val sharedControl: Boolean = false,
    val useDisplayedCostAsResourceCost: Boolean = false,
    val teamLock: Boolean = false,
    val fixedAllyTeams: Boolean = false,
    val allowSpectators: Boolean = true,
    val roomLock: Boolean = false,
    val randomSeed: Int = 0,
) {
    fun toEngineSettings(mapPath: String, mode: GameModeType): GameRoomSettings = GameRoomSettings().also {
        it.mapPath = mapPath.replace('\\', '/').substringAfterLast('/')
        it.gameModeType = mode
        it.fogMode = fogMode
        it.startingCredits = startingCredits
        it.revealedMap = revealedMap
        it.aiDifficulty = aiDifficulty
        it.startingUnits = startingUnits
        it.incomeMultiplier = incomeMultiplier
        it.noNukes = noNukes
        it.j = legacyRuleJ
        it.sharedControl = sharedControl
        it.useDisplayedCostAsResourceCost = useDisplayedCostAsResourceCost
        it.teamLock = teamLock
        it.fixedAllyTeams = fixedAllyTeams
        it.allowSpectators = allowSpectators
        it.roomLock = roomLock
        it.randomSeed = randomSeed
    }

    companion object {
        fun fromEngine(settings: GameRoomSettings): MapRoomRules = with(settings) {
            MapRoomRules(
                fogMode, startingCredits, revealedMap, aiDifficulty, startingUnits,
                incomeMultiplier, noNukes, j, sharedControl, useDisplayedCostAsResourceCost,
                teamLock, fixedAllyTeams, allowSpectators, roomLock, randomSeed
            )
        }
    }
}
