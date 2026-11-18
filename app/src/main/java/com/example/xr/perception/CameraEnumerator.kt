package com.example.xr.perception

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Range
import kotlin.math.atan

data class CameraLensDescriptor(
    val cameraId: String,
    val isLogicalMultiCamera: Boolean,
    val isPhysicalSubCamera: Boolean,
    val parentLogicalId: String?,
    val lensFacing: Int,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val horizontalFovDeg: Float,
    val verticalFovDeg: Float,
    val sensorOrientationDeg: Int,
    val timestampSourceRealtime: Boolean,
    val supportedFpsRanges: List<Range<Int>>,
    val hardwareLevelLabel: String
) {
    val label: String
        get() = buildString {
            append("Cam $cameraId")
            if (isPhysicalSubCamera && parentLogicalId != null) append(" (phys of $parentLogicalId)")
            else if (isLogicalMultiCamera) append(" (logical)")
            append(" · HFOV ${"%.0f".format(horizontalFovDeg)}° · ${"%.1f".format(focalLengthMm)}mm")
        }
}

/**
 * Enumerates rear Camera2 logical and physical lenses at runtime, computes optical FOVs
 * from physical sensor size and focal length, and prefers the widest-FOV usable rear lens.
 */
object CameraEnumerator {

    private const val TAG = "CameraEnumerator"

    @Volatile
    private var lastLoggedSummarySig: String? = null

    fun logEnumerationSummary(
        cameras: List<CameraLensDescriptor>,
        chosen: CameraLensDescriptor?,
        preferredCameraId: String? = null
    ) {
        val sig = "${cameras.joinToString(",") { "${it.cameraId}:${"%.1f".format(it.horizontalFovDeg)}" }}|${chosen?.cameraId}|$preferredCameraId"
        if (sig == lastLoggedSummarySig) return
        lastLoggedSummarySig = sig

        Log.i(
            TAG,
            "Rear camera enumeration (${cameras.size} entries), preferred=$preferredCameraId, chosen=${chosen?.cameraId ?: "NONE"} (${chosen?.label ?: "none"})"
        )
        cameras.forEach { cam ->
            val fpsStr = cam.supportedFpsRanges.joinToString(",") { "[${it.lower}-${it.upper}]" }
            Log.i(
                TAG,
                "  id=${cam.cameraId} logical=${cam.isLogicalMultiCamera} physSub=${cam.isPhysicalSubCamera}(parent=${cam.parentLogicalId}) " +
                    "focal=${"%.2f".format(cam.focalLengthMm)}mm sensor=${"%.2f".format(cam.sensorWidthMm)}x${"%.2f".format(cam.sensorHeightMm)}mm " +
                    "hfov=${"%.1f".format(cam.horizontalFovDeg)}° vfov=${"%.1f".format(cam.verticalFovDeg)}° " +
                    "sensorOrient=${cam.sensorOrientationDeg}° rtTs=${cam.timestampSourceRealtime} hw=${cam.hardwareLevelLabel} fps=[$fpsStr]"
            )
        }
    }

    fun computeFovDegrees(sensorSizeMm: Float, focalLengthMm: Float): Float {
        if (sensorSizeMm <= 0.1f || focalLengthMm <= 0.1f) return 65f
        val rad = 2.0 * atan((sensorSizeMm / (2.0 * focalLengthMm)))
        return Math.toDegrees(rad).toFloat().coerceIn(20f, 150f)
    }

    fun enumerateRearCameras(context: Context): List<CameraLensDescriptor> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val out = mutableListOf<CameraLensDescriptor>()
        val seenIds = mutableSetOf<String>()

        val logicalIds = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        for (id in logicalIds) {
            val chars = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { chars.physicalCameraIds }.getOrDefault(emptySet())
            } else emptySet()

            if (seenIds.add(id)) {
                out += extractDescriptor(
                    id = id,
                    chars = chars,
                    isLogicalMulti = physicalIds.size > 1,
                    isPhysicalSub = false,
                    parentLogicalId = null
                )
            }

            for (physId in physicalIds) {
                if (seenIds.add(physId)) {
                    val pChars = runCatching { cm.getCameraCharacteristics(physId) }.getOrNull() ?: continue
                    out += extractDescriptor(
                        id = physId,
                        chars = pChars,
                        isLogicalMulti = false,
                        isPhysicalSub = true,
                        parentLogicalId = id
                    )
                }
            }
        }
        return out.sortedByDescending { it.horizontalFovDeg }
    }

    fun pickWidestRearCamera(
        cameras: List<CameraLensDescriptor>,
        preferredCameraId: String? = null
    ): CameraLensDescriptor? {
        if (cameras.isEmpty()) return null
        if (!preferredCameraId.isNullOrBlank()) {
            cameras.firstOrNull { it.cameraId == preferredCameraId }?.let { return it }
        }
        // Prefer widest-FOV usable rear lens (ultra-wide if exposed, otherwise widest logical rear cam)
        return cameras.maxByOrNull { it.horizontalFovDeg }
    }

    fun pickBestAeFpsRange(ranges: List<Range<Int>>): Range<Int>? {
        if (ranges.isEmpty()) return null
        // Prefer [30, 60] or [30, 30] to avoid low-FPS exposure blur
        val exact30to60 = ranges.firstOrNull { it.lower >= 30 && it.upper <= 60 }
        if (exact30to60 != null) return exact30to60
        val highUpper = ranges.filter { it.upper >= 30 }.maxWithOrNull(
            compareBy<Range<Int>> { it.lower }.thenBy { it.upper }
        )
        return highUpper ?: ranges.maxByOrNull { it.upper }
    }

    private fun extractDescriptor(
        id: String,
        chars: CameraCharacteristics,
        isLogicalMulti: Boolean,
        isPhysicalSub: Boolean,
        parentLogicalId: String?
    ): CameraLensDescriptor {
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focal = focalLengths?.minOrNull() ?: 4.2f
        val physSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorW = physSize?.width ?: 5.6f
        val sensorH = physSize?.height ?: 4.2f
        val hfov = computeFovDegrees(sensorW, focal)
        val vfov = computeFovDegrees(sensorH, focal)
        val orient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val tsSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val isRealtime = tsSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            else -> "EXTERNAL/UNKNOWN"
        }
        return CameraLensDescriptor(
            cameraId = id,
            isLogicalMultiCamera = isLogicalMulti,
            isPhysicalSubCamera = isPhysicalSub,
            parentLogicalId = parentLogicalId,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            focalLengthMm = focal,
            sensorWidthMm = sensorW,
            sensorHeightMm = sensorH,
            horizontalFovDeg = hfov,
            verticalFovDeg = vfov,
            sensorOrientationDeg = orient,
            timestampSourceRealtime = isRealtime,
            supportedFpsRanges = fpsRanges,
            hardwareLevelLabel = hwLevel
        )
    }
}
