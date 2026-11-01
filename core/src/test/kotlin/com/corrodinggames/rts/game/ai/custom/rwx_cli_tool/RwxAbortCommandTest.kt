package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import java.lang.reflect.Proxy
import kotlin.test.*

/**
 * `rwx-game-abort` 的命令层测试。
 *
 * 这条命令存在的理由只有一个：**不投降地结束一局，同时不破坏材料**。所以这里固定两件事——
 * 参数是否原样到达服务层，以及回执是否把“胜负未记录”说清楚（否则读的人会以为这局判了负）。
 */
class RwxAbortCommandTest {
    private class FakeService {
        var reason: String? = "unset"
        var calls = 0

        val service: RwxLocalGameService = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(RwxLocalGameService::class.java),
        ) { _, method, args ->
            val values = args ?: emptyArray()
            when (method.name) {
                "abort" -> {
                    calls++
                    reason = values[0] as String?
                    "已中止本局（未投降，胜负未记录）：tick 120，游戏时间 1920 ms"
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as RwxLocalGameService
    }

    @Test
    fun `abort is a local game command`() {
        val command = assertNotNull(RwxCliCommands.find("rwx-game-abort"))
        assertEquals(RwxCliCommands.Command.ABORT, command)
        assertEquals(RwxCliCommands.Domain.LOCAL_GAME, command.domain())
    }

    @Test
    fun `reason is forwarded verbatim and the reply never claims a result`() {
        val fake = FakeService()
        val executor = RwxLocalGameCommands(fake.service)
        val command = assertNotNull(RwxCliCommands.find("rwx-game-abort"))
        val reply = executor.execute(command, listOf("--reason", "agent_gone"))
        assertEquals("agent_gone", fake.reason)
        assertTrue(reply.contains("胜负未记录"))
    }

    @Test
    fun `no reason means the service decides the default label`() {
        val fake = FakeService()
        val executor = RwxLocalGameCommands(fake.service)
        executor.execute(assertNotNull(RwxCliCommands.find("rwx-game-abort")), emptyList())
        assertNull(fake.reason)
        assertEquals(1, fake.calls)
    }

    @Test
    fun `unknown options are rejected before anything is stopped`() {
        val fake = FakeService()
        val executor = RwxLocalGameCommands(fake.service)
        val command = assertNotNull(RwxCliCommands.find("rwx-game-abort"))
        assertFailsWith<IllegalArgumentException> { executor.execute(command, listOf("--surrender", "true")) }
        assertEquals(0, fake.calls)
    }
}
