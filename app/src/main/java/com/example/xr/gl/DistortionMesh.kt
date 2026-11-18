package com.example.xr.gl

import com.example.xr.model.OpticsProfile
import kotlin.math.sqrt

/**
 * Generates a pre-computed 40x40 Brown-Conrady radial distortion mesh per eye
 * with separate R, G, B texture coordinates for chromatic aberration compensation.
 */
object DistortionMesh {
    const val GRID_SIZE = 40
    // Interleaved vertex format: posX, posY, rU, rV, gU, gV, bU, bV, vignette (9 floats per vertex)
    const val FLOATS_PER_VERTEX = 9

    data class MeshData(
        val vertices: FloatArray,
        val indices: ShortArray
    )

    fun buildEyeMesh(
        optics: OpticsProfile,
        isLeftEye: Boolean,
        gridSize: Int = GRID_SIZE
    ): MeshData {
        val cols = gridSize + 1
        val rows = gridSize + 1
        val vertices = FloatArray(cols * rows * FLOATS_PER_VERTEX)
        val indices = ShortArray(gridSize * gridSize * 6)

        val yOffset = if (isLeftEye) optics.leftEyeVerticalOffset else optics.rightEyeVerticalOffset
        // Mild optical center shift based on IPD relative to standard 63mm
        val ipdDelta = (optics.ipdMm - 63f) * 0.004f
        val xCenterShift = if (isLeftEye) ipdDelta else -ipdDelta
        val dpiAspectAdjust = (optics.xdpi / optics.ydpi).coerceIn(0.85f, 1.15f)

        val k1 = optics.k1
        val k2 = optics.k2
        val p1 = optics.p1
        val p2 = optics.p2
        val ca = optics.chromaticAberration

        var vIdx = 0
        for (row in 0 until rows) {
            val ny = (row.toFloat() / gridSize) * 2f - 1f // -1..1
            for (col in 0 until cols) {
                val nx = (col.toFloat() / gridSize) * 2f - 1f // -1..1

                // Screen NDC for this eye (-1..1 in eye viewport)
                val posX = nx
                val posY = ny

                val dx = (nx - xCenterShift) * dpiAspectAdjust
                val dy = ny - yOffset
                val r2 = dx * dx + dy * dy
                val r4 = r2 * r2
                // Full Brown-Conrady pre-distortion: radial (k1, k2) + tangential decentering (p1, p2)
                val radialTerm = (1f - k1 * r2 + k2 * r4).coerceAtLeast(0.5f)
                val tangX = 2f * p1 * dx * dy + p2 * (r2 + 2f * dx * dx)
                val tangY = p1 * (r2 + 2f * dy * dy) + 2f * p2 * dx * dy

                val warpX = dx * radialTerm + tangX
                val warpY = dy * radialTerm + tangY

                // Per-channel UVs for chromatic aberration
                val rU = (warpX * (1f - ca) + 1f) * 0.5f
                val rV = (warpY * (1f - ca) + 1f) * 0.5f
                val gU = (warpX + 1f) * 0.5f
                val gV = (warpY + 1f) * 0.5f
                val bU = (warpX * (1f + ca) + 1f) * 0.5f
                val bV = (warpY * (1f + ca) + 1f) * 0.5f

                val radial = sqrt(r2)
                val safeInner = (0.91f - optics.lensSafeMarginFrac).coerceIn(0.72f, 0.92f)
                val vignette = (1f - ((radial - safeInner).coerceAtLeast(0f) / 0.45f)).coerceIn(0f, 1f)

                vertices[vIdx++] = posX
                vertices[vIdx++] = posY
                vertices[vIdx++] = rU
                vertices[vIdx++] = rV
                vertices[vIdx++] = gU
                vertices[vIdx++] = gV
                vertices[vIdx++] = bU
                vertices[vIdx++] = bV
                vertices[vIdx++] = vignette
            }
        }

        var iIdx = 0
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val topLeft = (row * cols + col).toShort()
                val topRight = (topLeft + 1).toShort()
                val bottomLeft = ((row + 1) * cols + col).toShort()
                val bottomRight = (bottomLeft + 1).toShort()

                indices[iIdx++] = topLeft
                indices[iIdx++] = bottomLeft
                indices[iIdx++] = topRight

                indices[iIdx++] = topRight
                indices[iIdx++] = bottomLeft
                indices[iIdx++] = bottomRight
            }
        }

        return MeshData(vertices = vertices, indices = indices)
    }
}
