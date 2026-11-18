package com.example.xr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the form-factor decision that switches the app between the phone
 * stereo viewer and the Android XR spatial-panel experience.
 */
class XrEnvironmentTest {

    @Test
    fun phonesAreNotHeadsets() {
        assertFalse(
            XrEnvironment.isHeadsetFormFactor(
                vrUiMode = false,
                hasTouchscreen = true,
                hasMotionSensors = true
            )
        )
    }

    @Test
    fun tabletsAndFoldablesWithTouchStayOnThePhonePath() {
        assertFalse(
            XrEnvironment.isHeadsetFormFactor(
                vrUiMode = false,
                hasTouchscreen = true,
                hasMotionSensors = false
            )
        )
    }

    @Test
    fun headsetUiModeIsDetectedEvenWithATouchscreen() {
        assertTrue(
            XrEnvironment.isHeadsetFormFactor(
                vrUiMode = true,
                hasTouchscreen = true,
                hasMotionSensors = true
            )
        )
    }

    @Test
    fun touchlessDeviceWithSensorsIsTreatedAsAHeadset() {
        assertTrue(
            XrEnvironment.isHeadsetFormFactor(
                vrUiMode = false,
                hasTouchscreen = false,
                hasMotionSensors = true
            )
        )
    }

    @Test
    fun touchlessDeviceWithoutSensorsIsNotAHeadset() {
        assertFalse(
            XrEnvironment.isHeadsetFormFactor(
                vrUiMode = false,
                hasTouchscreen = false,
                hasMotionSensors = false
            )
        )
    }
}
