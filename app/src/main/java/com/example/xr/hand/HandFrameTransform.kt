package com.example.xr.hand

import android.view.Surface
import com.example.xr.tracking.Vec3
import java.nio.ByteBuffer

/**
 * Pure coordinate & pixel-buffer transform utilities for aligning CameraX frames
 * with display orientation, user rotation overrides, horizontal mirroring, and ROI tracking.
 */
object HandFrameTransform {

    data class CameraOrientationTransform(
        val sensorOrientationDeg: Int,
        val displayRotation: Int,
        val displayRotationDeg: Int,
        val baseRotationDeg: Int,
        val userOffsetDeg: Int,
        val finalRotationDeg: Int,
        val glPassthroughBaseDeg: Int,
        val glPassthroughFinalDeg: Int,
        val hasCameraTransform: Boolean,
        val mirrorHorizontal: Boolean,
        val lensFacingBack: Boolean
    )

    data class AspectFitScale(
        val scaleX: Float,
        val scaleY: Float
    )

    fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * Standard Android camera upright formula:
     * Rear camera: (sensorOrientation - displayDegrees + 360) % 360
     * Front camera: (sensorOrientation + displayDegrees) % 360
     */
    fun computeBaseCameraUprightRotation(
        sensorOrientationDeg: Int,
        lensFacingBack: Boolean,
        displayRotation: Int
    ): Int {
        val displayDeg = surfaceRotationToDegrees(displayRotation)
        val sign = if (lensFacingBack) 1 else -1
        return (((sensorOrientationDeg - displayDeg * sign) % 360) + 360) % 360
    }

    /**
     * Single source of truth shared by Passthrough OES quad, CameraX Preview/ImageAnalysis,
     * HandTrackingEngine, and ARCore CPU hand extraction.
     * Additive [userRotationOffsetDeg] (the preserved 0/90/180/270 rotate setting) is applied
     * strictly ON TOP of the automatic base, with 0 meaning no extra offset.
     */
    fun resolveCameraOrientation(
        sensorOrientationDeg: Int,
        lensFacingBack: Boolean,
        displayRotation: Int,
        reportedBaseRotationDeg: Int? = null,
        hasCameraTransform: Boolean = true,
        streamMirroring: Boolean = false,
        userRotationOffsetDeg: Int = 0,
        userMirrorHorizontal: Boolean = false
    ): CameraOrientationTransform {
        val sensorNorm = normalizeRotationDeg(sensorOrientationDeg)
        val displayDeg = surfaceRotationToDegrees(displayRotation)
        val computedBase = computeBaseCameraUprightRotation(
            sensorOrientationDeg = sensorNorm,
            lensFacingBack = lensFacingBack,
            displayRotation = displayRotation
        )
        val baseRot = reportedBaseRotationDeg?.let { normalizeRotationDeg(it) } ?: computedBase
        val userOffset = normalizeRotationDeg(userRotationOffsetDeg)
        val finalRot = normalizeRotationDeg(baseRot + userOffset)

        // When SurfaceTexture receives a Camera2 BufferQueue transform (hasCameraTransform = true),
        // SurfaceTexture.getTransformMatrix() already includes sensorOrientation (natural ROTATION_0).
        // Subtracting sensorNorm yields the residual display-relative GL rotation ((-displayDeg) % 360 for rear).
        val glBaseRot = if (hasCameraTransform) {
            normalizeRotationDeg(baseRot - if (lensFacingBack) sensorNorm else -sensorNorm)
        } else {
            baseRot
        }
        val glFinalRot = normalizeRotationDeg(glBaseRot + userOffset)

        // Rear cameras never mirror by default; front cameras mirror by default (flipped if user toggles)
        val baseMirror = if (lensFacingBack) streamMirroring else !streamMirroring
        val finalMirror = baseMirror xor userMirrorHorizontal
        return CameraOrientationTransform(
            sensorOrientationDeg = sensorNorm,
            displayRotation = displayRotation,
            displayRotationDeg = displayDeg,
            baseRotationDeg = baseRot,
            userOffsetDeg = userOffset,
            finalRotationDeg = finalRot,
            glPassthroughBaseDeg = glBaseRot,
            glPassthroughFinalDeg = glFinalRot,
            hasCameraTransform = hasCameraTransform,
            mirrorHorizontal = finalMirror,
            lensFacingBack = lensFacingBack
        )
    }

