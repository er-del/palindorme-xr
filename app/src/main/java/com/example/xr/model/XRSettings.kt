package com.example.xr.model

enum class HandednessPreference(val label: String) {
    RIGHT("Right Hand"),
    LEFT("Left Hand"),
    BOTH("Both Hands")
}

enum class TrackingSourceMode(val label: String) {
    AUTO("Auto (Game Rotation Vector)"),
    ROTATION_VECTOR("Rotation Vector (Compass)"),
    MANUAL_ONLY("Touch / Gamepad Only")
}

enum class CinemaProjectionMode(val label: String) {
    MONO_2D("2D Screen"),
    SBS_3D("3D Side-by-Side"),
    VR_180("180° Wide"),
    VR_360("360° Sphere")
}

enum class ThermalPriority(val label: String) {
    HANDS_PRIORITY("Prioritize Hands"),
    ARCORE_PRIORITY("Prioritize ARCore 6-DOF")
}

data class HandCalibrationProfile(
    val palmWidthMeters: Float = 0.085f,
    val pinchClosedThreshold: Float = 0.25f,
    val pinchOpenThreshold: Float = 0.45f,
    val calibrated: Boolean = false
)

data class XRSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val hasCompletedWelcome: Boolean = false,
    val startInStereoMode: Boolean = true,
    val optics: OpticsProfile = OpticsProfile(),
    val environment: EnvironmentConfig = EnvironmentConfig(),

    // Interaction & Gaze
    val gazeDwellSeconds: Float = 1.5f,
    val autoGazeFallbackWhenHandsLost: Boolean = true,
    val audioClickEnabled: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val leftHandedController: Boolean = false,
    val bluetoothClickerEnabled: Boolean = true,

    // Hand Interaction (Look + Pinch default like Vision Pro / Galaxy XR; optional Hand-Ray)
    val handControlEnabled: Boolean = false,
    val handRayModeEnabled: Boolean = false,
    val handSkeletonOverlay: Boolean = true,
    val relaxedPinchSensitivity: Boolean = false,
    val oneHandedMode: Boolean = false,
    val handMinConfidence: Float = 0.45f,
    val gestureSizeGain: Float = 1.0f,
    val preferredCameraId: String? = null,
    val handedness: HandednessPreference = HandednessPreference.BOTH,
    val handCalibration: HandCalibrationProfile = HandCalibrationProfile(),

    // Tracking & Prediction
    val trackingSourceMode: TrackingSourceMode = TrackingSourceMode.AUTO,
    val predictionMs: Float = 25f,
    val autoRecenterOnStart: Boolean = true,

    // Passthrough, Immersion & ARCore
    val passthroughEnabled: Boolean = false,
    val immersionLevel: Float = 1.0f, // 0.0 = full passthrough, 1.0 = full virtual environment
    val passthroughBrightness: Float = 1.0f,
    val passthroughExposureComp: Float = 0.0f,
    val passthroughUndistort: Boolean = true,
    val cameraRotationDegrees: Int = 0, // 0, 90, 180, 270
    val cameraMirrorHorizontal: Boolean = false,
    val galleryRotationDegrees: Int = 0, // 0, 90, 180, 270
    val showCalibrationGrid: Boolean = false,
    val arCoreEnabled: Boolean = false,
    val arCoreSixDofEnabled: Boolean = true,
    val cinemaModeEnabled: Boolean = false,
    val cinemaProjectionMode: CinemaProjectionMode = CinemaProjectionMode.MONO_2D,

    // Comfort, Thermal & Display
    val thermalPriority: ThermalPriority = ThermalPriority.HANDS_PRIORITY,
    val angularScaleWindowsByDepth: Boolean = true,
    val prefer90HzWhenThermallySafe: Boolean = true,
    val sessionBreakMinutes: Int = 30,
    val largeTextMode: Boolean = false,
    val highContrastGlass: Boolean = false,
    val reduceMotion: Boolean = false,
    val lowPowerMode: Boolean = false,
    val softwareWebViewFallback: Boolean = false,
    val returnNotificationEnabled: Boolean = true,

    // Opt-in Voice & Browser
    val voiceCommandsEnabled: Boolean = false,
    val browserHomeUrl: String = "https://en.wikipedia.org/wiki/Spatial_computing",
    val browserHttpsOnly: Boolean = true,
    val favoriteApps: Set<String> = emptySet(),
    val recentApps: List<String> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

