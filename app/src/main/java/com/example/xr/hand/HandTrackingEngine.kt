package com.example.xr.hand

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageProxy
import com.example.xr.tracking.ClockDomainConverter
import com.example.xr.tracking.Vec3
import com.example.xr.tracking.Vec3OneEuroFilter
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed interface HandTrackingStatus {
    data object Disabled : HandTrackingStatus
    data object Initializing : HandTrackingStatus
    data class Active(val delegate: String, val handsDetected: Int, val meanLuminance: Float) : HandTrackingStatus
    data class IdleWakeProbe(val meanLuminance: Float) : HandTrackingStatus
    data class Unavailable(val reason: String) : HandTrackingStatus
}

data class HandTrackingTelemetry(
    val status: HandTrackingStatus = HandTrackingStatus.Disabled,
    val delegateName: String = "CPU",
    val delegateLoadMs: Float = 0f,
    val delegateBenchmarkNote: String = "Pending",
    val inferenceLatencyMs: Float = 0f,
    val inferenceAvgMs: Float = 0f,
    val inferenceP95Ms: Float = 0f,
    val endToEndLatencyMs: Float = 0f,
    val effectiveFps: Float = 0f,
    val meanLuminance: Float = 128f,
    val luminanceVariance: Float = 40f,
    val lowLightWarning: Boolean = false,
    val cameraOccludedWarning: Boolean = false,
    val noHandEverFound: Boolean = true,
    val handsLostDurationMs: Long = 0L,
    val continuousHandRaisedMs: Long = 0L,
    val showRestHint: Boolean = false,
    val handsVisible: Boolean = false,
    val isDropoutHoldActive: Boolean = false,
    val modelBundled: Boolean = false,
    val activeCameraLabel: String = "Default Rear",
    val activeCameraHfovDeg: Float = 70f,
    val minConfidence: Float = 0.45f,
    val lastErrorDetail: String? = null,
    val diagnosticAdvice: String = "Hold open hand 25–60 cm in front of the rear lens",
    val traceRecording: Boolean = false,
    val traceFrameCount: Int = 0,
    val lastTraceFilePath: String? = null,
    val camSensorOrientationDeg: Int = 90,
    val camDisplayRotationDeg: Int = 90,
    val camRotationDegrees: Int = 0,
    val camMirror: Boolean = false,
    val camUserOffsetDeg: Int = 0,
    val camFinalRotationDeg: Int = 0
)

/**
 * On-device MediaPipe Hand Landmarker engine running in LIVE_STREAM async mode on a dedicated HandlerThread.
 * Benchmarks GPU vs CPU delegates on first run, caches the fastest working delegate,
 * uses a 3-slot zero-GC RGBA pixel buffer ring, aligns timestamps to elapsedRealtimeNanos,
 * and adds One-Euro landmark smoothing + 200ms dropout hold + landmarks-only trace recording.
 */
class HandTrackingEngine(private val context: Context) {

    companion object {
        private const val TAG = "HandTrackingEngine"
        const val MODEL_ASSET_NAME = "hand_landmarker.task"
        private const val PREFS_NAME = "palindorme_hand_prefs"
        private const val KEY_CACHED_DELEGATE = "cached_delegate"
        private const val KEY_CACHED_BENCH_NOTE = "cached_bench_note"
        private const val IDLE_THRESHOLD_MS = 10_000L
        private const val WAKE_PROBE_INTERVAL_MS = 200L
        private const val REST_HINT_THRESHOLD_MS = 60_000L
        private const val DROPOUT_HOLD_MS = 200L
    }

    private data class PendingFrameMeta(
        val frameTimestampNs: Long,
        val submitNs: Long,
        val meanLuminance: Float,
        val luminanceVariance: Float,
        val isIdle: Boolean,
        val isMirrored: Boolean,
        val hfovDeg: Float,
        val vfovDeg: Float,
        val debugPreviewCopy: Bitmap?
    )

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var landmarker: HandLandmarker? = null

    @Volatile
    private var landmarkerReady: Boolean = false
    @Volatile
    private var gpuDelegateFailed: Boolean = false
    private var activeDelegateName = "CPU"
    private var lastLoadMs = 0f
    private var benchmarkNote = "Not benchmarked yet"
    private var lastErrorDetail: String? = null

    private val isProcessing = AtomicBoolean(false)
    private val latestHands = AtomicReference<List<HandFrame>>(emptyList())
    private val latestDebugBitmap = AtomicReference<Bitmap?>(null)
    private val pendingMeta = ConcurrentHashMap<Long, PendingFrameMeta>()

    private val _status = MutableStateFlow<HandTrackingStatus>(HandTrackingStatus.Disabled)
    val status: StateFlow<HandTrackingStatus> = _status.asStateFlow()

    private val _telemetry = MutableStateFlow(HandTrackingTelemetry())
    val telemetry: StateFlow<HandTrackingTelemetry> = _telemetry.asStateFlow()

    private var lastHandDetectedMs: Long = 0L
    private var hasEverSeenHand: Boolean = false
    private var reacquireStreak: Int = 0
    private var lastValidHands: List<HandFrame> = emptyList()
    private var continuousRaisedStartMs: Long = 0L
    private var restHintDismissed: Boolean = false
    private var lastFrameProcessedMs: Long = 0L
    private var lastLiveStreamTimestampMs: Long = 0L
    private var maxTargetFps: Int = 30
    private var maxNumHands: Int = 2
    private var enabled: Boolean = false

