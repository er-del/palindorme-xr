package com.example.xr.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import com.example.xr.perception.CameraEnumerator
import com.example.xr.perception.CameraLensDescriptor
import kotlin.math.sqrt

data class DeviceHardwareDiagnostics(
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val socInfo: String = "Unknown SoC",
    val totalRamMb: Int = 0,
    val glRenderer: String = "Waiting for GL context...",
    val glVendor: String = "",
    val glVersion: String = "",
    val supportedRefreshRates: List<Int> = emptyList(),
    val activeRefreshRateHz: Float = 60f,
    val displayResolutionPx: String = "",
    val physicalScreenInches: Float = 6.4f,
    val xdpi: Float = 409f,
    val ydpi: Float = 409f,
    val rearCameras: List<CameraLensDescriptor> = emptyList(),
    val ignoringBatteryOptimizations: Boolean = true,
    val sustainedPerformanceSupported: Boolean = false
)

/**
 * Reads actual SoC, RAM, display modes/DPI, GL renderer, Camera2 lenses, and battery
 * optimization status from Android system APIs at runtime (never hardcoded per model).
 */
object DeviceDiagnostics {

    fun collect(
        context: Context,
        glRenderer: String = "Pending GL init",
        glVendor: String = "",
        glVersion: String = "",
        activeRefreshHz: Float = 60f
    ): DeviceHardwareDiagnostics {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }
        val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).toInt()

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOfNotNull(
                Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
                Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ).joinToString(" ").ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }

        val dm = context.resources.displayMetrics
        val xdpi = dm.xdpi.takeIf { it > 60f } ?: 409f
        val ydpi = dm.ydpi.takeIf { it > 60f } ?: 409f
        val longPx = maxOf(dm.widthPixels, dm.heightPixels)
        val shortPx = minOf(dm.widthPixels, dm.heightPixels)
        val diagIn = sqrt((longPx / xdpi) * (longPx / xdpi) + (shortPx / ydpi) * (shortPx / ydpi))

        val displayMgr = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayMgr?.getDisplay(Display.DEFAULT_DISPLAY)
        val refreshRates = runCatching {
            display?.supportedModes?.map { it.refreshRate.toInt() }?.distinct()?.sortedDescending()
                ?: listOf(60)
        }.getOrDefault(listOf(60))

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false }.getOrDefault(false)
        } else true

        val sustainedSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { pm?.isSustainedPerformanceModeSupported ?: false }.getOrDefault(false)
        } else false

        val rearCams = CameraEnumerator.enumerateRearCameras(context)

        return DeviceHardwareDiagnostics(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            socInfo = "$socModel (${Build.BOARD})",
            totalRamMb = totalRamMb,
            glRenderer = glRenderer,
            glVendor = glVendor,
            glVersion = glVersion,
            supportedRefreshRates = refreshRates,
            activeRefreshRateHz = activeRefreshHz,
            displayResolutionPx = "${longPx}×${shortPx}px",
            physicalScreenInches = diagIn,
            xdpi = xdpi,
            ydpi = ydpi,
            rearCameras = rearCams,
            ignoringBatteryOptimizations = ignoringBattery,
            sustainedPerformanceSupported = sustainedSupported
        )
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
