package com.example.xr.hand

import com.example.xr.model.HandCalibrationProfile
import com.example.xr.model.HandednessPreference
import com.example.xr.tracking.Vec3
import kotlin.math.atan2

/**
 * Pure Kotlin state machine for camera-based hand gestures.
 * Uses 3D pinch ratio with hysteresis, 2-frame debounce, tunable confidence gate,
 * tap vs hold-drag, double-pinch peek, push/pull depth, two-hand resize,
 * two-finger scroll, palm-open Control Center hold, handedness normalization, and dropout cancel.
 */
class GestureRecognizer(
    var minConfidence: Float = 0.45f,
    private val debounceFrames: Int = 2,
    private val tapMaxDurationMs: Long = 400L,
    private val palmHoldDurationMs: Long = 600L,
    private val doublePinchWindowMs: Long = 450L
) {
    private enum class PinchPhase { OPEN, CLOSED_TAP_PENDING, DRAGGING }

    private class PerHandState {
        var phase: PinchPhase = PinchPhase.OPEN
        var closeStreak: Int = 0
        var openStreak: Int = 0
        var pinchStartMs: Long = 0L
        var lastTapMs: Long = 0L
        var lastSeenMs: Long = 0L
        var lastPalm: Vec3 = Vec3.ZERO
        var lastPalmWidth: Float = 0.1f
        var lastScrollY: Float? = null
        var scrollStreak: Int = 0
    }

    private val handStates = mutableMapOf(
        HandSide.LEFT to PerHandState(),
        HandSide.RIGHT to PerHandState()
    )

    private var palmMenuStartMs: Long? = null
    private var palmMenuFiredForHold: Boolean = false
    private var twoHandActive: Boolean = false
    private var prevTwoHandDistance: Float = 0f
    private var prevTwoHandAngleRad: Float = 0f

    var calibration: HandCalibrationProfile = HandCalibrationProfile()
    var handedness: HandednessPreference = HandednessPreference.BOTH
    var relaxedSensitivity: Boolean = false
    var gestureSizeGain: Float = 1.0f

    fun reset() {
        handStates.values.forEach { state ->
            state.phase = PinchPhase.OPEN
            state.closeStreak = 0
            state.openStreak = 0
            state.pinchStartMs = 0L
            state.lastTapMs = 0L
            state.lastSeenMs = 0L
            state.lastScrollY = null
            state.scrollStreak = 0
        }
        palmMenuStartMs = null
        palmMenuFiredForHold = false
        twoHandActive = false
    }

    fun normalizeSide(reportedSide: HandSide, isMirroredImage: Boolean): HandSide {
        return if (!isMirroredImage) reportedSide
        else if (reportedSide == HandSide.LEFT) HandSide.RIGHT else HandSide.LEFT
    }

    fun processFrame(hands: List<HandFrame>, timestampMs: Long): List<HandGestureEvent> {
        val events = mutableListOf<HandGestureEvent>()
        val filteredHands = hands
            .filter { it.confidence >= minConfidence && it.imageLandmarks.size >= 21 }
            .map { frame ->
                val trueSide = normalizeSide(frame.side, frame.isMirrored)
                if (trueSide != frame.side) frame.copy(side = trueSide) else frame
            }
            .filter { frame ->
                when (handedness) {
                    HandednessPreference.RIGHT -> frame.side == HandSide.RIGHT
                    HandednessPreference.LEFT -> frame.side == HandSide.LEFT
                    HandednessPreference.BOTH -> true
                }
            }

        val visibleSides = filteredHands.map { it.side }.toSet()

        // Dropout cancellation if a hand disappears mid-drag past the dropout hold window
        for ((side, state) in handStates) {
            if (side !in visibleSides && state.phase != PinchPhase.OPEN) {
                if (state.lastSeenMs > 0L && (timestampMs - state.lastSeenMs) > 350L) {
                    if (state.phase == PinchPhase.DRAGGING) {
                        events += HandGestureEvent.GestureCancelled("Hand left camera view")
                    }
                    state.phase = PinchPhase.OPEN
                    state.closeStreak = 0
                    state.openStreak = 0
                }
            }
        }

        val closeThresh = if (relaxedSensitivity) {
            (calibration.pinchClosedThreshold + 0.08f).coerceAtMost(0.45f)
        } else {
            calibration.pinchClosedThreshold
        }
        val openThresh = if (relaxedSensitivity) {
            (calibration.pinchOpenThreshold + 0.05f).coerceAtLeast(closeThresh + 0.12f)
        } else {
            calibration.pinchOpenThreshold
        }

        val gain = gestureSizeGain.coerceIn(0.6f, 1.8f)

        for (hand in filteredHands) {
            val state = handStates.getValue(hand.side)
            state.lastSeenMs = timestampMs
            val ratio = hand.normalizedPinchRatio()
            val palm = hand.palmCenterImage()
            val palmWidth = hand.palmWidthImage().coerceAtLeast(0.02f)

            if (state.phase == PinchPhase.DRAGGING && hand.isOpenPalm()) {
                state.phase = PinchPhase.OPEN
                state.closeStreak = 0
                events += HandGestureEvent.GestureCancelled("Open palm cancel gesture")
                continue
            }

            when (state.phase) {
                PinchPhase.OPEN -> {
                    if (ratio < closeThresh) {
                        state.closeStreak++
                        state.openStreak = 0
                        if (state.closeStreak >= debounceFrames) {
                            state.phase = PinchPhase.CLOSED_TAP_PENDING
                            state.pinchStartMs = timestampMs
                            state.lastPalm = palm
                            state.lastPalmWidth = palmWidth
                            state.closeStreak = 0
                        }
                    } else {
                        state.closeStreak = 0
                        if (hand.isTwoFingerScrollPose()) {
                            state.scrollStreak++
                            if (state.scrollStreak >= debounceFrames) {
                                val tipY = hand.imageLandmarks[HandLandmarkIndex.INDEX_TIP].y
                                val prevY = state.lastScrollY
                                if (prevY != null) {
                                    val delta = (tipY - prevY)
                                    if (kotlin.math.abs(delta) > 0.004f) {
                                        events += HandGestureEvent.TwoFingerScroll(hand.side, delta * 3.0f * gain)
                                    }
                                }
                                state.lastScrollY = tipY
                            }
                        } else {
                            state.scrollStreak = 0
                            state.lastScrollY = null
                        }
                    }
                }
                PinchPhase.CLOSED_TAP_PENDING -> {
                    if (ratio > openThresh) {
                        state.openStreak++
                        if (state.openStreak >= 2) {
                            state.phase = PinchPhase.OPEN
                            state.openStreak = 0
                            events += HandGestureEvent.PinchTap(hand.side, ratio)
                            if (state.lastTapMs > 0L && (timestampMs - state.lastTapMs) in 80L..doublePinchWindowMs) {
                                events += HandGestureEvent.DoublePinchPeek(hand.side)
                                state.lastTapMs = 0L
                            } else {
                                state.lastTapMs = timestampMs
                            }
                        }
                    } else {
                        state.openStreak = 0
                        if (timestampMs - state.pinchStartMs >= tapMaxDurationMs) {
                            state.phase = PinchPhase.DRAGGING
                            state.lastPalm = palm
                            state.lastPalmWidth = palmWidth
                            events += HandGestureEvent.PinchDragStart(hand.side, palm, palmWidth)
                        }
                    }
                }
                PinchPhase.DRAGGING -> {
                    if (ratio > openThresh) {
                        state.openStreak++
                        if (state.openStreak >= 2) {
                            state.phase = PinchPhase.OPEN
                            state.openStreak = 0
                            events += HandGestureEvent.PinchDragEnd(hand.side)
                        }
                    } else {
                        state.openStreak = 0
                        val dx = palm.x - state.lastPalm.x
                        val dy = palm.y - state.lastPalm.y
                        val scaleRatio = palmWidth / state.lastPalmWidth
                        val depthDelta = (1f - scaleRatio).coerceIn(-0.15f, 0.15f) * 2.5f * gain
                        state.lastPalm = palm
                        state.lastPalmWidth = palmWidth
                        if (!twoHandActive) {
                            events += HandGestureEvent.PinchDragMove(
                                side = hand.side,
                                deltaAzimuthDeg = -dx * 90f * gain,
                                deltaElevationDeg = -dy * 65f * gain,
                                depthDeltaMeters = depthDelta
                            )
                        }
                    }
                }
            }
        }

        // Two-hand pinch resize + yaw rotate
        val leftDragging = handStates.getValue(HandSide.LEFT).phase == PinchPhase.DRAGGING
        val rightDragging = handStates.getValue(HandSide.RIGHT).phase == PinchPhase.DRAGGING
        if (leftDragging && rightDragging && filteredHands.size >= 2) {
            val leftPalm = filteredHands.firstOrNull { it.side == HandSide.LEFT }?.palmCenterImage()
            val rightPalm = filteredHands.firstOrNull { it.side == HandSide.RIGHT }?.palmCenterImage()
            if (leftPalm != null && rightPalm != null) {
                val dist = leftPalm.distanceTo(rightPalm).coerceAtLeast(0.05f)
                val angle = atan2(rightPalm.y - leftPalm.y, rightPalm.x - leftPalm.x)
                if (!twoHandActive) {
                    twoHandActive = true
                    prevTwoHandDistance = dist
                    prevTwoHandAngleRad = angle
                } else {
                    val scaleRatio = (dist / prevTwoHandDistance).coerceIn(0.88f, 1.12f)
                    val yawDelta = Math.toDegrees((angle - prevTwoHandAngleRad).toDouble()).toFloat()
                    prevTwoHandDistance = dist
                    prevTwoHandAngleRad = angle
                    events += HandGestureEvent.TwoHandResize(scaleRatio, yawDelta)
                }
            }
        } else {
            twoHandActive = false
        }

        // Open palm hold toward headset -> Control Center (only when no hand is pinching)
        val anyPinching = handStates.values.any { it.phase != PinchPhase.OPEN }
        val openPalmHand = if (!anyPinching) filteredHands.firstOrNull { it.isOpenPalm() } else null
        if (openPalmHand != null) {
            val start = palmMenuStartMs ?: timestampMs.also { palmMenuStartMs = it }
            val elapsed = timestampMs - start
            val progress = (elapsed.toFloat() / palmHoldDurationMs.toFloat()).coerceIn(0f, 1f)
            events += HandGestureEvent.PalmMenuHoldProgress(progress)
            if (elapsed >= palmHoldDurationMs && !palmMenuFiredForHold) {
                palmMenuFiredForHold = true
                events += HandGestureEvent.PalmMenuTriggered
            }
        } else {
            if (palmMenuStartMs != null) {
                events += HandGestureEvent.PalmMenuHoldProgress(0f)
            }
            palmMenuStartMs = null
            palmMenuFiredForHold = false
        }

        return events
    }
}
