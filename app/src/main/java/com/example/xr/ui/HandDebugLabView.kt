package com.example.xr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CanvasObsidian
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.RoseError
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.SkyPrimaryDim
import com.example.ui.theme.SpatialGlass
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.xr.XRLauncherCoordinator
import com.example.xr.hand.HandFrameTransform
import com.example.xr.hand.HandLandmarkIndex
import com.example.xr.hand.HandTrackingTelemetry
import com.example.xr.model.XRSettings
import com.example.xr.perception.CameraLensDescriptor
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandDebugLabCard(
    coordinator: XRLauncherCoordinator,
    settings: XRSettings,
    handTelemetry: HandTrackingTelemetry,
    cameras: List<CameraLensDescriptor>
) {
    var pollTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.handControlEnabled) {
        while (true) {
            pollTick++
            delay(85L)
        }
    }

    val debugBmp = remember(pollTick) { coordinator.handTracking.getLatestDebugBitmap() }
    val liveHands = remember(pollTick) { coordinator.handTracking.getLatestHands() }

    Card(
        colors = CardDefaults.cardColors(containerColor = SpatialGlass),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SkyPrimaryDim, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Hand Debug Lab (Raw Image + Aligned Landmarks)",
                    style = MaterialTheme.typography.titleMedium,
                    color = SkyPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${"%.0f".format(handTelemetry.effectiveFps)} FPS",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldActive
                )
            }

            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CanvasObsidian)
                    .border(1.dp, SkyPrimaryDim, RoundedCornerShape(14.dp))
                    .testTag("hand_debug_lab_canvas")
            ) {
                val fit = if (debugBmp != null && !debugBmp.isRecycled) {
                    HandFrameTransform.computeAspectFitScale(
                        srcWidthPx = debugBmp.width,
                        srcHeightPx = debugBmp.height,
                        rotationDeg = 0,
                        dstWidth = size.width,
                        dstHeight = size.height
                    )
                } else {
                    HandFrameTransform.AspectFitScale(1f, 1f)
                }
                val drawW = (size.width * fit.scaleX).coerceAtLeast(1f)
                val drawH = (size.height * fit.scaleY).coerceAtLeast(1f)
                val offX = (size.width - drawW) * 0.5f
                val offY = (size.height - drawH) * 0.5f

                if (debugBmp != null && !debugBmp.isRecycled) {
                    drawImage(
                        image = debugBmp.asImageBitmap(),
                        dstOffset = IntOffset(offX.toInt(), offY.toInt()),
                        dstSize = IntSize(drawW.toInt().coerceAtLeast(1), drawH.toInt().coerceAtLeast(1))
                    )
                }

                // Target center reach zone outline
                drawRect(
                    color = SkyPrimary.copy(alpha = 0.45f),
                    topLeft = Offset(offX + drawW * 0.12f, offY + drawH * 0.12f),
                    size = Size(drawW * 0.76f, drawH * 0.76f),
                    style = Stroke(width = 2f)
                )

                for (hand in liveHands) {
                    if (hand.imageLandmarks.size < 21) continue
                    for ((a, b) in HandLandmarkIndex.BONE_CONNECTIONS) {
                        val pa = hand.imageLandmarks[a]
                        val pb = hand.imageLandmarks[b]
                        drawLine(
                            color = SkyPrimary,
                            start = Offset(offX + pa.x * drawW, offY + pa.y * drawH),
                            end = Offset(offX + pb.x * drawW, offY + pb.y * drawH),
                            strokeWidth = 4f
                        )
                    }
                    hand.imageLandmarks.forEachIndexed { idx, lm ->
                        val isTip = idx == 4 || idx == 8 || idx == 12 || idx == 16 || idx == 20
                        drawCircle(
                            color = if (isTip) EmeraldActive else Color.White,
                            radius = if (isTip) 7f else 3.5f,
                            center = Offset(offX + lm.x * drawW, offY + lm.y * drawH)
                        )
                    }
                    val contact = hand.pinchContactPointImage()
                    drawCircle(
                        color = AmberWarning,
                        radius = 14f,
                        center = Offset(offX + contact.x * drawW, offY + contact.y * drawH),
                        style = Stroke(width = 3f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            val statusColor = when {
                handTelemetry.cameraOccludedWarning -> RoseError
                handTelemetry.lowLightWarning -> AmberWarning
                handTelemetry.handsVisible -> EmeraldActive
                else -> TextSecondary
            }
            Text(handTelemetry.diagnosticAdvice, style = MaterialTheme.typography.bodyMedium, color = statusColor)

            Spacer(Modifier.height(6.dp))
            Text(
                "Delegate: ${handTelemetry.delegateName} (load ${"%.0f".format(handTelemetry.delegateLoadMs)} ms) · ${handTelemetry.delegateBenchmarkNote}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                "Infer Avg/P95: ${"%.1f".format(handTelemetry.inferenceAvgMs)} / ${"%.1f".format(handTelemetry.inferenceP95Ms)} ms · E2E: ${"%.1f".format(handTelemetry.endToEndLatencyMs)} ms · Luma: ${"%.0f".format(handTelemetry.meanLuminance)}",
                style = MaterialTheme.typography.labelSmall,
                color = SkyPrimary
            )
            Text(
                "Cam Orient: sensor=${handTelemetry.camSensorOrientationDeg}° · disp=${handTelemetry.camDisplayRotationDeg}° · camRot=${handTelemetry.camRotationDegrees}° · mirror=${handTelemetry.camMirror} · userOff=${handTelemetry.camUserOffsetDeg}° -> final=${handTelemetry.camFinalRotationDeg}°",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            handTelemetry.lastErrorDetail?.let { err ->
                Text("Last Init/Runtime Note: $err", style = MaterialTheme.typography.labelSmall, color = AmberWarning)
            }

            if (cameras.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Rear Camera Lens (Auto-prefers widest FOV: ${handTelemetry.activeCameraLabel})",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = settings.preferredCameraId == null,
                        onClick = { coordinator.updateSettings { it.copy(preferredCameraId = null) } },
                        label = { Text("Auto Widest") },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("cam_auto_widest")
                    )
                    cameras.take(3).forEach { cam ->
                        FilterChip(
                            selected = settings.preferredCameraId == cam.cameraId,
                            onClick = { coordinator.updateSettings { it.copy(preferredCameraId = cam.cameraId) } },
                            label = { Text("${cam.label} (${cam.horizontalFovDeg.toInt()}°)") },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("cam_id_${cam.cameraId}")
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Detection Confidence Threshold (${"%.2f".format(settings.handMinConfidence)})", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Slider(
                value = settings.handMinConfidence,
                onValueChange = { v -> coordinator.updateSettings { it.copy(handMinConfidence = v) } },
                valueRange = 0.30f..0.75f
            )
            Text("Drag & Scroll Gesture Gain (${"%.2f".format(settings.gestureSizeGain)}x)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Slider(
                value = settings.gestureSizeGain,
                onValueChange = { v -> coordinator.updateSettings { it.copy(gestureSizeGain = v) } },
                valueRange = 0.6f..1.8f
            )

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { coordinator.handTracking.rerunDelegateBenchmark() },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("rebench_delegate_btn")
                ) {
                    Text("Re-Bench GPU/CPU")
                }
                Button(
                    onClick = { coordinator.toggleTraceRecording() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (handTelemetry.traceRecording) RoseError else SkyPrimary,
                        contentColor = CanvasObsidian
                    ),
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("toggle_trace_btn")
                ) {
                    Text(if (handTelemetry.traceRecording) "Stop Trace (${handTelemetry.traceFrameCount})" else "Record Landmarks")
                }
            }
            if (handTelemetry.lastTraceFilePath != null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { coordinator.replayLastTrace() },
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("replay_trace_btn")
                ) {
                    Text("Replay Captured Trace (Landmarks-Only .jsonl)")
                }
            }
        }
    }
}
