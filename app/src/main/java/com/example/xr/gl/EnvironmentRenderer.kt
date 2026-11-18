package com.example.xr.gl

import android.opengl.GLES30
import com.example.xr.model.EnvironmentConfig
import com.example.xr.model.VirtualEnvironmentType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the 6 procedural GPU-light virtual environments (< 1.5 MB total GPU memory)
 * with zero per-frame allocations.
 */
class EnvironmentRenderer {

    private var gridBuffer: FloatBuffer? = null
    private var gridVertexCount: Int = 0

    private var starBuffer: FloatBuffer? = null
    private var starVertexCount: Int = 0

    private var mountainBuffer: FloatBuffer? = null
    private var mountainVertexCount: Int = 0

    private var ringBuffer: FloatBuffer? = null
    private var ringVertexCount: Int = 0

    private var lastEnvType: VirtualEnvironmentType? = null
    private var lastTintHex: Long = 0L

    fun ensureGeometry(config: EnvironmentConfig) {
        if (lastEnvType == config.type && lastTintHex == config.tintHex && gridBuffer != null) return
        lastEnvType = config.type
        lastTintHex = config.tintHex

        val r = ((config.tintHex shr 16) and 0xFF) / 255f
        val g = ((config.tintHex shr 8) and 0xFF) / 255f
        val b = (config.tintHex and 0xFF) / 255f

        buildGrid(r, g, b, config.type)
        buildParticles(r, g, b, config.type)
        buildMountains(r, g, b)
        buildOrbitalRings(r, g, b)
    }

    private fun buildGrid(r: Float, g: Float, b: Float, type: VirtualEnvironmentType) {
        val lines = mutableListOf<Float>()
        val extent = 18
        val yFloor = -1.6f
        val alpha = when (type) {
            VirtualEnvironmentType.DARK_ROOM -> 0.16f
            VirtualEnvironmentType.SPACE_STATION -> 0.22f
            VirtualEnvironmentType.FLOATING_GRID -> 0.65f
            else -> 0.36f
        }
        for (i in -extent..extent step 2) {
            val f = i.toFloat()
            addVertex(lines, -extent.toFloat(), yFloor, f, r, g, b, alpha)
            addVertex(lines, extent.toFloat(), yFloor, f, r, g, b, alpha)
            addVertex(lines, f, yFloor, -extent.toFloat(), r, g, b, alpha)
            addVertex(lines, f, yFloor, extent.toFloat(), r, g, b, alpha)
        }
        // Overhead matrix plane for FLOATING_GRID & DIGITAL_WORKSPACE
        if (type == VirtualEnvironmentType.FLOATING_GRID || type == VirtualEnvironmentType.DIGITAL_WORKSPACE) {
            val yCeil = 4.2f
            for (i in -extent..extent step 4) {
                val f = i.toFloat()
                addVertex(lines, -extent.toFloat(), yCeil, f, r * 0.7f, g * 0.7f, b, alpha * 0.45f)
                addVertex(lines, extent.toFloat(), yCeil, f, r * 0.7f, g * 0.7f, b, alpha * 0.45f)
            }
        }
        gridVertexCount = lines.size / 7
        gridBuffer = toFloatBuffer(lines.toFloatArray())
    }

    private fun buildParticles(r: Float, g: Float, b: Float, type: VirtualEnvironmentType) {
        val pts = mutableListOf<Float>()
        val count = 180
        for (i in 0 until count) {
            val theta = (i * 2.39996f) // golden angle distribution
            val phi = ((i * 37) % 100) / 100f * PI.toFloat() * 0.85f - 0.3f
            val dist = 6f + (i % 7) * 1.4f
            val x = cos(theta) * cos(phi) * dist
            val y = sin(phi) * dist + 0.8f
            val z = sin(theta) * cos(phi) * dist
            val pr = if (type == VirtualEnvironmentType.NATURE_DUSK) 1.0f else (0.7f + 0.3f * r)
            val pg = if (type == VirtualEnvironmentType.NATURE_DUSK) 0.75f else (0.7f + 0.3f * g)
            val pb = if (type == VirtualEnvironmentType.NATURE_DUSK) 0.25f else (0.75f + 0.25f * b)
            addVertex(pts, x, y, z, pr, pg, pb, 0.85f)
        }
        starVertexCount = pts.size / 7
        starBuffer = toFloatBuffer(pts.toFloatArray())
    }

