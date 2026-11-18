package com.example.xr.companion

import kotlin.math.abs

/**
 * NTP-style 4-timestamp clock offset and jitter estimator between phone and PC clocks.
 * Keeps a sliding window of recent samples and selects the minimum-RTT sample for lowest
 * asymmetric queueing error, matching IEEE 1588 / NTP clock discipline conventions.
 */
class ClockSyncEstimator(private val windowSize: Int = 8) {

    data class SyncSample(
        val rttMs: Float,
        val clockOffsetNs: Long
    )

    private val lock = Any()
    private val samples = ArrayDeque<SyncSample>(windowSize + 1)

    @Volatile
    var rttMs: Float = 0f
        private set
    @Volatile
    var jitterMs: Float = 0f
        private set
    @Volatile
    var estimatedOffsetNs: Long = 0L
        private set
    @Volatile
    var synced: Boolean = false
        private set

    fun reset() = synchronized(lock) {
        samples.clear()
        rttMs = 0f
        jitterMs = 0f
        estimatedOffsetNs = 0L
        synced = false
    }

    /**
     * Records a PING/PONG sample:
     *   t1: phone send time (ns)
     *   t2: PC receive time (ns)
     *   t3: PC send time (ns)
     *   t4: phone receive time (ns)
     */
    fun recordSample(t1PhoneNs: Long, t2PcNs: Long, t3PcNs: Long, t4PhoneNs: Long): SyncSample? {
        val totalSpan = t4PhoneNs - t1PhoneNs
        if (totalSpan < 0L || totalSpan > 2_000_000_000L) return null
        val serverProc = (t3PcNs - t2PcNs).coerceIn(0L, totalSpan)
        val rttNs = (totalSpan - serverProc).coerceAtLeast(0L)
        val offsetNs = ((t2PcNs - t1PhoneNs) + (t3PcNs - t4PhoneNs)) / 2L
        val rtt = rttNs * 1e-6f

        val sample = SyncSample(rttMs = rtt, clockOffsetNs = offsetNs)
        synchronized(lock) {
            if (samples.size >= windowSize) samples.removeFirst()
            samples.addLast(sample)

            val prevRtt = rttMs
            // Best-offset corresponds to minimum RTT (lowest queueing asymmetry)
            val best = samples.minBy { it.rttMs }
            estimatedOffsetNs = best.clockOffsetNs
            val avgRtt = samples.map { it.rttMs }.average().toFloat()
            rttMs = avgRtt
            val delta = if (synced) abs(rtt - prevRtt) else 0f
            jitterMs = if (synced) jitterMs * 0.75f + delta * 0.25f else delta
            synced = true
        }
        return sample
    }

    fun pcTimeToPhoneTimeNs(pcTimestampNs: Long): Long = pcTimestampNs - estimatedOffsetNs
    fun phoneTimeToPcTimeNs(phoneTimestampNs: Long): Long = phoneTimestampNs + estimatedOffsetNs
}
