package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxEngineLocalGameService.PendingKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 下单失败什么时候该进待发队列。
 *
 * <p>回归点：{@code validateOrder} 里的“买不起”比 {@code reserveActionCredits} 更早抛出，
 * 之前只拦了升级中异常，于是 {@code --allow-unaffordable} 对升级/生产动作完全无效。</p>
 */
class RwxPendingRetryTest {

    private fun order(allowUnaffordable: Boolean = false, afterUpgrade: Boolean = false) =
        RwxLocalGameTypes.Order(
            RwxLocalGameTypes.OrderType.ACTION,
            listOf(28L),
            null,
            null,
            null,
            "extractorT3_0",
            null,
            null,
            null,
            false,
            false,
            emptyList<Long>(),
            1,
            false,
            0,
            false,
            false,
            false,
            emptyList<String>(),
            emptyList<String>(),
            null,
            allowUnaffordable,
            afterUpgrade,
        )

    private fun shortage() = RwxLocalGameTypes.CreditShortageException(
        "Action 'extractorT3_0' cannot be afforded by unit 28 right now (need 4000, available 1088)",
    )

    @Test
    fun `credit shortage without the flag stays an error`() {
        assertNull(RwxEngineLocalGameService.pendingKindForFailure(order(), shortage()))
    }

    @Test
    fun `allow-unaffordable holds production until credits arrive`() {
        assertEquals(
            PendingKind.CREDITS,
            RwxEngineLocalGameService.pendingKindForFailure(
                order(allowUnaffordable = true), shortage(),
            ),
        )
    }

    @Test
    fun `after-upgrade holds both credit and upgrade waits`() {
        assertEquals(
            PendingKind.UPGRADE,
            RwxEngineLocalGameService.pendingKindForFailure(order(afterUpgrade = true), shortage()),
        )
        assertEquals(
            PendingKind.UPGRADE,
            RwxEngineLocalGameService.pendingKindForFailure(
                order(afterUpgrade = true),
                RwxEngineLocalGameService.UpgradeInProgressException("still upgrading"),
            ),
        )
        assertNull(
            RwxEngineLocalGameService.pendingKindForFailure(
                order(),
                RwxEngineLocalGameService.UpgradeInProgressException("still upgrading"),
            ),
        )
    }
}
