package io.github.rwx.p2p

import java.nio.ByteBuffer

/** Incremental newline framing shared by both native feature transports. */
class FeatureMessageFramer {
    private var pending = ByteArray(4096)
    private var size = 0
    private var failed = false

    @Synchronized
    fun accept(data: ByteBuffer, onFrame: (String) -> Unit): Boolean {
        if (failed) return false
        while (data.hasRemaining()) {
            val start = data.position()
            var end = start
            while (end < data.limit() && data.get(end) != '\n'.code.toByte()) {
                if (end - start >= MAX_FRAME_BYTES - size) {
                    failed = true
                    pending = ByteArray(0)
                    size = 0
                    return false
                }
                end++
            }
            val count = end - start
            if (size + count > pending.size) {
                pending = pending.copyOf(maxOf(size + count, pending.size * 2).coerceAtMost(MAX_FRAME_BYTES))
            }
            data.get(pending, size, count)
            size += count
            if (data.hasRemaining()) {
                data.get() // newline
                val line = String(pending, 0, size, Charsets.UTF_8).trim()
                size = 0
                if (line.isNotEmpty()) onFrame(line)
            }
        }
        return true
    }

    companion object {
        const val MAX_FRAME_BYTES = 1024 * 1024
    }
}