    private fun buildMountains(r: Float, g: Float, b: Float) {
        val verts = mutableListOf<Float>()
        val segs = 48
        val radius = 14f
        val yBase = -1.6f
        for (i in 0 until segs) {
            val a0 = (i.toFloat() / segs) * 2f * PI.toFloat()
            val a1 = ((i + 1).toFloat() / segs) * 2f * PI.toFloat()
            val peakH = 1.2f + 1.8f * kotlin.math.abs(sin(i * 1.7f))
            val xm = cos((a0 + a1) * 0.5f) * radius
            val zm = sin((a0 + a1) * 0.5f) * radius

            addVertex(verts, cos(a0) * radius, yBase, sin(a0) * radius, 0.06f, 0.08f, 0.14f, 1f)
            addVertex(verts, cos(a1) * radius, yBase, sin(a1) * radius, 0.06f, 0.08f, 0.14f, 1f)
            addVertex(verts, xm, yBase + peakH, zm, r * 0.55f, g * 0.4f, b * 0.45f, 1f)
        }
        mountainVertexCount = verts.size / 7
        mountainBuffer = toFloatBuffer(verts.toFloatArray())
    }

    private fun buildOrbitalRings(r: Float, g: Float, b: Float) {
        val verts = mutableListOf<Float>()
        val segs = 64
        for (ringIdx in 0..2) {
            val rad = 5.5f + ringIdx * 2.2f
            val y = 1.2f + ringIdx * 1.1f
            for (i in 0 until segs) {
                val a0 = (i.toFloat() / segs) * 2f * PI.toFloat()
                val a1 = ((i + 1).toFloat() / segs) * 2f * PI.toFloat()
                addVertex(verts, cos(a0) * rad, y, sin(a0) * rad, r, g, b, 0.45f)
                addVertex(verts, cos(a1) * rad, y, sin(a1) * rad, r, g, b, 0.45f)
            }
        }
        ringVertexCount = verts.size / 7
        ringBuffer = toFloatBuffer(verts.toFloatArray())
    }

    fun draw(
        colorProg: Int,
        mvpLoc: Int,
        pointSizeLoc: Int,
        brightnessLoc: Int,
        mvpMatrix: FloatArray,
        config: EnvironmentConfig,
        particlesAllowed: Boolean,
        dimMultiplier: Float = 1.0f
    ) {
        ensureGeometry(config)
        GLES30.glUseProgram(colorProg)
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(brightnessLoc, config.brightness * dimMultiplier)

        // Floor / workspace grid
        gridBuffer?.let { buf ->
            drawBuffer(buf, gridVertexCount, GLES30.GL_LINES, pointSizeLoc, 1f)
        }

        // Environment-specific accents
        when (config.type) {
            VirtualEnvironmentType.NATURE_DUSK -> {
                mountainBuffer?.let { drawBuffer(it, mountainVertexCount, GLES30.GL_TRIANGLES, pointSizeLoc, 1f) }
            }
            VirtualEnvironmentType.MINIMAL_LAB,
            VirtualEnvironmentType.SPACE_STATION,
            VirtualEnvironmentType.DIGITAL_WORKSPACE -> {
                ringBuffer?.let { drawBuffer(it, ringVertexCount, GLES30.GL_LINES, pointSizeLoc, 1f) }
            }
            else -> Unit
        }

        if (particlesAllowed && config.particleDensity > 0.05f) {
            starBuffer?.let { buf ->
                val count = (starVertexCount * config.particleDensity).toInt().coerceIn(10, starVertexCount)
                drawBuffer(buf, count, GLES30.GL_POINTS, pointSizeLoc, 4.5f)
            }
        }
    }

    private fun drawBuffer(buf: FloatBuffer, count: Int, mode: Int, pointSizeLoc: Int, ptSize: Float) {
        GLES30.glUniform1f(pointSizeLoc, ptSize)
        val stride = 7 * 4
        buf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, buf)
        GLES30.glDrawArrays(mode, 0, count)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun addVertex(
        out: MutableList<Float>,
        x: Float, y: Float, z: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        out.add(x); out.add(y); out.add(z)
        out.add(r); out.add(g); out.add(b); out.add(a)
    }

    private fun toFloatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(arr); position(0) }
}
