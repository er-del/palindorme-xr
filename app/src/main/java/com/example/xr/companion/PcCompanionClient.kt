package com.example.xr.companion

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.view.Surface
import com.example.xr.hand.HandFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encrypted LAN/USB client connecting only to user-approved private IP / USB adb-reverse PC hosts.
 * Handles:
 *  - 6-digit pairing authentication (HMAC-SHA256) & AES-256-GCM encrypted control + UDP framing
 *  - NTP 4-timestamp clock synchronization
 *  - H.264 MediaCodec camera upload (KEEP_ONLY_LATEST) & remote desktop stream decode
 *  - Remote hand landmarks + automatic fallback when link drops or latency > threshold for 3s
 */
class PcCompanionClient(
    private val clockNowNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val uptimeNowMs: () -> Long = { SystemClock.uptimeMillis() }
) {
    val clockSync = ClockSyncEstimator()
    val stateMachine = CompanionStateMachine()
    val cameraEncoder = CompanionCameraEncoder { bytes, frameTsNs, encMs, _ ->
        sendCameraAccessUnit(bytes, frameTsNs, encMs)
    }
    val streamDecoder = CompanionStreamDecoder { decMs ->
        latestDecodeMs = decMs
    }

    private val _stats = MutableStateFlow(CompanionLinkStats())
    val stats: StateFlow<CompanionLinkStats> = _stats.asStateFlow()

    var onRemoteHandsReceived: ((hands: List<HandFrame>, captureTimestampPhoneNs: Long, e2eMs: Float) -> Unit)? = null
    var onFallbackTriggered: ((reason: String) -> Unit)? = null

    private val connected = AtomicBoolean(false)
    private val ioExecutor = Executors.newCachedThreadPool()
    private var tcpSocket: Socket? = null
    private var tcpWriter: BufferedWriter? = null
    private var udpSocket: DatagramSocket? = null
    private var remoteAddr: InetAddress? = null
    private var remoteUdpPort: Int = CompanionProtocol.DEFAULT_UDP_PORT
    private var sessionKey: ByteArray? = null

    private val tcpWriteLock = Any()
    private val cameraSeq = AtomicInteger(0)
    private val pingSeq = AtomicInteger(0)
    private var lastStreamSeq = -1
    private var streamExpected = 0
    private var streamLost = 0

    @Volatile
    private var latestEncodeMs = 0f
    @Volatile
    private var latestDecodeMs = 0f
    @Volatile
    private var latestHandE2eMs = 0f
    @Volatile
    private var uploadBytesWindow = 0L
    @Volatile
    private var streamBytesWindow = 0L
    @Volatile
    private var lastBitrateResetMs = 0L
    @Volatile
    private var currentHostLabel = ""
    @Volatile
    private var consentGranted = false
    @Volatile
    private var streamSurfaceReady: Surface? = null
    @Volatile
    var highLatencyThresholdMs: Float = 120f
        set(value) {
            field = value.coerceIn(50f, 350f)
            stateMachine.highLatencyFallbackThresholdMs = field
        }

    fun isConnected(): Boolean = connected.get()

    fun setQualitySelector(tier: CompanionQualityTier) {
        stateMachine.qualitySelector = tier
        cameraEncoder.updateTier(if (tier == CompanionQualityTier.AUTO) stateMachine.effectiveTier else tier)
        if (connected.get()) {
            sendEncryptedControl(CompanionProtocol.encodeQualityConfig(tier))
        }
        publishStats()
    }

    fun attachDesktopStreamSurface(surface: Surface?, width: Int, height: Int) {
        streamSurfaceReady = surface
        if (surface != null) {
            streamDecoder.configure(surface, width, height, useHevc = false)
        } else {
            streamDecoder.release()
        }
    }

    fun submitCameraFrame(bitmap: Bitmap, captureTimestampNs: Long) {
        if (!connected.get()) return
        cameraEncoder.submitLatestBitmap(bitmap, captureTimestampNs)
    }

    fun sendRemoteMouse(action: String, normX: Float, normY: Float, button: Int = 1, scrollDy: Float = 0f) {
        if (!connected.get()) return
        sendEncryptedControl(CompanionProtocol.encodeRemoteMouse(action, normX, normY, button, scrollDy))
    }

    fun sendRemoteKey(keyCode: Int, unicodeChar: Int, action: Int) {
        if (!connected.get()) return
        sendEncryptedControl(CompanionProtocol.encodeRemoteKey(keyCode, unicodeChar, action))
    }

    fun connect(
        host: String,
        controlPort: Int = CompanionProtocol.DEFAULT_CONTROL_PORT,
        udpPort: Int = CompanionProtocol.DEFAULT_UDP_PORT,
        pairingCode: String,
        allowNonPrivateOverride: Boolean = false,
        consentAccepted: Boolean = false,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        if (!consentAccepted) {
            val msg = "Consent required before streaming camera frames to PC."
            stateMachine.onDisconnected(msg, fallbackToDevice = false)
            publishStats()
            onResult?.invoke(false, msg)
            return
        }
        val cleanHost = host.trim()
        if (!allowNonPrivateOverride && !CompanionNetworkSecurity.isPrivateOrUsbHost(cleanHost)) {
            val msg = "Refused non-private host ($cleanHost): LAN or USB adb reverse only."
            stateMachine.onDisconnected(msg, fallbackToDevice = false)
            publishStats()
            onResult?.invoke(false, msg)
            return
        }
        if (!CompanionNetworkSecurity.isValidPairingCode(pairingCode)) {
            val msg = "Pairing code must be 6 digits."
            stateMachine.onDisconnected(msg, fallbackToDevice = false)
            publishStats()
            onResult?.invoke(false, msg)
            return
        }

        disconnect(triggerFallback = false)
        consentGranted = true
        currentHostLabel = "$cleanHost:$controlPort"
        stateMachine.onStartPairing()
        publishStats()

        ioExecutor.execute {
            val ok = performBlockingConnect(cleanHost, controlPort, udpPort, pairingCode)
            if (ok.first) {
                onResult?.invoke(true, ok.second)
            } else {
                stateMachine.onDisconnected(ok.second, fallbackToDevice = true)
                publishStats()
                onFallbackTriggered?.invoke(ok.second)
                onResult?.invoke(false, ok.second)
            }
        }
    }

    private fun performBlockingConnect(
        host: String,
        controlPort: Int,
        udpPort: Int,
        pairingCode: String
    ): Pair<Boolean, String> {
        return runCatching {
            val addr = InetAddress.getByName(host)
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(addr, controlPort), 4000)
            sock.soTimeout = 5000

            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))

            val clientNonce = CompanionNetworkSecurity.generateNonceHex(16)
            writer.write(CompanionProtocol.encodeControlHello(clientNonce))
            writer.newLine()
            writer.flush()

            val challengeRaw = reader.readLine() ?: throw IllegalStateException("No challenge from PC")
            val challenge = JSONObject(challengeRaw)
            val serverNonce = challenge.getString("serverNonce")
            val pcProof = challenge.optString("proof", "")
            val announcedUdpPort = challenge.optInt("udpPort", udpPort)

            val derivedKey = CompanionNetworkSecurity.deriveSessionKey(pairingCode, clientNonce, serverNonce)
            val expectedPcProof = CompanionNetworkSecurity.computeAuthProof(derivedKey, "SERVER:$clientNonce:$serverNonce")
            if (!expectedPcProof.equals(pcProof, ignoreCase = true)) {
                sock.close()
                throw SecurityException("6-digit pairing code mismatch")
            }

            val clientProof = CompanionNetworkSecurity.computeAuthProof(derivedKey, "CLIENT:$serverNonce:$clientNonce")
            writer.write(CompanionProtocol.encodeAuthProof(clientProof, stateMachine.qualitySelector))
            writer.newLine()
            writer.flush()

            val ackLine = reader.readLine() ?: throw IllegalStateException("No auth ack from PC")
            val ackJson = JSONObject(ackLine)
            if (!ackJson.optBoolean("ok", false)) {
                sock.close()
                throw SecurityException(ackJson.optString("error", "PC rejected auth"))
            }

            val udp = DatagramSocket()
            udp.soTimeout = 1000

            tcpSocket = sock
            tcpWriter = writer
            udpSocket = udp
            remoteAddr = addr
            remoteUdpPort = announcedUdpPort
            sessionKey = derivedKey
            clockSync.reset()
            lastBitrateResetMs = uptimeNowMs()
            uploadBytesWindow = 0L
            streamBytesWindow = 0L
            streamExpected = 0
            streamLost = 0
            lastStreamSeq = -1
            connected.set(true)

            sock.soTimeout = 0
            stateMachine.onConnected()
            cameraEncoder.start(stateMachine.effectiveTier)
            publishStats()

            startTcpReadLoop(reader)
            startUdpReadLoop(udp)
            startPingAndHealthLoop()
            true to "Connected to $host ($announcedUdpPort)"
        }.getOrElse { err ->
            false to (err.message ?: "Connection failed")
        }
    }

    private fun startTcpReadLoop(reader: BufferedReader) {
        ioExecutor.execute {
            while (connected.get()) {
                val line = runCatching { reader.readLine() }.getOrNull()
                if (line == null) {
                    if (connected.get()) {
                        handleLinkDrop("TCP control stream closed")
                    }
                    break
                }
                val key = sessionKey ?: continue
                val plaintext = runCatching {
                    val encBytes = Base64.decode(line, Base64.NO_WRAP)
                    CompanionNetworkSecurity.decryptAesGcm(key, encBytes)?.toString(Charsets.UTF_8)
                }.getOrNull() ?: continue

                handleControlJson(plaintext)
            }
        }
    }

    private fun startUdpReadLoop(udp: DatagramSocket) {
        ioExecutor.execute {
            val rxBuf = ByteArray(65507)
            val dp = DatagramPacket(rxBuf, rxBuf.size)
            while (connected.get()) {
                val ok = runCatching { udp.receive(dp) }.isSuccess
                if (!ok) continue
                val key = sessionKey ?: continue
                val packet = CompanionProtocol.decodeUdpPacket(dp.data, dp.length) ?: continue
                val plain = CompanionNetworkSecurity.decryptAesGcm(key, packet.payload) ?: continue
                if (packet.type == CompanionProtocol.PKT_STREAM_VIDEO) {
                    streamBytesWindow += plain.size
                    if (lastStreamSeq >= 0 && packet.seq > lastStreamSeq + 1) {
                        streamLost += (packet.seq - lastStreamSeq - 1)
                    }
                    streamExpected += 1 + if (lastStreamSeq >= 0 && packet.seq > lastStreamSeq + 1) (packet.seq - lastStreamSeq - 1) else 0
                    lastStreamSeq = packet.seq
                    streamDecoder.submitEncodedAccessUnit(plain)
                }
            }
        }
    }

    private fun startPingAndHealthLoop() {
        ioExecutor.execute {
            while (connected.get()) {
                val t1 = clockNowNs()
                sendEncryptedControl(CompanionProtocol.encodePing(pingSeq.incrementAndGet(), t1))

                val nowMs = uptimeNowMs()
                val lossPct = if (streamExpected > 20) (streamLost * 100f / streamExpected) else 0f
                val fallback = stateMachine.evaluateMetrics(
                    nowMs = nowMs,
                    rttMs = clockSync.rttMs,
                    jitterMs = clockSync.jitterMs,
                    packetLossPercent = lossPct,
                    handEndToEndMs = latestHandE2eMs
                )
                cameraEncoder.updateTier(stateMachine.effectiveTier)
                publishStats()
                if (fallback) {
                    val reason = stateMachine.statusBanner ?: "Sustained latency > ${highLatencyThresholdMs.toInt()} ms"
                    disconnectInternal(keepFallbackState = true)
                    onFallbackTriggered?.invoke(reason)
                    break
                }
                Thread.sleep(500L)
            }
        }
    }

    private fun handleControlJson(jsonText: String) {
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "pong" -> {
                val t1 = obj.optLong("t1Ns", 0L)
                val t2 = obj.optLong("t2PcNs", 0L)
                val t3 = obj.optLong("t3PcNs", 0L)
                val t4 = clockNowNs()
                if (t1 > 0L && t2 > 0L && t3 > 0L) {
                    clockSync.recordSample(t1, t2, t3, t4)
                }
            }
            "landmarks" -> {
                val (frameTsNs, hands) = CompanionProtocol.parseHandLandmarksMessage(obj)
                val nowNs = clockNowNs()
                val e2e = if (frameTsNs > 0L) ((nowNs - frameTsNs) * 1e-6f).coerceIn(1f, 600f) else clockSync.rttMs
                latestHandE2eMs = e2e
                onRemoteHandsReceived?.invoke(hands, frameTsNs, e2e)
            }
        }
    }

    private fun sendCameraAccessUnit(bytes: ByteArray, frameTimestampNs: Long, encodeMs: Float) {
        val key = sessionKey ?: return
        val udp = udpSocket ?: return
        val addr = remoteAddr ?: return
        if (!connected.get()) return
        latestEncodeMs = encodeMs

        val encrypted = CompanionNetworkSecurity.encryptAesGcm(key, bytes)
        uploadBytesWindow += encrypted.size
        val pkt = CompanionProtocol.encodeUdpPacket(
            CompanionProtocol.UdpPacket(
                type = CompanionProtocol.PKT_CAMERA_H264,
                flags = 0,
                seq = cameraSeq.incrementAndGet(),
                timestampNs = frameTimestampNs,
                payload = encrypted
            )
        )
        runCatching {
            udp.send(DatagramPacket(pkt, pkt.size, addr, remoteUdpPort))
        }
    }

    private fun sendEncryptedControl(plainJson: String) {
        val key = sessionKey ?: return
        val writer = tcpWriter ?: return
        val encrypted = CompanionNetworkSecurity.encryptAesGcm(key, plainJson.toByteArray(Charsets.UTF_8))
        val line = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        synchronized(tcpWriteLock) {
            runCatching {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }.onFailure {
                if (connected.get()) handleLinkDrop("TCP send error")
            }
        }
    }

    private fun handleLinkDrop(reason: String) {
        disconnectInternal(keepFallbackState = true)
        stateMachine.onDisconnected(reason, fallbackToDevice = true)
        publishStats()
        onFallbackTriggered?.invoke(reason)
    }

    fun disconnect(triggerFallback: Boolean = false) {
        disconnectInternal(keepFallbackState = triggerFallback)
        if (!triggerFallback) {
            stateMachine.onDisconnected("Disconnected by user", fallbackToDevice = false)
        } else {
            stateMachine.onDisconnected("Link closed", fallbackToDevice = true)
        }
        publishStats()
    }

    private fun disconnectInternal(keepFallbackState: Boolean) {
        connected.set(false)
        cameraEncoder.stop()
        streamDecoder.release()
        runCatching { tcpSocket?.close() }
        runCatching { udpSocket?.close() }
        tcpSocket = null
        tcpWriter = null
        udpSocket = null
        sessionKey = null
        if (!keepFallbackState) {
            consentGranted = false
        }
    }

    private fun publishStats() {
        val nowMs = uptimeNowMs()
        val elapsedSec = ((nowMs - lastBitrateResetMs).coerceAtLeast(500L)) / 1000f
        val upKbps = ((uploadBytesWindow * 8f / 1000f) / elapsedSec).toInt()
        val downKbps = ((streamBytesWindow * 8f / 1000f) / elapsedSec).toInt()
        if (elapsedSec >= 1.5f) {
            uploadBytesWindow = 0L
            streamBytesWindow = 0L
            lastBitrateResetMs = nowMs
        }
        val lossPct = if (streamExpected > 10) (streamLost * 100f / streamExpected) else 0f
        _stats.value = CompanionLinkStats(
            state = stateMachine.state,
            hostLabel = currentHostLabel,
            isUsbReverse = currentHostLabel.startsWith("127.0.0.1") || currentHostLabel.startsWith("localhost"),
            consentGranted = consentGranted,
            rttMs = clockSync.rttMs,
            jitterMs = clockSync.jitterMs,
            packetLossPercent = lossPct,
            uploadBitrateKbps = upKbps,
            streamBitrateKbps = downKbps,
            cameraEncodeMs = latestEncodeMs,
            streamDecodeMs = latestDecodeMs,
            handEndToEndMs = latestHandE2eMs,
            qualitySelector = stateMachine.qualitySelector,
            effectiveQualityTier = stateMachine.effectiveTier,
            statusBanner = stateMachine.statusBanner
        )
    }
}
