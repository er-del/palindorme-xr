package com.example.xr.perception

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.xr.hand.HandTrackingEngine
import com.example.xr.tracking.ClockDomainConverter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class CameraOrientationDiagnostics(
    val cameraId: String = "0",
    val lensFacingBack: Boolean = true,
    val sensorOrientationDeg: Int = 90,
    val displayRotation: Int = Surface.ROTATION_90,
    val reportedPreviewRotationDeg: Int? = null,
    val hasCameraTransform: Boolean = true,
    val streamMirroring: Boolean = false,
    val previewWidth: Int = 640,
    val previewHeight: Int = 480,
    val analysisWidth: Int = 640,
    val analysisHeight: Int = 480,
    val cropRect: Rect = Rect()
)

/**
 * Single owner of CameraX when in Mode A (CameraX shared mode).
 * Uses CameraEnumerator to select the widest-FOV rear lens (or user override),
 * configures RGBA_8888, 30-60 FPS AE range, continuous AF, aligned frame timestamps,
 * and keeps Preview + ImageAnalysis synchronized across ROTATION_90 <-> ROTATION_270 without restart.
 */
class SharedCameraFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val handTrackingEngine: HandTrackingEngine,
    private val onPassthroughLatencyMs: (Float) -> Unit
) : CameraModeCoordinator.CameraOwnerHandle {

    override val mode: CameraConsumerMode = CameraConsumerMode.CAMERAX_SHARED

    companion object {
        private const val TAG = "SharedCameraSource"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    private var previewSurface: Surface? = null

    @Volatile
    var passthroughTexture: SurfaceTexture? = null
    @Volatile
    var enablePreviewFeed: Boolean = false
    @Volatile
    var enableHandAnalysis: Boolean = false
    @Volatile
    var preferredCameraId: String? = null
    @Volatile
    var availableRearCameras: List<CameraLensDescriptor> = emptyList()
        private set
    @Volatile
    var activeCameraDescriptor: CameraLensDescriptor? = null
        private set
    @Volatile
    private var lastDiagnostics: CameraOrientationDiagnostics = CameraOrientationDiagnostics()
    @Volatile
    private var lastLoggedAnalysisSig: String? = null
    @Volatile
    private var lastLoggedTransformSig: String? = null

    var onOrientationDiagnosticsChanged: ((CameraOrientationDiagnostics) -> Unit)? = null

    fun refreshCameraList(): List<CameraLensDescriptor> {
        val list = CameraEnumerator.enumerateRearCameras(context)
        availableRearCameras = list
        return list
    }

    fun currentDiagnostics(): CameraOrientationDiagnostics = lastDiagnostics

    fun updateTargetRotation(displayRot: Int) {
        previewUseCase?.targetRotation = displayRot
        analysisUseCase?.targetRotation = displayRot
        lastDiagnostics = lastDiagnostics.copy(displayRotation = displayRot)
        Log.i(TAG, "updateTargetRotation -> displayRot=$displayRot (${surfaceRotLabel(displayRot)})")
        onOrientationDiagnosticsChanged?.invoke(lastDiagnostics)
    }

    private fun surfaceRotLabel(rot: Int): String = when (rot) {
        Surface.ROTATION_0 -> "ROTATION_0(0°)"
        Surface.ROTATION_90 -> "ROTATION_90(90°)"
        Surface.ROTATION_180 -> "ROTATION_180(180°)"
        Surface.ROTATION_270 -> "ROTATION_270(270°)"
        else -> "UNKNOWN($rot)"
    }

    // TransformationInfo.targetRotation is marked @RestrictTo(LIBRARY_GROUP) in
    // CameraX but is present and stable in the pinned version. It is used only
    // for on-screen diagnostics here, never to drive rendering.
    @SuppressLint("RestrictedApi")
    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun acquire(): Boolean = withContext(Dispatchers.Main.immediate) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext false

        val providerFuture = ProcessCameraProvider.getInstance(context)
        val deferred = CompletableDeferred<ProcessCameraProvider?>()
        providerFuture.addListener(
            { deferred.complete(runCatching { providerFuture.get() }.getOrNull()) },
            ContextCompat.getMainExecutor(context)
        )
        val provider = deferred.await() ?: return@withContext false
        cameraProvider = provider

        if (analysisExecutor == null) {
            analysisExecutor = Executors.newSingleThreadExecutor()
        }

        val rearCameras = refreshCameraList()
        val chosen = CameraEnumerator.pickWidestRearCamera(rearCameras, preferredCameraId)
        CameraEnumerator.logEnumerationSummary(rearCameras, chosen, preferredCameraId)
        activeCameraDescriptor = chosen
        val sensorOrient = chosen?.sensorOrientationDeg ?: 90
        val lensBack = (chosen?.lensFacing ?: CameraCharacteristics.LENS_FACING_BACK) == CameraCharacteristics.LENS_FACING_BACK
        if (chosen != null) {
            handTrackingEngine.updateActiveCameraMetadata(
                label = chosen.label,
                hfovDeg = chosen.horizontalFovDeg,
                vfovDeg = chosen.verticalFovDeg,
                sensorOrientationDeg = sensorOrient,
                lensFacingBack = lensBack
            )
        }

        val displayRot = currentDisplayRotation()
        lastLoggedAnalysisSig = null
        lastLoggedTransformSig = null
        Log.i(
            TAG,
            "acquire(): preview=$enablePreviewFeed handAnalysis=$enableHandAnalysis displayRot=${surfaceRotLabel(displayRot)} " +
                "chosenId=${chosen?.cameraId ?: "default"} sensorOrient=$sensorOrient°"
        )
        lastDiagnostics = lastDiagnostics.copy(
            cameraId = chosen?.cameraId ?: "0",
            lensFacingBack = lensBack,
            sensorOrientationDeg = sensorOrient,
            displayRotation = displayRot
        )
        onOrientationDiagnosticsChanged?.invoke(lastDiagnostics)

        val useCases = mutableListOf<androidx.camera.core.UseCase>()
        previewUseCase = null
        analysisUseCase = null
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val st = passthroughTexture
        if (enablePreviewFeed && st != null) {
            val previewResSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(960, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(previewResSelector)
                .setTargetRotation(displayRot)
                .build()
            preview.setSurfaceProvider(mainExecutor) { request ->
                val reqRes = request.resolution
                st.setDefaultBufferSize(reqRes.width, reqRes.height)
                previewSurface?.release()
                val surface = Surface(st)
                previewSurface = surface
                lastDiagnostics = lastDiagnostics.copy(
                    previewWidth = reqRes.width,
                    previewHeight = reqRes.height
                )
                onOrientationDiagnosticsChanged?.invoke(lastDiagnostics)
                request.setTransformationInfoListener(mainExecutor) { info ->
                    val sig = "${reqRes.width}x${reqRes.height}|${info.targetRotation}|${info.rotationDegrees}|${info.hasCameraTransform()}|${info.isMirroring}|${info.cropRect}"
                    if (sig != lastLoggedTransformSig) {
                        lastLoggedTransformSig = sig
                        Log.i(
                            TAG,
                            "Preview TransformationInfo: res=${reqRes.width}x${reqRes.height} targetRot=${surfaceRotLabel(info.targetRotation)} " +
                                "reportedRot=${info.rotationDegrees}° hasCamTransform=${info.hasCameraTransform()} " +
                                "mirroring=${info.isMirroring} crop=${info.cropRect}"
                        )
                    }
                    lastDiagnostics = lastDiagnostics.copy(
                        displayRotation = info.targetRotation,
                        reportedPreviewRotationDeg = info.rotationDegrees,
                        hasCameraTransform = info.hasCameraTransform(),
                        streamMirroring = info.isMirroring,
                        cropRect = info.cropRect
                    )
                    onOrientationDiagnosticsChanged?.invoke(lastDiagnostics)
                }
                request.provideSurface(surface, mainExecutor) {
                    surface.release()
                    if (previewSurface === surface) previewSurface = null
                }
            }
            previewUseCase = preview
            useCases += preview
        }

        if (enableHandAnalysis) {
            val resSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resSelector)
                .setTargetRotation(displayRot)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            val bestRange = chosen?.let { CameraEnumerator.pickBestAeFpsRange(it.supportedFpsRanges) }
            Log.i(TAG, "ImageAnalysis config: targetRes=640x480 RGBA_8888 targetRot=${surfaceRotLabel(displayRot)} aeFpsRange=$bestRange")
            runCatching {
                val ext = Camera2Interop.Extender(analysisBuilder)
                if (bestRange != null) {
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)
                }
                ext.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                // Prefer fast anti-blur metering when supported
                ext.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
                )
            }

            val analysis = analysisBuilder.build()
            val isRealtimeTs = chosen?.timestampSourceRealtime ?: true

            analysis.setAnalyzer(analysisExecutor!!) { proxy ->
                val startNs = SystemClock.elapsedRealtimeNanos()
                // SystemClock.uptimeNanos() is only public from API 35 (Android 15); on older
                // devices it is a blocked hidden API and throws NoSuchMethodError. System.nanoTime()
                // reads the same CLOCK_MONOTONIC source and is available on every supported level.
                val uptimeNs = System.nanoTime()
                val frameElapsedNs = ClockDomainConverter.cameraTimestampToElapsedRealtimeNs(
                    cameraTimestampNs = proxy.imageInfo.timestamp,
                    isRealtimeClockSource = isRealtimeTs,
                    elapsedRealtimeNsNow = startNs,
                    uptimeNsNow = uptimeNs
                )
                val (meanLum, lumVar) = estimateLuminanceAndVariance(proxy)
                val frameSig = "${proxy.width}x${proxy.height}|${proxy.format}|${proxy.imageInfo.rotationDegrees}"
                if (frameSig != lastLoggedAnalysisSig) {
                    lastLoggedAnalysisSig = frameSig
                    val plane0 = proxy.planes.firstOrNull()
                    Log.i(
                        TAG,
                        "First/Changed Analysis frame: size=${proxy.width}x${proxy.height} fmt=${proxy.format} " +
                            "imgRotDeg=${proxy.imageInfo.rotationDegrees}° planes=${proxy.planes.size} " +
                            "rowStride=${plane0?.rowStride} pxStride=${plane0?.pixelStride} rtTs=$isRealtimeTs " +
                            "meanLum=${"%.1f".format(meanLum)} lumVar=${"%.1f".format(lumVar)}"
                    )
                }
                if (lastDiagnostics.analysisWidth != proxy.width || lastDiagnostics.analysisHeight != proxy.height) {
                    lastDiagnostics = lastDiagnostics.copy(analysisWidth = proxy.width, analysisHeight = proxy.height)
                    onOrientationDiagnosticsChanged?.invoke(lastDiagnostics)
                }
                handTrackingEngine.submitCameraFrame(
                    imageProxy = proxy,
                    meanLuminance = meanLum,
                    luminanceVariance = lumVar,
                    frameTimestampElapsedNs = frameElapsedNs
                )
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) * 1e-6f
                onPassthroughLatencyMs(elapsedMs)
            }
            analysisUseCase = analysis
            useCases += analysis
        }

        if (useCases.isEmpty()) return@withContext true

        val selector = buildSelectorForCamera(provider, chosen)
        var usedFallbackSelector = false
        val camera = runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                *useCases.toTypedArray()
            )
        }.getOrElse { bindErr ->
            usedFallbackSelector = true
            Log.w(TAG, "Primary bind failed for chosen=${chosen?.cameraId}; retrying DEFAULT_BACK_CAMERA: ${bindErr.message}")
            // Graceful fallback to DEFAULT_BACK_CAMERA if physical ID wasn't directly bindable
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases.toTypedArray()
                )
            }.onFailure { fbErr ->
                Log.e(TAG, "Fallback DEFAULT_BACK_CAMERA bind failed: ${fbErr.message}", fbErr)
            }.getOrNull()
        } ?: return@withContext false

        boundCamera = camera
        val actualBoundId = runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }.getOrDefault("unknown")
        val zoomState = camera.cameraInfo.zoomState.value
        var appliedZoom = zoomState?.zoomRatio ?: 1.0f
        // If bound to a logical multi-camera that supports <1.0x ultra-wide zoom, zoom out when auto-widest
        if (preferredCameraId == null) {
            val minZoom = zoomState?.minZoomRatio ?: 1.0f
            if (minZoom < 0.98f) {
                runCatching {
                    camera.cameraControl.setZoomRatio(minZoom)
                    appliedZoom = minZoom
                }
            }
        }
        Log.i(
            TAG,
            "Bound camera active: actualCam2Id=$actualBoundId fallbackUsed=$usedFallbackSelector " +
                "zoomRange=[${zoomState?.minZoomRatio ?: 1f}..${zoomState?.maxZoomRatio ?: 1f}] appliedZoom=$appliedZoom"
        )
        true
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildSelectorForCamera(
        provider: ProcessCameraProvider,
        chosen: CameraLensDescriptor?
    ): CameraSelector {
        if (chosen == null) return CameraSelector.DEFAULT_BACK_CAMERA
        val targetId = chosen.cameraId
        val exposedIds = runCatching {
            provider.availableCameraInfos.mapNotNull { info ->
                runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
            }
        }.getOrDefault(emptyList())

        val bindId = when {
            targetId in exposedIds -> targetId
            chosen.parentLogicalId != null && chosen.parentLogicalId in exposedIds -> chosen.parentLogicalId
            else -> {
                Log.i(TAG, "CameraX availableCameraInfos=$exposedIds lacks targetId=$targetId/parent=${chosen.parentLogicalId}; using DEFAULT_BACK_CAMERA")
                return CameraSelector.DEFAULT_BACK_CAMERA
            }
        }
        Log.i(TAG, "CameraX selector: availableCameraInfos=$exposedIds -> binding bindId=$bindId (target=$targetId)")

        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == bindId }.getOrDefault(false)
                }
            }
            .build()
    }

    private fun currentDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display?.rotation }.getOrNull() ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_90
        }
    }

    fun applyExposureCompensation(evSteps: Float) {
        val cam = boundCamera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        val clamped = evSteps.toInt().coerceIn(range.lower, range.upper)
        runCatching { cam.cameraControl.setExposureCompensationIndex(clamped) }
    }

    override suspend fun release() = withContext(Dispatchers.Main.immediate) {
        runCatching { cameraProvider?.unbindAll() }
        boundCamera = null
        previewSurface?.release()
        previewSurface = null
    }

    override fun forceClose() {
        runCatching { cameraProvider?.unbindAll() }
        boundCamera = null
        previewSurface?.release()
        previewSurface = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
    }

    private fun estimateLuminanceAndVariance(imageProxy: androidx.camera.core.ImageProxy): Pair<Float, Float> {
        return runCatching {
            val plane = imageProxy.planes.firstOrNull() ?: return 128f to 40f
            val buf = plane.buffer
            val remaining = buf.remaining()
            if (remaining <= 0) return 128f to 40f

            val isRgba = imageProxy.format == android.graphics.PixelFormat.RGBA_8888 || plane.pixelStride >= 4
            val pixelStepBytes = if (isRgba) 4 else 1
            val totalPixels = remaining / pixelStepBytes
            val sampleStep = (totalPixels / 256).coerceAtLeast(1) * pixelStepBytes

            var sum = 0.0
            var sumSq = 0.0
            var count = 0
            var idx = 0
            while (idx + (if (isRgba) 2 else 0) < remaining) {
                val lum = if (isRgba) {
                    val r = buf.get(idx).toInt() and 0xFF
                    val g = buf.get(idx + 1).toInt() and 0xFF
                    val b = buf.get(idx + 2).toInt() and 0xFF
                    (0.299 * r + 0.587 * g + 0.114 * b)
                } else {
                    (buf.get(idx).toInt() and 0xFF).toDouble()
                }
                sum += lum
                sumSq += lum * lum
                count++
                idx += sampleStep
            }
            if (count <= 1) return 128f to 40f
            val mean = (sum / count).toFloat()
            val variance = ((sumSq / count) - (mean * mean)).toFloat().coerceAtLeast(0f)
            mean to kotlin.math.sqrt(variance)
        }.getOrDefault(128f to 40f)
    }
}
