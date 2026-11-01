package io.github.rwx.headless

import com.corrodinggames.rts.R
import com.corrodinggames.rts.gameFramework.graphics.*
import io.github.rwx.PlatformStorage
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import io.github.rwx.render.canvas.Paint
import io.github.rwx.render.frame.GameCanvasBlendMode
import java.io.File
import java.io.InputStream
import java.util.concurrent.locks.Lock
import javax.imageio.ImageIO

/** No window, GPU, native renderer or drawing surface. Image dimensions remain exact. */
class HeadlessGraphics(private val storage: PlatformStorage, private val target: Texture? = null) : GraphicsEngine {
    private fun texture(width: Int, height: Int) = Texture().apply {
        p = width.coerceAtLeast(1); q = height.coerceAtLeast(1); g()
    }
    private fun decode(input: InputStream?): Texture {
        if (input == null) return texture(2, 2)
        val bytes = input.readBytes()
        ImageIO.createImageInputStream(bytes.inputStream()).use { image ->
            val readers = ImageIO.getImageReaders(image)
            require(readers.hasNext()) { "Unsupported headless image format" }
            val reader = readers.next()
            try {
                reader.input = image
                return texture(reader.getWidth(0), reader.getHeight(0)).apply {
                    setArgbPixelLoader {
                        val bitmap = ImageIO.read(bytes.inputStream())
                        bitmap.getRGB(0, 0, p, q, null, 0, p)
                    }
                }
            } finally { reader.dispose() }
        }
    }
    override fun backendCapabilities() = GraphicsBackendCapabilities(
        supportsLayerBufferPreRendering = false, supportsSmoothFogLayerBuffers = false)
    override fun b(texture: Texture?): GraphicsEngine = HeadlessGraphics(storage, texture)
    override fun a(lock: Lock?) { lock?.lock() }
    override fun b(lock: Lock?) { lock?.unlock() }
    override fun a(i: Int): Texture = a(i, true)
    override fun a(i: Int, z: Boolean): Texture {
        val name = R.drawable::class.java.fields.firstOrNull { it.getInt(null) == i }?.name
        for (extension in listOf(".png", ".9.png", ".jpg", ".jpeg")) {
            storage.openAssetStream("drawable/$name$extension")?.use { return decode(it) }
        }
        return r()
    }
    override fun a(inputStream: InputStream?, z: Boolean): Texture = decode(inputStream)
    override fun a(i: Int, i2: Int, z: Boolean): Texture = texture(i, i2)
    override fun b(i: Int, i2: Int, z: Boolean): Texture = texture(i, i2)
    override fun a(texture: Texture?, f: Float, f2: Float, f3: Float, paint: Paint?) = Unit
    override fun a(texture: Texture?, rect: Rect?, f: Float, f2: Float, f3: Float, paint: Paint?) = Unit
    override fun a(texture: Texture?, rect: Rect?, rect2: Rect?, paint: Paint?) = Unit
    override fun a(texture: Texture?, rect: Rect?, rectF: RectF?, paint: Paint?) = Unit
    override fun a(texture: Texture?, f: Float, f2: Float, paint: Paint?) = Unit
    override fun a(texture: Texture?, f: Float, f2: Float, paint: Paint?, f3: Float, f4: Float) = Unit
    override fun b(texture: Texture?, f: Float, f2: Float, paint: Paint?) = Unit
    override fun b(texture: Texture?, rect: Rect?, rect2: Rect?, paint: Paint?) = Unit
    override fun a(rect: Rect?, paint: Paint?) = Unit
    override fun a(texture: Texture?, rect: Rect?, paint: Paint?) = Unit
    override fun a(texture: Texture?, rect: Rect?, paint: Paint?, i: Int, i2: Int, i3: Int, i4: Int) = Unit
    override fun a(texture: Texture?, rectF: RectF?, paint: Paint?, f: Float, f2: Float, i: Int, i2: Int) = Unit
    override fun b(i: Int) = Unit
    override fun a(i: Int, mode: GameCanvasBlendMode?) = Unit
    override fun a(str: String?, f: Float, f2: Float, paint: Paint?, paint2: Paint?, f3: Float) = Unit
    override fun a(str: String?, f: Float, f2: Float, paint: Paint?) = Unit
    override fun b(rect: Rect?, paint: Paint?) = Unit
    override fun a(z: Boolean) = Unit
    override fun f() = Unit
    override fun a(rectF: RectF?, paint: Paint?) = Unit
    override fun c(rect: Rect?, paint: Paint?) = Unit
    override fun a(rect: Rect?) = Unit
    override fun a(rectF: RectF?) = Unit
    override fun a(f: Float, f2: Float, f3: Float, paint: Paint?) = Unit
    override fun b(f: Float, f2: Float, f3: Float, paint: Paint?) = Unit
    override fun a(fArr: FloatArray?, i: Int, i2: Int, paint: Paint?) = Unit
    override fun i() = Unit
    override fun j() = Unit
    override fun k() = Unit
    override fun l() = Unit
    override fun a(f: Float, f2: Float, f3: Float) = Unit
    override fun a(f: Float, f2: Float) = Unit
    override fun a(f: Float, f2: Float, f3: Float, f4: Float) = Unit
    override fun b(f: Float, f2: Float) = Unit
    override fun a(f: Float, f2: Float, f3: Float, f4: Float, paint: Paint?) = Unit
    override fun m(): Int = target?.width() ?: 1280
    override fun n(): Int = target?.height() ?: 720
    override fun a(i: Int, i2: Int) = Unit
    override fun o() = Unit
    override fun p() = Unit
    override fun q() = Unit
    override fun a(shaderProgram: ShaderProgram?) = Unit
    override fun a(str: String?, paint: Paint?): Int = ((str?.length ?: 0) * 8)
    override fun b(str: String?, paint: Paint?): Int = 16
    override fun r(): Texture = texture(2, 2)
    override fun a(texture: Texture?, file: File?) = Unit
}
