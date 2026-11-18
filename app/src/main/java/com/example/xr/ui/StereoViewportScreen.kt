package com.example.xr.ui

import android.opengl.GLSurfaceView
import android.os.Build
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DoNotTouch
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CanvasObsidian
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.RoseError
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.SkyPrimaryDim
import com.example.ui.theme.SpatialGlass
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.xr.AppMode
import com.example.xr.PendingDialogState
import com.example.xr.XRLauncherCoordinator
import com.example.xr.companion.CompanionConnectionState
import com.example.xr.companion.CompanionLinkStats
import com.example.xr.hand.HandTrackingTelemetry
import com.example.xr.model.XRSettings
import com.example.xr.system.ComfortState
import com.example.xr.system.PerformanceTelemetry
import com.example.xr.windows.WindowType
import kotlin.math.abs

@Composable
fun StereoViewportScreen(
    coordinator: XRLauncherCoordinator,
    settings: XRSettings,
    dialogs: PendingDialogState,
    comfort: ComfortState,
    handTelemetry: HandTrackingTelemetry,
    perf: PerformanceTelemetry = PerformanceTelemetry(),
    pcLink: CompanionLinkStats = CompanionLinkStats()
) {
    var showHud by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var glViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }
    var surfaceReadyCount by remember { mutableIntStateOf(0) }
    var lastAppliedSurfaceHz by remember { mutableIntStateOf(-1) }

    LaunchedEffect(surfaceReadyCount, glViewRef, perf.recommendedRefreshRateHz, settings.prefer90HzWhenThermallySafe) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        val surface = glViewRef?.holder?.surface ?: return@LaunchedEffect
        if (!surface.isValid) return@LaunchedEffect
        val desiredHz = if (settings.prefer90HzWhenThermallySafe) perf.recommendedRefreshRateHz else 60
        if (desiredHz != lastAppliedSurfaceHz) {
            runCatching {
                surface.setFrameRate(
                    desiredHz.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
                lastAppliedSurfaceHz = desiredHz
            }
        }
    }

    DisposableEffect(lifecycleOwner, glViewRef) {
        val view = glViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.onPause()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CanvasObsidian)) {
        // Off-screen View Host (invisible 1px-alpha container so WebView/Views lay out cleanly)
        AndroidView(
            factory = { ctx ->
                coordinator.offscreenContainer.apply {
                    (parent as? ViewGroup)?.removeView(this)
                }
            },
            modifier = Modifier.size(1.dp)
        )

        // OpenGL ES 3.0 Dual-Viewport Stereo Surface
        AndroidView(
            factory = { ctx ->
                var downX = 0f
                var downY = 0f
                var moved = false
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    preserveEGLContextOnPause = false
                    setRenderer(coordinator.glRenderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceReadyCount++
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            surfaceReadyCount++
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            lastAppliedSurfaceHz = -1
                        }
                    })
                    setOnTouchListener { _, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = ev.x; downY = ev.y; moved = false
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = ev.x - downX
                                val dy = ev.y - downY
                                if (abs(dx) > 6f || abs(dy) > 6f) moved = true
                                coordinator.headTracking.applyManualLookDelta(-dx * 0.14f, -dy * 0.14f)
                                downX = ev.x; downY = ev.y
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!moved) coordinator.inputManager.triggerInstantTap()
                                true
                            }
                            else -> false
                        }
                    }
                    glViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("stereo_gl_surface")
        )

        // Persistent Camera-In-Use Indicator (shows "sending to PC" when PC Companion streaming)
        val cameraActive = settings.passthroughEnabled || settings.handControlEnabled || settings.arCoreEnabled
        val sendingToPc = pcLink.state == CompanionConnectionState.CONNECTED_ACTIVE && cameraActive
        if (cameraActive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SpatialGlass)
                    .border(
                        1.dp,
                        if (sendingToPc) AmberWarning.copy(alpha = 0.85f) else EmeraldActive.copy(alpha = 0.6f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("camera_active_indicator")
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (sendingToPc) AmberWarning else EmeraldActive)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        sendingToPc -> "Hand Cam · Sending to PC (${pcLink.hostLabel})"
                        settings.arCoreEnabled -> "ARCore Active · Cam ${settings.cameraRotationDegrees}°"
                        settings.passthroughEnabled -> "AR Passthrough · Cam ${settings.cameraRotationDegrees}°"
                        else -> "Hand Cam (${handTelemetry.activeCameraHfovDeg.toInt()}°)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary
                )
            }
        }

        // Dismissible Frosted Glass Stereo HUD Controls (IPD, Re-Center, AR, Rotate Cam, Hands, 2D Hub)
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = if (cameraActive) 44.dp else 10.dp, start = 12.dp, end = 12.dp)
        ) {
            Surface(
                color = SpatialGlass.copy(alpha = 0.62f),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .border(1.dp, SkyPrimary.copy(alpha = 0.42f), RoundedCornerShape(26.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "IPD ${"%.1f".format(settings.optics.ipdMm)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = SkyPrimary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    IconButton(
                        onClick = {
                            coordinator.updateSettings { s ->
                                s.copy(optics = s.optics.copy(ipdMm = (s.optics.ipdMm - 0.5f).coerceIn(52f, 75f)))
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_ipd_minus")
                    ) {
                        Text("−", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    }
                    IconButton(
                        onClick = {
                            coordinator.updateSettings { s ->
                                s.copy(optics = s.optics.copy(ipdMm = (s.optics.ipdMm + 0.5f).coerceIn(52f, 75f)))
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_ipd_plus")
                    ) {
                        Text("+", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    }
                    IconButton(
                        onClick = { coordinator.headTracking.recenter() },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_recenter")
                    ) {
                        Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Re-center view", tint = SkyPrimary)
                    }
                    IconButton(
                        onClick = { coordinator.requestPassthroughToggle(!settings.passthroughEnabled) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_ar_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ViewInAr,
                            contentDescription = if (settings.passthroughEnabled) "Disable AR Passthrough" else "Enable AR Passthrough",
                            tint = if (settings.passthroughEnabled) EmeraldActive else TextSecondary
                        )
                    }
                    IconButton(
                        onClick = { coordinator.togglePeekPassthrough() },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_peek_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RemoveRedEye,
                            contentDescription = "Peek Real World",
                            tint = if (settings.passthroughEnabled && settings.immersionLevel < 0.4f) EmeraldActive else SkyPrimary
                        )
                    }
                    IconButton(
                        onClick = {
                            coordinator.updateSettings { s ->
                                s.copy(cameraRotationDegrees = (s.cameraRotationDegrees + 90) % 360)
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_rotate_camera")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = "Rotate camera view 90 degrees", tint = SkyPrimary)
                    }
                    IconButton(
                        onClick = { coordinator.toggleHandsImmediate(!settings.handControlEnabled) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_hands_toggle")
                    ) {
                        Icon(
                            imageVector = if (settings.handControlEnabled) Icons.Rounded.PanTool else Icons.Rounded.DoNotTouch,
                            contentDescription = if (settings.handControlEnabled) "Hands off" else "Enable hands",
                            tint = if (settings.handControlEnabled) EmeraldActive else TextSecondary
                        )
                    }
                    IconButton(
                        onClick = { coordinator.windowManager.openOrFocusWindow(WindowType.HAND_LAB) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_hand_lab")
                    ) {
                        Text("Lab", style = MaterialTheme.typography.labelMedium, color = SkyPrimary)
                    }
                    IconButton(
                        onClick = { coordinator.switchAppMode(AppMode.HUB_2D) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_open_2d_hub")
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = "2D Settings Hub", tint = TextPrimary)
                    }
                    IconButton(
                        onClick = { showHud = false },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_hide")
                    ) {
                        Icon(Icons.Rounded.VisibilityOff, contentDescription = "Hide HUD", tint = TextSecondary)
                    }
                }
            }
        }

        if (!showHud) {
            IconButton(
                onClick = { showHud = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(SpatialGlass)
                    .minimumInteractiveComponentSize()
                    .testTag("hud_show")
            ) {
                Icon(Icons.Rounded.Visibility, contentDescription = "Show HUD", tint = SkyPrimary)
            }
        }

        // Faint bottom hand-lost indicator or 60s raised-hand rest hint
        if (settings.handControlEnabled && handTelemetry.showRestHint) {
            Surface(
                color = SpatialGlass,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 12.dp)
                    .border(1.dp, AmberWarning.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .testTag("hand_rest_hint")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Rest your hands anytime — gaze dwell takes over automatically",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberWarning
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { coordinator.handTracking.dismissRestHint() },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("dismiss_rest_hint_btn")
                    ) {
                        Text("OK")
                    }
                }
            }
        } else if (pcLink.state == CompanionConnectionState.FALLBACK_ON_DEVICE && pcLink.statusBanner != null) {
            Surface(
                color = SpatialGlass,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 12.dp)
                    .border(1.dp, AmberWarning.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .testTag("pc_fallback_banner")
            ) {
                Text(
                    text = pcLink.statusBanner,
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberWarning,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        } else if (settings.handControlEnabled && !handTelemetry.handsVisible && handTelemetry.handsLostDurationMs > 1500L) {
            Surface(
                color = SpatialGlass,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 12.dp)
                    .border(1.dp, SkyPrimaryDim, RoundedCornerShape(16.dp))
                    .testTag("hand_lost_indicator")
            ) {
                Text(
                    text = when {
                        handTelemetry.cameraOccludedWarning -> "Camera blocked — open headset cutout (gaze dwell active)"
                        handTelemetry.lowLightWarning -> "Low light (luma < 38) — turn on room light (gaze dwell active)"
                        handTelemetry.noHandEverFound -> "Raise hand 25–60 cm in front of rear lens (gaze dwell active)"
                        else -> "Hand outside camera FOV · Gaze dwell active"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Comfort / High-Acceleration Warning Banner
        if (comfort.highAccelerationWarning) {
            Surface(
                color = RoseError.copy(alpha = 0.92f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .testTag("high_accel_warning")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = "Warning", tint = CanvasObsidian)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Stationary use only! Rapid motion detected while camera active.",
                        style = MaterialTheme.typography.labelLarge,
                        color = CanvasObsidian
                    )
                }
            }
        }

        // Session Break Prompt (default 30 min)
        if (comfort.showBreakPrompt) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SpatialGlass),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .widthIn(max = 360.dp)
                    .border(1.dp, AmberWarning.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .testTag("session_break_prompt")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Recommended Session Break (${comfort.sessionMinutes} min)",
                        style = MaterialTheme.typography.titleMedium,
                        color = AmberWarning
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Remove the headset for a few minutes to rest your eyes. Stop immediately if you feel dizzy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { coordinator.comfortManager.dismissBreakPrompt() },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = CanvasObsidian),
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Dismiss Break Reminder")
                    }
                }
            }
        }

        // First-launch Welcome & Out-of-Headset 2D Hub Card (Section 3.1 & 3.2)
        if (dialogs.showFirstUseWelcomeCard) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SpatialGlass),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .widthIn(max = 520.dp)
                        .padding(16.dp)
                        .border(1.dp, SkyPrimary.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                        .testTag("welcome_headset_card")
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "PALINDORME XR",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SkyPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Place your phone in your VR headset",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "First-use guidance: Sit down for a 10-minute initial session. Not intended for children under your headset manufacturer's recommended age. Stop immediately if you feel dizzy.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { coordinator.dismissWelcomeCard(goToHub = true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("welcome_2d_setup_button")
                            ) {
                                Text("I'm using it in 2D / Set up first")
                            }
                            Button(
                                onClick = { coordinator.dismissWelcomeCard(goToHub = false) },
                                colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("welcome_enter_stereo_button")
                            ) {
                                Text("Enter Stereo Home")
                            }
                        }
                    }
                }
            }
        }
    }
}
