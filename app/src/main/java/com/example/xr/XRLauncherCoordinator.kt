package com.example.xr

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.widget.FrameLayout
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.xr.apps.AndroidAppManager
import com.example.xr.apps.LaunchableApp
import com.example.xr.apps.VoiceCommandAction
import com.example.xr.apps.VoiceCommandManager
import com.example.xr.companion.CompanionLinkStats
import com.example.xr.companion.CompanionNsdDiscovery
import com.example.xr.companion.DiscoveredPcHost
import com.example.xr.companion.PcCompanionClient
import com.example.xr.gl.GltfModelLoader
import com.example.xr.gl.StereoRenderingEngine
import com.example.xr.hand.HandFrameTransform
import com.example.xr.hand.HandTraceRecorder
import com.example.xr.hand.HandTrackingEngine
import com.example.xr.hand.HandTrackingTelemetry
import com.example.xr.input.InputInteractionManager
import com.example.xr.model.CinemaProjectionMode
import com.example.xr.model.HandednessPreference
import com.example.xr.model.ThermalPriority
import com.example.xr.model.XRSettings
import com.example.xr.perception.ARPerceptionEngine
import com.example.xr.perception.ArAvailabilityStatus
import com.example.xr.perception.ArPerceptionTelemetry
import com.example.xr.perception.CameraConsumerMode
import com.example.xr.perception.CameraEnumerator
import com.example.xr.perception.CameraLensDescriptor
import com.example.xr.perception.CameraModeCoordinator
import com.example.xr.perception.CameraOrientationDiagnostics
import com.example.xr.perception.CameraStatusSnapshot
import com.example.xr.perception.SharedCameraFrameSource
import com.example.xr.settings.XRSettingsManager
import com.example.xr.system.AdaptiveQualityTier
import com.example.xr.system.AudioSpatializerHelper
import com.example.xr.system.ComfortManager
import com.example.xr.system.ComfortState
import com.example.xr.system.DeviceDiagnostics
import com.example.xr.system.DeviceHardwareDiagnostics
import com.example.xr.system.PerformanceMonitor
import com.example.xr.system.PerformanceTelemetry
import com.example.xr.system.SpatialAudioStatus
import com.example.xr.tracking.HeadTrackingEngine
import com.example.xr.tracking.TrackingTelemetry
import com.example.xr.windows.DockActionId
import com.example.xr.windows.SpatialWindowManager
import com.example.xr.windows.VirtualContentHost
import com.example.xr.windows.WindowType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppMode {
    STEREO_VR,
    HUB_2D
}

enum class HubTab(val label: String) {
    DISPLAY("Display"),
    TRACKING("Tracking"),
    CAMERA_HANDS("Camera & Hands"),
    OPTICS("Optics"),
    PERFORMANCE("Performance")
}

data class CompanionConnectRequest(
    val host: String,
    val controlPort: Int,
    val udpPort: Int,
    val pairingCode: String,
    val allowNonPrivateOverride: Boolean
)

data class PendingDialogState(
    val showFirstUseWelcomeCard: Boolean = false,
    val showPassthroughSafetyModal: Boolean = false,
    val showHandWizardModal: Boolean = false,
    val pendingCompanionRequest: CompanionConnectRequest? = null,
    val pendingLaunchApp: LaunchableApp? = null,
    val statusToast: String? = null
)

class XRLauncherCoordinator(application: Application) : AndroidViewModel(application) {

    private val ctx: Context = application.applicationContext
    val settingsManager = XRSettingsManager(ctx)
    val headTracking = HeadTrackingEngine(ctx)
    val performanceMonitor = PerformanceMonitor(ctx)
    val comfortManager = ComfortManager()
    val appManager = AndroidAppManager(ctx)
    val handTracking = HandTrackingEngine(ctx)
    val voiceCommands = VoiceCommandManager(ctx)
    val cameraCoordinator = CameraModeCoordinator()
    val arPerception = ARPerceptionEngine(ctx)
    val pcCompanion = PcCompanionClient()
    val nsdDiscovery = CompanionNsdDiscovery(ctx)

    private val isLowRam = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    val windowManager = SpatialWindowManager(maxActiveContentWindows = if (isLowRam) 2 else 4)

    val offscreenContainer = FrameLayout(ctx).apply {
        // Rendered strictly off-screen via Surface.lockHardwareCanvas
        alpha = 0f
    }
    val contentHost = VirtualContentHost(ctx, offscreenContainer)
    val inputManager = InputInteractionManager(ctx, headTracking, windowManager, contentHost)