    /**
     * Preserves source aspect ratio inside a target viewport/quad (fit / letterbox, never stretch).
     * Swaps [srcWidthPx] and [srcHeightPx] when [rotationDeg] is 90 or 270.
     */
    fun computeAspectFitScale(
        srcWidthPx: Int,
        srcHeightPx: Int,
        rotationDeg: Int,
        dstWidth: Float,
        dstHeight: Float
    ): AspectFitScale {
        if (srcWidthPx <= 0 || srcHeightPx <= 0 || dstWidth <= 0f || dstHeight <= 0f) {
            return AspectFitScale(1f, 1f)
        }
        val rot = normalizeRotationDeg(rotationDeg)
        val effW = if (rot == 90 || rot == 270) srcHeightPx.toFloat() else srcWidthPx.toFloat()
        val effH = if (rot == 90 || rot == 270) srcWidthPx.toFloat() else srcHeightPx.toFloat()
        val srcAspect = effW / effH
        val dstAspect = dstWidth / dstHeight
        return if (srcAspect > dstAspect) {
            // Source is wider than target -> fill full width, letterbox vertically
            AspectFitScale(scaleX = 1f, scaleY = (dstAspect / srcAspect).coerceIn(0.25f, 1f))
        } else {
            // Source is taller than target -> fill full height, pillarbox horizontally
            AspectFitScale(scaleX = (srcAspect / dstAspect).coerceIn(0.25f, 1f), scaleY = 1f)
        }
    }

    /**
     * Backward-compatible convenience overload.
     */
    fun computeRearCameraUprightRotation(
        sensorOrientationDeg: Int,
        displayRotation: Int,
        userRotationDegrees: Int = 0
    ): Int = resolveCameraOrientation(
        sensorOrientationDeg = sensorOrientationDeg,
        lensFacingBack = true,
        displayRotation = displayRotation,
        userRotationOffsetDeg = userRotationDegrees
    ).finalRotationDeg

    fun normalizeRotationDeg(deg: Int): Int = ((deg % 360) + 360) % 360

    /**
     * Rotates clockwise by [rotationDeg] (0, 90, 180, 270) and optionally mirrors horizontally
     * for normalized [0, 1] image coordinates (top-left origin).
     */
    fun transformNormalizedPoint(
        x: Float,
        y: Float,
        z: Float,
        rotationDeg: Int,
        mirrorHorizontal: Boolean
    ): Vec3 {
        val rot = normalizeRotationDeg(rotationDeg)
        val (rx, ry) = when (rot) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
        val mx = if (mirrorHorizontal) (1f - rx) else rx
        return Vec3(mx, ry, z)
    }

    /**
     * Inverse of [transformNormalizedPoint] to map landmarks back onto raw unrotated sensor frame.
     */
    fun inverseTransformNormalizedPoint(
        x: Float,
        y: Float,
        z: Float,
        rotationDeg: Int,
        mirrorHorizontal: Boolean
    ): Vec3 {
        val unmirroredX = if (mirrorHorizontal) (1f - x) else x
        val rot = normalizeRotationDeg(rotationDeg)
        val (ox, oy) = when (rot) {
            90 -> y to (1f - unmirroredX)
            180 -> (1f - unmirroredX) to (1f - y)
            270 -> (1f - y) to unmirroredX
            else -> unmirroredX to y
        }
        return Vec3(ox, oy, z)
    }

    /**
     * Single-pass RGBA_8888 rotation & optional horizontal mirror into a pre-allocated [dstPixels] buffer
     * without allocating intermediate Bitmaps. Returns Pair(outWidth, outHeight).
     */
    fun transformRgbaBuffer(
        srcBuffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        rowStrideBytes: Int,
        pixelStrideBytes: Int,
        rotationDeg: Int,
        mirrorHorizontal: Boolean,
        dstPixels: IntArray
    ): Pair<Int, Int> {
        val rot = normalizeRotationDeg(rotationDeg)
        val outW = if (rot == 90 || rot == 270) srcHeight else srcWidth
        val outH = if (rot == 90 || rot == 270) srcWidth else srcHeight
        if (dstPixels.size < outW * outH) return outW to outH

        val dup = srcBuffer.duplicate()
        dup.rewind()

        for (sy in 0 until srcHeight) {
            val rowBase = sy * rowStrideBytes
            for (sx in 0 until srcWidth) {
                val off = rowBase + sx * pixelStrideBytes
                val r = dup.get(off).toInt() and 0xFF
                val g = dup.get(off + 1).toInt() and 0xFF
                val b = dup.get(off + 2).toInt() and 0xFF
                val a = dup.get(off + 3).toInt() and 0xFF
                val argb = (a shl 24) or (r shl 16) or (g shl 8) or b

                var dx: Int
                var dy: Int
                when (rot) {
                    90 -> {
                        dx = (srcHeight - 1) - sy
                        dy = sx
                    }
                    180 -> {
                        dx = (srcWidth - 1) - sx
                        dy = (srcHeight - 1) - sy
                    }
                    270 -> {
                        dx = sy
                        dy = (srcWidth - 1) - sx
                    }
                    else -> {
                        dx = sx
                        dy = sy
                    }
                }
                if (mirrorHorizontal) {
                    dx = (outW - 1) - dx
                }
                dstPixels[dy * outW + dx] = argb
            }
        }
        return outW to outH
    }
}
