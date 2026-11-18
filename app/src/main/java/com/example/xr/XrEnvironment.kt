package com.example.xr

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Helpers for running this app on Android XR headsets (for example Galaxy XR).
 *
 * Android XR presents ordinary 2D apps inside a resizable spatial panel, and the
 * platform owns head pose tracking. That conflicts with two phone-specific
 * behaviours baked into this app:
 *
 *  - [AppMode.STEREO_VR] renders a side-by-side image for a phone-in-a-viewer,
 *    which is wrong on a headset that already does stereo rendering.
 *  - Sensor-fused head tracking would fight the platform's own pose tracking.
 *
 * Detection note: API 36 does not expose a public XR `PackageManager` feature
 * constant (the XR feature names ship with the Jetpack XR SDK, which this app
 * does not depend on). Two platform-level signals are used instead:
 *
 *  1. `Configuration.UI_MODE_TYPE_VR_HEADSET`, the documented way to detect a
 *     headset, and
 *  2. no touchscreen + motion sensors, which covers headsets and wired XR
 *     glasses that report a normal UI mode.
 *
 * Phones, tablets and foldables all have a touchscreen and a normal UI mode, so
 * their behaviour is provably unchanged.
 */
object XrEnvironment {

    /**
     * Pure form-factor decision, split out so it can be unit tested without a
     * device. A headset is either explicitly in headset UI mode, or it has
     * motion sensors but no touchscreen.
     */
    internal fun isHeadsetFormFactor(
        vrUiMode: Boolean,
        hasTouchscreen: Boolean,
        hasMotionSensors: Boolean
    ): Boolean = vrUiMode || (!hasTouchscreen && hasMotionSensors)

    /**
     * True on a headset / glasses form factor where the app should behave as a
     * spatial panel app rather than a phone VR viewer app.
     */
    fun isHeadset(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) ||
            pm.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH)
        val hasMotion = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE) ||
            pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)
        isHeadsetFormFactor(
            vrUiMode = uiMode == Configuration.UI_MODE_TYPE_VR_HEADSET,
            hasTouchscreen = hasTouchscreen,
            hasMotionSensors = hasMotion
        )
    }.getOrDefault(false)

    /**
     * True when the phone-in-a-viewer stereo path is meaningful. On a headset the
     * 2D hub runs as a panel instead.
     */
    fun supportsPhoneVrStereo(context: Context): Boolean = !isHeadset(context)

    /** The mode the app should start in for this form factor. */
    fun defaultAppMode(context: Context): AppMode =
        if (supportsPhoneVrStereo(context)) AppMode.STEREO_VR else AppMode.HUB_2D
}
