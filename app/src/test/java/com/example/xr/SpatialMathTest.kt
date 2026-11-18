package com.example.xr

import com.example.xr.gl.DistortionMesh
import com.example.xr.hand.HandFrame
import com.example.xr.hand.HandLandmarkIndex
import com.example.xr.hand.HandRayInteractor
import com.example.xr.hand.HandSide
import com.example.xr.model.OpticsPreset
import com.example.xr.model.OpticsProfile
import com.example.xr.tracking.HeadPoseSnapshot
import com.example.xr.tracking.OneEuroFilter
import com.example.xr.tracking.Quat
import com.example.xr.tracking.Ray
import com.example.xr.tracking.Vec3
import com.example.xr.windows.SpatialWindowManager
import com.example.xr.windows.WindowHitRegion
import com.example.xr.windows.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialMathTest {

    @Test
    fun quaternionPrediction_advancesOrientationAlongAngularVelocity() {
        val base = Quat.IDENTITY
        val angVel = Vec3(0f, 1.0f, 0f) // 1 rad/s yaw
        val predicted = Quat.predictOrientation(base, angVel, 0.03f)
        val fwd = predicted.rotate(Vec3.FORWARD)
        assertTrue("Expected yawed forward X < 0, was ${fwd.x}", fwd.x < -0.02f)
    }

    @Test
    fun rayQuadIntersection_returnsCenteredUv() {
        val ray = Ray(origin = Vec3.ZERO, direction = Vec3.FORWARD)
        val hit = ray.intersectQuad(
            quadCenter = Vec3(0f, 0f, -2f),
            quadRight = Vec3.RIGHT,
            quadUp = Vec3.UP,
            quadNormal = Vec3(0f, 0f, 1f),
            width = 1.6f,
            height = 1.0f
        )
        assertNotNull(hit)
        assertEquals(2.0f, hit!!.distance, 0.001f)
        assertEquals(0.5f, hit.u, 0.001f)
        assertEquals(0.5f, hit.v, 0.001f)
    }

    @Test
    fun rayQuadIntersection_missesOutsideBounds() {
        val ray = Ray(origin = Vec3.ZERO, direction = Vec3(1f, 0f, -1f).normalized())
        val hit = ray.intersectQuad(
            quadCenter = Vec3(0f, 0f, -2f),
            quadRight = Vec3.RIGHT,
            quadUp = Vec3.UP,
            quadNormal = Vec3(0f, 0f, 1f),
            width = 1.0f,
            height = 1.0f
        )
        assertNull(hit)
    }

    @Test
    fun oneEuroFilter_dampensJitterAtRest() {
        val filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.05f)
        var t = 1_000_000_000L
        val outputs = mutableListOf<Float>()
        for (i in 0 until 20) {
            val noisy = if (i % 2 == 0) 0.02f else -0.02f
            outputs += filter.filter(noisy, t)
            t += 16_000_000L
        }
        assertTrue("Expected smoothed output < raw amplitude", abs(outputs.last()) < 0.015f)
    }

    @Test
    fun distortionMesh_buildsExpected40x40GridWithChromaticSeparation() {
        val optics = OpticsProfile(preset = OpticsPreset.OPEN_VR_STANDARD)
        val mesh = DistortionMesh.buildEyeMesh(optics, isLeftEye = true)
        assertEquals(41 * 41 * DistortionMesh.FLOATS_PER_VERTEX, mesh.vertices.size)
        assertEquals(40 * 40 * 6, mesh.indices.size)
        // Corner vertex red U != blue U due to chromatic aberration
        val rU = mesh.vertices[2]
        val gU = mesh.vertices[4]
        val bU = mesh.vertices[6]
        assertTrue("Expected chromatic UV separation at edge", abs(rU - bU) > 0.001f)
        // Negative k1 (-0.18) pre-distorts corners outward (barrel compensation -> gU < 0 at col=0, row=0)
        assertTrue("Expected barrel pre-distortion gU < 0 at bottom-left corner, was $gU", gU < 0f)
    }

    @Test
    fun windowManager_defaultWorkspaceCentersSpatialPhoneLauncher() {
        val mgr = SpatialWindowManager()
        val launcher = mgr.windows.value.firstOrNull { it.type == WindowType.APP_LIBRARY }
        assertNotNull(launcher)
        assertEquals(0f, launcher!!.azimuthDeg, 0.01f)
        assertEquals(launcher.id, mgr.focusedWindowId.value)
    }

    @Test
    fun windowManager_enforcesMaxActiveContentWindowLimit() {
        val mgr = SpatialWindowManager(maxActiveContentWindows = 2)
        mgr.openOrFocusWindow(WindowType.BROWSER)
        mgr.openOrFocusWindow(WindowType.MEDIA_PLAYER)
        mgr.openOrFocusWindow(WindowType.GALLERY)
        val activeContentCount = mgr.windows.value.count {
            !it.minimized && !it.suspended && it.type.countsAgainstActiveLimit
        }
        assertEquals(2, activeContentCount)
    }

    @Test
    fun windowManager_clampsDepthAndRevertsCancelledDrag() {
        val mgr = SpatialWindowManager()
        val win = mgr.openOrFocusWindow(WindowType.SETTINGS)
        val originalAz = win.azimuthDeg
        mgr.snapshotForDrag(win.id)
        mgr.moveWindow(win.id, deltaAzimuthDeg = 40f, deltaElevationDeg = 10f, depthDeltaMeters = 10f)
        val moved = mgr.windows.value.first { it.id == win.id }
        assertEquals(4.5f, moved.distanceMeters, 0.001f)
        mgr.revertDrag(win.id)
        val reverted = mgr.windows.value.first { it.id == win.id }
        assertEquals(originalAz, reverted.azimuthDeg, 0.001f)
    }

    @Test
    fun windowManager_targetMagnetismClassifiesGrabberAndCloseCleanly() {
        assertEquals(WindowHitRegion.GRABBER_BAR, SpatialWindowManager.classifyRegionStatic(0.50f, 0.94f))
        assertEquals(WindowHitRegion.CLOSE_BUTTON, SpatialWindowManager.classifyRegionStatic(0.94f, 0.055f))
        assertEquals(WindowHitRegion.MINIMIZE_BUTTON, SpatialWindowManager.classifyRegionStatic(0.84f, 0.055f))
        assertEquals(WindowHitRegion.RESIZE_HANDLE, SpatialWindowManager.classifyRegionStatic(0.95f, 0.94f))
    }

    @Test
    fun handRayInteractor_translatesCameraHandCoordsIntoVirtualPointerRay() {
        val interactor = HandRayInteractor()
        val headPose = HeadPoseSnapshot(position = Vec3.ZERO, orientation = Quat.IDENTITY)

        fun makePointerHand(tipX: Float, tipY: Float): HandFrame {
            val pts = MutableList(21) { Vec3(0.5f, 0.7f, 0f) }
            pts[HandLandmarkIndex.WRIST] = Vec3(0.5f, 0.8f, 0f)
            pts[HandLandmarkIndex.INDEX_MCP] = Vec3(0.45f, 0.65f, 0f)
            pts[HandLandmarkIndex.PINKY_MCP] = Vec3(0.55f, 0.65f, 0f)
            pts[HandLandmarkIndex.INDEX_TIP] = Vec3(tipX, tipY, 0f)
            return HandFrame(HandSide.RIGHT, 0.95f, pts, pts, 100L)
        }

        val leftPointRay = interactor.computeInteractionRay(headPose, true, makePointerHand(0.20f, 0.30f))
        val rightPointRay = interactor.computeInteractionRay(headPose, true, makePointerHand(0.80f, 0.30f))
        assertTrue("Left hand point should aim negative X", leftPointRay.direction.x < -0.25f)
        assertTrue("Right hand point should aim positive X", rightPointRay.direction.x > 0.15f)
        assertTrue("Upward finger should aim positive Y", leftPointRay.direction.y > 0.15f)
    }

    @Test
    fun handRayInteractor_directPokeDetectsCloseWindowSurface() {
        val interactor = HandRayInteractor()
        val mgr = SpatialWindowManager()
        val win = mgr.windows.value.first { it.type == WindowType.APP_LIBRARY }
        // Place palm width so estimated depth ~ distanceMeters (2.20m) or pull window close to 0.45m
        mgr.moveWindow(win.id, 0f, -6f, -1.0f) // brings window to 1.25m, 0 deg
        val updated = listOf(win.copy(azimuthDeg = 0f, elevationDeg = 0f, distanceMeters = 0.45f))

        val pts = MutableList(21) { Vec3(0.5f, 0.5f, 0f) }
        pts[HandLandmarkIndex.WRIST] = Vec3(0.5f, 0.6f, 0f)
        // palmPx = 0.18 => estDepth = 0.085 * 0.95 / 0.18 = 0.4486m (~1.4mm from 0.45m quad!)
        pts[HandLandmarkIndex.INDEX_MCP] = Vec3(0.41f, 0.55f, 0f)
        pts[HandLandmarkIndex.PINKY_MCP] = Vec3(0.59f, 0.55f, 0f)
        pts[HandLandmarkIndex.INDEX_TIP] = Vec3(0.5f, 0.5f, 0f)
        val hand = HandFrame(HandSide.RIGHT, 0.9f, pts, pts, 100L, cameraHfovDeg = 55.5f)

        val poke = interactor.findDirectPokeHit(
            headPose = HeadPoseSnapshot(orientation = Quat.IDENTITY, position = Vec3.ZERO),
            hand = hand,
            calibratedPalmWidthMeters = 0.085f,
            windows = updated
        )
        assertNotNull("Expected direct near-field poke hit at 45cm", poke)
    }
}
