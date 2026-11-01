package io.github.rwx.render

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

/** Ordered nearest-neighbour image quads, accumulated only on an untransformed retained target. */
internal class SkiaAtlasBatch(private val buffers: SkiaAtlasBuffers) : AutoCloseable {
    private val positions = FloatArray(MAX_QUADS * 8)
    private val coordinates = FloatArray(MAX_QUADS * 8)
    private var count = 0
    private var image: Image? = null
    private var paintOwner: Paint? = null
    private var paintVersion = 0L
    private var batchPaint: Paint? = null

    fun add(
        canvas: Canvas, image: Image, paint: Paint, version: Long,
        sl: Float, st: Float, sr: Float, sb: Float,
        dl: Float, dt: Float, dr: Float, db: Float,
    ) {
        if (this.image !== image || paintOwner !== paint || paintVersion != version || count == positions.size) {
            flush(canvas)
            this.image = image
            // Different target engines own different adapters, even when their
            // version numbers match. Borrow only the identity; freeze the draw below.
            paintOwner = paint
            paintVersion = version
            batchPaint = paint.makeClone().apply {
                image.makeShader(null).use { shader = it }
            }
        }
        quad(positions, dl, dt, dr, db)
        quad(coordinates, sl, st, sr, sb)
        count += 8
    }

    private fun quad(values: FloatArray, l: Float, t: Float, r: Float, b: Float) {
        values[count] = l; values[count + 1] = t
        values[count + 2] = r; values[count + 3] = t
        values[count + 4] = r; values[count + 5] = b
        values[count + 6] = l; values[count + 7] = b
    }

    fun flush(canvas: Canvas) {
        if (count == 0) return
        val vertices: FloatArray
        val uv: FloatArray
        val indices: ShortArray
        if (count == positions.size) {
            vertices = positions
            uv = coordinates
            indices = buffers.fullIndices()
        } else {
            val partial = buffers.get(count)
            positions.copyInto(partial.positions, endIndex = count)
            coordinates.copyInto(partial.coordinates, endIndex = count)
            vertices = partial.positions
            uv = partial.coordinates
            indices = partial.indices
        }
        // Skia copies the mesh into the picture; these staging arrays can be reused.
        canvas.drawVertices(
            VertexMode.TRIANGLES, vertices, texCoords = uv, indices = indices,
            blendMode = BlendMode.MODULATE, paint = checkNotNull(batchPaint),
        )
        close()
    }

    override fun close() {
        count = 0
        image = null
        paintOwner = null
        paintVersion = 0L
        batchPaint?.close()
        batchPaint = null
    }

}

/** Exact-sized JNI staging arrays shared on the render thread; native draws copy them. */
internal class SkiaAtlasBuffers {
    class Buffer(size: Int) {
        val positions = FloatArray(size)
        val coordinates = FloatArray(size)
        val indices = quadIndices(size / 8)
        val bytes = size * Float.SIZE_BYTES * 2 + indices.size * Short.SIZE_BYTES
    }

    private val entries = LinkedHashMap<Int, Buffer>(16, .75f, true)
    private var bytes = 0
    private var completeIndices: ShortArray? = null

    fun fullIndices(): ShortArray = completeIndices ?: quadIndices(MAX_QUADS).also {
        completeIndices = it
        bytes += it.size * Short.SIZE_BYTES
        trim()
    }

    fun get(size: Int): Buffer = entries[size] ?: Buffer(size).also { buffer ->
        entries[size] = buffer
        bytes += buffer.bytes
        trim()
    }

    private fun trim() {
        val oldest = entries.entries.iterator()
        while (bytes > MAX_BYTES) {
            bytes -= oldest.next().value.bytes
            oldest.remove()
        }
    }

    fun clear() {
        entries.clear()
        completeIndices = null
        bytes = 0
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}

private const val MAX_QUADS = 2048

/** TL/TR/BR/BL vertices retain the same two ordered triangles as the expanded mesh. */
private fun quadIndices(quads: Int): ShortArray = ShortArray(quads * 6).also { indices ->
    repeat(quads) { quad ->
        val vertex = quad * 4
        val index = quad * 6
        indices[index] = vertex.toShort()
        indices[index + 1] = (vertex + 1).toShort()
        indices[index + 2] = (vertex + 2).toShort()
        indices[index + 3] = vertex.toShort()
        indices[index + 4] = (vertex + 2).toShort()
        indices[index + 5] = (vertex + 3).toShort()
    }
}
