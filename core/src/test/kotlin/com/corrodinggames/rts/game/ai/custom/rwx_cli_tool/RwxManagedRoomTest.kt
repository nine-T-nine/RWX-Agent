package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.NetworkConnection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import java.net.Socket
import kotlin.test.*

class RwxManagedRoomTest {
    @Test fun `lobby elects by registration order and a rejoining client cannot displace its manager`() {
        GameEngine.getInstance() ?: GameLogic()
        val network = NetworkEngine()
        val first = NetworkConnection(network, Socket())
        val second = NetworkConnection(network, Socket())
        val joined = mapOf(first to 1L, second to 2L)
        assertSame(first, RwxManagedRoom.selectManager(null, listOf(second, first), joined, false))
        assertSame(second, RwxManagedRoom.selectManager(first, listOf(second), joined, false))
        assertSame(second, RwxManagedRoom.selectManager(second, listOf(first, second), joined, false))
        assertNull(RwxManagedRoom.selectManager(second, emptyList(), joined, false))
    }

    @Test fun `running games freeze ownership even after the manager disconnects`() {
        GameEngine.getInstance() ?: GameLogic()
        val network = NetworkEngine()
        val first = NetworkConnection(network, Socket())
        val second = NetworkConnection(network, Socket())
        assertSame(first, RwxManagedRoom.selectManager(first, listOf(second), mapOf(second to 2L), true))
        assertNull(RwxManagedRoom.selectManager(null, listOf(second), mapOf(second to 2L), true))
    }

    @Test fun `GUI proxy commands retain quoted map names and native fog and spectator conventions`() {
        assertEquals(listOf("rwx-battleroom-select-map", "--name", "Lake (2p)"),
            RwxManagedRoom.nativeArguments("-map 'Lake (2p)'"))
        assertEquals(listOf("rwx-battleroom-settings", "--fog", "none"),
            RwxManagedRoom.nativeArguments("-fog off"))
        assertEquals(listOf("rwx-battleroom-set-player", "--player", "1", "--move", "spec"),
            RwxManagedRoom.nativeArguments("-move 1 -2"))
        assertEquals(listOf("rwx-battleroom-settings", "--revealedMap", "true"),
            RwxManagedRoom.nativeArguments("-revealedmap false"))
    }
}
