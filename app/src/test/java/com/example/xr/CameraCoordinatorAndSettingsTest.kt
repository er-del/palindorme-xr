package com.example.xr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.xr.apps.AndroidAppManager
import com.example.xr.perception.CameraConsumerMode
import com.example.xr.perception.CameraCoordinatorState
import com.example.xr.perception.CameraModeCoordinator
import com.example.xr.settings.XRSettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraCoordinatorAndSettingsTest {

    private class FakeCameraOwner(override val mode: CameraConsumerMode) :
        CameraModeCoordinator.CameraOwnerHandle {
        var released = false
        override suspend fun acquire(): Boolean = true
        override suspend fun release() {
            released = true
        }
        override fun forceClose() {
            released = true
        }
    }

    @Test
    fun cameraCoordinator_hundredConsecutiveSwitches_succeedWithoutError() = runTest {
        val coordinator = CameraModeCoordinator()
        val ownerA = FakeCameraOwner(CameraConsumerMode.CAMERAX_SHARED)
        val ownerB = FakeCameraOwner(CameraConsumerMode.ARCORE_MODE)

        for (i in 0 until 100) {
            val target = if (i % 2 == 0) CameraConsumerMode.CAMERAX_SHARED else CameraConsumerMode.ARCORE_MODE
            val ok = coordinator.switchModeSync(target) { mode ->
                if (mode == CameraConsumerMode.CAMERAX_SHARED) ownerA else ownerB
            }
            assertTrue("Switch $i failed", ok)
            assertEquals(CameraCoordinatorState.ACTIVE, coordinator.status.value.state)
            assertEquals(target, coordinator.status.value.activeMode)
        }
        assertEquals(100, coordinator.status.value.switchCount)
    }

    @Test
    fun settingsManager_persistsOpticsAndComfortChanges() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = XRSettingsManager(context)
        manager.update {
            it.copy(
                optics = it.optics.copy(ipdMm = 66.5f),
                gazeDwellSeconds = 1.5f,
                cameraRotationDegrees = 90,
                cameraMirrorHorizontal = true,
                galleryRotationDegrees = 270,
                showCalibrationGrid = true,
                handRayModeEnabled = true
            )
        }
        val loaded = manager.settingsFlow.first()
        assertEquals(66.5f, loaded.optics.ipdMm, 0.01f)
        assertEquals(1.5f, loaded.gazeDwellSeconds, 0.01f)
        assertEquals(90, loaded.cameraRotationDegrees)
        assertTrue(loaded.cameraMirrorHorizontal)
        assertEquals(270, loaded.galleryRotationDegrees)
        assertTrue(loaded.showCalibrationGrid)
        assertTrue(loaded.handRayModeEnabled)

        // Verify saved camera_rot_deg retains additive offset semantics on top of automatic landscape base
        val resolvedLandscape = com.example.xr.hand.HandFrameTransform.resolveCameraOrientation(
            sensorOrientationDeg = 90,
            lensFacingBack = true,
            displayRotation = android.view.Surface.ROTATION_90,
            hasCameraTransform = true,
            userRotationOffsetDeg = loaded.cameraRotationDegrees
        )
        assertEquals(0, resolvedLandscape.baseRotationDeg)
        assertEquals(90, resolvedLandscape.userOffsetDeg)
        assertEquals(90, resolvedLandscape.finalRotationDeg)
        assertEquals(270, resolvedLandscape.glPassthroughBaseDeg)
        assertEquals(0, resolvedLandscape.glPassthroughFinalDeg)

        manager.update {
            it.copy(
                immersionLevel = 0.35f,
                cinemaModeEnabled = true,
                cinemaProjectionMode = com.example.xr.model.CinemaProjectionMode.SBS_3D
            )
        }
        val cinemaLoaded = manager.settingsFlow.first()
        assertEquals(0.35f, cinemaLoaded.immersionLevel, 0.01f)
        assertTrue(cinemaLoaded.cinemaModeEnabled)
        assertEquals(com.example.xr.model.CinemaProjectionMode.SBS_3D, cinemaLoaded.cinemaProjectionMode)
    }

    @Test
    fun appManager_refreshCompletesCleanly() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appMgr = AndroidAppManager(context)
        appMgr.refreshInstalledApps()
        assertTrue(appMgr.apps.value.size >= 0)
    }

    @Test
    fun handTrackingEngine_reportsBundledMediaPipeModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.xr.hand.HandTrackingEngine(context)
        assertTrue("Expected hand_landmarker.task to be bundled in assets", engine.isModelBundled())
    }
}
