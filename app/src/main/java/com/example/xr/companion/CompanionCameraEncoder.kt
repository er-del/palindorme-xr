package com.example.xr.companion

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Low-latency MediaCodec H.264 camera uploader (640x480 @ 30fps default, adaptive bitrate).
 * Strictly keeps only the latest RGBA frame and drops stale frames before encoding.
 */
class CompanionCameraEncoder(
    private val onEncodedAccessUnit: (data: ByteArray, frameTimestampNs: Long, encodeMs: Float, isKeyFrame: Boolean) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var encoderThread: Thread? = null

    private var width = 640
    private var height = 480
    private var fps = 30
    private var bitrateBps = 1_100_000
    private var minIntervalMs = 33L
    private var lastSubmittedMs = 0L

    private data class LatestFrame(
        val pixels: IntArray,
        val srcW: Int,
        val srcH: Int,
        val timestampNs: Long
    )

    private val pendingFrame = AtomicReference<LatestFrame?>(null)
    private val frameSignal = Object()

    fun start(tier: CompanionQualityTier) {
        width = tier.camWidth
        height = tier.camHeight
        fps = tier.camFps
        bitrateBps = tier.camBitrateBps
        minIntervalMs = (1000L / fps.coerceIn(10, 60))
        if (running.getAndSet(true)) {
            updateTier(tier)
            return
        }
        encoderThread = Thread({ runEncodeLoop() }, "CompanionCamEnc").apply { start() }
    }

    fun updateTier(tier: CompanionQualityTier) {
        bitrateBps = tier.camBitrateBps
        minIntervalMs = (1000L / tier.camFps.coerceIn(10, 60))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            runCatching {
                codec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps)
                })
            }
        }
    }

    /**
     * Submits the newest upright camera bitmap. Replaces any unconsumed frame immediately
     * (KEEP_ONLY_LATEST) and never queues stale frames.
     */
    fun submitLatestBitmap(bitmap: Bitmap, captureTimestampNs: Long) {
        if (!running.get() || bitmap.isRecycled) return
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastSubmittedMs < minIntervalMs) return
        lastSubmittedMs = nowMs

        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, false)
        } else bitmap

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        pendingFrame.set(LatestFrame(pixels, width, height, captureTimestampNs))
        synchronized(frameSignal) { frameSignal.notify() }
    }

    fun stop() {
        running.set(false)
        pendingFrame.set(null)
        synchronized(frameSignal) { frameSignal.notifyAll() }
        encoderThread?.join(500L)
        encoderThread = null
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }

    private fun runEncodeLoop() {
        val created = runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Make every keyframe self-contained (SPS/PPS in-band) so a PC
                // decoder that joins mid-stream, or resyncs after packet loss,
                // can start from the next keyframe. Without this the parameter
                // sets only arrive once, in a separate codec-config buffer.
                // Using the literal key keeps this safe below API 29, where the
                // MediaFormat constant does not exist.
                runCatching { setInteger("prepend-sps-pps-to-idr-frames", 1) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.getOrNull() ?: run {
            running.set(false)
            return
        }

        codec = created
        val info = MediaCodec.BufferInfo()
        val yuvScratch = ByteArray((width * height * 3) / 2)
        val submitTimestampsNs = ArrayDeque<Pair<Long, Long>>(8)
        var frameIdx = 0L

        while (running.get()) {
            val frame = pendingFrame.getAndSet(null)
            if (frame == null) {
                synchronized(frameSignal) {
                    if (pendingFrame.get() == null && running.get()) {
                        runCatching { frameSignal.wait(50L) }
                    }
                }
                continue
            }

            val inIdx = runCatching { created.dequeueInputBuffer(2_000L) }.getOrDefault(-1)
            if (inIdx >= 0) {
                val inputBuf = created.getInputBuffer(inIdx)
                if (inputBuf != null) {
                    argbToI420(frame.pixels, width, height, yuvScratch)
                    inputBuf.clear()
                    val toWrite = minOf(inputBuf.capacity(), yuvScratch.size)
                    inputBuf.put(yuvScratch, 0, toWrite)
                    val ptsUs = (frameIdx++ * 1_000_000L) / fps.coerceAtLeast(1)
                    submitTimestampsNs.addLast(frame.timestampNs to SystemClock.elapsedRealtimeNanos())
                    if (submitTimestampsNs.size > 6) submitTimestampsNs.removeFirst()
                    created.queueInputBuffer(inIdx, 0, toWrite, ptsUs, 0)
                }
            }

            var outIdx = runCatching { created.dequeueOutputBuffer(info, 2_000L) }.getOrDefault(-1)
            while (outIdx >= 0) {
                val outBuf = created.getOutputBuffer(outIdx)
                if (outBuf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(bytes)
                    // Codec-config buffers (SPS/PPS) are extra outputs that do not
                    // correspond to an input frame. Consuming a capture timestamp
                    // for them would shift every later frame's timestamp.
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val tsPair = if (isConfig) null else submitTimestampsNs.removeFirstOrNull()
                    val capNs = tsPair?.first ?: frame.timestampNs
                    val encStartNs = tsPair?.second ?: SystemClock.elapsedRealtimeNanos()
                    val encodeMs = ((SystemClock.elapsedRealtimeNanos() - encStartNs) * 1e-6f).coerceIn(0f, 100f)
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || isConfig
                    onEncodedAccessUnit(bytes, capNs, encodeMs, isKey)
                }
                created.releaseOutputBuffer(outIdx, false)
                outIdx = runCatching { created.dequeueOutputBuffer(info, 0L) }.getOrDefault(-1)
            }
        }
    }

    private fun argbToI420(argb: IntArray, w: Int, h: Int, out: ByteArray) {
        val frameSize = w * h
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + (frameSize / 4)

        for (j in 0 until h) {
            for (i in 0 until w) {
                val px = argb[j * w + i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()

                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uIndex++] = u.coerceIn(0, 255).toByte()
                    out[vIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
