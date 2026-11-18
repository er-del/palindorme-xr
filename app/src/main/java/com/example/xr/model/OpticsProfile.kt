package com.example.xr.model

import android.content.Context
import org.json.JSONObject
import kotlin.math.sqrt

enum class OpticsPreset(
    val displayName: String,
    val description: String,
    val ipdMm: Float,
    val fovDegrees: Float,
    val k1: Float,
    val k2: Float,
    val p1: Float = 0f,
    val p2: Float = 0f
) {
    OPEN_VR_STANDARD(
        displayName = "Open VR Standard",
        description = "Balanced 63 mm IPD, 90° FOV target for standard viewers",
        ipdMm = 63.0f,
        fovDegrees = 90.0f,
        k1 = -0.18f,
        k2 = 0.04f
    ),
    CARDBOARD_V2(
        displayName = "Cardboard v2",
        description = "60 mm IPD, 80° FOV, 37 mm biconvex acrylic lenses",
        ipdMm = 60.0f,
        fovDegrees = 80.0f,
        k1 = -0.15f,
        k2 = 0.03f
    ),
    BOBOVR_Z6(
        displayName = "BoboVR Z6 / Wide",
        description = "64 mm IPD, 102° FOV for large 52 mm aspheric lenses",
        ipdMm = 64.0f,
        fovDegrees = 102.0f,
        k1 = -0.23f,
        k2 = 0.06f
    ),
    SHINECON_G04E(
        displayName = "VR Shinecon",
        description = "63 mm IPD, 92° FOV for adjustable slider holders",
        ipdMm = 63.0f,
        fovDegrees = 92.0f,
        k1 = -0.19f,
        k2 = 0.04f
    ),
    WIDE_FOV(
        displayName = "Wide-FOV Generic",
        description = "64 mm IPD, 100° FOV target for deep-immersion lenses",
        ipdMm = 64.0f,
        fovDegrees = 100.0f,
        k1 = -0.22f,
        k2 = 0.05f
    ),
    COMPACT_VIEWER(
        displayName = "Compact Viewer",
        description = "60 mm IPD, 80° FOV target for compact / folding headsets",
        ipdMm = 60.0f,
        fovDegrees = 80.0f,
        k1 = -0.14f,
        k2 = 0.03f
    ),
    CUSTOM(
        displayName = "Custom / Imported",
        description = "User-fitted or QR/JSON-imported Brown-Conrady distortion",
        ipdMm = 63.0f,
        fovDegrees = 90.0f,
        k1 = -0.18f,
        k2 = 0.04f
    )
}