    val glRenderer = StereoRenderingEngine(
        headTracking = headTracking,
        windowManager = windowManager,
        contentHost = contentHost,
        performanceMonitor = performanceMonitor,
        onArFrameTick = { sixDof -> arPerception.updateFrameOnGlThread(sixDof) }
    )

    private var sharedCameraSource: SharedCameraFrameSource? = null
    private var lastLifecycleOwner: LifecycleOwner? = null
    @Volatile
    private var currentDisplayRotation: Int = Surface.ROTATION_90
    @Volatile
    private var glViewportWidth: Int = 1280
    @Volatile
    private var glViewportHeight: Int = 720

    private val _appMode = MutableStateFlow(XrEnvironment.defaultAppMode(ctx))
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    private val _selectedHubTab = MutableStateFlow(HubTab.DISPLAY)
    val selectedHubTab: StateFlow<HubTab> = _selectedHubTab.asStateFlow()

    private val _settings = MutableStateFlow(XRSettings())
    val settings: StateFlow<XRSettings> = _settings.asStateFlow()

    private val _dialogs = MutableStateFlow(PendingDialogState(showFirstUseWelcomeCard = true))
    val dialogs: StateFlow<PendingDialogState> = _dialogs.asStateFlow()

    private val _galleryUris = MutableStateFlow<List<Uri>>(emptyList())
    val galleryUris: StateFlow<List<Uri>> = _galleryUris.asStateFlow()

    val trackingTelemetry: StateFlow<TrackingTelemetry> = headTracking.telemetry
    val performanceTelemetry: StateFlow<PerformanceTelemetry> = performanceMonitor.telemetry
    val comfortState: StateFlow<ComfortState> = comfortManager.state
    val handTelemetry: StateFlow<HandTrackingTelemetry> = handTracking.telemetry
    val arTelemetry: StateFlow<ArPerceptionTelemetry> = arPerception.telemetry
    val cameraStatus: StateFlow<CameraStatusSnapshot> = cameraCoordinator.status
    val installedApps: StateFlow<List<LaunchableApp>> = appManager.apps
    val pcLinkStats: StateFlow<CompanionLinkStats> = pcCompanion.stats
    val discoveredPcs: StateFlow<List<DiscoveredPcHost>> = nsdDiscovery.hosts
    private val _deviceHardware = MutableStateFlow(DeviceDiagnostics.collect(ctx))
    val deviceHardware: StateFlow<DeviceHardwareDiagnostics> = _deviceHardware.asStateFlow()
    private val _availableCameras = MutableStateFlow(_deviceHardware.value.rearCameras)
    val availableCameras: StateFlow<List<CameraLensDescriptor>> = _availableCameras.asStateFlow()
    private val _spatialAudioStatus = MutableStateFlow(AudioSpatializerHelper.query(ctx))
    val spatialAudioStatus: StateFlow<SpatialAudioStatus> = _spatialAudioStatus.asStateFlow()

    var onRequestCameraPermission: ((onGranted: () -> Unit) -> Unit)? = null
    var onRequestMicPermission: ((onGranted: () -> Unit) -> Unit)? = null
    var onRequestPhotoPicker: (() -> Unit)? = null
    var onRequestVideoPicker: (() -> Unit)? = null
    var onRequestGltfPicker: (() -> Unit)? = null

