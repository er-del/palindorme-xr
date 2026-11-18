package com.example.xr.hand

import com.example.xr.tracking.Vec3

enum class HandSide {
    LEFT,
    RIGHT
}

/**
 * 21 standard MediaPipe hand landmark indices.
 */
object HandLandmarkIndex {
    const val WRIST = 0
    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_DIP = 11
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_DIP = 15
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_DIP = 19
    const val PINKY_TIP = 20

    val BONE_CONNECTIONS = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )
}

data class HandFrame(
    val side: HandSide,
    val confidence: Float,
    val imageLandmarks: List<Vec3>, // normalized camera image coords x,y in 0..1, relative z
    val worldLandmarks: List<Vec3>, // metric 3D landmarks in meters
    val timestampMs: Long,
    val isMirrored: Boolean = false,
    val frameTimestampNs: Long = 0L, // camera capture timestamp in elapsedRealtimeNanos
    val inferenceReadyTimestampNs: Long = 0L,
    val cameraHfovDeg: Float = 70f,
    val cameraVfovDeg: Float = 55f,
    val isHeldDropout: Boolean = false
) {
    /**
     * 3D pinch distance normalized by palm span.
     * Uses metric worldLandmarks when valid (scale/tilt invariant), falling back to 3D imageLandmarks.
     */
    fun normalizedPinchRatio(): Float {
        val pts = if (worldLandmarks.size >= 21) {
            val wPalm = worldLandmarks[HandLandmarkIndex.INDEX_MCP]
                .distanceTo(worldLandmarks[HandLandmarkIndex.PINKY_MCP])
            if (wPalm > 0.025f) worldLandmarks else imageLandmarks
        } else {
            imageLandmarks
        }
        if (pts.size < 21) return 1f
        val thumb = pts[HandLandmarkIndex.THUMB_TIP]
        val index = pts[HandLandmarkIndex.INDEX_TIP]
        val indexMcp = pts[HandLandmarkIndex.INDEX_MCP]
        val pinkyMcp = pts[HandLandmarkIndex.PINKY_MCP]
        val palmSpan = indexMcp.distanceTo(pinkyMcp).coerceAtLeast(0.02f)
        return thumb.distanceTo(index) / palmSpan
    }

    fun pinchContactPointImage(): Vec3 {
        if (imageLandmarks.size < 21) return Vec3(0.5f, 0.5f, 0f)
        return (imageLandmarks[HandLandmarkIndex.THUMB_TIP] + imageLandmarks[HandLandmarkIndex.INDEX_TIP]) * 0.5f
    }

    fun palmWidthImage(): Float {
        if (imageLandmarks.size < 21) return 0f
        return imageLandmarks[HandLandmarkIndex.INDEX_MCP]
            .distanceTo(imageLandmarks[HandLandmarkIndex.PINKY_MCP])
    }

    fun palmCenterImage(): Vec3 {
        if (imageLandmarks.size < 21) return Vec3(0.5f, 0.5f, 0f)
        val wrist = imageLandmarks[HandLandmarkIndex.WRIST]
        val indexMcp = imageLandmarks[HandLandmarkIndex.INDEX_MCP]
        val pinkyMcp = imageLandmarks[HandLandmarkIndex.PINKY_MCP]
        return (wrist + indexMcp + pinkyMcp) / 3f
    }

    fun isOpenPalm(): Boolean {
        if (imageLandmarks.size < 21) return false
        val wrist = imageLandmarks[HandLandmarkIndex.WRIST]
        val indexExt = imageLandmarks[HandLandmarkIndex.INDEX_TIP].distanceTo(wrist) >
            imageLandmarks[HandLandmarkIndex.INDEX_PIP].distanceTo(wrist) * 1.15f
        val middleExt = imageLandmarks[HandLandmarkIndex.MIDDLE_TIP].distanceTo(wrist) >
            imageLandmarks[HandLandmarkIndex.MIDDLE_PIP].distanceTo(wrist) * 1.15f
        val ringExt = imageLandmarks[HandLandmarkIndex.RING_TIP].distanceTo(wrist) >
            imageLandmarks[HandLandmarkIndex.RING_PIP].distanceTo(wrist) * 1.15f
        val pinkyExt = imageLandmarks[HandLandmarkIndex.PINKY_TIP].distanceTo(wrist) >
            imageLandmarks[HandLandmarkIndex.PINKY_PIP].distanceTo(wrist) * 1.10f
        return indexExt && middleExt && ringExt && pinkyExt && normalizedPinchRatio() > 0.55f
    }

    fun isTwoFingerScrollPose(): Boolean {
        if (imageLandmarks.size < 21) return false
        val wrist = imageLandmarks[HandLandmarkIndex.WRIST]
        val indexExt = imageLandmarks[HandLandmarkIndex.INDEX_TIP].distanceTo(wrist) >
            imageLandmarks[HandLandmarkIndex.INDEX_PIP].distanceTo(wrist) * 1.15f
        val middleExt = imageLandmarks[HandLandmarkIndex.MIDDLE_TIP].distanceTo(wrist) >
            imageLandmarks[HandLandmarkIndex.MIDDLE_PIP].distanceTo(wrist) * 1.15f
        val ringFolded = imageLandmarks[HandLandmarkIndex.RING_TIP].distanceTo(wrist) <
            imageLandmarks[HandLandmarkIndex.RING_PIP].distanceTo(wrist) * 1.05f
        val pinkyFolded = imageLandmarks[HandLandmarkIndex.PINKY_TIP].distanceTo(wrist) <
            imageLandmarks[HandLandmarkIndex.PINKY_PIP].distanceTo(wrist) * 1.05f
        return indexExt && middleExt && ringFolded && pinkyFolded
    }
}

sealed interface HandGestureEvent {
    data class PinchTap(val side: HandSide, val pinchRatio: Float) : HandGestureEvent
    data class DoublePinchPeek(val side: HandSide) : HandGestureEvent
    data class PinchDragStart(val side: HandSide, val anchorPalm: Vec3, val anchorScale: Float) : HandGestureEvent
    data class PinchDragMove(
        val side: HandSide,
        val deltaAzimuthDeg: Float,
        val deltaElevationDeg: Float,
        val depthDeltaMeters: Float
    ) : HandGestureEvent
    data class PinchDragEnd(val side: HandSide) : HandGestureEvent
    data class TwoHandResize(val scaleRatio: Float, val yawDeltaDeg: Float) : HandGestureEvent
    data class TwoFingerScroll(val side: HandSide, val verticalDelta: Float) : HandGestureEvent
    data class PalmMenuHoldProgress(val progress: Float) : HandGestureEvent
    data object PalmMenuTriggered : HandGestureEvent
    data class GestureCancelled(val reason: String) : HandGestureEvent
}

