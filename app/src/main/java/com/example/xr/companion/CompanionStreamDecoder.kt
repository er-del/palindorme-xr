package com.example.xr.companion

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency MediaCodec H.264 / H.265 stream decoder rendering PC desktop content
 * directly into the spatial PC_DESKTOP SurfaceTexture surface (no B-frames).
 */
class CompanionStreamDecoder(
    private val onDecodeLatencyMs: (Float) -> Unit
) {
    private var codec: MediaCodec? = null
    private var targetSurface: Surface? = null
    private var width: Int = 1280
    private var height: Int = 720
    private var mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
    private val started = AtomicBoolean(false)
    private var ptsCounterUs = 0L
    private val ptsSubmitNs = ConcurrentHashMap<Long, Long>()

    @Synchronized
    fun configure(surface: Surface, width: Int, height: Int, useHevc: Boolean = false) {
        release()
        this.targetSurface = surface
        this.width = width
        this.height = height
        this.mimeType = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        runCatching {
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }
            val dec = MediaCodec.createDecoderByType(mimeType)
            dec.configure(format, surface, null, 0)
            dec.start()
            codec = dec
            started.set(true)
        }
    }

    fun submitEncodedAccessUnit(bytes: ByteArray) {
        val dec = codec ?: return
        if (!started.get() || bytes.isEmpty()) return

        runCatching {
            val inIdx = dec.dequeueInputBuffer(4_000L)
            if (inIdx >= 0) {
                val buf = dec.getInputBuffer(inIdx)
                if (buf != null) {
                    buf.clear()
                    val len = minOf(buf.capacity(), bytes.size)
                    buf.put(bytes, 0, len)
                    val ptsUs = ptsCounterUs
                    ptsCounterUs += 16_667L
                    ptsSubmitNs[ptsUs] = SystemClock.elapsedRealtimeNanos()
                    dec.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                }
            }

            val info = MediaCodec.BufferInfo()
            var outIdx = dec.dequeueOutputBuffer(info, 2_000L)
            while (outIdx >= 0) {
                val submitNs = ptsSubmitNs.remove(info.presentationTimeUs)
                dec.releaseOutputBuffer(outIdx, true)
                if (submitNs != null) {
                    val decMs = ((SystemClock.elapsedRealtimeNanos() - submitNs) * 1e-6f).coerceIn(0f, 150f)
                    onDecodeLatencyMs(decMs)
                }
                outIdx = dec.dequeueOutputBuffer(info, 0L)
            }
        }
    }

    @Synchronized
    fun release() {
        started.set(false)
        ptsSubmitNs.clear()
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        targetSurface = null
    }
}
