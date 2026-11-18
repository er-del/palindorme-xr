package com.example.xr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CanvasObsidian
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.RoseError
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.SpatialGlassOpaque
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.xr.PendingDialogState
import com.example.xr.XRLauncherCoordinator
import com.example.xr.hand.HandTrackingTelemetry

@Composable
fun HubAndXrDialogs(
    coordinator: XRLauncherCoordinator,
    dialogs: PendingDialogState,
    handTelemetry: HandTrackingTelemetry
) {
    // 1. Passthrough Safety Modal (Section 3.1)
    if (dialogs.showPassthroughSafetyModal) {
        AlertDialog(
            onDismissRequest = { coordinator.dismissPassthroughSafetyModal() },
            containerColor = SpatialGlassOpaque,
            title = {
                Text("Experimental Monocular Passthrough", color = AmberWarning)
            },
            text = {
                Text(
                    "Stationary use only. Never walk, cycle, or drive while using camera passthrough. " +
                        "A single rear camera produces a flat monocular view with higher latency than dedicated headsets.\n\n" +
                        "Camera frames stay in memory and are never saved or sent.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { coordinator.confirmPassthroughSafetyModal() },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("confirm_passthrough_safety")
                ) {
                    Text("I Understand — Enable Camera")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { coordinator.dismissPassthroughSafetyModal() },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // 2. Live 30-Second Guided Hand Control Setup & Calibration Wizard
    if (dialogs.showHandWizardModal) {
        HandSetupWizardDialog(
            coordinator = coordinator,
            handTelemetry = handTelemetry,
            initialPalmMm = coordinator.settings.value.handCalibration.palmWidthMeters * 1000f,
            initialClose = coordinator.settings.value.handCalibration.pinchClosedThreshold,
            initialOpen = coordinator.settings.value.handCalibration.pinchOpenThreshold,
            onDismiss = { coordinator.closeHandSetupWizard() },
            onComplete = { palmM, closeT, openT ->
                coordinator.enableHandsAfterWizard(palmM, closeT, openT)
            }
        )
    }

    // 2b. Explicit Opt-In Consent Modal for PC Companion Camera Frame Transmission
    dialogs.pendingCompanionRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { coordinator.dismissCompanionConsent() },
            containerColor = SpatialGlassOpaque,
            title = { Text("Send Camera Frames to Your PC?", color = AmberWarning) },
            text = {
                Text(
                    "In PC Companion mode, rear camera frames will be H.264-encoded and streamed over your encrypted local network / USB tunnel ONLY to ${req.host}:${req.controlPort}.\n\n" +
                        "• Never sent to the internet, relays, or cloud.\n" +
                        "• Head tracking, distortion, and UI rendering remain on your phone.\n" +
                        "• A persistent badge will read 'Sending to PC' whenever streaming is active.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { coordinator.confirmCompanionConsent() },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("confirm_companion_consent_btn")
                ) {
                    Text("I Understand — Pair & Stream")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { coordinator.dismissCompanionConsent() },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // 3. Stereo-Exit Notice before Launching 3rd-Party Android App (Section 3.1 & 4.3)
    dialogs.pendingLaunchApp?.let { app ->
        AlertDialog(
            onDismissRequest = { coordinator.cancelPendingAppLaunch() },
            containerColor = SpatialGlassOpaque,
            title = { Text("Leaving Stereo View", color = TextPrimary) },
            text = {
                Text(
                    "Launching ${app.label} hands foreground control to Android and exits the stereo VR view. " +
                        "A 'Return to PALINDORME XR' notification can bring you back without removing your phone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { coordinator.confirmPendingAppLaunch() },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("confirm_app_launch")
                ) {
                    Text("Launch ${app.label}")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { coordinator.cancelPendingAppLaunch() },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Stay in XR", color = TextSecondary)
                }
            }
        )
    }

    // 4. Status Toast / Notice Dialog
    dialogs.statusToast?.let { msg ->
        AlertDialog(
            onDismissRequest = { coordinator.postStatusToast(null) },
            containerColor = SpatialGlassOpaque,
            title = { Text("PALINDORME XR", color = SkyPrimary) },
            text = { Text(msg, color = TextPrimary) },
            confirmButton = {
                TextButton(
                    onClick = { coordinator.postStatusToast(null) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("OK", color = SkyPrimary)
                }
            }
        )
    }
}

@Composable
private fun HandSetupWizardDialog(
    coordinator: XRLauncherCoordinator,
    handTelemetry: HandTrackingTelemetry,
    initialPalmMm: Float,
    initialClose: Float,
    initialOpen: Float,
    onDismiss: () -> Unit,
    onComplete: (palmWidthM: Float, closeThresh: Float, openThresh: Float) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var palmMm by remember { mutableFloatStateOf(initialPalmMm.coerceIn(60f, 115f)) }
    var closeThresh by remember { mutableFloatStateOf(initialClose) }
    var openThresh by remember { mutableFloatStateOf(initialOpen) }
    var practiceSuccess by remember { mutableStateOf(false) }
    var liveCaptureNote by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpatialGlassOpaque,
        title = {
            Text("Hand Control Setup · Step $step / 4", color = SkyPrimary)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { step / 4f },
                    modifier = Modifier.fillMaxWidth(),
                    color = SkyPrimary
                )
                Spacer(Modifier.height(12.dp))
                when (step) {
                    1 -> {
                        Text("Privacy, Safety & Lens Cutout", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "• Uses your widest rear camera in RAM only — never saved or sent unless you explicitly opt into LAN PC Companion.\n" +
                                "• Requires an open headset cutout or front flap so the ultra-wide lens sees your hands.\n" +
                                "• Gaze dwell (1.5s), gamepad, and Bluetooth shutter clicker always work as zero-hand fallbacks.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    2 -> {
                        Text("Live Camera, Lens FOV & Lighting", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Active Lens: ${handTelemetry.activeCameraLabel} (${handTelemetry.activeCameraHfovDeg.toInt()}° HFOV) · Delegate: ${handTelemetry.delegateName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SkyPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (handTelemetry.modelBundled) {
                                "Hand Landmarker Ready · Luma: ${"%.0f".format(handTelemetry.meanLuminance)} · Hold hand 25–60 cm from rear lens."
                            } else {
                                "hand_landmarker.task missing from assets — gaze dwell & gamepad remain active."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (handTelemetry.modelBundled) EmeraldActive else AmberWarning
                        )
                        if (handTelemetry.cameraOccludedWarning) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Rear camera looks covered (low brightness + flat texture). Open front cover.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = RoseError
                            )
                        } else if (handTelemetry.lowLightWarning) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Low light detected — move to brighter room light for crisp tracking.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AmberWarning
                            )
                        }
                    }
                    3 -> {
                        Text("Live Pinch & Open-Hand Calibration", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    val live = coordinator.handTracking.getLatestHands().firstOrNull()
                                    if (live != null) {
                                        openThresh = (live.normalizedPinchRatio() * 0.72f).coerceIn(0.36f, 0.65f)
                                        liveCaptureNote = "Captured open-hand ratio (${"%.2f".format(live.normalizedPinchRatio())})"
                                    } else {
                                        liveCaptureNote = "Hold an open hand in front of the lens first, or use sliders."
                                    }
                                },
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                            ) {
                                Text("Sample Open Hand")
                            }
                            OutlinedButton(
                                onClick = {
                                    val live = coordinator.handTracking.getLatestHands().firstOrNull()
                                    if (live != null) {
                                        closeThresh = (live.normalizedPinchRatio() + 0.06f).coerceIn(0.16f, 0.36f)
                                        liveCaptureNote = "Captured pinched ratio (${"%.2f".format(live.normalizedPinchRatio())})"
                                    } else {
                                        liveCaptureNote = "Pinch thumb+index in view first, or adjust slider."
                                    }
                                },
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                            ) {
                                Text("Sample Pinch")
                            }
                        }
                        liveCaptureNote?.let { note ->
                            Spacer(Modifier.height(4.dp))
                            Text(note, style = MaterialTheme.typography.labelSmall, color = SkyPrimary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Palm Width Reference: ${palmMm.toInt()} mm", color = TextSecondary)
                        Slider(value = palmMm, onValueChange = { palmMm = it }, valueRange = 60f..115f)
                        Text("Pinch Close Threshold: ${"%.2f".format(closeThresh)}", color = TextSecondary)
                        Slider(value = closeThresh, onValueChange = { closeThresh = it }, valueRange = 0.14f..0.38f)
                        Text("Pinch Release Hysteresis: ${"%.2f".format(openThresh)}", color = TextSecondary)
                        Slider(
                            value = openThresh,
                            onValueChange = { openThresh = it.coerceAtLeast(closeThresh + 0.10f) },
                            valueRange = 0.34f..0.68f
                        )
                    }
                    4 -> {
                        Text("Practice Target: Look + Pinch", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Default Look+Pinch: look with your head reticle and pinch thumb+index (or press Bluetooth clicker Volume Up) to click. " +
                                "Double-pinch quickly to Peek at the real world, or show an open palm for Control Center.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { practiceSuccess = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (practiceSuccess) EmeraldActive else SkyPrimary,
                                contentColor = CanvasObsidian
                            ),
                            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("hand_practice_btn")
                        ) {
                            Text(if (practiceSuccess) "Target Activated! Ready to Finish" else "Tap / Pinch Practice Target")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 1) {
                    OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Back")
                    }
                }
                Button(
                    onClick = {
                        if (step < 4) step++ else onComplete(palmMm / 1000f, closeThresh, openThresh)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("hand_wizard_next")
                ) {
                    Text(if (step < 4) "Next" else "Enable Hand Control")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
