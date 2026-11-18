package com.example

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.PalindormeXRTheme
import com.example.xr.AppMode
import com.example.xr.XRLauncherCoordinator
import com.example.xr.XrEnvironment
import com.example.xr.ui.Hub2DScreen
import com.example.xr.ui.HubAndXrDialogs
import com.example.xr.ui.StereoViewportScreen

class MainActivity : ComponentActivity() {

    private val coordinator: XRLauncherCoordinator by viewModels()

    private var pendingCameraAction: (() -> Unit)? = null
    private var pendingMicAction: (() -> Unit)? = null
    private var displayListenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            val activeDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY
            }
            if (displayId == activeDisplayId || displayId == Display.DEFAULT_DISPLAY) {
                syncDisplayRotationToSensors()
            }
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCameraAction?.invoke()
        } else {
            coordinator.updateSettings { it.copy(passthroughEnabled = false, handControlEnabled = false, arCoreEnabled = false) }
            coordinator.postStatusToast("Camera permission denied — virtual environments, gaze dwell, and gamepad remain active.")
        }
        pendingCameraAction = null
    }

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingMicAction?.invoke()
        } else {
            coordinator.postStatusToast("Microphone permission denied — voice commands stay off.")
        }
        pendingMicAction = null
    }

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8)
    ) { uris ->
        coordinator.addGalleryPhotos(uris)
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { coordinator.loadPickedVideo(it) }
    }

    private val gltfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { coordinator.loadPickedGltf(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate start (API ${Build.VERSION.SDK_INT})")
        runCatching { enableEdgeToEdge() }.onFailure {
            Log.w(TAG, "enableEdgeToEdge failed cleanly", it)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureDisplayAndThermal()
        applyImmersiveAndOrientation(coordinator.appMode.value)

        coordinator.attachLifecycleOwner(this)
        coordinator.onRequestCameraPermission = { onGranted ->
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                pendingCameraAction = onGranted
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        coordinator.onRequestMicPermission = { onGranted ->
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                pendingMicAction = onGranted
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        coordinator.onRequestPhotoPicker = {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        coordinator.onRequestVideoPicker = {
            videoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        coordinator.onRequestGltfPicker = {
            gltfPickerLauncher.launch(arrayOf("model/gltf-binary", "model/gltf+json", "application/octet-stream", "*/*"))
        }

        setContent {
            PalindormeXRTheme {
                val appMode by coordinator.appMode.collectAsStateWithLifecycle()
                val selectedTab by coordinator.selectedHubTab.collectAsStateWithLifecycle()
                val settings by coordinator.settings.collectAsStateWithLifecycle()
                val dialogs by coordinator.dialogs.collectAsStateWithLifecycle()
                val perf by coordinator.performanceTelemetry.collectAsStateWithLifecycle()
                val tracking by coordinator.trackingTelemetry.collectAsStateWithLifecycle()
                val comfort by coordinator.comfortState.collectAsStateWithLifecycle()
                val handTelemetry by coordinator.handTelemetry.collectAsStateWithLifecycle()
                val arTelemetry by coordinator.arTelemetry.collectAsStateWithLifecycle()
                val cameraStatus by coordinator.cameraStatus.collectAsStateWithLifecycle()

                val pcLink by coordinator.pcLinkStats.collectAsStateWithLifecycle()

                LaunchedEffect(appMode) {
                    applyImmersiveAndOrientation(appMode)
                    syncDisplayRotationToSensors()
                }
                LaunchedEffect(perf.recommendedRefreshRateHz, settings.prefer90HzWhenThermallySafe) {
                    applyPreferredRefreshRate(perf.recommendedRefreshRateHz, settings.prefer90HzWhenThermallySafe)
                }

                when (appMode) {
                    AppMode.STEREO_VR -> {
                        StereoViewportScreen(
                            coordinator = coordinator,
                            settings = settings,
                            dialogs = dialogs,
                            comfort = comfort,
                            handTelemetry = handTelemetry,
                            perf = perf,
                            pcLink = pcLink
                        )
                    }
                    AppMode.HUB_2D -> {
                        Hub2DScreen(
                            coordinator = coordinator,
                            settings = settings,
                            selectedTab = selectedTab,
                            perf = perf,
                            tracking = tracking,
                            handTelemetry = handTelemetry,
                            arTelemetry = arTelemetry,
                            cameraStatus = cameraStatus,
                            onRequestArToggle = { enable -> coordinator.requestArCoreToggle(enable, this@MainActivity) }
                        )
                    }
                }

                HubAndXrDialogs(
                    coordinator = coordinator,
                    dialogs = dialogs,
                    handTelemetry = handTelemetry
                )
            }
        }
        Log.i(TAG, "onCreate setContent complete")
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart mode=${coordinator.appMode.value}")
        registerDisplayListenerIfNeeded()
        syncDisplayRotationToSensors()
        coordinator.onActivityStarted()
    }

    override fun onResume() {
        super.onResume()
        syncDisplayRotationToSensors()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncDisplayRotationToSensors()
    }

    override fun onStop() {
        super.onStop()
        unregisterDisplayListenerIfNeeded()
        coordinator.onActivityStopped()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && coordinator.handleKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && coordinator.handleGenericMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun registerDisplayListenerIfNeeded() {
        if (displayListenerRegistered) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        dm.registerDisplayListener(displayListener, null)
        displayListenerRegistered = true
    }

    private fun unregisterDisplayListenerIfNeeded() {
        if (!displayListenerRegistered) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        dm.unregisterDisplayListener(displayListener)
        displayListenerRegistered = false
    }

    private fun syncDisplayRotationToSensors() {
        val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { display?.rotation }.getOrNull() ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_90
        }
        coordinator.onDisplayRotationChanged(rot)
    }

    private fun applyImmersiveAndOrientation(mode: AppMode) {
        // On XR headsets the app is presented inside a resizable spatial panel, so
        // locking a landscape orientation fights the panel sizing. Phones keep the
        // landscape lock this app was designed around.
        if (XrEnvironment.supportsPhoneVrStereo(this)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        runCatching {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (mode == AppMode.STEREO_VR) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }.onFailure {
            Log.w(TAG, "applyImmersiveAndOrientation failed cleanly", it)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }

    private fun configureDisplayAndThermal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { window.setSustainedPerformanceMode(true) }
        }
        applyPreferredRefreshRate(90, coordinator.settings.value.prefer90HzWhenThermallySafe)
    }

    private fun applyPreferredRefreshRate(targetHz: Int, allow90Hz: Boolean) {
        val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { display }.getOrNull()
                ?: (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        }
        val modes = runCatching { currentDisplay?.supportedModes }.getOrNull() ?: return
        val desired = if (allow90Hz) targetHz else 60
        val bestMode = if (desired >= 85) {
            modes.maxByOrNull { it.refreshRate }
        } else {
            modes.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
        }
        if (bestMode != null) {
            coordinator.performanceMonitor.targetRefreshRateHz = bestMode.refreshRate
            val lp = window.attributes
            if (lp.preferredDisplayModeId != bestMode.modeId) {
                lp.preferredDisplayModeId = bestMode.modeId
                window.attributes = lp
            }
        }
    }
}