    @Volatile
    var minConfidence: Float = 0.45f
        set(value) {
            field = value.coerceIn(0.25f, 0.85f)
            gestureRecognizer.minConfidence = field
            _telemetry.value = _telemetry.value.copy(minConfidence = field)
        }

    @Volatile
    var userRotationDegrees: Int = 0
    @Volatile
    var mirrorHorizontal: Boolean = false
    @Volatile
    var debugPreviewActive: Boolean = false
    @Volatile
    var cameraHfovDeg: Float = 70f
    @Volatile
    var cameraVfovDeg: Float = 55f
    @Volatile
    var activeCameraLabel: String = "Default Rear"
    @Volatile
    var activeSensorOrientationDeg: Int = 90
    @Volatile
    var activeLensFacingBack: Boolean = true
    @Volatile
    var currentDisplayRotation: Int = Surface.ROTATION_90
    @Volatile
    private var lastReportedBaseRotDeg: Int = 0
    @Volatile
    private var lastFinalRotDeg: Int = 0
    @Volatile
    private var lastMirrorApplied: Boolean = false
    @Volatile
    private var lastLoggedOrientSig: String? = null
    @Volatile
    private var lastDiagLogMs: Long = 0L
    @Volatile
    private var lastLoggedHandCount: Int = -1

    // 3-slot ping-pong pixel & Bitmap buffers to prevent per-frame GC and async read races
    private var rgbaScratch = IntArray(0)
    private val bitmapRing = arrayOfNulls<Bitmap>(3)
    private var ringCursor = 0
    private var lastDebugSnapMs = 0L

    // Inference latency sliding window for avg & p95
    private val infHistoryMs = FloatArray(40)
    private var infCursor = 0
    private var infCount = 0
    private val fpsCounter = AtomicInteger(0)
    private var fpsWindowStartMs = 0L
    private var measuredFps = 0f

    // Adaptive One-Euro filters per hand (21 landmarks * 2 hands)
    private val leftFilters = Array(21) { Vec3OneEuroFilter(minCutoff = 4.2f, beta = 0.45f) }
    private val rightFilters = Array(21) { Vec3OneEuroFilter(minCutoff = 4.2f, beta = 0.45f) }

    val traceRecorder = HandTraceRecorder(maxFrames = 300)
    @Volatile
    var offloadHandsToPc: Boolean = false
    var onCompanionCameraBitmap: ((Bitmap, Long) -> Unit)? = null
    var onGestureEvents: ((List<HandGestureEvent>) -> Unit)? = null
    val gestureRecognizer = GestureRecognizer(minConfidence = minConfidence)

    private data class AssetModelInfo(val bundled: Boolean, val byteSize: Long, val uncompressedFd: Boolean)

    private fun inspectModelAssetInfo(): AssetModelInfo {
        val fdLen = runCatching {
            context.assets.openFd(MODEL_ASSET_NAME).use { it.length }
        }.getOrNull()
        if (fdLen != null && fdLen > 0L) {
            return AssetModelInfo(bundled = true, byteSize = fdLen, uncompressedFd = true)
        }
        val streamLen = runCatching {
            context.assets.open(MODEL_ASSET_NAME).use { it.available().toLong() }
        }.getOrNull()
        return if (streamLen != null && streamLen >= 0L) {
            AssetModelInfo(bundled = true, byteSize = streamLen, uncompressedFd = false)
        } else {
            AssetModelInfo(bundled = false, byteSize = 0L, uncompressedFd = false)
        }
    }

    fun isModelBundled(): Boolean = inspectModelAssetInfo().bundled

    fun start(targetFps: Int = 30, allowTwoHands: Boolean = true) {
        maxTargetFps = targetFps.coerceIn(5, 30)
        maxNumHands = if (allowTwoHands) 2 else 1
        if (enabled && workerThread != null) return
        enabled = true
        continuousRaisedStartMs = 0L
        restHintDismissed = false
        reacquireStreak = 0
        lastValidHands = emptyList()
        lastLoggedOrientSig = null
        lastLoggedHandCount = -1
        _status.value = HandTrackingStatus.Initializing

        val thread = HandlerThread("HandTrackingThread").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler

        Log.i(TAG, "start(): targetFps=$maxTargetFps maxNumHands=$maxNumHands minConfidence=$minConfidence offloadToPc=$offloadHandsToPc")
        handler.post {
            initMediaPipeLandmarker(forceBenchmark = false)
        }
    }

