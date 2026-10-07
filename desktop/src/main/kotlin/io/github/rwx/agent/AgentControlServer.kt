package io.github.rwx.agent

import io.github.rwx.app.AppSession
import com.corrodinggames.rts.gameFramework.agent.LocalAgentRuntime
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Small, opt-in control endpoint for local agent tooling.
 *
 * It exposes semantic commands that the simulation thread validates and applies.
 * It is bound to loopback and is never started unless DesktopMain receives
 * --agent-port=<port>.
 */
internal class AgentControlServer private constructor(
    private val server: ServerSocket,
    @Suppress("UNUSED_PARAMETER") private val session: AppSession,
) : Runnable {
    override fun run() {
        while (!server.isClosed) {
            runCatching { server.accept() }.onSuccess { client ->
                runCatching { serve(client) }.onFailure { error ->
                    System.err.println("RWX agent control client failed: ${error.message}")
                }
            }.onFailure { error ->
                if (!server.isClosed) System.err.println("RWX agent control accept failed: ${error.message}")
            }
        }
    }

    private fun serve(socket: Socket) = socket.use { client ->
        client.tcpNoDelay = true
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val output = PrintWriter(client.getOutputStream(), true, Charsets.UTF_8)
        input.lineSequence().forEach { line -> output.println(handle(line)) }
    }

    private fun handle(line: String): String = runCatching {
        val parts = line.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        require(parts.isNotEmpty()) { "empty command" }
        when (parts[0].lowercase()) {
            "ping" -> "pong"
            "brief" -> LocalAgentRuntime.brief()
            "units" -> LocalAgentRuntime.units(parts.getOrElse(1) { "all" })
            "move", "attack-move" -> LocalAgentRuntime.submit(parts[0], parts.ids(1), parts.floatAfterIds(1, 0), parts.floatAfterIds(1, 1), -1)
            "attack" -> LocalAgentRuntime.submit("attack", parts.ids(1), 0f, 0f, parts.longAfterIds(1))
            "stop" -> LocalAgentRuntime.submit("stop", parts.ids(1), 0f, 0f, -1)
            "help" -> "ping | brief | units [own|enemy|all] | move IDS X Y | attack-move IDS X Y | attack IDS TARGET_ID | stop IDS"
            else -> "error: unknown command '${parts[0]}' (send: help)"
        }
    }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }

    private fun List<String>.ids(index: Int): LongArray = getOrNull(index)
        ?.split(',')?.map { it.toLongOrNull() ?: error("unit ids must be comma-separated integers") }?.toLongArray()
        ?.takeIf { it.isNotEmpty() } ?: error("unit ids are required")
    private fun List<String>.floatAfterIds(index: Int, offset: Int): Float {
        val value = getOrNull(index + 1 + offset)?.toFloatOrNull() ?: error("coordinates are required")
        require(value.isFinite()) { "coordinates must be finite" }; return value
    }
    private fun List<String>.longAfterIds(index: Int): Long = getOrNull(index + 1)?.toLongOrNull() ?: error("target id is required")

    companion object {
        fun start(port: Int, session: AppSession) {
            require(port in 1..65535) { "--agent-port must be between 1 and 65535" }
            val listener = ServerSocket(port, 16, InetAddress.getLoopbackAddress())
            Thread(AgentControlServer(listener, session), "RWX-agent-control-$port").apply {
                isDaemon = true
                start()
            }
            println("RWX_AGENT_CONTROL_READY port=$port host=127.0.0.1")
        }
    }
}
