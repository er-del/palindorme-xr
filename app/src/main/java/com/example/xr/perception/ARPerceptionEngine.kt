package com.example.xr.perception

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.xr.tracking.Quat
import com.example.xr.tracking.Vec3
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class ArAvailabilityStatus(val label: String, val isSupported: Boolean) {
    CHECKING("Checking ARCore support...", false),
    SUPPORTED_INSTALLED("ARCore Installed & Ready", true),
    SUPPORTED_NEEDS_INSTALL("ARCore Supported (Needs Google Play Services for AR)", true),
    UNSUPPORTED("ARCore Unsupported on This Device (VR Mode Only)", false)
}

data class ArPerceptionTelemetry(
    val availability: ArAvailabilityStatus = ArAvailabilityStatus.CHECKING,
    val sessionActive: Boolean = false,
    val trackingState: String = "Idle",
    val trackingQualityWarning: String? = null,
    val detectedPlanesCount: Int = 0,
    val ambientLightIntensity: Float = 1.0f,
    val depthSupported: Boolean = false,
    val sixDofFallbackTriggered: Boolean = false
)

/**
 * Optional Stage 3 ARCore perception engine.
 * Safely checks availability before loading any Session classes, supports plane/light/depth,
 * and auto-falls back to 3-DOF when camera is occluded inside a headset.
 */
class ARPerceptionEngine(private val context: Context) : CameraModeCoordinator.CameraOwnerHandle {

    override val mode: CameraConsumerMode = CameraConsumerMode.ARCORE_MODE

    private var arSession: Session? = null
    private var pausedTrackingStartMs: Long = 0L
    private var cameraTextureId: Int = -1
    private var lastArHandExtractMs: Long = 0L
    private val arPixelScratch = IntArray(160 * 120)

    @Volatile
    var activeSensorOrientationDeg: Int = 90
        private set
    @Volatile
    private var currentDisplayRotation: Int = Surface.ROTATION_90
    @Volatile
    private var viewportWidthPx: Int = 1280
    @Volatile
    private var viewportHeightPx: Int = 720
    @Volatile
    private var pendingDisplayGeometryUpdate: Boolean = false

    private val _telemetry = MutableStateFlow(ArPerceptionTelemetry())
    val telemetry: StateFlow<ArPerceptionTelemetry> = _telemetry.asStateFlow()

    var onSixDofPose: ((Quat?, Vec3?) -> Unit)? = null
    var onArCameraFrameForHands: ((Bitmap, Float) -> Unit)? = null
    @Volatile
    var forwardFramesToHands: Boolean = false

    fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) {
        currentDisplayRotation = displayRotation
        if (width > 0 && height > 0) {
            viewportWidthPx = width
            viewportHeightPx = height
        }
        pendingDisplayGeometryUpdate = true
    }

    fun checkAvailability(): ArAvailabilityStatus {
        val hasPlayOrArPackage = isPackageInstalled("com.android.vending") ||
            isPackageInstalled("com.google.ar.core")
        if (!hasPlayOrArPackage) {
            _telemetry.value = _telemetry.value.copy(availability = ArAvailabilityStatus.UNSUPPORTED)
            return ArAvailabilityStatus.UNSUPPORTED
        }

        val status = runCatching {
            val avail = ArCoreApk.getInstance().checkAvailability(context)
            when {
                avail == ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailabilityStatus.SUPPORTED_INSTALLED
                avail.isSupported -> ArAvailabilityStatus.SUPPORTED_NEEDS_INSTALL
                else -> ArAvailabilityStatus.UNSUPPORTED
            }
        }.getOrDefault(ArAvailabilityStatus.UNSUPPORTED)
        _telemetry.value = _telemetry.value.copy(availability = status)
        return status
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    fun requestArCoreInstall(activity: Activity): Boolean {
        return runCatching {
            ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED
        }.getOrDefault(false)
    }

    fun setCameraTextureId(texId: Int) {
        cameraTextureId = texId
        runCatching { arSession?.setCameraTextureName(texId) }
    }

    fun setHeavyPerceptionEnabled(enabled: Boolean) {
        val session = arSession ?: return
        runCatching {
            val cfg = Config(session).apply {
                planeFindingMode = if (enabled) Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL else Config.PlaneFindingMode.DISABLED
                lightEstimationMode = if (enabled) Config.LightEstimationMode.ENVIRONMENTAL_HDR else Config.LightEstimationMode.DISABLED
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                depthMode = if (enabled && session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
            }
            session.configure(cfg)
        }
    }

    override suspend fun acquire(): Boolean = withContext(Dispatchers.Main.immediate) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext false

        if (checkAvailability() != ArAvailabilityStatus.SUPPORTED_INSTALLED) {
            return@withContext false
        }

        val session = runCatching {
            Session(context).apply {
                val cfg = Config(this).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    if (isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                }
                configure(cfg)
                activeSensorOrientationDeg = runCatching {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    cm?.getCameraCharacteristics(cameraConfig.cameraId)
                        ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                }.getOrDefault(90)
                if (cameraTextureId > 0) setCameraTextureName(cameraTextureId)
                if (viewportWidthPx > 0 && viewportHeightPx > 0) {
                    setDisplayGeometry(currentDisplayRotation, viewportWidthPx, viewportHeightPx)
                }
                resume()
            }
        }.getOrNull() ?: return@withContext false

        arSession = session
        pendingDisplayGeometryUpdate = true
        pausedTrackingStartMs = 0L
        _telemetry.value = _telemetry.value.copy(
            sessionActive = true,
            trackingState = "Initializing",
            depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC),
            sixDofFallbackTriggered = false,
            trackingQualityWarning = null
        )
        true
    }

    fun updateFrameOnGlThread(enableSixDof: Boolean) {
        val session = arSession ?: return
        runCatching {
            if (cameraTextureId > 0) session.setCameraTextureName(cameraTextureId)
            if (pendingDisplayGeometryUpdate && viewportWidthPx > 0 && viewportHeightPx > 0) {
                pendingDisplayGeometryUpdate = false
                session.setDisplayGeometry(currentDisplayRotation, viewportWidthPx, viewportHeightPx)
            }
            val frame = session.update()
            if (forwardFramesToHands && onArCameraFrameForHands != null) {
                val nowMs = android.os.SystemClock.uptimeMillis()
                if (nowMs - lastArHandExtractMs >= 75L) {
                    lastArHandExtractMs = nowMs
                    runCatching {
                        frame.acquireCameraImage().use { img ->
                            val yPlane = img.planes.firstOrNull() ?: return@use
                            val buf = yPlane.buffer
                            val srcW = img.width
                            val srcH = img.height
                            val rowStride = yPlane.rowStride
                            val dstW = 160
                            val dstH = 120
                            var sum = 0L
                            for (y in 0 until dstH) {
                                val sy = (y * srcH) / dstH
                                val rowOffset = sy * rowStride
                                for (x in 0 until dstW) {
                                    val sx = (x * srcW) / dstW
                                    val luma = buf.get(rowOffset + sx).toInt() and 0xFF
                                    sum += luma
                                    arPixelScratch[y * dstW + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                                }
                            }
                            val bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                            bmp.setPixels(arPixelScratch, 0, dstW, 0, 0, dstW, dstH)
                            val meanLum = sum.toFloat() / (dstW * dstH)
                            onArCameraFrameForHands?.invoke(bmp, meanLum)
                        }
                    }
                }
            }
            val cam = frame.camera
            val planes = session.getAllTrackables(Plane::class.java).count {
                it.trackingState == TrackingState.TRACKING
            }
            val lightIntensity = frame.lightEstimate.pixelIntensity.coerceIn(0.2f, 2.0f)

            if (cam.trackingState == TrackingState.TRACKING) {
                pausedTrackingStartMs = 0L
                if (enableSixDof) {
                    val pose = cam.displayOrientedPose
                    val q = Quat(pose.qx(), pose.qy(), pose.qz(), pose.qw()).normalized()
                    val p = Vec3(pose.tx(), pose.ty(), pose.tz())
                    onSixDofPose?.invoke(q, p)
                } else {
                    onSixDofPose?.invoke(null, null)
                }
                _telemetry.value = _telemetry.value.copy(
                    trackingState = "Tracking (6-DOF Ready)",
                    trackingQualityWarning = null,
                    detectedPlanesCount = planes,
                    ambientLightIntensity = lightIntensity,
                    sixDofFallbackTriggered = false
                )
            } else {
                val now = System.currentTimeMillis()
                if (pausedTrackingStartMs == 0L) pausedTrackingStartMs = now
                val pausedTooLong = (now - pausedTrackingStartMs) > 2500L
                if (pausedTooLong) {
                    onSixDofPose?.invoke(null, null)
                }
                val reason = when (cam.trackingFailureReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Insufficient light for ARCore"
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Camera view blocked or featureless (using 3-DOF)"
                    TrackingFailureReason.EXCESSIVE_MOTION -> "Excessive motion"
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
                    else -> "Initializing visual tracking"
                }
                _telemetry.value = _telemetry.value.copy(
                    trackingState = if (pausedTooLong) "3-DOF Fallback (Poor AR Tracking)" else "Paused",
                    trackingQualityWarning = reason,
                    detectedPlanesCount = planes,
                    ambientLightIntensity = lightIntensity,
                    sixDofFallbackTriggered = pausedTooLong
                )
            }
        }
    }

    override suspend fun release() = withContext(Dispatchers.Main.immediate) {
        onSixDofPose?.invoke(null, null)
        runCatching {
            arSession?.pause()
            arSession?.close()
        }
        arSession = null
        _telemetry.value = _telemetry.value.copy(
            sessionActive = false,
            trackingState = "Idle",
            detectedPlanesCount = 0
        )
    }

    override fun forceClose() {
        onSixDofPose?.invoke(null, null)
        runCatching { arSession?.close() }
        arSession = null
        _telemetry.value = _telemetry.value.copy(sessionActive = false, trackingState = "Closed")
    }
}