data class OpticsProfile(
    val preset: OpticsPreset = OpticsPreset.OPEN_VR_STANDARD,
    val ipdMm: Float = preset.ipdMm,
    val fovDegrees: Float = preset.fovDegrees,
    val k1: Float = preset.k1,
    val k2: Float = preset.k2,
    val p1: Float = preset.p1,
    val p2: Float = preset.p2,
    val chromaticAberration: Float = 0.014f,
    val leftEyeVerticalOffset: Float = 0.0f,
    val rightEyeVerticalOffset: Float = 0.0f,
    val renderScale: Float = 0.90f,
    val xdpi: Float = 409f,
    val ydpi: Float = 409f,
    val centerGapDp: Float = 0f,
    val lensSafeMarginFrac: Float = 0.06f
) {
    /**
     * Converts IPD in millimeters to half-screen horizontal pixel span using physical DPI.
     */
    fun ipdPixels(screenWidthPx: Int): Float {
        val pxPerMm = (xdpi.coerceIn(120f, 900f)) / 25.4f
        val desiredPx = ipdMm * pxPerMm
        return desiredPx.coerceIn(screenWidthPx * 0.35f, screenWidthPx * 0.65f)
    }

    fun eyeSeparationMeters(): Float = (ipdMm / 1000f).coerceIn(0.050f, 0.075f)

    fun estimatedHorizontalFoVDegFromScreen(screenWidthPx: Int): Float {
        val screenWidthMm = (screenWidthPx / xdpi.coerceIn(120f, 900f)) * 25.4f
        val lensSpacingFactor = (screenWidthMm / 145f).coerceIn(0.82f, 1.15f)
        return (fovDegrees * lensSpacingFactor).coerceIn(65f, 115f)
    }

    companion object {
        fun fromDisplayMetrics(context: Context): OpticsProfile {
            val dm = context.resources.displayMetrics
            val xdpi = dm.xdpi.takeIf { it in 120f..900f } ?: 409f
            val ydpi = dm.ydpi.takeIf { it in 120f..900f } ?: 409f
            val longPx = maxOf(dm.widthPixels, dm.heightPixels)
            val shortPx = minOf(dm.widthPixels, dm.heightPixels)
            val widthInches = longPx / xdpi
            val heightInches = shortPx / ydpi
            val diagInches = sqrt(widthInches * widthInches + heightInches * heightInches)
            val preset = if (diagInches >= 6.2f) OpticsPreset.OPEN_VR_STANDARD else OpticsPreset.COMPACT_VIEWER
            return OpticsProfile(
                preset = preset,
                ipdMm = preset.ipdMm,
                fovDegrees = preset.fovDegrees,
                k1 = preset.k1,
                k2 = preset.k2,
                p1 = preset.p1,
                p2 = preset.p2,
                xdpi = xdpi,
                ydpi = ydpi
            )
        }

        /**
         * Imports JSON ({"ipdMm":63,"fovDegrees":92,"k1":-0.19,"k2":0.04,"p1":0,"p2":0})
         * or key=value / Cardboard-style query URL strings.
         */
        fun importFromString(raw: String, base: OpticsProfile = OpticsProfile()): OpticsProfile? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                return runCatching {
                    val obj = JSONObject(trimmed)
                    base.copy(
                        preset = OpticsPreset.CUSTOM,
                        ipdMm = obj.optDouble("ipdMm", base.ipdMm.toDouble()).toFloat().coerceIn(52f, 75f),
                        fovDegrees = obj.optDouble("fovDegrees", base.fovDegrees.toDouble()).toFloat().coerceIn(65f, 115f),
                        k1 = obj.optDouble("k1", base.k1.toDouble()).toFloat().coerceIn(-0.45f, 0.25f),
                        k2 = obj.optDouble("k2", base.k2.toDouble()).toFloat().coerceIn(-0.20f, 0.25f),
                        p1 = obj.optDouble("p1", base.p1.toDouble()).toFloat().coerceIn(-0.08f, 0.08f),
                        p2 = obj.optDouble("p2", base.p2.toDouble()).toFloat().coerceIn(-0.08f, 0.08f)
                    )
                }.getOrNull()
            }

            // Parse URL query or comma/semicolon separated key=value pairs
            val query = trimmed.substringAfter('?', trimmed)
            val pairs = query.split('&', ',', ';', '\n')
                .mapNotNull { token ->
                    val parts = token.split('=', ':')
                    if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim().toFloatOrNull() else null
                }
                .toMap()
            if (pairs.isEmpty()) return null
            val hasValid = listOf("ipd", "ipdmm", "fov", "fovdegrees", "k1", "k2", "p1", "p2").any { pairs[it] != null }
            if (!hasValid) return null

            return base.copy(
                preset = OpticsPreset.CUSTOM,
                ipdMm = (pairs["ipdmm"] ?: pairs["ipd"] ?: base.ipdMm).coerceIn(52f, 75f),
                fovDegrees = (pairs["fovdegrees"] ?: pairs["fov"] ?: base.fovDegrees).coerceIn(65f, 115f),
                k1 = (pairs["k1"] ?: base.k1).coerceIn(-0.45f, 0.25f),
                k2 = (pairs["k2"] ?: base.k2).coerceIn(-0.20f, 0.25f),
                p1 = (pairs["p1"] ?: base.p1).coerceIn(-0.08f, 0.08f),
                p2 = (pairs["p2"] ?: base.p2).coerceIn(-0.08f, 0.08f)
            )
        }
    }
}

