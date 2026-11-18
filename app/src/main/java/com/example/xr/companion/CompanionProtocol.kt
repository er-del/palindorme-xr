package com.example.xr.companion

import com.example.xr.hand.HandFrame
import com.example.xr.hand.HandSide
import com.example.xr.tracking.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class CompanionQualityTier(
    val label: String,
    val camWidth: Int,
    val camHeight: Int,
    val camFps: Int,
    val camBitrateBps: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val streamFps: Int,
    val streamBitrateKbps: Int
) {
    AUTO("Auto", 640, 480, 30, 1_100_000, 1280, 720, 60, 6_000),
    LOW("Low (Bandwidth Saver)", 320, 240, 20, 450_000, 960, 540, 30, 3_000),
    BALANCED("Balanced", 480, 360, 25, 850_000, 1280, 720, 60, 6_000),
    HIGH("High (USB / Wi-Fi 6)", 640, 480, 30, 1_500_000, 1920, 1080, 60, 12_000)
}

/**
 * Binary UDP packet framing + JSON control message encoding for the LAN/USB PC Companion link.
 * Header format (24 bytes big-endian):
 *   magic(2B: 0x5058) | type(1B) | flags(1B) | seq(4B) | timestampNs(8B) | payloadLen(4B) | reserved(4B)
 */
object CompanionProtocol {
    const val MAGIC: Short = 0x5058 // 'PX'
    const val HEADER_BYTES = 24
    const val DEFAULT_CONTROL_PORT = 19850
    const val DEFAULT_UDP_PORT = 19851
    const val SERVICE_TYPE = "_palindormexr._tcp."

    const val PKT_CAMERA_H264: Byte = 1
    const val PKT_STREAM_VIDEO: Byte = 2
    const val PKT_UDP_HEARTBEAT: Byte = 3

    data class UdpPacket(
        val type: Byte,
        val flags: Byte,
        val seq: Int,
        val timestampNs: Long,
        val payload: ByteArray
    )

    fun encodeUdpPacket(packet: UdpPacket): ByteArray {
        val out = ByteArray(HEADER_BYTES + packet.payload.size)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(MAGIC)
        buf.put(packet.type)
        buf.put(packet.flags)
        buf.putInt(packet.seq)
        buf.putLong(packet.timestampNs)
        buf.putInt(packet.payload.size)
        buf.putInt(0)
        buf.put(packet.payload)
        return out
    }

    fun decodeUdpPacket(data: ByteArray, length: Int = data.size): UdpPacket? {
        if (length < HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (buf.short != MAGIC) return null
        val type = buf.get()
        val flags = buf.get()
        val seq = buf.int
        val tsNs = buf.long
        val payloadLen = buf.int
        buf.int // reserved
        if (payloadLen < 0 || HEADER_BYTES + payloadLen > length) return null
        val payload = ByteArray(payloadLen)
        buf.get(payload)
        return UdpPacket(type = type, flags = flags, seq = seq, timestampNs = tsNs, payload = payload)
    }

    fun encodeControlHello(clientNonce: String): String = JSONObject()
        .put("type", "hello")
        .put("proto", 1)
        .put("clientNonce", clientNonce)
        .toString()

    fun encodeAuthProof(proofHex: String, quality: CompanionQualityTier): String = JSONObject()
        .put("type", "auth")
        .put("proof", proofHex)
        .put("quality", quality.name)
        .toString()

    fun encodePing(seq: Int, t1PhoneNs: Long): String = JSONObject()
        .put("type", "ping")
        .put("seq", seq)
        .put("t1Ns", t1PhoneNs)
        .toString()

    fun encodeQualityConfig(tier: CompanionQualityTier): String = JSONObject()
        .put("type", "quality")
        .put("tier", tier.name)
        .put("camW", tier.camWidth)
        .put("camH", tier.camHeight)
        .put("camFps", tier.camFps)
        .put("streamW", tier.streamWidth)
        .put("streamH", tier.streamHeight)
        .put("streamFps", tier.streamFps)
        .put("streamBitrateKbps", tier.streamBitrateKbps)
        .put("streamEnabled", true)
        .toString()

    fun encodeRemoteMouse(action: String, normX: Float, normY: Float, button: Int = 1, scrollDy: Float = 0f): String =
        JSONObject()
            .put("type", "input_mouse")
            .put("action", action)
            .put("x", normX.toDouble())
            .put("y", normY.toDouble())
            .put("button", button)
            .put("scroll", scrollDy.toDouble())
            .toString()

    fun encodeRemoteKey(keyCode: Int, unicodeChar: Int, action: Int): String =
        JSONObject()
            .put("type", "input_key")
            .put("keyCode", keyCode)
            .put("unicode", unicodeChar)
            .put("action", action)
            .toString()

    fun parseHandLandmarksMessage(
        obj: JSONObject,
        defaultHfovDeg: Float = 70f,
        defaultVfovDeg: Float = 55f
    ): Pair<Long, List<HandFrame>> {
        val frameTsNs = obj.optLong("frameTsNs", 0L)
        val arr = obj.optJSONArray("hands") ?: JSONArray()
        val hands = ArrayList<HandFrame>(arr.length())
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            val sideStr = h.optString("side", "RIGHT")
            val side = if (sideStr.equals("LEFT", ignoreCase = true)) HandSide.LEFT else HandSide.RIGHT
            val conf = h.optDouble("conf", 0.9).toFloat()
            val imgArr = h.optJSONArray("img") ?: continue
            val worldArr = h.optJSONArray("world")
            val imgPts = decodeVecArray(imgArr)
            val worldPts = if (worldArr != null && worldArr.length() >= 21) decodeVecArray(worldArr) else imgPts
            if (imgPts.size >= 21) {
                hands += HandFrame(
                    side = side,
                    confidence = conf,
                    imageLandmarks = imgPts,
                    worldLandmarks = worldPts,
                    timestampMs = (frameTsNs / 1_000_000L),
                    isMirrored = false,
                    frameTimestampNs = frameTsNs,
                    cameraHfovDeg = defaultHfovDeg,
                    cameraVfovDeg = defaultVfovDeg
                )
            }
        }
        return frameTsNs to hands
    }

    private fun decodeVecArray(arr: JSONArray): List<Vec3> {
        val out = ArrayList<Vec3>(arr.length())
        for (i in 0 until arr.length()) {
            val pt = arr.optJSONArray(i) ?: continue
            if (pt.length() >= 3) {
                out += Vec3(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat(), pt.getDouble(2).toFloat())
            }
        }
        return out
    }
}