    init {
        glRenderer.displayDensity = ctx.resources.displayMetrics.density
        wireCallbacks()
        viewModelScope.launch {
            settingsManager.settingsFlow.collect { next ->
                _settings.value = next
                applySettingsToEngines(next)
            }
        }
        viewModelScope.launch {
            appManager.refreshInstalledApps()
        }
        viewModelScope.launch {
            var lastThermalMaxFps = -1
            var tickCounter = 0
            while (isActive) {
                tickCounter++
                val cur = _settings.value
                val perf = performanceMonitor.telemetry.value
                val tier = perf.qualityTier

                if (cur.handControlEnabled) {
                    val effectiveHandFps = if (tier == AdaptiveQualityTier.THERMAL_SAVER &&
                        cur.arCoreEnabled && cur.thermalPriority == ThermalPriority.ARCORE_PRIORITY
                    ) 0 else tier.handMaxFps
                    if (effectiveHandFps != lastThermalMaxFps) {
                        lastThermalMaxFps = effectiveHandFps
                        val allowTwo = effectiveHandFps >= 25 && !cur.oneHandedMode
                        handTracking.updateThermalTier(effectiveHandFps, allowTwo)
                    }
                    arPerception.setHeavyPerceptionEnabled(
                        !(tier == AdaptiveQualityTier.THERMAL_SAVER && cur.thermalPriority == ThermalPriority.HANDS_PRIORITY)
                    )
                }

                val handLabOpen = windowManager.windows.value.any { !it.minimized && it.type == WindowType.HAND_LAB }
                handTracking.debugPreviewActive = handLabOpen ||
                    (_appMode.value == AppMode.HUB_2D && _selectedHubTab.value == HubTab.CAMERA_HANDS)

                performanceMonitor.handInferenceLatencyMs = handTracking.telemetry.value.inferenceLatencyMs

                if (tickCounter % 8 == 0) {
                    refreshDeviceHardware()
                }

                val camActive = cur.passthroughEnabled || cur.handControlEnabled || cur.arCoreEnabled
                comfortManager.tick(
                    breakIntervalMinutes = cur.sessionBreakMinutes,
                    linearAccelG = trackingTelemetry.value.linearAccelerationG,
                    cameraActive = camActive
                )
                contentHost.updateSnapshots(
                    settings = cur,
                    apps = appManager.apps.value,
                    perf = perf,
                    tracking = headTracking.telemetry.value,
                    hand = handTracking.telemetry.value,
                    ar = arPerception.telemetry.value,
                    galleryUris = _galleryUris.value,
                    device = _deviceHardware.value,
                    pcLink = pcCompanion.stats.value,
                    debugBitmap = handTracking.getLatestDebugBitmap(),
                    debugHands = handTracking.getLatestHands()
                )
                _spatialAudioStatus.value = AudioSpatializerHelper.query(ctx)
                delay(250L)
            }
        }
    }

