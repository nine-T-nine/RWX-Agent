package io.github.rwx

import com.corrodinggames.rts.game.PlayerTeam

fun battleRoomPlayerDisplayName(rawName: String?, isAi: Boolean, slotIndex: Int): String {
    val slotNumber = (slotIndex + 1).coerceAtLeast(1)
    val name = rawName?.trim()?.takeIf { it.isNotEmpty() }
    if (!isAi) {
        return name ?: "Player $slotNumber"
    }

    val defaultName = "AI $slotNumber"
    if (name == null || name.equals("AI", ignoreCase = true)) {
        return defaultName
    }

    val difficultyPrefix = "AI - "
    if (name.startsWith(difficultyPrefix, ignoreCase = true)) {
        val difficulty = name.substring(difficultyPrefix.length).trim()
        return if (difficulty.isEmpty()) defaultName else "$defaultName - $difficulty"
    }

    return name
}

fun battleRoomAiDifficultyLabel(aiDifficulty: Int): String =
    com.corrodinggames.rts.game.ai.strategy.AiRegistry.displayName(aiDifficulty)

fun battleRoomPlayerPingLabel(player: PlayerTeam): String = player.z()
