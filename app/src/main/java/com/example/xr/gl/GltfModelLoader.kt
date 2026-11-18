package com.example.xr.gl

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class MeshGeometry(
    val name: String,
    val positions: FloatArray, // x, y, z per vertex
    val normals: FloatArray,   // nx, ny, nz per vertex
    val indices: ShortArray
)

/**
 * Parses glTF 2.0 (.glb binary and embedded-buffer .gltf JSON) meshes
 * and generates 3 built-in procedural 3D meshes (< 100 KB total).
 */
object GltfModelLoader {

    fun loadPreset(index: Int): MeshGeometry = when (index % 3) {
        0 -> buildGyroPrism()
        1 -> buildOrbitalTorus()
        else -> buildCrystalOctahedron()
    }

    fun loadFromUri(context: Context, uri: Uri): MeshGeometry? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        if (bytes.size >= 12 &&
            bytes[0] == 'g'.code.toByte() &&
            bytes[1] == 'l'.code.toByte() &&
            bytes[2] == 'T'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            return parseGlb(bytes)
        }
        return parseGltfJson(String(bytes, Charsets.UTF_8))
    }

    internal fun parseGlb(bytes: ByteArray): MeshGeometry? {
        return runCatching {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.int
            val version = buf.int
            val length = buf.int
            if (magic != 0x46546C67 || version != 2 || length > bytes.size) return null

            var jsonStr: String? = null
            var binChunk: ByteArray? = null
            while (buf.remaining() >= 8) {
                val chunkLen = buf.int
                val chunkType = buf.int
                if (chunkLen < 0 || chunkLen > buf.remaining()) break
                val chunkBytes = ByteArray(chunkLen)
                buf.get(chunkBytes)
                if (chunkType == 0x4E4F534A) { // JSON
                    jsonStr = String(chunkBytes, Charsets.UTF_8)
                } else if (chunkType == 0x004E4942) { // BIN
                    binChunk = chunkBytes
                }
            }
            val json = jsonStr ?: return null
            extractFirstPrimitive(JSONObject(json), binChunk)
        }.getOrNull()
    }

    internal fun parseGltfJson(jsonText: String): MeshGeometry? {
        return runCatching {
            val root = JSONObject(jsonText)
            val buffers = root.optJSONArray("buffers") ?: return null
            val uri = buffers.optJSONObject(0)?.optString("uri").orEmpty()
            val bin = if (uri.startsWith("data:") && ";base64," in uri) {
                val b64 = uri.substringAfter(";base64,")
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } else {
                null
            }
            extractFirstPrimitive(root, bin)
        }.getOrNull()
    }

    private fun extractFirstPrimitive(root: JSONObject, binBuffer: ByteArray?): MeshGeometry? {
        val bin = binBuffer ?: return null
        val meshes = root.optJSONArray("meshes") ?: return null
        if (meshes.length() == 0) return null
        val meshObj = meshes.getJSONObject(0)
        val meshName = meshObj.optString("name", "Imported glTF")
        val prim = meshObj.getJSONArray("primitives").getJSONObject(0)
        val attrs = prim.getJSONObject("attributes")
        val posAccessorIdx = attrs.getInt("POSITION")
        val normAccessorIdx = attrs.optInt("NORMAL", -1)
        val idxAccessorIdx = prim.optInt("indices", -1)

        val accessors = root.getJSONArray("accessors")
        val bufferViews = root.getJSONArray("bufferViews")

        val posFloats = readFloatAccessor(accessors.getJSONObject(posAccessorIdx), bufferViews, bin, 3)
            ?: return null
        val normFloats = if (normAccessorIdx >= 0) {
            readFloatAccessor(accessors.getJSONObject(normAccessorIdx), bufferViews, bin, 3)
        } else {
            null
        } ?: FloatArray(posFloats.size).apply {
            for (i in indices step 3) {
                this[i + 1] = 1f
            }
        }

        val indices: ShortArray = if (idxAccessorIdx >= 0) {
            readIndexAccessor(accessors.getJSONObject(idxAccessorIdx), bufferViews, bin)
                ?: ShortArray(posFloats.size / 3) { it.toShort() }
        } else {
            ShortArray(posFloats.size / 3) { it.toShort() }
        }

        // Normalize model scale to ~0.45m bounding sphere so it fits neatly on the Spatial Stage
        var maxR = 0.001f
        for (i in posFloats.indices step 3) {
            val r = kotlin.math.sqrt(
                posFloats[i] * posFloats[i] +
                    posFloats[i + 1] * posFloats[i + 1] +
                    posFloats[i + 2] * posFloats[i + 2]
            )
            if (r > maxR) maxR = r
        }
        val scale = 0.28f / maxR
        for (i in posFloats.indices) posFloats[i] *= scale

        return MeshGeometry(meshName, posFloats, normFloats, indices)
    }

    private fun readFloatAccessor(
        accessor: JSONObject,
        bufferViews: org.json.JSONArray,
        bin: ByteArray,
        components: Int
    ): FloatArray? {
        val bv = bufferViews.getJSONObject(accessor.getInt("bufferView"))
        val offset = bv.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
        val stride = bv.optInt("byteStride", components * 4).coerceAtLeast(components * 4)
        val count = accessor.getInt("count")
        val out = FloatArray(count * components)
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val base = offset + i * stride
            if (base + components * 4 > bin.size) return null
            for (c in 0 until components) {
                out[i * components + c] = bb.getFloat(base + c * 4)
            }
        }
        return out
    }

    private fun readIndexAccessor(
        accessor: JSONObject,
        bufferViews: org.json.JSONArray,
        bin: ByteArray
    ): ShortArray? {
        val bv = bufferViews.getJSONObject(accessor.getInt("bufferView"))
        val offset = bv.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
        val count = accessor.getInt("count")
        val compType = accessor.getInt("componentType") // 5123=USHORT, 5125=UINT, 5121=UBYTE
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(count)
        for (i in 0 until count) {
            out[i] = when (compType) {
                5123 -> bb.getShort(offset + i * 2)
                5125 -> bb.getInt(offset + i * 4).toShort()
                5121 -> (bin[offset + i].toInt() and 0xFF).toShort()
                else -> i.toShort()
            }
        }
        return out
    }

    private fun buildCrystalOctahedron(): MeshGeometry {
        val r = 0.24f
        val verts = floatArrayOf(
            0f, r, 0f,
            r, 0f, 0f,
            0f, 0f, r,
            -r, 0f, 0f,
            0f, 0f, -r,
            0f, -r, 0f
        )
        val norms = floatArrayOf(
            0f, 1f, 0f,
            1f, 0f, 0f,
            0f, 0f, 1f,
            -1f, 0f, 0f,
            0f, 0f, -1f,
            0f, -1f, 0f
        )
        val idx = shortArrayOf(
            0, 2, 1, 0, 1, 4, 0, 4, 3, 0, 3, 2,
            5, 1, 2, 5, 4, 1, 5, 3, 4, 5, 2, 3
        )
        return MeshGeometry("Crystal Polyhedron", verts, norms, idx)
    }

    private fun buildGyroPrism(): MeshGeometry {
        val sides = 12
        val pos = mutableListOf<Float>()
        val norm = mutableListOf<Float>()
        val idx = mutableListOf<Short>()

        pos.addAll(listOf(0f, 0.25f, 0f))
        norm.addAll(listOf(0f, 1f, 0f))
        pos.addAll(listOf(0f, -0.25f, 0f))
        norm.addAll(listOf(0f, -1f, 0f))

        for (i in 0 until sides) {
            val a = (i.toFloat() / sides) * (2f * PI.toFloat())
            val x = cos(a) * 0.22f
            val z = sin(a) * 0.22f
            pos.addAll(listOf(x, 0f, z))
            norm.addAll(listOf(cos(a), 0f, sin(a)))
        }

        for (i in 0 until sides) {
            val curr = (2 + i).toShort()
            val next = (2 + (i + 1) % sides).toShort()
            idx.addAll(listOf(0, next, curr))
            idx.addAll(listOf(1, curr, next))
        }
        return MeshGeometry("Palindorme Gyro Prism", pos.toFloatArray(), norm.toFloatArray(), idx.toShortArray())
    }

    private fun buildOrbitalTorus(): MeshGeometry {
        val majorSegs = 24
        val minorSegs = 12
        val R = 0.20f
        val r = 0.06f
        val pos = mutableListOf<Float>()
        val norm = mutableListOf<Float>()
        val idx = mutableListOf<Short>()

        for (i in 0..majorSegs) {
            val u = (i.toFloat() / majorSegs) * 2f * PI.toFloat()
            val cu = cos(u)
            val su = sin(u)
            for (j in 0..minorSegs) {
                val v = (j.toFloat() / minorSegs) * 2f * PI.toFloat()
                val cv = cos(v)
                val sv = sin(v)
                val x = (R + r * cv) * cu
                val y = r * sv
                val z = (R + r * cv) * su
                pos.addAll(listOf(x, y, z))
                norm.addAll(listOf(cv * cu, sv, cv * su))
            }
        }
        val stride = minorSegs + 1
        for (i in 0 until majorSegs) {
            for (j in 0 until minorSegs) {
                val a = (i * stride + j).toShort()
                val b = ((i + 1) * stride + j).toShort()
                val c = ((i + 1) * stride + j + 1).toShort()
                val d = (i * stride + j + 1).toShort()
                idx.addAll(listOf(a, b, d, b, c, d))
            }
        }
        return MeshGeometry("Orbital Telemetry Ring", pos.toFloatArray(), norm.toFloatArray(), idx.toShortArray())
    }
}