    private fun wireCallbacks() {
        glRenderer.onFrameInteractionTick = { pose ->
            val liveHands = handTracking.getLatestHands()
            glRenderer.latestHands = liveHands
            inputManager.latestHands = liveHands
            inputManager.handsVisible = liveHands.isNotEmpty() || handTracking.telemetry.value.handsVisible
            inputManager.onFrameTick(pose)
            glRenderer.currentRaycast = inputManager.currentHit
            glRenderer.currentRay = inputManager.currentRay
            glRenderer.dwellProgress = inputManager.dwellProgress
            glRenderer.palmHoldProgress = inputManager.palmHoldProgress
            glRenderer.lastPokeWorldPoint = inputManager.lastPokeWorldPoint
        }

        glRenderer.onPassthroughSurfaceReady = { surfaceTexture, texId ->
            sharedCameraSource?.passthroughTexture = surfaceTexture
            arPerception.setCameraTextureId(texId)
        }

        glRenderer.onSurfaceDimensionsChanged = { width, height ->
            glViewportWidth = width
            glViewportHeight = height
            arPerception.setDisplayGeometry(currentDisplayRotation, width, height)
        }

        glRenderer.onDesktopStreamSurfaceReady = { surfaceTexture, width, height ->
            glRenderer.pcStreamWidthPx = width
            glRenderer.pcStreamHeightPx = height
            pcCompanion.attachDesktopStreamSurface(Surface(surfaceTexture), width, height)
        }

        inputManager.onPeekToggle = { togglePeekPassthrough() }
        inputManager.onPcRemoteMouse = { action, u, v, button, scroll ->
            pcCompanion.sendRemoteMouse(action, u, v, button, scroll)
        }
        inputManager.onPcRemoteKey = { keyCode, unicode, action ->
            pcCompanion.sendRemoteKey(keyCode, unicode, action)
        }

        handTracking.onGestureEvents = { events ->
            inputManager.handleHandGestureEvents(events)
        }
        handTracking.onCompanionCameraBitmap = { bmp, tsNs ->
            pcCompanion.submitCameraFrame(bmp, tsNs)
        }

        pcCompanion.onRemoteHandsReceived = { hands, _, e2eMs ->
            performanceMonitor.handInferenceLatencyMs = e2eMs
            handTracking.injectDiagnosticHands(hands, SystemClock.uptimeMillis())
        }
        pcCompanion.onFallbackTriggered = { reason ->
            handTracking.offloadHandsToPc = false
            updateSettings { it.copy(handControlEnabled = true) }
            postStatusToast("PC Companion auto-fallback: $reason")
        }

        arPerception.onArCameraFrameForHands = { bmp, lum ->
            val baseRot = HandFrameTransform.computeBaseCameraUprightRotation(
                sensorOrientationDeg = arPerception.activeSensorOrientationDeg,
                lensFacingBack = true,
                displayRotation = currentDisplayRotation
            )
            handTracking.submitArgbBitmap(bmp, lum, baseRot)
        }

        arPerception.onSixDofPose = { orient, pos ->
            headTracking.updateArCoreSixDofPose(orient, pos)
        }

        inputManager.onDockAction = { action ->
            handleDockAction(action)
        }

        contentHost.onLaunchAppRequested = { app ->
            _dialogs.value = _dialogs.value.copy(pendingLaunchApp = app)
        }

        contentHost.onOpenKeyboardRequested = { initial, _ ->
            windowManager.openOrFocusWindow(WindowType.SPATIAL_KEYBOARD)
            if (initial.isNotBlank()) {
                postStatusToast("Spatial keyboard ready")
            }
        }

        contentHost.onPickPhotoRequested = { onRequestPhotoPicker?.invoke() }
        contentHost.onPickVideoRequested = { onRequestVideoPicker?.invoke() }
        contentHost.onPickGltfRequested = { onRequestGltfPicker?.invoke() }
        contentHost.onGltfPresetSelected = { idx ->
            glRenderer.setGltfMesh(GltfModelLoader.loadPreset(idx))
        }
        contentHost.onMediaVideoSizeChanged = { width, height ->
            if (width > 0 && height > 0) {
                glRenderer.mediaVideoWidthPx = width
                glRenderer.mediaVideoHeightPx = height
            }
        }
        contentHost.onSettingsMutated = { transform -> updateSettings(transform) }
        contentHost.onActionCommand = { cmd ->
            when (cmd) {
                "recenter" -> headTracking.recenter()
                "recenter_windows" -> windowManager.recenterWindowsToFront()
                "toggle_passthrough" -> requestPassthroughToggle(!_settings.value.passthroughEnabled)
                "toggle_peek" -> togglePeekPassthrough()
                "toggle_hands" -> toggleHandsImmediate(!_settings.value.handControlEnabled)
                "toggle_arcore" -> requestArCoreToggle(!_settings.value.arCoreEnabled)
                "toggle_cinema" -> updateSettings { it.copy(cinemaModeEnabled = !it.cinemaModeEnabled) }
                "cycle_cinema_proj" -> updateSettings { s ->
                    val nextProj = when (s.cinemaProjectionMode) {
                        CinemaProjectionMode.MONO_2D -> CinemaProjectionMode.SBS_3D
                        CinemaProjectionMode.SBS_3D -> CinemaProjectionMode.VR_180
                        CinemaProjectionMode.VR_180 -> CinemaProjectionMode.VR_360
                        CinemaProjectionMode.VR_360 -> CinemaProjectionMode.MONO_2D
                    }
                    s.copy(cinemaProjectionMode = nextProj)
                }
                "media_play_pause" -> contentHost.toggleMediaPlayback()
                "toggle_calibration_grid" -> updateSettings { it.copy(showCalibrationGrid = !it.showCalibrationGrid) }
                "rotate_camera_90" -> updateSettings { it.copy(cameraRotationDegrees = (it.cameraRotationDegrees + 90) % 360) }
                "open_browser" -> windowManager.openOrFocusWindow(WindowType.BROWSER)
                "open_media" -> windowManager.openOrFocusWindow(WindowType.MEDIA_PLAYER)
                "open_gallery" -> windowManager.openOrFocusWindow(WindowType.GALLERY)
                "open_gltf" -> windowManager.openOrFocusWindow(WindowType.GLTF_VIEWER)
                "open_settings" -> windowManager.openOrFocusWindow(WindowType.SETTINGS)
                "open_hand_lab" -> windowManager.openOrFocusWindow(WindowType.HAND_LAB)
                "open_pc_desktop" -> windowManager.openOrFocusWindow(WindowType.PC_DESKTOP)
                "toggle_trace_capture" -> toggleTraceRecording()
                "rebenchmark_hand_delegate" -> handTracking.rerunDelegateBenchmark()
                "open_2d_hand_lab" -> switchAppMode(AppMode.HUB_2D, HubTab.CAMERA_HANDS)
                "voice_dictate" -> requestVoiceListen()
                "open_2d_hub" -> switchAppMode(AppMode.HUB_2D)
                "close_keyboard" -> {
                    windowManager.windows.value
                        .firstOrNull { it.type == WindowType.SPATIAL_KEYBOARD }
                        ?.let { windowManager.closeWindow(it.id) }
                }
            }
        }

        voiceCommands.onAction = { action ->
            when (action) {
                VoiceCommandAction.OpenBrowser -> windowManager.openOrFocusWindow(WindowType.BROWSER)
                VoiceCommandAction.OpenMedia -> windowManager.openOrFocusWindow(WindowType.MEDIA_PLAYER)
                VoiceCommandAction.OpenApps -> windowManager.openOrFocusWindow(WindowType.APP_LIBRARY)
                VoiceCommandAction.OpenGallery -> windowManager.openOrFocusWindow(WindowType.GALLERY)
                VoiceCommandAction.OpenSettings -> windowManager.openOrFocusWindow(WindowType.SETTINGS)
                VoiceCommandAction.Recenter -> headTracking.recenter()
                VoiceCommandAction.TogglePassthrough -> requestPassthroughToggle(!_settings.value.passthroughEnabled)
                VoiceCommandAction.HandsOff -> toggleHandsImmediate(false)
                VoiceCommandAction.CloseFocusedWindow -> {
                    windowManager.focusedWindowId.value?.let { windowManager.closeWindow(it) }
                }
            }
        }
    }

