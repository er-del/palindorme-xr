@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.xr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CanvasObsidian
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.IndigoSecondary
import com.example.ui.theme.RoseError
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.SkyPrimaryDim
import com.example.ui.theme.SpatialGlass
import com.example.ui.theme.SurfaceObsidian
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.xr.AppMode
import com.example.xr.HubTab
import com.example.xr.XRLauncherCoordinator
import com.example.xr.companion.CompanionLinkStats
import com.example.xr.companion.DiscoveredPcHost
import com.example.xr.hand.HandTrackingTelemetry
import com.example.xr.model.CinemaProjectionMode
import com.example.xr.model.HandednessPreference
import com.example.xr.model.OpticsPreset
import com.example.xr.model.OpticsProfile
import com.example.xr.model.ThermalPriority
import com.example.xr.model.TrackingSourceMode
import com.example.xr.model.VirtualEnvironmentType
import com.example.xr.model.XRSettings
import com.example.xr.perception.ArPerceptionTelemetry
import com.example.xr.perception.CameraLensDescriptor
import com.example.xr.perception.CameraStatusSnapshot
import com.example.xr.system.DeviceDiagnostics
import com.example.xr.system.DeviceHardwareDiagnostics
import com.example.xr.system.PerformanceTelemetry
import com.example.xr.tracking.TrackingTelemetry
import com.example.xr.windows.WindowType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Hub2DScreen(
    coordinator: XRLauncherCoordinator,
    settings: XRSettings,
    selectedTab: HubTab,
    perf: PerformanceTelemetry,
    tracking: TrackingTelemetry,
    handTelemetry: HandTrackingTelemetry,
    arTelemetry: ArPerceptionTelemetry,
    cameraStatus: CameraStatusSnapshot,
    onRequestArToggle: (Boolean) -> Unit
) {
    BackHandler {
        coordinator.switchAppMode(AppMode.STEREO_VR)
    }

    val spatialAudio by coordinator.spatialAudioStatus.collectAsStateWithLifecycle()
    val deviceHardware by coordinator.deviceHardware.collectAsStateWithLifecycle()
    val availableCameras by coordinator.availableCameras.collectAsStateWithLifecycle()
    val pcLinkStats by coordinator.pcLinkStats.collectAsStateWithLifecycle()
    val discoveredPcs by coordinator.discoveredPcs.collectAsStateWithLifecycle()

    val tabIcons = mapOf(
        HubTab.DISPLAY to Icons.Rounded.DisplaySettings,
        HubTab.TRACKING to Icons.Rounded.Explore,
        HubTab.CAMERA_HANDS to Icons.Rounded.CameraAlt,
        HubTab.OPTICS to Icons.Rounded.RemoveRedEye,
        HubTab.PERFORMANCE to Icons.Rounded.Speed
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(CanvasObsidian, SurfaceObsidian, CanvasObsidian)
                )
            )
    ) {
        val useRail = maxWidth >= 600.dp || maxWidth > maxHeight

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasObsidian.copy(alpha = 0.85f)),
                    navigationIcon = {
                        IconButton(
                            onClick = { coordinator.switchAppMode(AppMode.STEREO_VR) },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("hub_back_to_stereo")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Return to Stereo VR", tint = SkyPrimary)
                        }
                    },
                    title = {
                        Column {
                            Text("PALINDORME XR Hub", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                            Text("2D Configuration & Optics Calibration", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    },
                    actions = {
                        Button(
                            onClick = { coordinator.switchAppMode(AppMode.STEREO_VR) },
                            colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("enter_stereo_vr_button")
                        ) {
                            Icon(Icons.Rounded.ViewInAr, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Enter VR")
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = SurfaceObsidian,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        HubTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == selectedTab,
                                onClick = { coordinator.selectHubTab(tab) },
                                icon = { Icon(tabIcons.getValue(tab), contentDescription = tab.label) },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = SurfaceObsidian,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        HubTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = tab == selectedTab,
                                onClick = { coordinator.selectHubTab(tab) },
                                icon = { Icon(tabIcons.getValue(tab), contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("rail_${tab.name.lowercase()}")
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
                    ) {
                        item {
                            LiveStereoPreviewBanner(settings = settings, perf = perf)
                        }
                        when (selectedTab) {
                            HubTab.DISPLAY -> {
                                item { DisplayTabContent(coordinator, settings, spatialAudio) }
                            }
                            HubTab.TRACKING -> {
                                item { TrackingTabContent(coordinator, settings, tracking, arTelemetry, onRequestArToggle) }
                            }
                            HubTab.CAMERA_HANDS -> {
                                item {
                                    CameraHandsTabContent(
                                        coordinator = coordinator,
                                        settings = settings,
                                        handTelemetry = handTelemetry,
                                        cameraStatus = cameraStatus,
                                        availableCameras = availableCameras,
                                        pcLinkStats = pcLinkStats,
                                        discoveredPcs = discoveredPcs
                                    )
                                }
                            }
                            HubTab.OPTICS -> {
                                item { OpticsTabContent(coordinator, settings) }
                            }
                            HubTab.PERFORMANCE -> {
                                item { PerformanceTabContent(coordinator, settings, perf, handTelemetry, arTelemetry, deviceHardware) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStereoPreviewBanner(settings: XRSettings, perf: PerformanceTelemetry) {
    val envTint = Color(settings.environment.tintHex.toInt())
    GlassCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Live Dual-Viewport Preview", style = MaterialTheme.typography.titleMedium, color = SkyPrimary)
            Text(
                "${"%.0f".format(perf.fps)} FPS · Cam ${settings.cameraRotationDegrees}° · ${settings.optics.preset.displayName}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when {
                        settings.passthroughEnabled -> Color(0xFF0E1B2E)
                        settings.environment.type.isOledBlack -> Color.Black
                        else -> CanvasObsidian
                    }
                )
                .border(1.dp, SkyPrimaryDim, RoundedCornerShape(14.dp))
                .testTag("live_stereo_preview_canvas")
        ) {
            val eyeW = size.width * 0.5f
            val eyeH = size.height
            val ipdShift = ((settings.optics.ipdMm - 63f) / 15f) * 14f
            val kScale = (1f + settings.optics.k1 * 0.45f).coerceIn(0.7f, 1.1f)

            // Center divider
            drawLine(SkyPrimaryDim, Offset(eyeW, 0f), Offset(eyeW, eyeH), strokeWidth = 2f)

            for (eye in 0..1) {
                val cx = eyeW * eye + eyeW * 0.5f + (if (eye == 0) ipdShift else -ipdShift)
                val cy = eyeH * 0.5f + (if (eye == 0) settings.optics.leftEyeVerticalOffset else settings.optics.rightEyeVerticalOffset) * 80f
                val rx = eyeW * 0.36f * kScale
                val ry = eyeH * 0.36f * kScale

                if (settings.showCalibrationGrid) {
                    drawLine(SkyPrimary.copy(alpha = 0.55f), Offset(cx - rx, cy), Offset(cx + rx, cy), strokeWidth = 1.5f)
                    drawLine(SkyPrimary.copy(alpha = 0.55f), Offset(cx, cy - ry), Offset(cx, cy + ry), strokeWidth = 1.5f)
                }

                // Lens circle
                drawCircle(
                    color = envTint.copy(alpha = 0.25f),
                    radius = ry * 1.12f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f)
                )
                // Translucent frosted glass window card preview inside each eye
                drawRect(
                    color = SkyPrimary.copy(alpha = 0.14f),
                    topLeft = Offset(cx - rx * 0.65f, cy - ry * 0.55f),
                    size = Size(rx * 1.3f, ry * 1.1f)
                )
                drawRect(
                    color = SkyPrimary.copy(alpha = 0.85f),
                    topLeft = Offset(cx - rx * 0.65f, cy - ry * 0.55f),
                    size = Size(rx * 1.3f, ry * 1.1f),
                    style = Stroke(width = 2f)
                )
            }
        }
    }
}

@Composable
private fun DisplayTabContent(
    coordinator: XRLauncherCoordinator,
    settings: XRSettings,
    spatialAudio: com.example.xr.system.SpatialAudioStatus
) {
    GlassCard {
        SectionHeader("Procedural Virtual Environments (6 GPU-Light Presets)")
        Spacer(Modifier.height(8.dp))
        VirtualEnvironmentType.entries.forEach { env ->
            val selected = env == settings.environment.type
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) SkyPrimary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable {
                        coordinator.updateSettings {
                            it.copy(environment = it.environment.copy(type = env, tintHex = env.defaultTintHex))
                        }
                    }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("env_option_${env.id}")
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(env.displayName, style = MaterialTheme.typography.titleMedium, color = if (selected) SkyPrimary else TextPrimary)
                    Text(env.subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
                if (selected) Text("ACTIVE", style = MaterialTheme.typography.labelMedium, color = SkyPrimary)
            }
        }

        Spacer(Modifier.height(10.dp))
        LabeledSlider("Immersion Dial (${(settings.immersionLevel * 100).toInt()}% Virtual ↔ AR)", settings.immersionLevel, 0f..1.0f) { v ->
            coordinator.updateSettings { it.copy(immersionLevel = v) }
        }
        LabeledSlider("Environment Brightness", settings.environment.brightness, 0.2f..1.0f) { v ->
            coordinator.updateSettings { it.copy(environment = it.environment.copy(brightness = v)) }
        }
        LabeledSlider("Particle Density", settings.environment.particleDensity, 0f..1.0f) { v ->
            coordinator.updateSettings { it.copy(environment = it.environment.copy(particleDensity = v)) }
        }
        LabeledSwitch("Environment Effects", settings.environment.effectsEnabled) { v ->
            coordinator.updateSettings { it.copy(environment = it.environment.copy(effectsEnabled = v)) }
        }
    }

    Spacer(Modifier.height(12.dp))
    GlassCard {
        SectionHeader("Cinema Mode & Spatial Audio")
        LabeledSwitch("Cinema Mode (Dim Surroundings for Media3)", settings.cinemaModeEnabled, testTag = "cinema_mode_switch") { v ->
            coordinator.updateSettings { it.copy(cinemaModeEnabled = v) }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            CinemaProjectionMode.entries.forEach { proj ->
                FilterChip(
                    selected = settings.cinemaProjectionMode == proj,
                    onClick = { coordinator.updateSettings { it.copy(cinemaProjectionMode = proj) } },
                    label = { Text(proj.label, maxLines = 1) },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("cinema_proj_${proj.name.lowercase()}")
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Android Spatializer: ${spatialAudio.label} (Head-Tracked Audio: ${if (spatialAudio.isHeadTrackerAvailable) "Ready" else "Headset/Device Dependent"})",
            style = MaterialTheme.typography.labelMedium,
            color = if (spatialAudio.isEnabled) EmeraldActive else TextSecondary
        )
    }

    Spacer(Modifier.height(12.dp))
    val context = LocalContext.current
    GlassCard {
        SectionHeader("VR Edition Phone Launcher")
        Text(
            "Use PALINDORME XR as a spatial Android Home launcher for passive headsets (Galaxy XR / Vision style translucent workspace).",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                val homeIntent = Intent(Settings.ACTION_HOME_SETTINGS)
                if (homeIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(homeIntent)
                } else {
                    coordinator.postStatusToast("Open Android Settings > Apps > Default Home App to set PALINDORME XR")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("set_default_home_launcher_btn")
        ) {
            Icon(Icons.Rounded.Home, contentDescription = null, tint = SkyPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Choose Default Home Launcher in Android Settings", color = SkyPrimary)
        }
    }

    Spacer(Modifier.height(12.dp))
    GlassCard {
        SectionHeader("Comfort & Accessibility")
        LabeledSlider("Gaze Dwell Timer (${"%.1f".format(settings.gazeDwellSeconds)}s)", settings.gazeDwellSeconds, 0.5f..2.0f) { v ->
            coordinator.updateSettings { it.copy(gazeDwellSeconds = v) }
        }
        LabeledSlider("Session Break Reminder (${settings.sessionBreakMinutes} min)", settings.sessionBreakMinutes.toFloat(), 10f..90f) { v ->
            coordinator.updateSettings { it.copy(sessionBreakMinutes = v.toInt()) }
        }
        LabeledSwitch("High-Contrast Glass (7:1 Stereo Readability)", settings.highContrastGlass) { v ->
            coordinator.updateSettings { it.copy(highContrastGlass = v) }
        }
        LabeledSwitch("Reduce Motion (Disable Particles & Parallax)", settings.reduceMotion) { v ->
            coordinator.updateSettings { it.copy(reduceMotion = v) }
        }
        LabeledSwitch("Audio Click Feedback", settings.audioClickEnabled) { v ->
            coordinator.updateSettings { it.copy(audioClickEnabled = v) }
        }
        LabeledSwitch("Haptic Pulse Feedback", settings.hapticFeedbackEnabled) { v ->
            coordinator.updateSettings { it.copy(hapticFeedbackEnabled = v) }
        }
        LabeledSwitch("Left-Handed Gamepad Layout", settings.leftHandedController) { v ->
            coordinator.updateSettings { it.copy(leftHandedController = v) }
        }
        LabeledSwitch("Bluetooth Shutter / Volume Clicker Select", settings.bluetoothClickerEnabled) { v ->
            coordinator.updateSettings { it.copy(bluetoothClickerEnabled = v) }
        }
        LabeledSwitch("Angular-Size Depth Scaling (Legible Text at Depth)", settings.angularScaleWindowsByDepth) { v ->
            coordinator.updateSettings { it.copy(angularScaleWindowsByDepth = v) }
        }
        LabeledSwitch("One-Handed Accessibility Mode", settings.oneHandedMode) { v ->
            coordinator.updateSettings { it.copy(oneHandedMode = v) }
        }
    }
}

@Composable
private fun TrackingTabContent(
    coordinator: XRLauncherCoordinator,
    settings: XRSettings,
    tracking: TrackingTelemetry,
    arTelemetry: ArPerceptionTelemetry,
    onRequestArToggle: (Boolean) -> Unit
) {
    GlassCard {
        SectionHeader("Head Tracking (3-DOF Default)")
        Text("Active Source: ${tracking.source.label}", style = MaterialTheme.typography.bodyLarge, color = SkyPrimary)
        Text(
            "Sample Rate: ${"%.0f".format(tracking.sampleRateHz)} Hz · Gyroscope: ${if (tracking.hasGyroscope) "Present" else "Missing (Fallback)"}",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TrackingSourceMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.trackingSourceMode == mode,
                    onClick = { coordinator.updateSettings { it.copy(trackingSourceMode = mode) } },
                    label = { Text(mode.name.replace("_", " "), maxLines = 1) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LabeledSlider("Motion Prediction (${settings.predictionMs.toInt()} ms)", settings.predictionMs, 0f..45f) { v ->
            coordinator.updateSettings { it.copy(predictionMs = v) }
        }
        Button(
            onClick = { coordinator.headTracking.recenter() },
            colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("hub_recenter_btn")
        ) {
            Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Re-Center Horizon & Yaw")
        }
    }

    Spacer(Modifier.height(12.dp))
    GlassCard {
        SectionHeader("Optional ARCore Perception & 6-DOF (Experimental)")
        Text(arTelemetry.availability.label, style = MaterialTheme.typography.bodyLarge, color = if (arTelemetry.availability.isSupported) EmeraldActive else AmberWarning)
        Text(
            "State: ${arTelemetry.trackingState} · Planes: ${arTelemetry.detectedPlanesCount} · Depth API: ${if (arTelemetry.depthSupported) "Supported" else "Unavailable"}",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        arTelemetry.trackingQualityWarning?.let { warn ->
            Text(warn, style = MaterialTheme.typography.bodyMedium, color = AmberWarning)
        }
        Spacer(Modifier.height(8.dp))
        LabeledSwitch("Enable ARCore Mode (Mode B)", settings.arCoreEnabled) { onRequestArToggle(it) }
        LabeledSwitch("Enable 6-DOF Positional Tracking (Auto-falls back to 3-DOF)", settings.arCoreSixDofEnabled) { v ->
            coordinator.updateSettings { it.copy(arCoreSixDofEnabled = v) }
        }
    }
}

@Composable
private fun CameraHandsTabContent(
    coordinator: XRLauncherCoordinator,
    settings: XRSettings,
    handTelemetry: HandTrackingTelemetry,
    cameraStatus: CameraStatusSnapshot,
    availableCameras: List<CameraLensDescriptor>,
    pcLinkStats: CompanionLinkStats,
    discoveredPcs: List<DiscoveredPcHost>
) {
    GlassCard {
        SectionHeader("Camera-Based Hand Control (LIVE_STREAM · Look+Pinch)")
        Text(
            "Default Vision-style Look+Pinch (head reticle aims, pinch anywhere to click/drag) + near-field fingertip poke. Optional Hand-Ray pointing below.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Status: ${handTelemetry.status::class.simpleName} · ${handTelemetry.delegateName} · ${"%.1f".format(handTelemetry.inferenceLatencyMs)} ms · Lens: ${handTelemetry.activeCameraLabel} (${handTelemetry.activeCameraHfovDeg.toInt()}°)",
            style = MaterialTheme.typography.labelMedium,
            color = if (handTelemetry.handsVisible) EmeraldActive else AmberWarning
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { coordinator.openHandSetupWizard() },
                colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("open_hand_wizard_btn")
            ) {
                Text("Guided Setup")
            }
            OutlinedButton(
                onClick = {
                    coordinator.windowManager.openOrFocusWindow(WindowType.HAND_LAB)
                    coordinator.switchAppMode(AppMode.STEREO_VR)
                },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("open_vr_hand_lab_btn")
            ) {
                Text("VR Lab")
            }
            OutlinedButton(
                onClick = { coordinator.toggleHandsImmediate(false) },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("hands_off_quick_btn")
            ) {
                Text("Hands Off", color = RoseError)
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            HandednessPreference.entries.forEach { pref ->
                FilterChip(
                    selected = settings.handedness == pref,
                    onClick = { coordinator.updateSettings { it.copy(handedness = pref) } },
                    label = { Text(pref.label) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                )
            }
        }
        LabeledSwitch("Show 3D Stereo Hand Skeleton & Reach Zone", settings.handSkeletonOverlay) { v ->
            coordinator.updateSettings { it.copy(handSkeletonOverlay = v) }
        }
        LabeledSwitch("Hand-Ray Pointing Mode (Off = Look+Pinch Default)", settings.handRayModeEnabled) { v ->
            coordinator.updateSettings { it.copy(handRayModeEnabled = v) }
        }
        LabeledSwitch("Relaxed Pinch Sensitivity (Accessibility)", settings.relaxedPinchSensitivity) { v ->
            coordinator.updateSettings { it.copy(relaxedPinchSensitivity = v) }
        }
        LabeledSwitch("Auto-Enable Gaze Dwell When Hands Not Visible", settings.autoGazeFallbackWhenHandsLost) { v ->
            coordinator.updateSettings { it.copy(autoGazeFallbackWhenHandsLost = v) }
        }
    }

    Spacer(Modifier.height(12.dp))
    HandDebugLabCard(
        coordinator = coordinator,
        settings = settings,
        handTelemetry = handTelemetry,
        cameras = availableCameras
    )

    Spacer(Modifier.height(12.dp))
    PcCompanionCard(
        coordinator = coordinator,
        stats = pcLinkStats,
        discoveredHosts = discoveredPcs
    )

    Spacer(Modifier.height(12.dp))
    GlassCard {
        SectionHeader("Experimental Monocular Passthrough & View Rotation (CameraX)")
        Text("Coordinator: ${cameraStatus.state.name} (${cameraStatus.statusMessage})", style = MaterialTheme.typography.labelMedium, color = SkyPrimary)
        Spacer(Modifier.height(6.dp))
        LabeledSwitch("Monocular Passthrough (Stationary Use Only)", settings.passthroughEnabled) { enable ->
            coordinator.requestPassthroughToggle(enable)
        }
        LabeledSlider("Passthrough Brightness (${"%.2f".format(settings.passthroughBrightness)}x)", settings.passthroughBrightness, 0.3f..1.4f) { v ->
            coordinator.updateSettings { it.copy(passthroughBrightness = v) }
        }
        LabeledSwitch("Compensate Lens Barrel Distortion on Camera Feed", settings.passthroughUndistort) { v ->
            coordinator.updateSettings { it.copy(passthroughUndistort = v) }
        }

        Spacer(Modifier.height(8.dp))
        Text("Camera Passthrough Rotation (Fix Horizontal / Portrait Sensor)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(0, 90, 180, 270).forEach { deg ->
                FilterChip(
                    selected = settings.cameraRotationDegrees == deg,
                    onClick = { coordinator.updateSettings { it.copy(cameraRotationDegrees = deg) } },
                    label = { Text("${deg}°") },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("cam_rot_$deg")
                )
            }
        }
        LabeledSwitch("Mirror Camera Horizontally", settings.cameraMirrorHorizontal, testTag = "cam_mirror_switch") { v ->
            coordinator.updateSettings { it.copy(cameraMirrorHorizontal = v) }
        }

        Spacer(Modifier.height(6.dp))
        Text("Gallery Photo Rotation", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(0, 90, 180, 270).forEach { deg ->
                FilterChip(
                    selected = settings.galleryRotationDegrees == deg,
                    onClick = { coordinator.updateSettings { it.copy(galleryRotationDegrees = deg) } },
                    label = { Text("${deg}°") },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("gallery_rot_$deg")
                )
            }
        }
    }
}

@Composable
private fun OpticsTabContent(coordinator: XRLauncherCoordinator, settings: XRSettings) {
    var importInput by remember { mutableStateOf("") }

    GlassCard {
        SectionHeader("Headset Optics Profile (Full Brown-Conrady k1/k2/p1/p2)")
        Text(
            "Detected Display DPI: ${settings.optics.xdpi.toInt()} × ${settings.optics.ydpi.toInt()} dpi",
            style = MaterialTheme.typography.labelMedium,
            color = SkyPrimary
        )
        Spacer(Modifier.height(8.dp))
        OpticsPreset.entries.filter { it != OpticsPreset.CUSTOM }.forEach { preset ->
            val selected = settings.optics.preset == preset
            OutlinedButton(
                onClick = {
                    coordinator.updateSettings {
                        it.copy(
                            optics = it.optics.copy(
                                preset = preset,
                                ipdMm = preset.ipdMm,
                                fovDegrees = preset.fovDegrees,
                                k1 = preset.k1,
                                k2 = preset.k2,
                                p1 = preset.p1,
                                p2 = preset.p2
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).minimumInteractiveComponentSize().testTag("optics_preset_${preset.name.lowercase()}")
            ) {
                Text(
                    "${preset.displayName} (${preset.ipdMm.toInt()}mm / ${preset.fovDegrees.toInt()}°)",
                    color = if (selected) SkyPrimary else TextPrimary
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        LabeledSlider("IPD Separation (${"%.1f".format(settings.optics.ipdMm)} mm)", settings.optics.ipdMm, 52f..75f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(preset = OpticsPreset.CUSTOM, ipdMm = v)) }
        }
        LabeledSlider("Target FOV (${"%.0f".format(settings.optics.fovDegrees)}°)", settings.optics.fovDegrees, 65f..115f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(preset = OpticsPreset.CUSTOM, fovDegrees = v)) }
        }
        LabeledSlider("Radial Distortion k1 (${"%.2f".format(settings.optics.k1)})", settings.optics.k1, -0.45f..0.20f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(preset = OpticsPreset.CUSTOM, k1 = v)) }
        }
        LabeledSlider("Radial Distortion k2 (${"%.2f".format(settings.optics.k2)})", settings.optics.k2, -0.15f..0.20f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(preset = OpticsPreset.CUSTOM, k2 = v)) }
        }
        LabeledSlider("Tangential Center p1 (${"%.3f".format(settings.optics.p1)})", settings.optics.p1, -0.05f..0.05f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(preset = OpticsPreset.CUSTOM, p1 = v)) }
        }
        LabeledSlider("Tangential Center p2 (${"%.3f".format(settings.optics.p2)})", settings.optics.p2, -0.05f..0.05f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(preset = OpticsPreset.CUSTOM, p2 = v)) }
        }
        LabeledSlider("Left Eye Vertical Offset (${"%.3f".format(settings.optics.leftEyeVerticalOffset)})", settings.optics.leftEyeVerticalOffset, -0.06f..0.06f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(leftEyeVerticalOffset = v)) }
        }
        LabeledSlider("Right Eye Vertical Offset (${"%.3f".format(settings.optics.rightEyeVerticalOffset)})", settings.optics.rightEyeVerticalOffset, -0.06f..0.06f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(rightEyeVerticalOffset = v)) }
        }
        LabeledSwitch(
            label = "Show In-Headset Alignment Calibration Grid",
            checked = settings.showCalibrationGrid,
            testTag = "calib_grid_switch"
        ) { v ->
            coordinator.updateSettings { it.copy(showCalibrationGrid = v) }
        }
    }

    Spacer(Modifier.height(12.dp))
    GlassCard {
        SectionHeader("Import QR / JSON / Cardboard Optics Profile")
        Text(
            "Paste a JSON snippet (e.g. {\"ipdMm\":64,\"fovDegrees\":98,\"k1\":-0.21,\"k2\":0.05}) or key=value query string.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = importInput,
            onValueChange = { importInput = it },
            label = { Text("Optics JSON or QR payload URL") },
            modifier = Modifier.fillMaxWidth().testTag("optics_import_input")
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val parsed = OpticsProfile.importFromString(importInput, settings.optics)
                if (parsed != null) {
                    coordinator.updateSettings { it.copy(optics = parsed) }
                    coordinator.postStatusToast("Imported custom optics profile (${parsed.ipdMm.toInt()}mm / ${parsed.fovDegrees.toInt()}°)")
                } else {
                    coordinator.postStatusToast("Could not parse optics JSON or key=value parameters")
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("optics_import_btn")
        ) {
            Text("Apply Imported Optics")
        }
    }
}

@Composable
private fun PerformanceTabContent(
    coordinator: XRLauncherCoordinator,
    settings: XRSettings,
    perf: PerformanceTelemetry,
    handTelemetry: HandTrackingTelemetry,
    arTelemetry: ArPerceptionTelemetry,
    device: DeviceHardwareDiagnostics
) {
    val context = LocalContext.current

    GlassCard {
        SectionHeader("Runtime Device Telemetry (Measured on Device)")
        val mono = MaterialTheme.typography.labelMedium
        Text("${device.deviceModel} · ${device.androidVersion}", style = mono, color = TextPrimary)
        Text("SoC: ${device.socInfo} · RAM: ${device.totalRamMb} MB", style = mono, color = TextSecondary)
        Text("GPU (GL_RENDERER): ${perf.glRendererString}", style = mono, color = SkyPrimary)
        Text(
            "Display: ${device.displayResolutionPx} (${"%.2f".format(device.physicalScreenInches)}\" · ${device.xdpi.toInt()}x${device.ydpi.toInt()} dpi) · Modes: ${device.supportedRefreshRates.joinToString("/")}Hz (Active ${"%.0f".format(perf.activeRefreshRateHz)}Hz)",
            style = mono,
            color = TextSecondary
        )
        Text("Sustained Perf API: ${if (device.sustainedPerformanceSupported) "Supported" else "Unavailable"} · Rear Lenses: ${device.rearCameras.size}", style = mono, color = TextSecondary)
    }

    Spacer(Modifier.height(12.dp))
    GlassCard {
        SectionHeader("Thermal Tiers & Frame Budgets")
        val mono = MaterialTheme.typography.labelMedium
        Text(
            "FPS: ${"%.1f".format(perf.fps)} · Avg: ${"%.2f".format(perf.frameTimeAvgMs)} ms · P95: ${"%.2f".format(perf.frameTimeP95Ms)} ms · P99: ${"%.2f".format(perf.frameTimeP99Ms)} ms · Drop: ${perf.droppedFrames}",
            style = mono,
            color = SkyPrimary
        )
        Text("Motion-to-Photon Est: ${"%.1f".format(perf.motionToPhotonEstMs)} ms  ·  ADPF: ${if (perf.adpfActive) "Active" else "API < 31 / Off"}", style = mono, color = TextSecondary)
        Text("Thermal Status: ${perf.thermalStatusLabel}  ·  Quality Tier: ${perf.qualityTier.label}", style = mono, color = EmeraldActive)
        Text("Memory: ${perf.memoryUsedMb} MB  ·  Battery: ${perf.batteryPercent}%", style = mono, color = TextSecondary)
        Text("Hand Infer Avg/P95: ${"%.1f".format(handTelemetry.inferenceAvgMs)} / ${"%.1f".format(handTelemetry.inferenceP95Ms)} ms  ·  E2E: ${"%.1f".format(handTelemetry.endToEndLatencyMs)} ms", style = mono, color = TextSecondary)
        Text(
            "Camera Orient: sensor=${handTelemetry.camSensorOrientationDeg}° · disp=${handTelemetry.camDisplayRotationDeg}° · camRot=${handTelemetry.camRotationDegrees}° · mirror=${handTelemetry.camMirror} · userOff=${handTelemetry.camUserOffsetDeg}° -> final=${handTelemetry.camFinalRotationDeg}°",
            style = mono,
            color = SkyPrimary
        )
        Text("ARCore: ${arTelemetry.trackingState} (${arTelemetry.detectedPlanesCount} planes)", style = mono, color = TextSecondary)

        Spacer(Modifier.height(12.dp))
        Text("Frame Time History (Last 60 Frames · Blue <11.1ms · Green <16.6ms · Rose Overrun)", style = MaterialTheme.typography.labelSmall, color = SkyPrimary)
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CanvasObsidian)
                .border(1.dp, SkyPrimaryDim, RoundedCornerShape(10.dp))
                .padding(6.dp)
                .testTag("frame_time_graph_canvas")
        ) {
            val samples = perf.frameTimeHistory
            val maxMs = 28f
            val y16 = size.height * (1f - (16.67f / maxMs))
            val y11 = size.height * (1f - (11.11f / maxMs))
            drawLine(AmberWarning.copy(alpha = 0.55f), Offset(0f, y16), Offset(size.width, y16), strokeWidth = 1.5f)
            drawLine(SkyPrimary.copy(alpha = 0.45f), Offset(0f, y11), Offset(size.width, y11), strokeWidth = 1.5f)

            if (samples.isNotEmpty()) {
                val barW = size.width / samples.size.coerceAtLeast(1)
                samples.forEachIndexed { idx, ms ->
                    val clamped = ms.coerceIn(0.5f, maxMs)
                    val barH = (clamped / maxMs) * size.height
                    val barColor = when {
                        ms <= 11.2f -> SkyPrimary
                        ms <= 16.8f -> EmeraldActive
                        else -> RoseError
                    }
                    drawRect(
                        color = barColor,
                        topLeft = Offset(idx * barW, size.height - barH),
                        size = Size((barW * 0.78f).coerceAtLeast(2f), barH)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Thermal Throttling Priority (If Hand Tracking + ARCore Both Active)", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ThermalPriority.entries.forEach { priority ->
                FilterChip(
                    selected = settings.thermalPriority == priority,
                    onClick = { coordinator.updateSettings { it.copy(thermalPriority = priority) } },
                    label = { Text(priority.label) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                )
            }
        }

        LabeledSlider("Eye FBO Render Scale (${(settings.optics.renderScale * 100).toInt()}%)", settings.optics.renderScale, 0.60f..1.0f) { v ->
            coordinator.updateSettings { it.copy(optics = it.optics.copy(renderScale = v)) }
        }
        LabeledSwitch("Use 90Hz Refresh When Thermally Safe", settings.prefer90HzWhenThermallySafe) { v ->
            coordinator.updateSettings { it.copy(prefer90HzWhenThermallySafe = v) }
        }
        LabeledSwitch("Low-Power Thermal Saver Mode", settings.lowPowerMode) { v ->
            coordinator.updateSettings { it.copy(lowPowerMode = v) }
        }
        LabeledSwitch("Software WebView Canvas Fallback (OEM Compatibility)", settings.softwareWebViewFallback) { v ->
            coordinator.updateSettings { it.copy(softwareWebViewFallback = v) }
        }
        LabeledSwitch("HTTPS-Only Upgrade in Spatial Browser", settings.browserHttpsOnly) { v ->
            coordinator.updateSettings { it.copy(browserHttpsOnly = v) }
        }
        LabeledSwitch("Post 'Return to XR' Notification on App Launch", settings.returnNotificationEnabled) { v ->
            coordinator.updateSettings { it.copy(returnNotificationEnabled = v) }
        }
        LabeledSwitch("Opt-In Voice Commands (On-Device Preferred on API 33+)", settings.voiceCommandsEnabled) { v ->
            coordinator.updateSettings { it.copy(voiceCommandsEnabled = v) }
        }
    }

    Spacer(Modifier.height(12.dp))
    GlassCard {
        SectionHeader("OEM Battery Optimization (Realme UI / ColorOS)")
        Text(
            text = if (device.ignoringBatteryOptimizations) {
                "Battery optimization is disabled for PALINDORME XR — background camera & sensor loops won't be killed mid-session."
            } else {
                "Realme UI / ColorOS often throttles GL + CameraX after ~2 minutes. Set PALINDORME XR to 'Don't optimize / Allow background activity' for uninterrupted VR sessions."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (device.ignoringBatteryOptimizations) EmeraldActive else AmberWarning
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { DeviceDiagnostics.openBatteryOptimizationSettings(context) },
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("battery_opt_settings_btn")
        ) {
            Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = SkyPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Open Battery Optimization Settings", color = SkyPrimary)
        }
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SpatialGlass),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SkyPrimaryDim, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = SkyPrimary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}
