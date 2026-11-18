package com.example.xr.companion

enum class CompanionConnectionState(val label: String) {
    DISABLED("Off (Phone-Only Mode)"),
    DISCOVERING("Scanning LAN / USB..."),
    PAIRING("Pairing with 6-digit code..."),
    CONNECTED_ACTIVE("Connected (Encrypted LAN/USB)"),
    FALLBACK_ON_DEVICE("Fallback to On-Device Hands & Gaze")
}

data class CompanionLinkStats(
    val state: CompanionConnectionState = CompanionConnectionState.DISABLED,
    val hostLabel: String = "",
    val isUsbReverse: Boolean = false,
    val consentGranted: Boolean = false,
    val rttMs: Float = 0f,
    val jitterMs: Float = 0f,
    val packetLossPercent: Float = 0f,
    val uploadBitrateKbps: Int = 0,
    val streamBitrateKbps: Int = 0,
    val cameraEncodeMs: Float = 0f,
    val streamDecodeMs: Float = 0f,
    val handEndToEndMs: Float = 0f,
    val qualitySelector: CompanionQualityTier = CompanionQualityTier.AUTO,
    val effectiveQualityTier: CompanionQualityTier = CompanionQualityTier.BALANCED,
    val statusBanner: String? = null
)

/**
 * Pure state machine managing adaptive quality scaling, packet-loss mitigation,
 * and automatic 3-second high-latency (>120 ms default) or drop fallback to on-device tracking.
 */
class CompanionStateMachine(
    var highLatencyFallbackThresholdMs: Float = 120f,
    var highLatencyHoldDurationMs: Long = 3000L
) {
    var state: CompanionConnectionState = CompanionConnectionState.DISABLED
        private set

    var qualitySelector: CompanionQualityTier = CompanionQualityTier.AUTO
    var effectiveTier: CompanionQualityTier = CompanionQualityTier.BALANCED
        private set

    var statusBanner: String? = null
        private set

    private var highLatencyStartMs: Long = 0L
    private var lastAdaptiveStepMs: Long = 0L

    fun onStartDiscovery() {
        state = CompanionConnectionState.DISCOVERING
        statusBanner = null
        highLatencyStartMs = 0L
    }

    fun onStartPairing() {
        state = CompanionConnectionState.PAIRING
        statusBanner = null
        highLatencyStartMs = 0L
    }

    fun onConnected() {
        state = CompanionConnectionState.CONNECTED_ACTIVE
        effectiveTier = if (qualitySelector == CompanionQualityTier.AUTO) {
            CompanionQualityTier.BALANCED
        } else {
            qualitySelector
        }
        highLatencyStartMs = 0L
        statusBanner = null
    }

    fun onDisconnected(reason: String, fallbackToDevice: Boolean) {
        highLatencyStartMs = 0L
        if (fallbackToDevice) {
            state = CompanionConnectionState.FALLBACK_ON_DEVICE
            statusBanner = "PC link dropped ($reason) — fell back to on-device hands & gaze"
        } else {
            state = CompanionConnectionState.DISABLED
            statusBanner = reason
        }
    }

    /**
     * Evaluates RTT, jitter, packet loss, and hand end-to-end latency.
     * Returns true if the link triggered an automatic fallback to on-device tracking.
     */
    fun evaluateMetrics(
        nowMs: Long,
        rttMs: Float,
        jitterMs: Float,
        packetLossPercent: Float,
        handEndToEndMs: Float
    ): Boolean {
        if (state != CompanionConnectionState.CONNECTED_ACTIVE) return false

        // Check sustained high hand end-to-end latency fallback (> 120ms for > 3s)
        if (handEndToEndMs > highLatencyFallbackThresholdMs) {
            if (highLatencyStartMs == 0L) highLatencyStartMs = nowMs
            if ((nowMs - highLatencyStartMs) >= highLatencyHoldDurationMs) {
                state = CompanionConnectionState.FALLBACK_ON_DEVICE
                statusBanner = "PC hand latency > ${highLatencyFallbackThresholdMs.toInt()} ms for 3s — switched to on-device hands"
                return true
            }
        } else {
            highLatencyStartMs = 0L
        }

        // Auto-adapt bitrate/resolution every >= 1.5s when qualitySelector == AUTO
        if (qualitySelector == CompanionQualityTier.AUTO && (nowMs - lastAdaptiveStepMs) >= 1500L) {
            lastAdaptiveStepMs = nowMs
            effectiveTier = when {
                packetLossPercent > 4.0f || jitterMs > 25f || rttMs > 70f -> CompanionQualityTier.LOW
                packetLossPercent > 1.5f || jitterMs > 12f || rttMs > 35f -> CompanionQualityTier.BALANCED
                else -> CompanionQualityTier.HIGH
            }
        } else if (qualitySelector != CompanionQualityTier.AUTO) {
            effectiveTier = qualitySelector
        }
        return false
    }
}
