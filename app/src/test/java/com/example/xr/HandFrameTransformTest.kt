package com.example.xr

import android.content.Context
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.example.xr.hand.HandFrameTransform
import com.example.xr.system.PerformanceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HandFrameTransformTest {

    @Test
    fun resolveCameraOrientation_tableDriven_rearAndFrontAcrossRotations() {
        val sensors = listOf(90, 270)
        val displayRotations = listOf(
            Surface.ROTATION_0 to 0,
            Surface.ROTATION_90 to 90,
            Surface.ROTATION_180 to 180,
            Surface.ROTATION_270 to 270
        )
        val userOffsets = listOf(0, 90, 180, 270)

        for (sensor in sensors) {
            for ((dispConst, dispDeg) in displayRotations) {
                for (offset in userOffsets) {
                    for (userMirror in listOf(false, true)) {
                        // Rear camera
                        val rear = HandFrameTransform.resolveCameraOrientation(
                            sensorOrientationDeg = sensor,
                            lensFacingBack = true,
                            displayRotation = dispConst,
                            hasCameraTransform = true,
                            streamMirroring = false,
                            userRotationOffsetDeg = offset,
                            userMirrorHorizontal = userMirror
                        )
                        val expectedRearBase = ((sensor - dispDeg) % 360 + 360) % 360
                        val expectedRearFinal = (expectedRearBase + offset) % 360
                        val expectedRearGlBase = ((expectedRearBase - sensor) % 360 + 360) % 360
                        val expectedRearGlFinal = (expectedRearGlBase + offset) % 360

                        assertEquals(expectedRearBase, rear.baseRotationDeg)
                        assertEquals(expectedRearFinal, rear.finalRotationDeg)
                        assertEquals(expectedRearGlBase, rear.glPassthroughBaseDeg)
                        assertEquals(expectedRearGlFinal, rear.glPassthroughFinalDeg)
                        assertEquals(userMirror, rear.mirrorHorizontal)

                        // Front camera
                        val front = HandFrameTransform.resolveCameraOrientation(
                            sensorOrientationDeg = sensor,
                            lensFacingBack = false,
                            displayRotation = dispConst,
                            hasCameraTransform = true,
                            streamMirroring = false,
                            userRotationOffsetDeg = offset,
                            userMirrorHorizontal = userMirror
                        )
                        val expectedFrontBase = ((sensor + dispDeg) % 360 + 360) % 360
                        val expectedFrontFinal = (expectedFrontBase + offset) % 360
                        val expectedFrontGlBase = ((expectedFrontBase + sensor) % 360 + 360) % 360
                        val expectedFrontGlFinal = (expectedFrontGlBase + offset) % 360

                        assertEquals(expectedFrontBase, front.baseRotationDeg)
                        assertEquals(expectedFrontFinal, front.finalRotationDeg)
                        assertEquals(expectedFrontGlBase, front.glPassthroughBaseDeg)
                        assertEquals(expectedFrontGlFinal, front.glPassthroughFinalDeg)
                        assertEquals(!userMirror, front.mirrorHorizontal)
                    }
                }
            }
        }
    }

    @Test
    fun resolveCameraOrientation_180DegreeLandscapeFlip_flipsGlAndAnalysisBy180() {
        val rot90 = HandFrameTransform.resolveCameraOrientation(
            sensorOrientationDeg = 90,
            lensFacingBack = true,
            displayRotation = Surface.ROTATION_90,
            hasCameraTransform = true,
            userRotationOffsetDeg = 0
        )
        val rot270 = HandFrameTransform.resolveCameraOrientation(
            sensorOrientationDeg = 90,
            lensFacingBack = true,
            displayRotation = Surface.ROTATION_270,
            hasCameraTransform = true,
            userRotationOffsetDeg = 0
        )
        assertEquals(0, rot90.finalRotationDeg)
        assertEquals(180, rot270.finalRotationDeg)
        assertEquals(270, rot90.glPassthroughFinalDeg)
        assertEquals(90, rot270.glPassthroughFinalDeg)
        assertFalse(rot90.mirrorHorizontal)
        assertFalse(rot270.mirrorHorizontal)
    }

    @Test
    fun resolveCameraOrientation_noCameraTransform_usesFullBaseForGl() {
        val noTransform = HandFrameTransform.resolveCameraOrientation(
            sensorOrientationDeg = 90,
            lensFacingBack = true,
            displayRotation = Surface.ROTATION_90,
            hasCameraTransform = false,
            userRotationOffsetDeg = 90
        )
        assertEquals(0, noTransform.glPassthroughBaseDeg)
        assertEquals(90, noTransform.glPassthroughFinalDeg)
    }

    @Test
    fun transformAndInverseNormalizedPoint_roundtripsAcrossAllRotationsAndMirrors() {
        val pts = listOf(0.18f to 0.31f, 0.77f to 0.12f, 0.45f to 0.89f)
        for (rot in listOf(0, 90, 180, 270)) {
            for (mirror in listOf(false, true)) {
                for ((x, y) in pts) {
                    val fwd = HandFrameTransform.transformNormalizedPoint(x, y, -0.25f, rot, mirror)
                    val inv = HandFrameTransform.inverseTransformNormalizedPoint(fwd.x, fwd.y, fwd.z, rot, mirror)
                    assertEquals(x, inv.x, 1e-5f)
                    assertEquals(y, inv.y, 1e-5f)
                    assertEquals(-0.25f, inv.z, 1e-5f)
                }
            }
        }
    }

    @Test
    fun computeAspectFitScale_preservesAspectAndHandlesRotationAndInvalidBounds() {
        // 4:3 landscape source inside 1:1 eye FBO with 0° rotation -> fill width, letterbox Y = 0.75
        val fitLandscape = HandFrameTransform.computeAspectFitScale(
            srcWidthPx = 960,
            srcHeightPx = 720,
            rotationDeg = 0,
            dstWidth = 1000f,
            dstHeight = 1000f
        )
        assertEquals(1.0f, fitLandscape.scaleX, 1e-4f)
        assertEquals(0.75f, fitLandscape.scaleY, 1e-4f)

        // Same 960x720 source rotated 90° -> effective 720x960 portrait -> fill height, pillarbox X = 0.75
        val fitPortrait = HandFrameTransform.computeAspectFitScale(
            srcWidthPx = 960,
            srcHeightPx = 720,
            rotationDeg = 90,
            dstWidth = 1000f,
            dstHeight = 1000f
        )
        assertEquals(0.75f, fitPortrait.scaleX, 1e-4f)
        assertEquals(1.0f, fitPortrait.scaleY, 1e-4f)

        // Invalid dimensions clamp safely to 1:1
        val invalid = HandFrameTransform.computeAspectFitScale(0, 720, 0, 1000f, 1000f)
        assertEquals(1f, invalid.scaleX, 1e-4f)
        assertEquals(1f, invalid.scaleY, 1e-4f)
    }

    @Test
    fun performanceMonitor_computesP99AndDroppedFramesCorrectly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val monitor = PerformanceMonitor(context)
        monitor.targetRefreshRateHz = 60f // budget = 16.667ms, threshold = 19.167ms

        // Record 99 fast frames (10ms) and 1 slow frame (25ms)
        repeat(99) {
            monitor.recordFrameDurationNs(10_000_000L, predictionMs = 18f, forceReport = false)
        }
        monitor.recordFrameDurationNs(25_000_000L, predictionMs = 18f, forceReport = true)

        val telemetry = monitor.telemetry.value
        assertEquals(1L, telemetry.droppedFrames)
        assertTrue(telemetry.frameTimeP99Ms >= 10f)
        assertTrue(telemetry.frameTimeP99Ms >= telemetry.frameTimeP95Ms)
    }
}
