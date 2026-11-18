package com.example.xr.windows

import com.example.xr.tracking.Quat
import com.example.xr.tracking.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class WindowType(
    val defaultTitle: String,
    val defaultWidthM: Float,
    val defaultHeightM: Float,
    val countsAgainstActiveLimit: Boolean
) {
    APP_LIBRARY("Spatial Phone Launcher", 1.56f, 1.04f, true),
    SPATIAL_DOCK("Spatial Dock", 2.40f, 0.68f, false),
    BROWSER("Spatial Browser", 1.65f, 1.10f, true),
    MEDIA_PLAYER("Media3 Cinema", 1.75f, 1.02f, true),
    GALLERY("Photo Gallery", 1.40f, 0.96f, true),
    GLTF_VIEWER("glTF 2.0 Stage", 1.25f, 1.00f, false),
    SETTINGS("Quick Optics & Camera", 1.42f, 1.02f, false),
    DIAGNOSTICS("XR Diagnostics", 1.30f, 0.92f, false),
    HAND_LAB("Hand Debug Lab", 1.46f, 1.08f, false),
    PC_DESKTOP("PC Companion Stream", 1.85f, 1.06f, true),
    SPATIAL_KEYBOARD("Spatial Keyboard", 1.35f, 0.56f, false),
    CONTROL_CENTER("Control Center", 1.30f, 0.92f, false),
    INFO("Comfort & Honesty Guide", 1.35f, 0.95f, false)
}

enum class WindowHitRegion {
    CONTENT_BODY,
    TITLE_BAR,
    CLOSE_BUTTON,
    MINIMIZE_BUTTON,
    RESIZE_HANDLE,
    GRABBER_BAR
}

enum class SpatialWindowAction {
    FOCUS,
    MINIMIZE,
    CLOSE
}

enum class DockActionId(val label: String, val iconGlyph: String) {
    HOME("Home", "⌂"),
    APPS("Apps", "⊞"),
    BROWSER("Web", "🌐"),
    MEDIA("Media", "▶"),
    GALLERY("Photos", "🖼"),
    GLTF("3D", "◈"),
    AR_PASSTHROUGH("AR View", "◎"),
    ENVIRONMENTS("Envs", "✦"),
    SETTINGS("Optics", "⚙"),
    CONTROL_CENTER("Control", "☰"),
    HANDS_OFF("Hands", "✋")
}

data class SpatialWindowState(
    val id: String,
    val type: WindowType,
    val title: String = type.defaultTitle,
    val azimuthDeg: Float = 0f,
    val elevationDeg: Float = 4f,
    val distanceMeters: Float = 2.25f,
    val widthMeters: Float = type.defaultWidthM,
    val heightMeters: Float = type.defaultHeightM,
    val yawOffsetDeg: Float = 0f,
    val minimized: Boolean = false,
    val suspended: Boolean = false,
    val zOrder: Int = 1,
    val pixelWidth: Int = 1024,
    val pixelHeight: Int = if (type == WindowType.SPATIAL_DOCK) 340 else 640
) {
    fun computeWorldBasis(): WindowBasis {
        val az = azimuthDeg * (PI.toFloat() / 180f)
        val el = elevationDeg * (PI.toFloat() / 180f)
        val cosEl = cos(el)
        val center = Vec3(
            x = sin(az) * cosEl * distanceMeters,
            y = sin(el) * distanceMeters,
            z = -cos(az) * cosEl * distanceMeters
        )
        // Face origin (head center), plus optional yawOffsetDeg
        val toOrigin = (Vec3.ZERO - center).normalized()
        val baseYaw = Quat.fromAxisAngle(Vec3.UP, -az + (yawOffsetDeg * PI.toFloat() / 180f))
        val pitchRot = Quat.fromAxisAngle(Vec3.RIGHT, -el)
        val orientation = (baseYaw * pitchRot).normalized()
        val right = orientation.rotate(Vec3.RIGHT).normalized()
        val up = orientation.rotate(Vec3.UP).normalized()
        val normal = toOrigin
        return WindowBasis(center = center, right = right, up = up, normal = normal, orientation = orientation)
    }
}

data class WindowBasis(
    val center: Vec3,
    val right: Vec3,
    val up: Vec3,
    val normal: Vec3,
    val orientation: Quat
)

data class DockTarget(
    val action: DockActionId,
    val center: Vec3,
    val right: Vec3,
    val up: Vec3,
    val normal: Vec3,
    val width: Float,
    val height: Float
)

sealed interface RaycastTarget {
    data class WindowHit(
        val windowId: String,
        val region: WindowHitRegion,
        val u: Float,
        val v: Float,
        val distance: Float
    ) : RaycastTarget

    data class DockHit(
        val action: DockActionId,
        val distance: Float
    ) : RaycastTarget
}
