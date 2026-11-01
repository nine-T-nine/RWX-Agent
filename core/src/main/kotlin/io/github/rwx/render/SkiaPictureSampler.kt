package io.github.rwx.render

import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader

/** Samples retained commands at the render target's pixel resolution, like an image. */
internal class SkiaPictureSampler : AutoCloseable {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null

    fun makeShader(content: Shader, source: Rect, destination: Rect): Shader {
        val program = effect ?: RuntimeEffect.makeForShader(SOURCE).also { effect = it }
        val activeBuilder = builder ?: RuntimeShaderBuilder(program).also { builder = it }
        // A runtime child receives explicit coordinates rather than the frame CTM.
        // This keeps Skia's picture cache at 1:1 as the camera changes scale, and
        // preserves raster target semantics for subpixel geometry and checkpoints.
        val sx = source.width / destination.width
        val sy = source.height / destination.height
        activeBuilder.child("content", content)
        activeBuilder.uniform(
            "mapping", sx, sy,
            source.left - destination.left * sx,
            source.top - destination.top * sy
        )
        activeBuilder.uniform("subset", source.left + .5f, source.top + .5f, source.right - .5f, source.bottom - .5f)
        // Skia snapshots the uniforms and child references. Later builder changes
        // must not affect shaders already retained by a recorded frame.
        return activeBuilder.makeShader()
    }

    override fun close() {
        builder?.close()
        builder = null
        effect?.close()
        effect = null
    }

    private companion object {
        const val SOURCE = """
            uniform shader content;
            uniform float4 mapping;
            uniform float4 subset;
            half4 main(float2 p) {
                return content.eval(clamp(p * mapping.xy + mapping.zw, subset.xy, subset.zw));
            }
        """
    }
}
