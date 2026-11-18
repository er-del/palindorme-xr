package com.example.xr.input

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.xr.hand.HandFrame
import com.example.xr.hand.HandGestureEvent
import com.example.xr.hand.HandRayInteractor
import com.example.xr.model.XRSettings
import com.example.xr.tracking.HeadPoseSnapshot
import com.example.xr.tracking.HeadTrackingEngine
import com.example.xr.tracking.Ray
import com.example.xr.tracking.Vec3
import com.example.xr.windows.DockActionId
import com.example.xr.windows.RaycastTarget
import com.example.xr.windows.SpatialWindowManager
import com.example.xr.windows.VirtualContentHost
import com.example.xr.windows.WindowHitRegion
import com.example.xr.windows.WindowType
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sin

class InputInteractionManager(
    private val context: Context,
    private val headTracking: HeadTrackingEngine,
    private val windowManager: SpatialWindowManager,
    private val contentHost: VirtualContentHost
) {
    val handRayInteractor = HandRayInteractor()

    @Volatile
    var latestSettings: XRSettings = XRSettings()
    @Volatile
    var latestHands: List<HandFrame> = emptyList()
    @Volatile
    var handsVisible: Boolean = false
    @Volatile
    var currentHit: RaycastTarget? = null
        private set
    @Volatile
    var currentRay: Ray? = null
        private set
    @Volatile
    var dwellProgress: Float = 0f
        private set
    @Volatile
    var palmHoldProgress: Float = 0f
        private set
    @Volatile
    var lastPokeWorldPoint: Vec3? = null
        private set

    var onDockAction: ((DockActionId) -> Unit)? = null
    var onPeekToggle: (() -> Unit)? = null
    var onPcRemoteMouse: ((action: String, u: Float, v: Float, button: Int, scroll: Float) -> Unit)? = null
    var onPcRemoteKey: ((keyCode: Int, unicode: Int, action: Int) -> Unit)? = null

    private var dwellTargetKey: String? = null
    private var dwellStartMs: Long = 0L
    private var dwellFiredForTarget: Boolean = false

    private var lockedDragWindowId: String? = null
    private var lockedDragIsResize: Boolean = false
    private var lockedDragIsContentScroll: Boolean = false
    private var dragCumulativeDelta: Float = 0f

    private var scrollInertiaWindowId: String? = null
    private var scrollInertiaVelocity: Float = 0f
    private var lastDirectPokeMs: Long = 0L

    private val feedbackExecutor = Executors.newSingleThreadExecutor()

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    private val clickSamples: ShortArray = ShortArray(720) { i ->
        val env = 1f - (i / 720f)
        (sin(2.0 * Math.PI * 1150.0 * i / 44100.0) * env * 9000.0).toInt().toShort()
    }

    /**
     * Called per GL frame with the predicted head pose.
     * Hand ray & poke transforms strictly use head pose AT THE CAMERA FRAME TIMESTAMP
     * so hands remain rock-solid during head rotation.
     */
    fun onFrameTick(pose: HeadPoseSnapshot) {
        val settings = latestSettings
        val hands = latestHands
        val dominantHand = hands.firstOrNull()
        val handCapturePose = dominantHand?.let {
            if (it.frameTimestampNs > 0L) headTracking.getPoseAtTimestamp(it.frameTimestampNs) else pose
        }

        val ray = handRayInteractor.computeInteractionRay(
            headPose = pose,
            handRayModeEnabled = settings.handControlEnabled && settings.handRayModeEnabled,
            dominantHand = dominantHand,
            dominantHandFramePose = handCapturePose,
            calibratedPalmWidthMeters = settings.handCalibration.palmWidthMeters
        )
        currentRay = ray

        val nowMs = SystemClock.uptimeMillis()
        if (settings.handControlEnabled && dominantHand != null && lockedDragWindowId == null) {
            val refPose = handCapturePose ?: pose
            val pokeHit = handRayInteractor.findDirectPokeHit(
                headPose = refPose,
                hand = dominantHand,
                calibratedPalmWidthMeters = settings.handCalibration.palmWidthMeters,
                windows = windowManager.windows.value
            )
            if (pokeHit != null) {
                lastPokeWorldPoint = handRayInteractor.estimateIndexTipWorld(
                    refPose,
                    dominantHand,
                    settings.handCalibration.palmWidthMeters
                )
                if ((nowMs - lastDirectPokeMs) > 520L) {
                    lastDirectPokeMs = nowMs
                    currentHit = pokeHit
                    triggerSelectionAt(pokeHit)
                    return
                }
            } else {
                lastPokeWorldPoint = null
            }
        } else {
            lastPokeWorldPoint = null
        }

        val hit = windowManager.raycast(ray)
        currentHit = hit

        // Forward hover coordinates to paired PC if user gazes at the PC_DESKTOP window
        if (hit is RaycastTarget.WindowHit && hit.region == WindowHitRegion.CONTENT_BODY) {
            val targetWin = windowManager.windows.value.firstOrNull { it.id == hit.windowId }
            if (targetWin?.type == WindowType.PC_DESKTOP) {
                onPcRemoteMouse?.invoke("move", hit.u, hit.v, 0, 0f)
            }
        }

        if (!lockedDragIsContentScroll && abs(scrollInertiaVelocity) > 0.004f) {
            scrollInertiaWindowId?.let { winId ->
                contentHost.dispatchScroll(winId, scrollInertiaVelocity)
            }
            scrollInertiaVelocity *= 0.88f
        } else if (!lockedDragIsContentScroll) {
            scrollInertiaVelocity = 0f
        }

        val dwellActive = !settings.handControlEnabled ||
            (settings.autoGazeFallbackWhenHandsLost && !handsVisible)

        if (!dwellActive || hit == null) {
            dwellTargetKey = null
            dwellProgress = 0f
            dwellFiredForTarget = false
            return
        }

        val key = when (hit) {
            is RaycastTarget.DockHit -> "dock_${hit.action.name}"
            is RaycastTarget.WindowHit -> when (hit.region) {
                WindowHitRegion.CLOSE_BUTTON,
                WindowHitRegion.MINIMIZE_BUTTON -> "win_${hit.windowId}_${hit.region.name}"
                WindowHitRegion.CONTENT_BODY -> "win_${hit.windowId}_btn_${(hit.u * 6).toInt()}_${(hit.v * 6).toInt()}"
                else -> "none"
            }
        }

        if (key == "none") {
            dwellTargetKey = null
            dwellProgress = 0f
            dwellFiredForTarget = false
            return
        }

        if (key != dwellTargetKey) {
            dwellTargetKey = key
            dwellStartMs = nowMs
            dwellFiredForTarget = false
            dwellProgress = 0f
        } else if (!dwellFiredForTarget) {
            val dwellMs = (settings.gazeDwellSeconds * 1000f).toLong().coerceIn(500L, 2500L)
            val elapsed = nowMs - dwellStartMs
            dwellProgress = (elapsed.toFloat() / dwellMs.toFloat()).coerceIn(0f, 1f)
            if (elapsed >= dwellMs) {
                dwellFiredForTarget = true
                dwellProgress = 0f
                triggerSelectionAt(hit)
            }
        }
    }

    fun handleHandGestureEvents(events: List<HandGestureEvent>) {
        if (!latestSettings.handControlEnabled) return
        for (ev in events) {
            when (ev) {
                is HandGestureEvent.PinchTap -> {
                    currentHit?.let { triggerSelectionAt(it) }
                }
                is HandGestureEvent.DoublePinchPeek -> {
                    playSelectionFeedback()
                    onPeekToggle?.invoke()
                }
                is HandGestureEvent.PinchDragStart -> {
                    when (val cur = currentHit) {
                        is RaycastTarget.DockHit -> {
                            triggerSelectionAt(cur)
                        }
                        is RaycastTarget.WindowHit -> {
                            lockedDragWindowId = cur.windowId
                            dragCumulativeDelta = 0f
                            windowManager.focusWindow(cur.windowId)
                            when (cur.region) {
                                WindowHitRegion.TITLE_BAR,
                                WindowHitRegion.GRABBER_BAR -> {
                                    lockedDragIsResize = false
                                    lockedDragIsContentScroll = false
                                    windowManager.snapshotForDrag(cur.windowId)
                                }
                                WindowHitRegion.RESIZE_HANDLE -> {
                                    lockedDragIsResize = true
                                    lockedDragIsContentScroll = false
                                    windowManager.snapshotForDrag(cur.windowId)
                                }
                                else -> {
                                    lockedDragIsResize = false
                                    lockedDragIsContentScroll = true
                                    scrollInertiaWindowId = cur.windowId
                                    scrollInertiaVelocity = 0f
                                    contentHost.dispatchTouch(cur.windowId, cur.u, cur.v, MotionEvent.ACTION_DOWN)
                                }
                            }
                        }
                        null -> Unit
                    }
                }
                is HandGestureEvent.PinchDragMove -> {
                    val winId = lockedDragWindowId ?: continue
                    dragCumulativeDelta += abs(ev.deltaAzimuthDeg) + abs(ev.deltaElevationDeg)
                    if (lockedDragIsResize) {
                        val scale = (1f + ev.deltaAzimuthDeg * 0.008f).coerceIn(0.92f, 1.08f)
                        windowManager.resizeWindow(winId, scale)
                    } else if (lockedDragIsContentScroll) {
                        val dy = -ev.deltaElevationDeg * 0.045f
                        contentHost.dispatchScroll(winId, dy)
                        scrollInertiaWindowId = winId
                        scrollInertiaVelocity = dy * 0.65f
                    } else {
                        windowManager.moveWindow(winId, ev.deltaAzimuthDeg, ev.deltaElevationDeg, ev.depthDeltaMeters)
                    }
                }
                is HandGestureEvent.PinchDragEnd -> {
                    val winId = lockedDragWindowId
                    if (winId != null) {
                        if (lockedDragIsContentScroll) {
                            val hit = currentHit as? RaycastTarget.WindowHit
                            if (dragCumulativeDelta < 2.2f && hit != null) {
                                triggerSelectionAt(hit)
                            }
                        } else {
                            windowManager.commitDrag(winId)
                        }
                    }
                    lockedDragWindowId = null
                    lockedDragIsResize = false
                    lockedDragIsContentScroll = false
                }
                is HandGestureEvent.TwoHandResize -> {
                    val targetId = lockedDragWindowId ?: windowManager.focusedWindowId.value ?: continue
                    windowManager.resizeWindow(targetId, ev.scaleRatio, ev.yawDeltaDeg)
                }
                is HandGestureEvent.TwoFingerScroll -> {
                    val hitWin = currentHit as? RaycastTarget.WindowHit
                    val winId = hitWin?.windowId ?: windowManager.focusedWindowId.value ?: continue
                    val targetWin = windowManager.windows.value.firstOrNull { it.id == winId }
                    if (targetWin?.type == WindowType.PC_DESKTOP && hitWin != null) {
                        onPcRemoteMouse?.invoke("scroll", hitWin.u, hitWin.v, 0, ev.verticalDelta)
                    } else {
                        contentHost.dispatchScroll(winId, ev.verticalDelta)
                        scrollInertiaWindowId = winId
                        scrollInertiaVelocity = ev.verticalDelta * 0.55f
                    }
                }
                is HandGestureEvent.PalmMenuHoldProgress -> {
                    palmHoldProgress = ev.progress
                }
                is HandGestureEvent.PalmMenuTriggered -> {
                    palmHoldProgress = 0f
                    playSelectionFeedback()
                    onDockAction?.invoke(DockActionId.CONTROL_CENTER)
                }
                is HandGestureEvent.GestureCancelled -> {
                    val winId = lockedDragWindowId
                    if (winId != null && !lockedDragIsContentScroll) {
                        windowManager.revertDrag(winId)
                    }
                    lockedDragWindowId = null
                    lockedDragIsResize = false
                    lockedDragIsContentScroll = false
                    scrollInertiaVelocity = 0f
                }
            }
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Forward hardware keyboard keys if PC_DESKTOP window is focused
        val focusedId = windowManager.focusedWindowId.value
        val focusedWin = windowManager.windows.value.firstOrNull { it.id == focusedId }
        if (focusedWin?.type == WindowType.PC_DESKTOP &&
            (event.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
            event.keyCode != KeyEvent.KEYCODE_BACK
        ) {
            onPcRemoteKey?.invoke(event.keyCode, event.unicodeChar, event.action)
        }

        val swapSides = latestSettings.leftHandedController
        val clickerAllowed = latestSettings.bluetoothClickerEnabled
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                currentHit?.let {
                    triggerSelectionAt(it)
                    true
                } ?: false
            }
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_SPACE -> {
                if (!clickerAllowed) return false
                currentHit?.let {
                    triggerSelectionAt(it)
                    true
                } ?: false
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!clickerAllowed) return false
                headTracking.recenter()
                playSelectionFeedback()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BACK -> {
                windowManager.focusedWindowId.value?.let { windowManager.minimizeWindow(it) }
                playSelectionFeedback()
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                windowManager.cycleFocus(forward = swapSides)
                playSelectionFeedback()
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                windowManager.cycleFocus(forward = !swapSides)
                playSelectionFeedback()
                true
            }
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                headTracking.recenter()
                playSelectionFeedback()
                true
            }
            else -> false
        }
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val hit = currentHit as? RaycastTarget.WindowHit
                    val winId = hit?.windowId ?: windowManager.focusedWindowId.value
                    if (winId != null) {
                        val win = windowManager.windows.value.firstOrNull { it.id == winId }
                        if (win?.type == WindowType.PC_DESKTOP && hit != null) {
                            onPcRemoteMouse?.invoke("scroll", hit.u, hit.v, 0, vScroll)
                        } else {
                            contentHost.dispatchScroll(winId, -vScroll * 0.25f)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    val dx = event.getAxisValue(MotionEvent.AXIS_X)
                    val dy = event.getAxisValue(MotionEvent.AXIS_Y)
                    headTracking.applyManualLookDelta(-dx * 0.02f, -dy * 0.02f)
                    return true
                }
            }
        }

        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
        val swapSides = latestSettings.leftHandedController
        val lookX = event.getAxisValue(if (swapSides) MotionEvent.AXIS_X else MotionEvent.AXIS_Z)
        val lookY = event.getAxisValue(if (swapSides) MotionEvent.AXIS_Y else MotionEvent.AXIS_RZ)
        val scrollY = event.getAxisValue(if (swapSides) MotionEvent.AXIS_RZ else MotionEvent.AXIS_Y)

        if (abs(lookX) > 0.15f || abs(lookY) > 0.15f) {
            headTracking.applyManualLookDelta(lookX * 2.4f, -lookY * 1.8f)
        }
        if (abs(scrollY) > 0.2f) {
            val winId = (currentHit as? RaycastTarget.WindowHit)?.windowId
                ?: windowManager.focusedWindowId.value
            if (winId != null) {
                contentHost.dispatchScroll(winId, scrollY * 0.25f)
            }
        }
        return true
    }

    fun triggerInstantTap() {
        currentHit?.let { triggerSelectionAt(it) }
    }

    private fun triggerSelectionAt(hit: RaycastTarget) {
        playSelectionFeedback()
        when (hit) {
            is RaycastTarget.DockHit -> {
                onDockAction?.invoke(hit.action)
            }
            is RaycastTarget.WindowHit -> {
                windowManager.focusWindow(hit.windowId)
                val targetWin = windowManager.windows.value.firstOrNull { it.id == hit.windowId }
                when (hit.region) {
                    WindowHitRegion.CLOSE_BUTTON -> windowManager.closeWindow(hit.windowId)
                    WindowHitRegion.MINIMIZE_BUTTON -> windowManager.minimizeWindow(hit.windowId)
                    WindowHitRegion.TITLE_BAR -> windowManager.openOrFocusWindow(WindowType.CONTROL_CENTER)
                    WindowHitRegion.GRABBER_BAR -> windowManager.recenterWindowsToFront()
                    WindowHitRegion.RESIZE_HANDLE -> windowManager.resizeWindow(hit.windowId, 1.12f)
                    WindowHitRegion.CONTENT_BODY -> {
                        if (targetWin?.type == WindowType.PC_DESKTOP) {
                            onPcRemoteMouse?.invoke("click", hit.u, hit.v, 1, 0f)
                        } else {
                            contentHost.dispatchTouch(hit.windowId, hit.u, hit.v, MotionEvent.ACTION_DOWN)
                            contentHost.dispatchTouch(hit.windowId, hit.u, hit.v, MotionEvent.ACTION_UP)
                        }
                    }
                }
            }
        }
    }

    fun playSelectionFeedback() {
        val settings = latestSettings
        if (!settings.hapticFeedbackEnabled && !settings.audioClickEnabled) return
        feedbackExecutor.execute {
            if (settings.hapticFeedbackEnabled) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(18L)
                    }
                }
            }
            if (settings.audioClickEnabled) {
                runCatching {
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(44100)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(clickSamples.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    track.write(clickSamples, 0, clickSamples.size)
                    track.play()
                }
            }
        }
    }
}

