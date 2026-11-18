package com.example.xr.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

enum class AdaptiveQualityTier(
    val label: String,
    val renderScaleMultiplier: Float,
    val effectsAllowed: Boolean,
    val particlesAllowed: Boolean,
    val handMaxFps: Int,
    val allowPassthrough: Boolean,
    val meshGridSize: Int,
    val preferredRefreshHz: Int
) {
    ULTRA("Ultra (90Hz Target)", 1.0f, true, true, 30, true, 40, 90),
    BALANCED("Balanced (60–90Hz Scaled)", 0.85f, true, true, 25, true, 32, 60),
    THERMAL_SAVER("Thermal Saver (60Hz / 20fps Single Hand)", 0.72f, false, false, 20, true, 28, 60),
    CRITICAL("Critical Thermal (VR Safe Mode)", 0.60f, false, false, 0, false, 24, 60)
}

data class PerformanceTelemetry(
    val fps: Float = 60f,
    val frameTimeAvgMs: Float = 16.6f,
    val frameTimeP95Ms: Float = 16.6f,
    val frameTimeP99Ms: Float = 16.6f,
    val droppedFrames: Long = 0L,
    val motionToPhotonEstMs: Float = 24f,
    val memoryUsedMb: Int = 120,
    val batteryPercent: Int = 100,
    val thermalStatusLabel: String = "Nominal",
    val qualityTier: AdaptiveQualityTier = AdaptiveQualityTier.ULTRA,
    val passthroughLatencyMs: Float = 0f,
    val handInferenceLatencyMs: Float = 0f,
    val adpfActive: Boolean = false,
    val glRendererString: String = "Pending GL",
    val glVendorString: String = "",
    val glVersionString: String = "",
    val activeRefreshRateHz: Float = 60f,
    val recommendedRefreshRateHz: Int = 60,
    val frameTimeHistory: List<Float> = emptyList()
)

class PerformanceMonitor(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var hintSession: PerformanceHintManager.Session? = null

    private val frameTimesMs = FloatArray(120)
    private var frameCursor = 0
    private var frameCount = 0
    private var droppedFramesCount = 0L
    private var lastReportTimeNs = 0L
    private var overBudgetStartNs = 0L

    @Volatile
    var targetRefreshRateHz: Float = 60f
    @Volatile
    var prefer90HzWhenSafe: Boolean = true
    @Volatile
    var lowPowerMode: Boolean = false
    @Volatile
    var passthroughLatencyMs: Float = 0f
    @Volatile
    var handInferenceLatencyMs: Float = 0f
    @Volatile
    var glRendererString: String = "Pending GL"
    @Volatile
    var glVendorString: String = ""
    @Volatile
    var glVersionString: String = ""

    private val _telemetry = MutableStateFlow(PerformanceTelemetry())
    val telemetry: StateFlow<PerformanceTelemetry> = _telemetry.asStateFlow()

    fun recordGlInfoOnRenderThread(renderer: String?, vendor: String?, version: String?) {
        glRendererString = renderer?.takeIf { it.isNotBlank() } ?: "Unknown GLES GPU"
        glVendorString = vendor.orEmpty()
        glVersionString = version.orEmpty()
    }

    fun initAdpfOnRenderThread() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hintSession == null) {
            runCatching {
                val hintMgr = context.getSystemService(PerformanceHintManager::class.java)
                val targetNs = TimeUnit.SECONDS.toNanos(1) / targetRefreshRateHz.toLong().coerceAtLeast(60L)
                hintSession = hintMgr?.createHintSession(intArrayOf(Process.myTid()), targetNs)
            }
        }
    }

    fun recordFrameDurationNs(durationNs: Long, predictionMs: Float, forceReport: Boolean = false) {
        val durationMs = durationNs * 1e-6f
        frameTimesMs[frameCursor] = durationMs
        frameCursor = (frameCursor + 1) % frameTimesMs.size
        if (frameCount < frameTimesMs.size) frameCount++

        val budgetMs = 1000f / targetRefreshRateHz.coerceAtLeast(60f)
        if (durationMs > budgetMs * 1.15f) {
            droppedFramesCount++
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { hintSession?.reportActualWorkDuration(durationNs) }
        }

        val nowNs = System.nanoTime()
        if (forceReport || (nowNs - lastReportTimeNs >= 500_000_000L && frameCount > 10)) {
            lastReportTimeNs = nowNs
            val sample = frameTimesMs.copyOf(frameCount).sortedArray()
            val avg = sample.average().toFloat()
            val p95 = sample[((sample.size - 1) * 0.95f).toInt()]
            val p99 = sample[((sample.size - 1) * 0.99f).toInt()]
            val fps = if (avg > 0.1f) (1000f / avg).coerceIn(1f, 144f) else 60f

            if (p95 > budgetMs * 1.15f) {
                if (overBudgetStartNs == 0L) overBudgetStartNs = nowNs
            } else {
                overBudgetStartNs = 0L
            }

            val overBudgetSustained = overBudgetStartNs != 0L && (nowNs - overBudgetStartNs) > 2_000_000_000L
            val (thermalLevel, thermalLabel) = readThermalStatus()
            val tier = computeQualityTier(thermalLevel, overBudgetSustained, lowPowerMode)
            val recommendedHz = if (prefer90HzWhenSafe && tier == AdaptiveQualityTier.ULTRA) 90 else 60

            val runtime = Runtime.getRuntime()
            val heapMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).toInt()
            val nativeMb = (Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt()

            val historyCount = minOf(frameCount, 60)
            val history = ArrayList<Float>(historyCount)
            val startIdx = (frameCursor - historyCount + frameTimesMs.size) % frameTimesMs.size
            for (i in 0 until historyCount) {
                history.add(frameTimesMs[(startIdx + i) % frameTimesMs.size])
            }

            _telemetry.value = PerformanceTelemetry(
                fps = fps,
                frameTimeAvgMs = avg,
                frameTimeP95Ms = p95,
                frameTimeP99Ms = p99,
                droppedFrames = droppedFramesCount,
                motionToPhotonEstMs = (avg + (30f - predictionMs).coerceAtLeast(6f)).coerceIn(10f, 60f),
                memoryUsedMb = heapMb + nativeMb,
                batteryPercent = readBatteryLevel(),
                thermalStatusLabel = thermalLabel,
                qualityTier = tier,
                passthroughLatencyMs = passthroughLatencyMs,
                handInferenceLatencyMs = handInferenceLatencyMs,
                adpfActive = hintSession != null,
                glRendererString = glRendererString,
                glVendorString = glVendorString,
                glVersionString = glVersionString,
                activeRefreshRateHz = targetRefreshRateHz,
                recommendedRefreshRateHz = recommendedHz,
                frameTimeHistory = history
            )
        }
    }

    internal fun computeQualityTier(
        thermalStatus: Int,
        overBudgetSustained: Boolean,
        lowPower: Boolean
    ): AdaptiveQualityTier {
        return when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> AdaptiveQualityTier.CRITICAL
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || lowPower -> AdaptiveQualityTier.THERMAL_SAVER
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT || overBudgetSustained -> AdaptiveQualityTier.BALANCED
            else -> AdaptiveQualityTier.ULTRA
        }
    }

    private fun readThermalStatus(): Pair<Int, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0 to "Nominal"
        val status = runCatching { powerManager.currentThermalStatus }.getOrDefault(0)
        val label = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light Warmth"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttle"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe Thermal"
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical"
            else -> "Nominal"
        }
        return status to label
    }

    private fun readBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100) / scale else 100
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { hintSession?.close() }
        }
        hintSession = null
    }
}
