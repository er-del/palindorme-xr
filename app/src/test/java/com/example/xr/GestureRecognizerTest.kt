package com.example.xr

import com.example.xr.hand.GestureRecognizer
import com.example.xr.hand.HandFrame
import com.example.xr.hand.HandGestureEvent
import com.example.xr.hand.HandLandmarkIndex
import com.example.xr.hand.HandSide
import com.example.xr.tracking.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureRecognizerTest {

    private fun makeHandFrame(
        side: HandSide = HandSide.RIGHT,
        pinchDistance: Float = 0.09f,
        palmX: Float = 0.5f,
        palmY: Float = 0.6f,
        palmScale: Float = 1.0f,
        openPalm: Boolean = false,
        isMirrored: Boolean = false,
        confidence: Float = 0.9f,
        timestampMs: Long = 0L
    ): HandFrame {
        val pts = MutableList(21) { Vec3(palmX, palmY, 0f) }
        val halfSpan = 0.06f * palmScale
        pts[HandLandmarkIndex.WRIST] = Vec3(palmX, palmY + 0.10f * palmScale, 0f)
        pts[HandLandmarkIndex.INDEX_MCP] = Vec3(palmX - halfSpan, palmY, 0f)
        pts[HandLandmarkIndex.PINKY_MCP] = Vec3(palmX + halfSpan, palmY, 0f)

        pts[HandLandmarkIndex.THUMB_TIP] = Vec3(palmX - pinchDistance * 0.5f, palmY - 0.05f * palmScale, 0f)
        pts[HandLandmarkIndex.INDEX_PIP] = Vec3(palmX - 0.02f, palmY - 0.06f * palmScale, 0f)
        pts[HandLandmarkIndex.INDEX_TIP] = Vec3(palmX + pinchDistance * 0.5f, palmY - 0.05f * palmScale, 0f)

        if (openPalm) {
            pts[HandLandmarkIndex.INDEX_TIP] = Vec3(palmX - 0.05f, palmY - 0.14f * palmScale, 0f)
            pts[HandLandmarkIndex.MIDDLE_PIP] = Vec3(palmX, palmY - 0.06f * palmScale, 0f)
            pts[HandLandmarkIndex.MIDDLE_TIP] = Vec3(palmX, palmY - 0.15f * palmScale, 0f)
            pts[HandLandmarkIndex.RING_PIP] = Vec3(palmX + 0.03f, palmY - 0.06f * palmScale, 0f)
            pts[HandLandmarkIndex.RING_TIP] = Vec3(palmX + 0.03f, palmY - 0.14f * palmScale, 0f)
            pts[HandLandmarkIndex.PINKY_PIP] = Vec3(palmX + 0.06f, palmY - 0.05f * palmScale, 0f)
            pts[HandLandmarkIndex.PINKY_TIP] = Vec3(palmX + 0.06f, palmY - 0.12f * palmScale, 0f)
        }
        return HandFrame(
            side = side,
            confidence = confidence,
            imageLandmarks = pts,
            worldLandmarks = pts,
            timestampMs = timestampMs,
            isMirrored = isMirrored
        )
    }

    @Test
    fun lookPlusPinchTap_firesOnReleaseAfterDebounce() {
        val recognizer = GestureRecognizer()
        var t = 1000L
        val events = mutableListOf<HandGestureEvent>()

        // 3 closed frames (< 0.25 normalized pinch ratio)
        repeat(3) {
            events += recognizer.processFrame(listOf(makeHandFrame(pinchDistance = 0.01f, timestampMs = t)), t)
            t += 33L
        }
        assertTrue("Tap should fire on release, not press", events.none { it is HandGestureEvent.PinchTap })

        // 2 open frames (> 0.45 ratio) within < 400ms
        repeat(2) {
            events += recognizer.processFrame(listOf(makeHandFrame(pinchDistance = 0.09f, timestampMs = t)), t)
            t += 33L
        }
        assertTrue(events.any { it is HandGestureEvent.PinchTap })
    }

    @Test
    fun pinchHold_startsAndUpdatesDrag_andCancelsOnOpenPalm() {
        val recognizer = GestureRecognizer()
        var t = 1000L
        val events = mutableListOf<HandGestureEvent>()

        // Close pinch and hold past 400ms + debounce so drag starts and moves
        repeat(20) {
            events += recognizer.processFrame(listOf(makeHandFrame(pinchDistance = 0.01f, palmX = 0.5f + it * 0.005f, timestampMs = t)), t)
            t += 33L
        }
        assertTrue(events.any { it is HandGestureEvent.PinchDragStart })
        assertTrue(events.any { it is HandGestureEvent.PinchDragMove })

        // Show open palm -> cancels drag
        val cancelEvents = recognizer.processFrame(listOf(makeHandFrame(openPalm = true, timestampMs = t)), t)
        assertTrue(cancelEvents.any { it is HandGestureEvent.GestureCancelled })
    }

    @Test
    fun twoHandPinch_emitsTwoHandResize() {
        val recognizer = GestureRecognizer()
        var t = 1000L
        val events = mutableListOf<HandGestureEvent>()

        // Both hands pinch and hold past 400ms while spreading apart
        for (i in 0 until 18) {
            val spread = i * 0.01f
            val left = makeHandFrame(HandSide.LEFT, pinchDistance = 0.01f, palmX = 0.35f - spread, timestampMs = t)
            val right = makeHandFrame(HandSide.RIGHT, pinchDistance = 0.01f, palmX = 0.65f + spread, timestampMs = t)
            events += recognizer.processFrame(listOf(left, right), t)
            t += 33L
        }
        assertTrue(events.any { it is HandGestureEvent.TwoHandResize && it.scaleRatio > 1.0f })
    }

    @Test
    fun openPalmHold_triggersControlCenter() {
        val recognizer = GestureRecognizer()
        var t = 1000L
        val events = mutableListOf<HandGestureEvent>()
        repeat(22) {
            events += recognizer.processFrame(listOf(makeHandFrame(openPalm = true, timestampMs = t)), t)
            t += 33L
        }
        assertTrue(events.any { it is HandGestureEvent.PalmMenuTriggered })
    }

    @Test
    fun mirroredHandednessConvention_normalizesCorrectly() {
        val recognizer = GestureRecognizer()
        assertEquals(HandSide.LEFT, recognizer.normalizeSide(HandSide.RIGHT, isMirroredImage = true))
        assertEquals(HandSide.RIGHT, recognizer.normalizeSide(HandSide.RIGHT, isMirroredImage = false))
    }

    @Test
    fun restingHandsFiveMinuteReplay_producesZeroFalsePinches() {
        val recognizer = GestureRecognizer()
        var t = 0L
        val falsePinches = mutableListOf<HandGestureEvent>()
        // 5 minutes at 30fps = 9000 frames of open / conversational hand motion
        for (i in 0 until 9000) {
            val sway = 0.08f + 0.02f * kotlin.math.sin(i * 0.1f)
            val ev = recognizer.processFrame(listOf(makeHandFrame(pinchDistance = sway, timestampMs = t)), t)
            falsePinches += ev.filterIsInstance<HandGestureEvent.PinchTap>()
            t += 33L
        }
        assertEquals(0, falsePinches.size)
    }

    @Test
    fun hundredScriptedLookPlusPinchTrials_exceed95PercentAccuracy() {
        val recognizer = GestureRecognizer()
        var t = 1000L
        var hits = 0
        val trials = 100

        for (trial in 0 until trials) {
            recognizer.reset()
            val side = if (trial % 2 == 0) HandSide.RIGHT else HandSide.LEFT
            val px = 0.35f + (trial % 5) * 0.07f
            val py = 0.45f + (trial % 3) * 0.06f
            var fired = false

            // 4 closed frames (debounce = 3)
            repeat(4) {
                val ev = recognizer.processFrame(
                    listOf(makeHandFrame(side = side, pinchDistance = 0.012f, palmX = px, palmY = py, timestampMs = t)),
                    t
                )
                if (ev.any { it is HandGestureEvent.PinchTap }) fired = true
                t += 30L
            }
            // 3 open release frames within < 400ms
            repeat(3) {
                val ev = recognizer.processFrame(
                    listOf(makeHandFrame(side = side, pinchDistance = 0.09f, palmX = px, palmY = py, timestampMs = t)),
                    t
                )
                if (ev.any { it is HandGestureEvent.PinchTap && it.side == side }) fired = true
                t += 30L
            }
            if (fired) hits++
            t += 120L
        }
        assertTrue("Expected >= 95% pinch tap accuracy across 100 trials, got $hits/100", hits >= 95)
    }

    @Test
    fun twoFingerScrollPose_emitsScrollEvents() {
        val recognizer = GestureRecognizer()
        var t = 500L
        val events = mutableListOf<HandGestureEvent>()

        for (i in 0 until 8) {
            val pts = MutableList(21) { Vec3(0.5f, 0.5f, 0f) }
            pts[HandLandmarkIndex.WRIST] = Vec3(0.5f, 0.75f, 0f)
            pts[HandLandmarkIndex.INDEX_MCP] = Vec3(0.44f, 0.55f, 0f)
            pts[HandLandmarkIndex.PINKY_MCP] = Vec3(0.56f, 0.55f, 0f)
            pts[HandLandmarkIndex.THUMB_TIP] = Vec3(0.36f, 0.58f, 0f)
            val dy = i * 0.02f
            pts[HandLandmarkIndex.INDEX_PIP] = Vec3(0.45f, 0.48f - dy, 0f)
            pts[HandLandmarkIndex.INDEX_TIP] = Vec3(0.45f, 0.34f - dy, 0f)
            pts[HandLandmarkIndex.MIDDLE_PIP] = Vec3(0.50f, 0.48f - dy, 0f)
            pts[HandLandmarkIndex.MIDDLE_TIP] = Vec3(0.50f, 0.33f - dy, 0f)
            pts[HandLandmarkIndex.RING_PIP] = Vec3(0.54f, 0.52f, 0f)
            pts[HandLandmarkIndex.RING_TIP] = Vec3(0.54f, 0.62f, 0f)
            pts[HandLandmarkIndex.PINKY_PIP] = Vec3(0.58f, 0.54f, 0f)
            pts[HandLandmarkIndex.PINKY_TIP] = Vec3(0.58f, 0.64f, 0f)

            val frame = HandFrame(HandSide.RIGHT, 0.92f, pts, pts, t)
            events += recognizer.processFrame(listOf(frame), t)
            t += 33L
        }
        assertTrue("Expected TwoFingerScroll events", events.any { it is HandGestureEvent.TwoFingerScroll })
    }
}