    fun rerunDelegateBenchmark() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CACHED_DELEGATE).remove(KEY_CACHED_BENCH_NOTE).apply()
        gpuDelegateFailed = false
        if (enabled && workerHandler != null) {
            workerHandler?.post {
                landmarkerReady = false
                runCatching { landmarker?.close() }
                landmarker = null
                initMediaPipeLandmarker(forceBenchmark = true)
            }
        }
    }

    fun updateThermalTier(maxFps: Int, allowTwoHands: Boolean) {
        val nextNumHands = if (allowTwoHands) 2 else 1
        val handsChanged = nextNumHands != maxNumHands
        maxTargetFps = maxFps
        maxNumHands = nextNumHands
        if (maxFps <= 0 && enabled) {
            stop("Paused due to thermal protection")
        } else if (handsChanged && enabled && landmarkerReady) {
            workerHandler?.post {
                landmarkerReady = false
                runCatching { landmarker?.close() }
                landmarker = null
                initMediaPipeLandmarker(forceBenchmark = false)
            }
        }
    }

    fun updateActiveCameraMetadata(
        label: String,
        hfovDeg: Float,
        vfovDeg: Float,
        sensorOrientationDeg: Int = 90,
        lensFacingBack: Boolean = true
    ) {
        activeCameraLabel = label
        cameraHfovDeg = hfovDeg
        cameraVfovDeg = vfovDeg
        activeSensorOrientationDeg = sensorOrientationDeg
        activeLensFacingBack = lensFacingBack
        publishCameraOrientationTelemetry(reportedBaseDeg = null)
    }

    fun updateDisplayRotation(displayRotation: Int) {
        currentDisplayRotation = displayRotation
        publishCameraOrientationTelemetry(reportedBaseDeg = null)
    }

    private fun publishCameraOrientationTelemetry(reportedBaseDeg: Int?) {
        val resolved = HandFrameTransform.resolveCameraOrientation(
            sensorOrientationDeg = activeSensorOrientationDeg,
            lensFacingBack = activeLensFacingBack,
            displayRotation = currentDisplayRotation,
            reportedBaseRotationDeg = reportedBaseDeg,
            userRotationOffsetDeg = userRotationDegrees,
            userMirrorHorizontal = mirrorHorizontal
        )
        logAndSyncResolvedOrientation(resolved, reportedBaseDeg)
    }

    private fun logAndSyncResolvedOrientation(
        resolved: HandFrameTransform.CameraOrientationTransform,
        reportedBaseDeg: Int?
    ) {
        lastReportedBaseRotDeg = resolved.baseRotationDeg
        lastFinalRotDeg = resolved.finalRotationDeg
        lastMirrorApplied = resolved.mirrorHorizontal
        val sig = "${resolved.sensorOrientationDeg}|${resolved.displayRotationDeg}|$reportedBaseDeg|${resolved.baseRotationDeg}|${resolved.userOffsetDeg}|${resolved.mirrorHorizontal}|${resolved.finalRotationDeg}"
        if (sig != lastLoggedOrientSig) {
            lastLoggedOrientSig = sig
            val formulaBase = HandFrameTransform.computeBaseCameraUprightRotation(
                resolved.sensorOrientationDeg,
                resolved.lensFacingBack,
                resolved.displayRotation
            )
            Log.i(
                TAG,
                "CamOrient: sensor=${resolved.sensorOrientationDeg}° disp=${resolved.displayRotationDeg}° " +
                    "reportedImgRot=${reportedBaseDeg ?: "none"} formulaBase=${formulaBase}° usedBase=${resolved.baseRotationDeg}° " +
                    "userOffset=${resolved.userOffsetDeg}° mirror=${resolved.mirrorHorizontal} -> finalFrameRot=${resolved.finalRotationDeg}° " +
                    "(glPassthroughFinal=${resolved.glPassthroughFinalDeg}°)"
            )
            _telemetry.value = _telemetry.value.copy(
                activeCameraLabel = activeCameraLabel,
                activeCameraHfovDeg = cameraHfovDeg,
                camSensorOrientationDeg = resolved.sensorOrientationDeg,
                camDisplayRotationDeg = resolved.displayRotationDeg,
                camRotationDegrees = resolved.baseRotationDeg,
                camMirror = resolved.mirrorHorizontal,
                camUserOffsetDeg = resolved.userOffsetDeg,
                camFinalRotationDeg = resolved.finalRotationDeg
            )
        }
    }

    fun dismissRestHint() {
        restHintDismissed = true
        _telemetry.value = _telemetry.value.copy(showRestHint = false)
    }

    fun startTraceCapture() {
        traceRecorder.startRecording(SystemClock.uptimeMillis())
        _telemetry.value = _telemetry.value.copy(traceRecording = true, traceFrameCount = 0)
    }

    fun stopTraceCaptureAndSave(): String? {
        val frames = traceRecorder.stopRecording()
        val file = traceRecorder.saveTraceToFile(context, frames)
        val path = file?.absolutePath
        _telemetry.value = _telemetry.value.copy(
            traceRecording = false,
            traceFrameCount = frames.size,
            lastTraceFilePath = path
        )
        return path
    }

    fun stop(reason: String? = null) {
        enabled = false
        landmarkerReady = false
        latestHands.set(emptyList())
        pendingMeta.clear()
        isProcessing.set(false)
        gestureRecognizer.reset()
        continuousRaisedStartMs = 0L
        workerHandler?.post {
            runCatching { landmarker?.close() }
            landmarker = null
        }
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        _status.value = if (reason != null) {
            HandTrackingStatus.Unavailable(reason)
        } else {
            HandTrackingStatus.Disabled
        }
        _telemetry.value = _telemetry.value.copy(
            status = _status.value,
            handsVisible = false,
            isDropoutHoldActive = false,
            continuousHandRaisedMs = 0L,
            showRestHint = false,
            modelBundled = isModelBundled()
        )
    }

    fun getLatestHands(): List<HandFrame> = latestHands.get()

    fun getLatestDebugBitmap(): Bitmap? = latestDebugBitmap.get()

    private fun initMediaPipeLandmarker(forceBenchmark: Boolean) {
        val modelInfo = inspectModelAssetInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val reqGlHex = "0x" + Integer.toHexString(am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0)
        Log.i(
            TAG,
            "Init MediaPipe on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}, ABIs=${Build.SUPPORTED_ABIS.joinToString()}, " +
                "reqGLES=$reqGlHex, emu=${isRunningOnEmulator()}): model=$MODEL_ASSET_NAME bundled=${modelInfo.bundled} " +
                "bytes=${modelInfo.byteSize} uncompressedFd=${modelInfo.uncompressedFd}"
        )
        if (!modelInfo.bundled) {
            val msg = "Hand model ($MODEL_ASSET_NAME) not bundled in assets; gaze dwell & gamepad remain active"
            Log.e(TAG, msg)
            _status.value = HandTrackingStatus.Unavailable(msg)
            _telemetry.value = _telemetry.value.copy(status = _status.value, modelBundled = false)
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedDelegate = if (forceBenchmark) null else prefs.getString(KEY_CACHED_DELEGATE, null)
        benchmarkNote = prefs.getString(KEY_CACHED_BENCH_NOTE, null) ?: "Benchmark pending"
        Log.i(TAG, "Delegate cache state: forceBenchmark=$forceBenchmark cached=$cachedDelegate note='$benchmarkNote'")

        val chosenDelegate = when (cachedDelegate) {
            "GPU" -> if (shouldTryGpuDelegate()) Delegate.GPU else Delegate.CPU
            "CPU" -> Delegate.CPU
            else -> benchmarkAndSelectDelegate(prefs)
        }

        val created = createLiveStreamLandmarker(chosenDelegate)
            ?: if (chosenDelegate == Delegate.GPU) {
                gpuDelegateFailed = true
                Log.w(TAG, "GPU LIVE_STREAM creation failed; falling back to CPU delegate")
                prefs.edit().putString(KEY_CACHED_DELEGATE, "CPU").apply()
                createLiveStreamLandmarker(Delegate.CPU)
            } else null

        if (created == null) {
            val msg = "MediaPipe HandLandmarker failed (${lastErrorDetail ?: "unknown"}); gaze dwell active"
            Log.e(TAG, msg)
            landmarkerReady = false
            _status.value = HandTrackingStatus.Unavailable(msg)
            _telemetry.value = _telemetry.value.copy(
                status = _status.value,
                modelBundled = true,
                lastErrorDetail = lastErrorDetail
            )
            return
        }

        landmarker = created
        landmarkerReady = true
        lastHandDetectedMs = SystemClock.uptimeMillis()
        fpsWindowStartMs = lastHandDetectedMs
        _status.value = HandTrackingStatus.Active(activeDelegateName, 0, 128f)
        _telemetry.value = _telemetry.value.copy(
            status = _status.value,
            delegateName = activeDelegateName,
            delegateLoadMs = lastLoadMs,
            delegateBenchmarkNote = benchmarkNote,
            modelBundled = true,
            lastErrorDetail = lastErrorDetail
        )
    }

    private fun benchmarkAndSelectDelegate(prefs: android.content.SharedPreferences): Delegate {
        val cpuMs = benchmarkSingleDelegate(Delegate.CPU, "CPU")
        val gpuMs = if (shouldTryGpuDelegate()) benchmarkSingleDelegate(Delegate.GPU, "GPU") else Float.POSITIVE_INFINITY

        val winner: Delegate
        val winnerName: String
        if (gpuMs.isFinite() && gpuMs < cpuMs * 0.92f) {
            winner = Delegate.GPU
            winnerName = "GPU"
        } else {
            winner = Delegate.CPU
            winnerName = "CPU"
            if (!gpuMs.isFinite()) gpuDelegateFailed = true
        }
        val note = "Bench: CPU=${fmtBench(cpuMs)}, GPU=${fmtBench(gpuMs)} -> $winnerName"
        benchmarkNote = note
        prefs.edit()
            .putString(KEY_CACHED_DELEGATE, winnerName)
            .putString(KEY_CACHED_BENCH_NOTE, note)
            .apply()
        Log.i(TAG, note)
        return winner
    }

    private fun fmtBench(ms: Float): String = if (ms.isFinite()) "${"%.1f".format(ms)}ms" else "failed"

    private fun benchmarkSingleDelegate(delegate: Delegate, name: String): Float {
        return runCatching {
            val tLoad0 = SystemClock.elapsedRealtimeNanos()
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_NAME)
                .setDelegate(delegate)
                .build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumHands(1)
                .setMinHandDetectionConfidence(minConfidence)
                .setMinHandPresenceConfidence(minConfidence)
                .setMinTrackingConfidence(minConfidence)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            val probe = HandLandmarker.createFromOptions(context, opts)
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val mpImg = BitmapImageBuilder(bmp).build()
            // 1 warmup + 2 timed runs
            probe.detect(mpImg)
            val t0 = SystemClock.elapsedRealtimeNanos()
            probe.detect(mpImg)
            probe.detect(mpImg)
            val avgMs = ((SystemClock.elapsedRealtimeNanos() - t0) * 1e-6f) / 2f
            probe.close()
            bmp.recycle()
            val totalLoadMs = (SystemClock.elapsedRealtimeNanos() - tLoad0) * 1e-6f
            Log.i(TAG, "Benchmark $name: load+warmup=${"%.1f".format(totalLoadMs)}ms, infer=${"%.1f".format(avgMs)}ms")
            avgMs
        }.onFailure { err ->
            lastErrorDetail = "$name bench (${err.javaClass.simpleName}): ${err.message}"
            Log.w(TAG, "Benchmark $name failed: ${err.javaClass.simpleName}: ${err.message}", err)
        }.getOrDefault(Float.POSITIVE_INFINITY)
    }

    private fun shouldTryGpuDelegate(): Boolean {
        if (gpuDelegateFailed) return false
        if (isRunningOnEmulator()) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val glVersion = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        return glVersion >= 0x00030001
    }

    private fun isRunningOnEmulator(): Boolean {
        val fp = Build.FINGERPRINT
        val model = Build.MODEL
        val hw = Build.HARDWARE
        val product = Build.PRODUCT
        return fp.startsWith("generic") ||
            fp.startsWith("unknown") ||
            fp.contains("emulator", ignoreCase = true) ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            hw.contains("goldfish") ||
            hw.contains("ranchu") ||
            product.contains("sdk", ignoreCase = true)
    }

    private fun createLiveStreamLandmarker(delegate: Delegate): HandLandmarker? {
        val name = if (delegate == Delegate.GPU) "GPU" else "CPU"
        val t0 = SystemClock.elapsedRealtimeNanos()
        return runCatching {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_NAME)
                .setDelegate(delegate)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumHands(maxNumHands)
                .setMinHandDetectionConfidence(minConfidence)
                .setMinHandPresenceConfidence(minConfidence)
                .setMinTrackingConfidence(minConfidence)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    onLiveStreamResult(result)
                }
                .setErrorListener { error ->
                    onLiveStreamError(error)
                }
                .build()
            HandLandmarker.createFromOptions(context, options).also {
                activeDelegateName = name
                lastLoadMs = (SystemClock.elapsedRealtimeNanos() - t0) * 1e-6f
                Log.i(TAG, "HandLandmarker LIVE_STREAM ready on $name in ${"%.1f".format(lastLoadMs)}ms")
            }
        }.onFailure { err ->
            lastErrorDetail = "$name init (${err.javaClass.simpleName}): ${err.message}"
            Log.w(TAG, "Failed to initialize LIVE_STREAM HandLandmarker ($name): ${err.javaClass.simpleName}: ${err.message}", err)
        }.getOrNull()
    }

    /**
     * Accepts a CameraX ImageProxy (RGBA_8888 preferred) from SharedCameraFrameSource. Always closes [imageProxy].
     */
    fun submitCameraFrame(
        imageProxy: ImageProxy,
        meanLuminance: Float,
        luminanceVariance: Float = 40f,
        frameTimestampElapsedNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        if (!enabled || maxTargetFps <= 0) {
            imageProxy.close()
            return
        }

        val nowMs = SystemClock.uptimeMillis()
        val timeSinceHand = nowMs - lastHandDetectedMs
        val isIdle = lastHandDetectedMs > 0L && timeSinceHand > IDLE_THRESHOLD_MS
        val minIntervalMs = if (isIdle) WAKE_PROBE_INTERVAL_MS else (1000L / maxTargetFps)

        if (nowMs - lastFrameProcessedMs < minIntervalMs || !isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        if (!landmarkerReady) {
            updateLightingWarningsOnly(meanLuminance, luminanceVariance)
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val reportedImgRot = imageProxy.imageInfo.rotationDegrees
        val orient = HandFrameTransform.resolveCameraOrientation(
            sensorOrientationDeg = activeSensorOrientationDeg,
            lensFacingBack = activeLensFacingBack,
            displayRotation = currentDisplayRotation,
            reportedBaseRotationDeg = reportedImgRot,
            userRotationOffsetDeg = userRotationDegrees,
            userMirrorHorizontal = mirrorHorizontal
        )
        logAndSyncResolvedOrientation(orient, reportedImgRot)
        val rotDeg = orient.finalRotationDeg
        val mirror = orient.mirrorHorizontal
        val bitmap = runCatching {
            extractRotatedBitmap(imageProxy, rotDeg, mirror)
        }.getOrNull()
        imageProxy.close()

        if (bitmap == null) {
            isProcessing.set(false)
            return
        }

        lastFrameProcessedMs = nowMs
        if (offloadHandsToPc && onCompanionCameraBitmap != null) {
            updateLightingWarningsOnly(meanLuminance, luminanceVariance)
            onCompanionCameraBitmap?.invoke(bitmap, frameTimestampElapsedNs)
            if (debugPreviewActive && (nowMs - lastDebugSnapMs) >= 85L) {
                lastDebugSnapMs = nowMs
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val old = latestDebugBitmap.getAndSet(copy)
                if (old != null && old !== copy && !old.isRecycled) old.recycle()
            }
            isProcessing.set(false)
            return
        }

        enqueueLiveStreamBitmap(
            bitmap = bitmap,
            frameTimestampNs = frameTimestampElapsedNs,
            meanLuminance = meanLuminance,
            luminanceVariance = luminanceVariance,
            isIdle = isIdle,
            isMirrored = mirror
        )
    }

    /**
     * Accepts a pre-decoded Bitmap (e.g. from ARCore CPU image in Mode B).
     */
    fun submitArgbBitmap(
        bitmap: Bitmap,
        meanLuminance: Float,
        rotationDeg: Int = 0,
        frameTimestampElapsedNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        if (!enabled || maxTargetFps <= 0) {
            bitmap.recycle()
            return
        }
        val nowMs = SystemClock.uptimeMillis()
        val timeSinceHand = nowMs - lastHandDetectedMs
        val isIdle = lastHandDetectedMs > 0L && timeSinceHand > IDLE_THRESHOLD_MS
        val minIntervalMs = if (isIdle) WAKE_PROBE_INTERVAL_MS else (1000L / maxTargetFps).coerceAtLeast(66L)
        if (!landmarkerReady || nowMs - lastFrameProcessedMs < minIntervalMs || !isProcessing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        val orient = HandFrameTransform.resolveCameraOrientation(
            sensorOrientationDeg = activeSensorOrientationDeg,
            lensFacingBack = activeLensFacingBack,
            displayRotation = currentDisplayRotation,
            reportedBaseRotationDeg = rotationDeg,
            userRotationOffsetDeg = userRotationDegrees,
            userMirrorHorizontal = mirrorHorizontal
        )
        logAndSyncResolvedOrientation(orient, rotationDeg)
        val totalRot = orient.finalRotationDeg
        val mirror = orient.mirrorHorizontal
        val readyBmp = if (totalRot != 0 || mirror) {
            val matrix = Matrix().apply {
                if (totalRot != 0) postRotate(totalRot.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                bitmap.recycle()
            }
        } else bitmap

        lastFrameProcessedMs = nowMs
        enqueueLiveStreamBitmap(
            bitmap = readyBmp,
            frameTimestampNs = frameTimestampElapsedNs,
            meanLuminance = meanLuminance,
            luminanceVariance = 35f,
            isIdle = isIdle,
            isMirrored = mirror
        )
    }

    private fun extractRotatedBitmap(imageProxy: ImageProxy, rotDeg: Int, mirror: Boolean): Bitmap? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        // Fast single-pass RGBA_8888 rotation/mirror into reusable scratch + 3-slot Bitmap ring
        if (imageProxy.format == android.graphics.PixelFormat.RGBA_8888 || imageProxy.planes.size == 1) {
            val w = imageProxy.width
            val h = imageProxy.height
            val needed = w * h
            if (rgbaScratch.size < needed) {
                rgbaScratch = IntArray(needed)
            }
            val (outW, outH) = HandFrameTransform.transformRgbaBuffer(
                srcBuffer = plane.buffer,
                srcWidth = w,
                srcHeight = h,
                rowStrideBytes = plane.rowStride,
                pixelStrideBytes = plane.pixelStride,
                rotationDeg = rotDeg,
                mirrorHorizontal = mirror,
                dstPixels = rgbaScratch
            )
            val slot = ringCursor % bitmapRing.size
            ringCursor++
            var bmp = bitmapRing[slot]
            if (bmp == null || bmp.isRecycled || bmp.width != outW || bmp.height != outH) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                bitmapRing[slot] = bmp
            }
            bmp.setPixels(rgbaScratch, 0, outW, 0, 0, outW, outH)
            return bmp
        }

        // YUV fallback
        val raw = imageProxy.toBitmap()
        if (rotDeg == 0 && !mirror) return raw
        val matrix = Matrix().apply {
            if (rotDeg != 0) postRotate(rotDeg.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
            raw.recycle()
        }
    }

    private fun enqueueLiveStreamBitmap(
        bitmap: Bitmap,
        frameTimestampNs: Long,
        meanLuminance: Float,
        luminanceVariance: Float,
        isIdle: Boolean,
        isMirrored: Boolean
    ) {
        val posted = workerHandler?.post {
            try {
                val activeLandmarker = landmarker
                if (!enabled || activeLandmarker == null) {
                    isProcessing.set(false)
                    return@post
                }

                val submitNs = SystemClock.elapsedRealtimeNanos()
                val rawMs = SystemClock.uptimeMillis()
                val monoMs = maxOf(rawMs, lastLiveStreamTimestampMs + 1L)
                lastLiveStreamTimestampMs = monoMs

                val debugCopy = if (debugPreviewActive && (rawMs - lastDebugSnapMs) >= 85L) {
                    lastDebugSnapMs = rawMs
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else null

                pendingMeta[monoMs] = PendingFrameMeta(
                    frameTimestampNs = frameTimestampNs,
                    submitNs = submitNs,
                    meanLuminance = meanLuminance,
                    luminanceVariance = luminanceVariance,
                    isIdle = isIdle,
                    isMirrored = isMirrored,
                    hfovDeg = cameraHfovDeg,
                    vfovDeg = cameraVfovDeg,
                    debugPreviewCopy = debugCopy
                )

                val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
                activeLandmarker.detectAsync(mpImage, monoMs)
            } catch (err: Throwable) {
                lastErrorDetail = err.message
                Log.w(TAG, "detectAsync error: ${err.message}")
                isProcessing.set(false)
            }
        } ?: false

        if (!posted) {
            isProcessing.set(false)
        }
    }

    private fun onLiveStreamResult(result: HandLandmarkerResult) {
        val readyNs = SystemClock.elapsedRealtimeNanos()
        val eventNowMs = SystemClock.uptimeMillis()
        val meta = pendingMeta.remove(result.timestampMs()) ?: PendingFrameMeta(
            frameTimestampNs = readyNs,
            submitNs = readyNs,
            meanLuminance = _telemetry.value.meanLuminance,
            luminanceVariance = _telemetry.value.luminanceVariance,
            isIdle = false,
            isMirrored = mirrorHorizontal,
            hfovDeg = cameraHfovDeg,
            vfovDeg = cameraVfovDeg,
            debugPreviewCopy = null
        )

        meta.debugPreviewCopy?.let { snap ->
            val old = latestDebugBitmap.getAndSet(snap)
            if (old != null && old !== snap && !old.isRecycled) {
                old.recycle()
            }
        }

        try {
            val infMs = ((readyNs - meta.submitNs) * 1e-6f).coerceIn(0f, 500f)
            val e2eMs = ((readyNs - meta.frameTimestampNs) * 1e-6f).coerceIn(infMs, 800f)
            val (avgInf, p95Inf) = recordInferenceLatency(infMs)

            fpsCounter.incrementAndGet()
            if (fpsWindowStartMs == 0L) fpsWindowStartMs = eventNowMs
            val fpsElapsed = eventNowMs - fpsWindowStartMs
            if (fpsElapsed >= 500L) {
                measuredFps = fpsCounter.getAndSet(0) * (1000f / fpsElapsed)
                fpsWindowStartMs = eventNowMs
            }

            val rawFrames = convertResult(
                result = result,
                timestampMs = eventNowMs,
                timestampNs = readyNs,
                frameTimestampNs = meta.frameTimestampNs,
                isMirrored = meta.isMirrored,
                hfovDeg = meta.hfovDeg,
                vfovDeg = meta.vfovDeg
            )

            // Dropout hold (200 ms grace window) & re-acquisition hysteresis
            val effectiveFrames: List<HandFrame>
            var dropoutHoldActive = false
            if (rawFrames.isNotEmpty()) {
                hasEverSeenHand = true
                reacquireStreak++
                lastHandDetectedMs = eventNowMs
                lastValidHands = rawFrames
                effectiveFrames = if (reacquireStreak >= 2 || lastValidHands.isNotEmpty()) rawFrames else emptyList()
                if (continuousRaisedStartMs == 0L) continuousRaisedStartMs = eventNowMs
            } else {
                val sinceSeen = if (lastHandDetectedMs > 0L) eventNowMs - lastHandDetectedMs else Long.MAX_VALUE
                if (lastValidHands.isNotEmpty() && sinceSeen <= DROPOUT_HOLD_MS) {
                    dropoutHoldActive = true
                    effectiveFrames = lastValidHands.map {
                        it.copy(timestampMs = eventNowMs, isHeldDropout = true)
                    }
                } else {
                    reacquireStreak = 0
                    lastValidHands = emptyList()
                    continuousRaisedStartMs = 0L
                    restHintDismissed = false
                    effectiveFrames = emptyList()
                }
            }

            latestHands.set(effectiveFrames)
            if (traceRecorder.isRecording()) {
                traceRecorder.recordFrame(eventNowMs, effectiveFrames)
            }

            val events = gestureRecognizer.processFrame(effectiveFrames, eventNowMs)
            if (events.isNotEmpty()) {
                onGestureEvents?.invoke(events)
            }

            val lostMs = if (effectiveFrames.isEmpty() && lastHandDetectedMs > 0L) {
                eventNowMs - lastHandDetectedMs
            } else 0L

            val continuousRaisedMs = if (continuousRaisedStartMs > 0L) {
                eventNowMs - continuousRaisedStartMs
            } else 0L

            val occluded = meta.meanLuminance < 22f && meta.luminanceVariance < 18f
            val lowLight = meta.meanLuminance < 38f && !occluded
            val advice = buildDiagnosticAdvice(
                occluded = occluded,
                lowLight = lowLight,
                handsVisible = effectiveFrames.isNotEmpty(),
                neverFound = !hasEverSeenHand
            )

            val newStatus = if (meta.isIdle && effectiveFrames.isEmpty()) {
                HandTrackingStatus.IdleWakeProbe(meta.meanLuminance)
            } else {
                HandTrackingStatus.Active(activeDelegateName, effectiveFrames.size, meta.meanLuminance)
            }
            if (effectiveFrames.size != lastLoggedHandCount || (eventNowMs - lastDiagLogMs) >= 2000L) {
                lastLoggedHandCount = effectiveFrames.size
                lastDiagLogMs = eventNowMs
                val h0 = effectiveFrames.firstOrNull()
                val handSummary = if (h0 != null) {
                    "side=${h0.side} conf=${"%.2f".format(h0.confidence)} pinch=${"%.2f".format(h0.normalizedPinchRatio())} " +
                        "palmImg=${"%.3f".format(h0.palmWidthImage())} hold=${h0.isHeldDropout}"
                } else {
                    "noHands(occluded=$occluded lowLight=$lowLight idle=${meta.isIdle})"
                }
                Log.i(
                    TAG,
                    "Diag: del=$activeDelegateName cam='$activeCameraLabel' hands=${effectiveFrames.size} $handSummary " +
                        "infer=${"%.1f".format(infMs)}ms(avg=${"%.1f".format(avgInf)}) e2e=${"%.1f".format(e2eMs)}ms " +
                        "fps=${"%.1f".format(measuredFps)} lum=${"%.0f".format(meta.meanLuminance)} var=${"%.0f".format(meta.luminanceVariance)} " +
                        "rot(sensor=$activeSensorOrientationDeg disp=${HandFrameTransform.surfaceRotationToDegrees(currentDisplayRotation)} final=$lastFinalRotDeg)"
                )
            }
            _status.value = newStatus
            _telemetry.value = _telemetry.value.copy(
                status = newStatus,
                delegateName = activeDelegateName,
                delegateLoadMs = lastLoadMs,
                delegateBenchmarkNote = benchmarkNote,
                inferenceLatencyMs = infMs,
                inferenceAvgMs = avgInf,
                inferenceP95Ms = p95Inf,
                endToEndLatencyMs = e2eMs,
                effectiveFps = if (measuredFps > 0f) measuredFps else maxTargetFps.toFloat(),
                meanLuminance = meta.meanLuminance,
                luminanceVariance = meta.luminanceVariance,
                lowLightWarning = lowLight,
                cameraOccludedWarning = occluded,
                noHandEverFound = !hasEverSeenHand,
                handsLostDurationMs = lostMs,
                continuousHandRaisedMs = continuousRaisedMs,
                showRestHint = !restHintDismissed && continuousRaisedMs >= REST_HINT_THRESHOLD_MS,
                handsVisible = effectiveFrames.isNotEmpty(),
                isDropoutHoldActive = dropoutHoldActive,
                modelBundled = true,
                activeCameraLabel = activeCameraLabel,
                activeCameraHfovDeg = cameraHfovDeg,
                minConfidence = minConfidence,
                diagnosticAdvice = advice,
                traceRecording = traceRecorder.isRecording(),
                traceFrameCount = traceRecorder.frameCount(),
                camSensorOrientationDeg = activeSensorOrientationDeg,
                camDisplayRotationDeg = HandFrameTransform.surfaceRotationToDegrees(currentDisplayRotation),
                camRotationDegrees = lastReportedBaseRotDeg,
                camMirror = lastMirrorApplied,
                camUserOffsetDeg = HandFrameTransform.normalizeRotationDeg(userRotationDegrees),
                camFinalRotationDeg = lastFinalRotDeg
            )
        } finally {
            isProcessing.set(false)
        }
    }

    private fun onLiveStreamError(error: RuntimeException) {
        lastErrorDetail = error.message
        Log.w(TAG, "MediaPipe LIVE_STREAM error on $activeDelegateName: ${error.message}")
        isProcessing.set(false)
        if (activeDelegateName == "GPU") {
            gpuDelegateFailed = true
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CACHED_DELEGATE, "CPU").apply()
            workerHandler?.post {
                landmarkerReady = false
                runCatching { landmarker?.close() }
                landmarker = null
                initMediaPipeLandmarker(forceBenchmark = false)
            }
        }
    }

    private fun updateLightingWarningsOnly(meanLuminance: Float, variance: Float) {
        val occluded = meanLuminance < 22f && variance < 18f
        val lowLight = meanLuminance < 38f && !occluded
        _telemetry.value = _telemetry.value.copy(
            meanLuminance = meanLuminance,
            luminanceVariance = variance,
            lowLightWarning = lowLight,
            cameraOccludedWarning = occluded,
            diagnosticAdvice = buildDiagnosticAdvice(occluded, lowLight, false, !hasEverSeenHand)
        )
    }

    private fun buildDiagnosticAdvice(
        occluded: Boolean,
        lowLight: Boolean,
        handsVisible: Boolean,
        neverFound: Boolean
    ): String = when {
        occluded -> "Camera appears blocked/covered — open the headset front flap or use a camera cutout."
        lowLight -> "Very dim scene (luma < 38) — turn on room light so fingers contrast clearly."
        handsVisible -> "Hand tracked cleanly — pinch thumb + index to tap or hold-drag."
        neverFound -> "No hand detected yet — hold hand 25–60 cm in front of the rear lens (not too close)."
        else -> "Hands outside camera FOV — raise hand into the center reach zone (gaze dwell active)."
    }

    private fun recordInferenceLatency(ms: Float): Pair<Float, Float> {
        infHistoryMs[infCursor] = ms
        infCursor = (infCursor + 1) % infHistoryMs.size
        if (infCount < infHistoryMs.size) infCount++
        val slice = infHistoryMs.copyOf(infCount).sortedArray()
        val avg = slice.average().toFloat()
        val p95 = slice[((slice.size - 1) * 0.95f).toInt()]
        return avg to p95
    }

    /**
     * Allows deterministic recorded-landmark replay for Hand Debug Lab & automated testing.
     */
    fun injectDiagnosticHands(hands: List<HandFrame>, timestampMs: Long) {
        latestHands.set(hands)
        if (hands.isNotEmpty()) {
            hasEverSeenHand = true
            lastHandDetectedMs = timestampMs
        }
        val events = gestureRecognizer.processFrame(hands, timestampMs)
        if (events.isNotEmpty()) onGestureEvents?.invoke(events)
    }

    private fun convertResult(
        result: HandLandmarkerResult,
        timestampMs: Long,
        timestampNs: Long,
        frameTimestampNs: Long,
        isMirrored: Boolean,
        hfovDeg: Float,
        vfovDeg: Float
    ): List<HandFrame> {
        val landmarksList = result.landmarks()
        val worldList = result.worldLandmarks()
        val handednessList = result.handedness()
        if (landmarksList.isEmpty()) return emptyList()

        val output = mutableListOf<HandFrame>()
        for (i in landmarksList.indices) {
            val handednessCategory = handednessList.getOrNull(i)?.firstOrNull()
            val side = if (handednessCategory?.categoryName()?.equals("Left", ignoreCase = true) == true) {
                HandSide.LEFT
            } else {
                HandSide.RIGHT
            }
            val score = handednessCategory?.score() ?: 0.8f
            val filters = if (side == HandSide.LEFT) leftFilters else rightFilters

            val imgPts = landmarksList[i].mapIndexed { idx, lm ->
                val raw = Vec3(lm.x(), lm.y(), lm.z())
                if (idx < filters.size) filters[idx].filter(raw, timestampNs) else raw
            }
            val worldPts = worldList.getOrNull(i)?.map { lm ->
                Vec3(lm.x(), lm.y(), lm.z())
            } ?: imgPts

            output += HandFrame(
                side = side,
                confidence = score,
                imageLandmarks = imgPts,
                worldLandmarks = worldPts,
                timestampMs = timestampMs,
                isMirrored = isMirrored,
                frameTimestampNs = frameTimestampNs,
                inferenceReadyTimestampNs = timestampNs,
                cameraHfovDeg = hfovDeg,
                cameraVfovDeg = vfovDeg,
                isHeldDropout = false
            )
        }
        return output
    }
}