    fun attachLifecycleOwner(owner: LifecycleOwner) {
        if (lastLifecycleOwner === owner && sharedCameraSource != null) return
        lastLifecycleOwner = owner
        sharedCameraSource = SharedCameraFrameSource(
            context = ctx,
            lifecycleOwner = owner,
            handTrackingEngine = handTracking,
            onPassthroughLatencyMs = { ms -> performanceMonitor.passthroughLatencyMs = ms }
        ).also { src ->
            src.onOrientationDiagnosticsChanged = { diag ->
                pushCameraTransformToRenderer(diag)
            }
        }
    }

    fun onDisplayRotationChanged(displayRotation: Int) {
        currentDisplayRotation = displayRotation
        headTracking.setDisplayRotation(displayRotation)
        handTracking.updateDisplayRotation(displayRotation)
        sharedCameraSource?.updateTargetRotation(displayRotation)
        arPerception.setDisplayGeometry(displayRotation, glViewportWidth, glViewportHeight)
        pushCameraTransformToRenderer(sharedCameraSource?.currentDiagnostics())
    }

    private fun pushCameraTransformToRenderer(diag: CameraOrientationDiagnostics?) {
        val s = _settings.value
        val d = diag ?: CameraOrientationDiagnostics(displayRotation = currentDisplayRotation)
        val hasTransform = if (s.arCoreEnabled) true else d.hasCameraTransform
        val sensorOrient = if (s.arCoreEnabled) arPerception.activeSensorOrientationDeg else d.sensorOrientationDeg
        glRenderer.updateCameraFeedOrientation(
            sensorOrientationDeg = sensorOrient,
            lensFacingBack = d.lensFacingBack,
            displayRotation = currentDisplayRotation,
            reportedBaseRotationDeg = d.reportedPreviewRotationDeg,
            hasCameraTransform = hasTransform,
            streamMirroring = d.streamMirroring,
            srcWidthPx = d.previewWidth,
            srcHeightPx = d.previewHeight
        )
    }

    fun onActivityStarted() {
        // On XR headsets the platform owns head pose. Running the phone sensor fusion
        // here would double-apply rotation to the panel, so it stays off there.
        if (XrEnvironment.supportsPhoneVrStereo(ctx)) {
            headTracking.start(_settings.value.trackingSourceMode)
        }
        contentHost.start()
        syncCameraPipeline()
    }

    fun onActivityStopped() {
        contentHost.stop()
        headTracking.stop()
        handTracking.stop()
        viewModelScope.launch { cameraCoordinator.releaseAll() }
    }

    fun updateSettings(transform: (XRSettings) -> XRSettings) {
        viewModelScope.launch { settingsManager.update(transform) }
    }

    fun switchAppMode(mode: AppMode, tab: HubTab? = null) {
        // The stereo viewer renders a side-by-side pair for a phone inside a
        // passive holder. On a headset the platform already renders each eye, so
        // that mode would produce a doubled, unusable image.
        if (mode == AppMode.STEREO_VR && !XrEnvironment.supportsPhoneVrStereo(ctx)) {
            postStatusToast("Stereo viewer mode is for phone VR holders — this headset runs the spatial hub.")
            return
        }
        _appMode.value = mode
        if (tab != null) _selectedHubTab.value = tab
        if (mode == AppMode.STEREO_VR) {
            _dialogs.value = _dialogs.value.copy(showFirstUseWelcomeCard = false)
        } else if (_selectedHubTab.value == HubTab.TRACKING) {
            arPerception.checkAvailability()
        }
    }

