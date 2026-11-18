package com.example.xr.hand

import android.content.Context
import com.example.xr.tracking.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecordedTraceFrame(
    val timestampMs: Long,
    val hands: List<HandFrame>
)

/**
 * Captures and replays landmarks-only JSONL traces (never camera images)
 * for Hand Debug Lab diagnostics and deterministic unit/Robolectric replay tests.
 */
class HandTraceRecorder(private val maxFrames: Int = 300) {

    private val lock = Any()
    private var recording = false
    private var startMs = 0L
    private val captured = mutableListOf<RecordedTraceFrame>()

    fun isRecording(): Boolean = synchronized(lock) { recording }

    fun frameCount(): Int = synchronized(lock) { captured.size }

    fun startRecording(nowMs: Long) = synchronized(lock) {
        captured.clear()
        startMs = nowMs
        recording = true
    }

    fun recordFrame(timestampMs: Long, hands: List<HandFrame>) = synchronized(lock) {
        if (!recording) return@synchronized
        val relMs = (timestampMs - startMs).coerceAtLeast(0L)
        captured += RecordedTraceFrame(relMs, hands.map { it.copy(timestampMs = relMs) })
        if (captured.size >= maxFrames) {
            recording = false
        }
    }

    fun stopRecording(): List<RecordedTraceFrame> = synchronized(lock) {
        recording = false
        captured.toList()
    }

    fun saveTraceToFile(context: Context, frames: List<RecordedTraceFrame>): File? {
        if (frames.isEmpty()) return null
        return runCatching {
            val dir = File(context.filesDir, "hand_traces").apply { mkdirs() }
            val file = File(dir, "hand_trace_${System.currentTimeMillis()}.jsonl")
            file.writeText(serializeTraceJsonl(frames))
            file
        }.getOrNull()
    }

    companion object {
        fun serializeTraceJsonl(frames: List<RecordedTraceFrame>): String {
            val sb = StringBuilder()
            for (frame in frames) {
                val obj = JSONObject()
                obj.put("tMs", frame.timestampMs)
                val handsArr = JSONArray()
                for (h in frame.hands) {
                    val hObj = JSONObject()
                    hObj.put("side", h.side.name)
                    hObj.put("conf", h.confidence.toDouble())
                    hObj.put("mirrored", h.isMirrored)
                    hObj.put("hfov", h.cameraHfovDeg.toDouble())
                    hObj.put("vfov", h.cameraVfovDeg.toDouble())
                    hObj.put("img", encodeVec3List(h.imageLandmarks))
                    hObj.put("world", encodeVec3List(h.worldLandmarks))
                    handsArr.put(hObj)
                }
                obj.put("hands", handsArr)
                sb.append(obj.toString()).append('\n')
            }
            return sb.toString()
        }

        fun parseTraceJsonl(jsonl: String): List<RecordedTraceFrame> {
            val out = mutableListOf<RecordedTraceFrame>()
            for (rawLine in jsonl.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                runCatching {
                    val obj = JSONObject(line)
                    val tMs = obj.getLong("tMs")
                    val handsArr = obj.getJSONArray("hands")
                    val hands = mutableListOf<HandFrame>()
                    for (i in 0 until handsArr.length()) {
                        val hObj = handsArr.getJSONObject(i)
                        val side = HandSide.valueOf(hObj.optString("side", "RIGHT"))
                        val conf = hObj.optDouble("conf", 0.9).toFloat()
                        val mirrored = hObj.optBoolean("mirrored", false)
                        val hfov = hObj.optDouble("hfov", 70.0).toFloat()
                        val vfov = hObj.optDouble("vfov", 55.0).toFloat()
                        val img = decodeVec3List(hObj.getJSONArray("img"))
                        val world = decodeVec3List(hObj.optJSONArray("world") ?: hObj.getJSONArray("img"))
                        hands += HandFrame(
                            side = side,
                            confidence = conf,
                            imageLandmarks = img,
                            worldLandmarks = world,
                            timestampMs = tMs,
                            isMirrored = mirrored,
                            cameraHfovDeg = hfov,
                            cameraVfovDeg = vfov
                        )
                    }
                    out += RecordedTraceFrame(tMs, hands)
                }
            }
            return out
        }

        private fun encodeVec3List(pts: List<Vec3>): JSONArray {
            val arr = JSONArray()
            for (p in pts) {
                val pt = JSONArray()
                pt.put(p.x.toDouble())
                pt.put(p.y.toDouble())
                pt.put(p.z.toDouble())
                arr.put(pt)
            }
            return arr
        }

        private fun decodeVec3List(arr: JSONArray): List<Vec3> {
            val list = ArrayList<Vec3>(arr.length())
            for (i in 0 until arr.length()) {
                val pt = arr.getJSONArray(i)
                list += Vec3(
                    pt.getDouble(0).toFloat(),
                    pt.getDouble(1).toFloat(),
                    pt.getDouble(2).toFloat()
                )
            }
            return list
        }
    }
}
