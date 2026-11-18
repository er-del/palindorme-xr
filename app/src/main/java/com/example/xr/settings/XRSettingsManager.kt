package com.example.xr.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.xr.model.CinemaProjectionMode
import com.example.xr.model.EnvironmentConfig
import com.example.xr.model.HandCalibrationProfile
import com.example.xr.model.HandednessPreference
import com.example.xr.model.OpticsPreset
import com.example.xr.model.OpticsProfile
import com.example.xr.model.ThermalPriority
import com.example.xr.model.TrackingSourceMode
import com.example.xr.model.VirtualEnvironmentType
import com.example.xr.model.XRSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.xrDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "palindorme_xr_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class XRSettingsManager(private val context: Context) {

    private object Keys {
        val SCHEMA_VERSION = intPreferencesKey("schema_version")
        val WELCOME_DONE = booleanPreferencesKey("welcome_done")
        val START_STEREO = booleanPreferencesKey("start_stereo")

        val OPTICS_PRESET = stringPreferencesKey("optics_preset")
        val IPD_MM = floatPreferencesKey("ipd_mm")
        val FOV_DEG = floatPreferencesKey("fov_deg")
        val DISTORT_K1 = floatPreferencesKey("distort_k1")
        val DISTORT_K2 = floatPreferencesKey("distort_k2")
        val DISTORT_P1 = floatPreferencesKey("distort_p1")
        val DISTORT_P2 = floatPreferencesKey("distort_p2")
        val CHROMATIC = floatPreferencesKey("chromatic_aberration")
        val LEFT_EYE_Y = floatPreferencesKey("left_eye_y")
        val RIGHT_EYE_Y = floatPreferencesKey("right_eye_y")
        val RENDER_SCALE = floatPreferencesKey("render_scale")

        val ENV_TYPE = stringPreferencesKey("env_type")
        val ENV_TINT = longPreferencesKey("env_tint")
        val ENV_AMBIENT = floatPreferencesKey("env_ambient")
        val ENV_PARTICLES = floatPreferencesKey("env_particles")
        val ENV_EFFECTS = booleanPreferencesKey("env_effects")
        val ENV_BRIGHTNESS = floatPreferencesKey("env_brightness")

        val GAZE_DWELL = floatPreferencesKey("gaze_dwell")
        val AUTO_GAZE_FALLBACK = booleanPreferencesKey("auto_gaze_fallback")
        val AUDIO_CLICK = booleanPreferencesKey("audio_click")
        val HAPTICS = booleanPreferencesKey("haptics")
        val LEFT_CONTROLLER = booleanPreferencesKey("left_controller")
        val BLUETOOTH_CLICKER = booleanPreferencesKey("bluetooth_clicker")

        val HAND_ENABLED = booleanPreferencesKey("hand_enabled")
        val HAND_RAY_MODE = booleanPreferencesKey("hand_ray_mode")
        val HAND_SKELETON = booleanPreferencesKey("hand_skeleton")
        val RELAXED_PINCH = booleanPreferencesKey("relaxed_pinch")
        val ONE_HANDED_MODE = booleanPreferencesKey("one_handed_mode")
        val HAND_MIN_CONFIDENCE = floatPreferencesKey("hand_min_conf")
        val GESTURE_SIZE_GAIN = floatPreferencesKey("gesture_size_gain")
        val PREFERRED_CAMERA_ID = stringPreferencesKey("preferred_camera_id")
        val HANDEDNESS = stringPreferencesKey("handedness")
        val PALM_WIDTH = floatPreferencesKey("palm_width")
        val PINCH_CLOSE = floatPreferencesKey("pinch_close")
        val PINCH_OPEN = floatPreferencesKey("pinch_open")
        val HAND_CALIBRATED = booleanPreferencesKey("hand_calibrated")

        val TRACKING_MODE = stringPreferencesKey("tracking_mode")
        val PREDICTION_MS = floatPreferencesKey("prediction_ms")
        val AUTO_RECENTER = booleanPreferencesKey("auto_recenter")

        val PASSTHROUGH_BRIGHTNESS = floatPreferencesKey("pt_brightness")
        val PASSTHROUGH_EXPOSURE = floatPreferencesKey("pt_exposure")
        val PASSTHROUGH_UNDISTORT = booleanPreferencesKey("pt_undistort")
        val IMMERSION_LEVEL = floatPreferencesKey("immersion_level")
        val CAMERA_ROT_DEG = intPreferencesKey("camera_rot_deg")
        val CAMERA_MIRROR = booleanPreferencesKey("camera_mirror")
        val GALLERY_ROT_DEG = intPreferencesKey("gallery_rot_deg")
        val CALIBRATION_GRID = booleanPreferencesKey("calibration_grid")
        val ARCORE_ENABLED = booleanPreferencesKey("arcore_enabled")
        val ARCORE_SIXDOF = booleanPreferencesKey("arcore_sixdof")
        val CINEMA_MODE = booleanPreferencesKey("cinema_mode")
        val CINEMA_PROJ = stringPreferencesKey("cinema_proj")

        val THERMAL_PRIORITY = stringPreferencesKey("thermal_priority")
        val ANGULAR_SCALE_WINDOWS = booleanPreferencesKey("angular_scale_windows")
        val PREFER_90HZ = booleanPreferencesKey("prefer_90hz")
        val BREAK_MINUTES = intPreferencesKey("break_minutes")
        val LARGE_TEXT = booleanPreferencesKey("large_text")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val LOW_POWER = booleanPreferencesKey("low_power")
        val SOFTWARE_WEBVIEW = booleanPreferencesKey("software_webview")
        val RETURN_NOTIF = booleanPreferencesKey("return_notif")

        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val BROWSER_URL = stringPreferencesKey("browser_url")
        val BROWSER_HTTPS = booleanPreferencesKey("browser_https")
        val FAVORITE_APPS = stringSetPreferencesKey("favorite_apps")
        val RECENT_APPS = stringPreferencesKey("recent_apps")
    }

    private val metrics = context.resources.displayMetrics

    val settingsFlow: Flow<XRSettings> = context.xrDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs -> mapPreferencesToSettings(prefs) }

    internal fun mapPreferencesToSettings(prefs: Preferences): XRSettings {
        val storedVersion = prefs[Keys.SCHEMA_VERSION] ?: XRSettings.CURRENT_SCHEMA_VERSION
        val defaultOptics = OpticsProfile.fromDisplayMetrics(context)
        val presetName = prefs[Keys.OPTICS_PRESET] ?: defaultOptics.preset.name
        val preset = OpticsPreset.entries.firstOrNull { it.name == presetName }
            ?: defaultOptics.preset

        val envType = VirtualEnvironmentType.fromId(
            prefs[Keys.ENV_TYPE] ?: VirtualEnvironmentType.MINIMAL_LAB.id
        )
        val handedness = HandednessPreference.entries.firstOrNull {
            it.name == prefs[Keys.HANDEDNESS]
        } ?: HandednessPreference.BOTH

        val trackingMode = TrackingSourceMode.entries.firstOrNull {
            it.name == prefs[Keys.TRACKING_MODE]
        } ?: TrackingSourceMode.AUTO

        val cinemaProj = CinemaProjectionMode.entries.firstOrNull {
            it.name == prefs[Keys.CINEMA_PROJ]
        } ?: CinemaProjectionMode.MONO_2D

        val thermalPriority = ThermalPriority.entries.firstOrNull {
            it.name == prefs[Keys.THERMAL_PRIORITY]
        } ?: ThermalPriority.HANDS_PRIORITY

        val recentsRaw = prefs[Keys.RECENT_APPS].orEmpty()
        val recents = if (recentsRaw.isBlank()) emptyList() else recentsRaw.split("|").filter { it.isNotBlank() }

        return XRSettings(
            schemaVersion = storedVersion,
            hasCompletedWelcome = prefs[Keys.WELCOME_DONE] ?: false,
            startInStereoMode = prefs[Keys.START_STEREO] ?: true,
            optics = OpticsProfile(
                preset = preset,
                ipdMm = (prefs[Keys.IPD_MM] ?: preset.ipdMm).coerceIn(52f, 75f),
                fovDegrees = (prefs[Keys.FOV_DEG] ?: preset.fovDegrees).coerceIn(65f, 115f),
                k1 = (prefs[Keys.DISTORT_K1] ?: preset.k1).coerceIn(-0.45f, 0.25f),
                k2 = (prefs[Keys.DISTORT_K2] ?: preset.k2).coerceIn(-0.20f, 0.25f),
                p1 = (prefs[Keys.DISTORT_P1] ?: preset.p1).coerceIn(-0.08f, 0.08f),
                p2 = (prefs[Keys.DISTORT_P2] ?: preset.p2).coerceIn(-0.08f, 0.08f),
                chromaticAberration = (prefs[Keys.CHROMATIC] ?: 0.014f).coerceIn(0f, 0.04f),
                leftEyeVerticalOffset = (prefs[Keys.LEFT_EYE_Y] ?: 0f).coerceIn(-0.08f, 0.08f),
                rightEyeVerticalOffset = (prefs[Keys.RIGHT_EYE_Y] ?: 0f).coerceIn(-0.08f, 0.08f),
                renderScale = (prefs[Keys.RENDER_SCALE] ?: 0.90f).coerceIn(0.6f, 1.0f),
                xdpi = metrics.xdpi.takeIf { it > 60f } ?: 409f,
                ydpi = metrics.ydpi.takeIf { it > 60f } ?: 409f
            ),
            environment = EnvironmentConfig(
                type = envType,
                tintHex = prefs[Keys.ENV_TINT] ?: envType.defaultTintHex,
                ambientLight = (prefs[Keys.ENV_AMBIENT] ?: 0.65f).coerceIn(0.1f, 1.0f),
                particleDensity = (prefs[Keys.ENV_PARTICLES] ?: 0.70f).coerceIn(0f, 1.0f),
                effectsEnabled = prefs[Keys.ENV_EFFECTS] ?: true,
                brightness = (prefs[Keys.ENV_BRIGHTNESS] ?: 0.90f).coerceIn(0.2f, 1.0f)
            ),
            gazeDwellSeconds = (prefs[Keys.GAZE_DWELL] ?: 1.5f).coerceIn(0.5f, 2.5f),
            autoGazeFallbackWhenHandsLost = prefs[Keys.AUTO_GAZE_FALLBACK] ?: true,
            audioClickEnabled = prefs[Keys.AUDIO_CLICK] ?: true,
            hapticFeedbackEnabled = prefs[Keys.HAPTICS] ?: true,
            leftHandedController = prefs[Keys.LEFT_CONTROLLER] ?: false,
            bluetoothClickerEnabled = prefs[Keys.BLUETOOTH_CLICKER] ?: true,
            handControlEnabled = prefs[Keys.HAND_ENABLED] ?: false,
            handRayModeEnabled = prefs[Keys.HAND_RAY_MODE] ?: false,
            handSkeletonOverlay = prefs[Keys.HAND_SKELETON] ?: true,
            relaxedPinchSensitivity = prefs[Keys.RELAXED_PINCH] ?: false,
            oneHandedMode = prefs[Keys.ONE_HANDED_MODE] ?: false,
            handMinConfidence = (prefs[Keys.HAND_MIN_CONFIDENCE] ?: 0.45f).coerceIn(0.25f, 0.85f),
            gestureSizeGain = (prefs[Keys.GESTURE_SIZE_GAIN] ?: 1.0f).coerceIn(0.6f, 1.8f),
            preferredCameraId = prefs[Keys.PREFERRED_CAMERA_ID]?.takeIf { it.isNotBlank() },
            handedness = handedness,
            handCalibration = HandCalibrationProfile(
                palmWidthMeters = (prefs[Keys.PALM_WIDTH] ?: 0.085f).coerceIn(0.055f, 0.125f),
                pinchClosedThreshold = (prefs[Keys.PINCH_CLOSE] ?: 0.25f).coerceIn(0.12f, 0.42f),
                pinchOpenThreshold = (prefs[Keys.PINCH_OPEN] ?: 0.45f).coerceIn(0.32f, 0.70f),
                calibrated = prefs[Keys.HAND_CALIBRATED] ?: false
            ),
            trackingSourceMode = trackingMode,
            predictionMs = (prefs[Keys.PREDICTION_MS] ?: 25f).coerceIn(0f, 45f),
            autoRecenterOnStart = prefs[Keys.AUTO_RECENTER] ?: true,
            passthroughBrightness = (prefs[Keys.PASSTHROUGH_BRIGHTNESS] ?: 1.0f).coerceIn(0.3f, 1.4f),
            passthroughExposureComp = (prefs[Keys.PASSTHROUGH_EXPOSURE] ?: 0f).coerceIn(-2f, 2f),
            passthroughUndistort = prefs[Keys.PASSTHROUGH_UNDISTORT] ?: true,
            immersionLevel = (prefs[Keys.IMMERSION_LEVEL] ?: 1.0f).coerceIn(0f, 1f),
            cameraRotationDegrees = ((prefs[Keys.CAMERA_ROT_DEG] ?: 0) / 90 * 90).mod(360),
            cameraMirrorHorizontal = prefs[Keys.CAMERA_MIRROR] ?: false,
            galleryRotationDegrees = ((prefs[Keys.GALLERY_ROT_DEG] ?: 0) / 90 * 90).mod(360),
            showCalibrationGrid = prefs[Keys.CALIBRATION_GRID] ?: false,
            arCoreEnabled = prefs[Keys.ARCORE_ENABLED] ?: false,
            arCoreSixDofEnabled = prefs[Keys.ARCORE_SIXDOF] ?: true,
            cinemaModeEnabled = prefs[Keys.CINEMA_MODE] ?: false,
            cinemaProjectionMode = cinemaProj,
            thermalPriority = thermalPriority,
            angularScaleWindowsByDepth = prefs[Keys.ANGULAR_SCALE_WINDOWS] ?: true,
            prefer90HzWhenThermallySafe = prefs[Keys.PREFER_90HZ] ?: true,
            sessionBreakMinutes = (prefs[Keys.BREAK_MINUTES] ?: 30).coerceIn(10, 90),
            largeTextMode = prefs[Keys.LARGE_TEXT] ?: false,
            highContrastGlass = prefs[Keys.HIGH_CONTRAST] ?: false,
            reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
            lowPowerMode = prefs[Keys.LOW_POWER] ?: false,
            softwareWebViewFallback = prefs[Keys.SOFTWARE_WEBVIEW] ?: false,
            returnNotificationEnabled = prefs[Keys.RETURN_NOTIF] ?: true,
            voiceCommandsEnabled = prefs[Keys.VOICE_ENABLED] ?: false,
            browserHomeUrl = prefs[Keys.BROWSER_URL] ?: "https://en.wikipedia.org/wiki/Spatial_computing",
            browserHttpsOnly = prefs[Keys.BROWSER_HTTPS] ?: true,
            favoriteApps = prefs[Keys.FAVORITE_APPS] ?: emptySet(),
            recentApps = recents
        )
    }

    suspend fun update(transform: (XRSettings) -> XRSettings) {
        context.xrDataStore.edit { prefs ->
            val current = mapPreferencesToSettings(prefs)
            val next = transform(current)
            prefs[Keys.SCHEMA_VERSION] = XRSettings.CURRENT_SCHEMA_VERSION
            prefs[Keys.WELCOME_DONE] = next.hasCompletedWelcome
            prefs[Keys.START_STEREO] = next.startInStereoMode

            prefs[Keys.OPTICS_PRESET] = next.optics.preset.name
            prefs[Keys.IPD_MM] = next.optics.ipdMm
            prefs[Keys.FOV_DEG] = next.optics.fovDegrees
            prefs[Keys.DISTORT_K1] = next.optics.k1
            prefs[Keys.DISTORT_K2] = next.optics.k2
            prefs[Keys.DISTORT_P1] = next.optics.p1
            prefs[Keys.DISTORT_P2] = next.optics.p2
            prefs[Keys.CHROMATIC] = next.optics.chromaticAberration
            prefs[Keys.LEFT_EYE_Y] = next.optics.leftEyeVerticalOffset
            prefs[Keys.RIGHT_EYE_Y] = next.optics.rightEyeVerticalOffset
            prefs[Keys.RENDER_SCALE] = next.optics.renderScale

            prefs[Keys.ENV_TYPE] = next.environment.type.id
            prefs[Keys.ENV_TINT] = next.environment.tintHex
            prefs[Keys.ENV_AMBIENT] = next.environment.ambientLight
            prefs[Keys.ENV_PARTICLES] = next.environment.particleDensity
            prefs[Keys.ENV_EFFECTS] = next.environment.effectsEnabled
            prefs[Keys.ENV_BRIGHTNESS] = next.environment.brightness

            prefs[Keys.GAZE_DWELL] = next.gazeDwellSeconds
            prefs[Keys.AUTO_GAZE_FALLBACK] = next.autoGazeFallbackWhenHandsLost
            prefs[Keys.AUDIO_CLICK] = next.audioClickEnabled
            prefs[Keys.HAPTICS] = next.hapticFeedbackEnabled
            prefs[Keys.LEFT_CONTROLLER] = next.leftHandedController
            prefs[Keys.BLUETOOTH_CLICKER] = next.bluetoothClickerEnabled

            prefs[Keys.HAND_ENABLED] = next.handControlEnabled
            prefs[Keys.HAND_RAY_MODE] = next.handRayModeEnabled
            prefs[Keys.HAND_SKELETON] = next.handSkeletonOverlay
            prefs[Keys.RELAXED_PINCH] = next.relaxedPinchSensitivity
            prefs[Keys.ONE_HANDED_MODE] = next.oneHandedMode
            prefs[Keys.HAND_MIN_CONFIDENCE] = next.handMinConfidence
            prefs[Keys.GESTURE_SIZE_GAIN] = next.gestureSizeGain
            if (next.preferredCameraId != null) {
                prefs[Keys.PREFERRED_CAMERA_ID] = next.preferredCameraId
            } else {
                prefs.remove(Keys.PREFERRED_CAMERA_ID)
            }
            prefs[Keys.HANDEDNESS] = next.handedness.name
            prefs[Keys.PALM_WIDTH] = next.handCalibration.palmWidthMeters
            prefs[Keys.PINCH_CLOSE] = next.handCalibration.pinchClosedThreshold
            prefs[Keys.PINCH_OPEN] = next.handCalibration.pinchOpenThreshold
            prefs[Keys.HAND_CALIBRATED] = next.handCalibration.calibrated

            prefs[Keys.TRACKING_MODE] = next.trackingSourceMode.name
            prefs[Keys.PREDICTION_MS] = next.predictionMs
            prefs[Keys.AUTO_RECENTER] = next.autoRecenterOnStart

            prefs[Keys.PASSTHROUGH_BRIGHTNESS] = next.passthroughBrightness
            prefs[Keys.PASSTHROUGH_EXPOSURE] = next.passthroughExposureComp
            prefs[Keys.PASSTHROUGH_UNDISTORT] = next.passthroughUndistort
            prefs[Keys.IMMERSION_LEVEL] = next.immersionLevel
            prefs[Keys.CAMERA_ROT_DEG] = next.cameraRotationDegrees
            prefs[Keys.CAMERA_MIRROR] = next.cameraMirrorHorizontal
            prefs[Keys.GALLERY_ROT_DEG] = next.galleryRotationDegrees
            prefs[Keys.CALIBRATION_GRID] = next.showCalibrationGrid
            prefs[Keys.ARCORE_ENABLED] = next.arCoreEnabled
            prefs[Keys.ARCORE_SIXDOF] = next.arCoreSixDofEnabled
            prefs[Keys.CINEMA_MODE] = next.cinemaModeEnabled
            prefs[Keys.CINEMA_PROJ] = next.cinemaProjectionMode.name

            prefs[Keys.THERMAL_PRIORITY] = next.thermalPriority.name
            prefs[Keys.ANGULAR_SCALE_WINDOWS] = next.angularScaleWindowsByDepth
            prefs[Keys.PREFER_90HZ] = next.prefer90HzWhenThermallySafe
            prefs[Keys.BREAK_MINUTES] = next.sessionBreakMinutes
            prefs[Keys.LARGE_TEXT] = next.largeTextMode
            prefs[Keys.HIGH_CONTRAST] = next.highContrastGlass
            prefs[Keys.REDUCE_MOTION] = next.reduceMotion
            prefs[Keys.LOW_POWER] = next.lowPowerMode
            prefs[Keys.SOFTWARE_WEBVIEW] = next.softwareWebViewFallback
            prefs[Keys.RETURN_NOTIF] = next.returnNotificationEnabled

            prefs[Keys.VOICE_ENABLED] = next.voiceCommandsEnabled
            prefs[Keys.BROWSER_URL] = next.browserHomeUrl
            prefs[Keys.BROWSER_HTTPS] = next.browserHttpsOnly
            prefs[Keys.FAVORITE_APPS] = next.favoriteApps
            prefs[Keys.RECENT_APPS] = next.recentApps.take(8).joinToString("|")
        }
    }

    suspend fun resetToDefaults() {
        context.xrDataStore.edit { it.clear() }
    }
}