    fun dismissWelcomeCard(goToHub: Boolean) {
        _dialogs.value = _dialogs.value.copy(showFirstUseWelcomeCard = false)
        updateSettings { it.copy(hasCompletedWelcome = true) }
        if (goToHub) {
            switchAppMode(AppMode.HUB_2D)
        }
    }

    fun selectHubTab(tab: HubTab) {
        _selectedHubTab.value = tab
        if (tab == HubTab.TRACKING || tab == HubTab.PERFORMANCE) {
            arPerception.checkAvailability()
        }
    }

    fun requestPassthroughToggle(enable: Boolean) {
        if (!enable) {
            updateSettings { it.copy(passthroughEnabled = false) }
            return
        }
        _dialogs.value = _dialogs.value.copy(showPassthroughSafetyModal = true)
    }

    fun confirmPassthroughSafetyModal() {
        _dialogs.value = _dialogs.value.copy(showPassthroughSafetyModal = false)
        val request = onRequestCameraPermission
        if (request == null) {
            postStatusToast("Camera permission launcher unavailable")
            return
        }
        request {
            updateSettings {
                it.copy(
                    passthroughEnabled = true,
                    immersionLevel = if (it.immersionLevel > 0.85f) 0.15f else it.immersionLevel
                )
            }
        }
    }

    fun togglePeekPassthrough() {
        val s = _settings.value
        if (!s.passthroughEnabled) {
            requestPassthroughToggle(true)
        } else if (s.immersionLevel > 0.5f) {
            updateSettings { it.copy(immersionLevel = 0.0f) }
        } else {
            updateSettings { it.copy(immersionLevel = 1.0f, passthroughEnabled = false) }
        }
    }

    fun dismissPassthroughSafetyModal() {
        _dialogs.value = _dialogs.value.copy(showPassthroughSafetyModal = false)
        updateSettings { it.copy(passthroughEnabled = false) }
    }

    fun openHandSetupWizard() {
        _dialogs.value = _dialogs.value.copy(showHandWizardModal = true)
    }

    fun closeHandSetupWizard() {
        _dialogs.value = _dialogs.value.copy(showHandWizardModal = false)
    }

    fun enableHandsAfterWizard(palmWidthM: Float, closeThresh: Float, openThresh: Float) {
        _dialogs.value = _dialogs.value.copy(showHandWizardModal = false)
        val request = onRequestCameraPermission ?: return
        request {
            updateSettings {
                it.copy(
                    handControlEnabled = true,
                    handCalibration = it.handCalibration.copy(
                        palmWidthMeters = palmWidthM,
                        pinchClosedThreshold = closeThresh,
                        pinchOpenThreshold = openThresh,
                        calibrated = true
                    )
                )
            }
        }
    }

    fun toggleHandsImmediate(enable: Boolean) {
        if (!enable) {
            updateSettings { it.copy(handControlEnabled = false) }
            handTracking.stop()
            postStatusToast("Hand control turned off — gaze dwell & gamepad active")
        } else {
            openHandSetupWizard()
        }
    }

    fun requestArCoreToggle(enable: Boolean, activity: Activity? = null) {
        if (!enable) {
            updateSettings { it.copy(arCoreEnabled = false) }
            return
        }
        val avail = arPerception.checkAvailability()
        if (avail == ArAvailabilityStatus.UNSUPPORTED) {
            postStatusToast("ARCore unsupported on this device — VR mode only")
            return
        }
        if (avail == ArAvailabilityStatus.SUPPORTED_NEEDS_INSTALL && activity != null) {
            arPerception.requestArCoreInstall(activity)
            return
        }
        onRequestCameraPermission?.invoke {
            updateSettings { it.copy(arCoreEnabled = true) }
        }
    }

    fun requestVoiceListen() {
        if (!_settings.value.voiceCommandsEnabled) {
            postStatusToast("Enable Opt-In Voice Commands in Hub first")
            return
        }
        onRequestMicPermission?.invoke {
            voiceCommands.startListeningOnce()
        }
    }

    fun confirmPendingAppLaunch() {
        val app = _dialogs.value.pendingLaunchApp ?: return
        _dialogs.value = _dialogs.value.copy(pendingLaunchApp = null)
        val launched = appManager.launchApp(app, _settings.value.returnNotificationEnabled)
        if (!launched) {
            postStatusToast("Unable to launch ${app.label}")
        }
    }

