package io.github.rwx.map

import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import kotlinx.serialization.Serializable

/** Preserve the actual type, including configured replacements for built-in units. */
object PortalUnitTypeIds {
    private const val CUSTOM_PREFIX = "custom:"

    @JvmStatic
    fun encode(type: UnitType): String? = when (type) {
        is UnitTypeEnum -> type.name
        is CustomUnitConfig -> type.name?.takeIf { it.isNotBlank() }?.let { CUSTOM_PREFIX + it }
        else -> null
    }

    @JvmStatic
    fun resolve(id: String): UnitType? = if (id.startsWith(CUSTOM_PREFIX)) {
        CustomUnitConfig.findConfigByName(id.removePrefix(CUSTOM_PREFIX))
    } else {
        runCatching { UnitTypeEnum.valueOf(id) }.getOrNull()
    }
}

@Serializable
data class TransferredUnit(
    val unitTypeId: String,
    val teamId: Int,
    val healthFraction: Float,
    val direction: Float,
)

@Serializable
data class PortalTransferMessage(
    val sourceMapPath: String?,
    val targetMapId: String,
    val targetPortalId: String?,
    val units: List<TransferredUnit>,
)