    fun cancelPendingAppLaunch() {
        _dialogs.value = _dialogs.value.copy(pendingLaunchApp = null)
    }

    fun addGalleryPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _galleryUris.value = (uris + _galleryUris.value).distinct().take(12)
        windowManager.openOrFocusWindow(WindowType.GALLERY)
    }

    fun loadPickedVideo(uri: Uri) {
        windowManager.openOrFocusWindow(WindowType.MEDIA_PLAYER)
        contentHost.playMediaUri(uri, "Local Video")
    }

    fun loadPickedGltf(uri: Uri) {
        viewModelScope.launch {
            val mesh = GltfModelLoader.loadFromUri(ctx, uri)
            if (mesh != null) {
                glRenderer.setGltfMesh(mesh)
                windowManager.openOrFocusWindow(WindowType.GLTF_VIEWER)
                postStatusToast("Loaded ${mesh.name}")
            } else {
                postStatusToast("Could not parse .glb/.gltf file")
            }
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean = inputManager.handleKeyEvent(event)
    fun handleGenericMotionEvent(event: MotionEvent): Boolean = inputManager.handleGenericMotionEvent(event)

    fun postStatusToast(msg: String?) {
        _dialogs.value = _dialogs.value.copy(statusToast = msg)
    }

    private fun handleDockAction(action: DockActionId) {
        when (action) {
            DockActionId.HOME -> windowManager.openDefaultWorkspace()
            DockActionId.APPS -> windowManager.openOrFocusWindow(WindowType.APP_LIBRARY)
            DockActionId.BROWSER -> windowManager.openOrFocusWindow(WindowType.BROWSER)
            DockActionId.MEDIA -> windowManager.openOrFocusWindow(WindowType.MEDIA_PLAYER)
            DockActionId.GALLERY -> windowManager.openOrFocusWindow(WindowType.GALLERY)
            DockActionId.GLTF -> windowManager.openOrFocusWindow(WindowType.GLTF_VIEWER)
            DockActionId.AR_PASSTHROUGH -> requestPassthroughToggle(!_settings.value.passthroughEnabled)
            DockActionId.ENVIRONMENTS,
            DockActionId.SETTINGS -> windowManager.openOrFocusWindow(WindowType.SETTINGS)
            DockActionId.CONTROL_CENTER -> windowManager.openOrFocusWindow(WindowType.CONTROL_CENTER)
            DockActionId.HANDS_OFF -> toggleHandsImmediate(!_settings.value.handControlEnabled)
        }
    }

    fun refreshDeviceHardware() {
        val perf = performanceMonitor.telemetry.value
        _deviceHardware.value = DeviceDiagnostics.collect(
            context = ctx,
            glRenderer = perf.glRendererString,
            glVendor = perf.glVendorString,
            glVersion = perf.glVersionString,
            activeRefreshHz = perf.activeRefreshRateHz
        )
        _availableCameras.value = _deviceHardware.value.rearCameras
    }

    fun toggleTraceRecording() {
        if (handTracking.traceRecorder.isRecording()) {
            val savedPath = handTracking.stopTraceCaptureAndSave()
            postStatusToast(if (savedPath != null) "Saved landmarks trace to app storage" else "No frames captured")
        } else {
            handTracking.startTraceCapture()
            postStatusToast("Recording landmarks-only trace (.jsonl) — tap again to save")
        }
    }

    fun replayLastTrace() {
        val path = handTracking.telemetry.value.lastTraceFilePath ?: return
        viewModelScope.launch {
            val file = java.io.File(path)
            if (!file.exists()) {
                postStatusToast("Trace file not found")
                return@launch
            }
            val parsed = HandTraceRecorder.parseTraceJsonl(file.readText())
            for (frame in parsed) {
                handTracking.injectDiagnosticHands(frame.hands, frame.timestampMs)
                delay(33L)
            }
            postStatusToast("Replayed ${parsed.size} trace frames (synthetic replay)")
        }
    }

    fun requestCompanionConnect(
        host: String,
        controlPort: Int,
        udpPort: Int,
        pairingCode: String,
        allowNonPrivateOverride: Boolean
    ) {
        _dialogs.value = _dialogs.value.copy(
            pendingCompanionRequest = CompanionConnectRequest(
                host = host,
                controlPort = controlPort,
                udpPort = udpPort,
                pairingCode = pairingCode,
                allowNonPrivateOverride = allowNonPrivateOverride
            )
        )
    }

    fun dismissCompanionConsent() {
        _dialogs.value = _dialogs.value.copy(pendingCompanionRequest = null)
    }

    fun confirmCompanionConsent() {
        val req = _dialogs.value.pendingCompanionRequest ?: return
        _dialogs.value = _dialogs.value.copy(pendingCompanionRequest = null)
        val connectBlock: () -> Unit = {
            pcCompanion.connect(
                host = req.host,
                controlPort = req.controlPort,
                udpPort = req.udpPort,
                pairingCode = req.pairingCode,
                allowNonPrivateOverride = req.allowNonPrivateOverride,
                consentAccepted = true
            ) { ok, msg ->
                handTracking.offloadHandsToPc = ok
                if (ok) {
                    updateSettings { it.copy(handControlEnabled = true) }
                } else {
                    postStatusToast("PC Companion: $msg")
                }
            }
        }
        onRequestCameraPermission?.invoke(connectBlock) ?: connectBlock()
    }

    fun disconnectCompanion() {
        handTracking.offloadHandsToPc = false
        pcCompanion.disconnect(triggerFallback = false)
    }

    private fun applySettingsToEngines(s: XRSettings) {
        glRenderer.currentSettings = s
        glRenderer.passthroughActive = s.passthroughEnabled
        glRenderer.arCoreActive = s.arCoreEnabled
        inputManager.latestSettings = s
        headTracking.setPredictionMs(s.predictionMs)
        headTracking.setTrackingMode(s.trackingSourceMode)
        performanceMonitor.lowPowerMode = s.lowPowerMode
        performanceMonitor.prefer90HzWhenSafe = s.prefer90HzWhenThermallySafe
        handTracking.userRotationDegrees = s.cameraRotationDegrees
        handTracking.mirrorHorizontal = s.cameraMirrorHorizontal
        handTracking.minConfidence = s.handMinConfidence
        handTracking.updateDisplayRotation(currentDisplayRotation)
        pushCameraTransformToRenderer(sharedCameraSource?.currentDiagnostics())
        handTracking.gestureRecognizer.calibration = s.handCalibration
        handTracking.gestureRecognizer.handedness = if (s.oneHandedMode && s.handedness == HandednessPreference.BOTH) {
            HandednessPreference.RIGHT
        } else {
            s.handedness
        }
        handTracking.gestureRecognizer.relaxedSensitivity = s.relaxedPinchSensitivity
        handTracking.gestureRecognizer.gestureSizeGain = s.gestureSizeGain
        arPerception.forwardFramesToHands = s.arCoreEnabled && s.handControlEnabled
        syncCameraPipeline()
    }

    private fun syncCameraPipeline() {
        val s = _settings.value
        val tier = performanceMonitor.telemetry.value.qualityTier
        val wantsArCore = s.arCoreEnabled && tier.allowPassthrough
        val wantsCameraX = !wantsArCore && (s.passthroughEnabled || s.handControlEnabled)

        if (s.handControlEnabled && tier.handMaxFps > 0) {
            handTracking.start(targetFps = tier.handMaxFps, allowTwoHands = tier.handMaxFps >= 25 && !s.oneHandedMode)
        } else {
            handTracking.stop()
        }

        val targetMode = when {
            wantsArCore -> CameraConsumerMode.ARCORE_MODE
            wantsCameraX -> CameraConsumerMode.CAMERAX_SHARED
            else -> CameraConsumerMode.OFF
        }

        val camChanged = sharedCameraSource?.preferredCameraId != s.preferredCameraId
        sharedCameraSource?.apply {
            enablePreviewFeed = s.passthroughEnabled
            enableHandAnalysis = s.handControlEnabled
            preferredCameraId = s.preferredCameraId
        }

        if (camChanged && targetMode == CameraConsumerMode.CAMERAX_SHARED) {
            viewModelScope.launch {
                cameraCoordinator.releaseAll()
                cameraCoordinator.requestModeSwitch(targetMode) { sharedCameraSource }
            }
        } else {
            cameraCoordinator.requestModeSwitch(targetMode) { mode ->
                when (mode) {
                    CameraConsumerMode.CAMERAX_SHARED -> sharedCameraSource
                    CameraConsumerMode.ARCORE_MODE -> arPerception
                    CameraConsumerMode.OFF -> null
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        nsdDiscovery.stopDiscovery()
        pcCompanion.disconnect(triggerFallback = false)
        contentHost.releaseAll()
        headTracking.stop()
        handTracking.stop()
        voiceCommands.release()
        performanceMonitor.release()
        viewModelScope.launch { cameraCoordinator.releaseAll() }
    }
}
