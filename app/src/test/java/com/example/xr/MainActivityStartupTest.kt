package com.example.xr

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w800dp-h360dp-land-xxhdpi")
class MainActivityStartupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivity_composeColdStart_rendersWelcomeStereoAndHubTabs() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome_headset_card").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_2d_setup_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("enter_stereo_vr_button").assertIsDisplayed()
        composeRule.onNodeWithTag("rail_camera_hands").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rail_performance").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("enter_stereo_vr_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hud_rotate_camera").assertIsDisplayed()
    }

    @Test
    fun mainActivity_lifecycleRestart_survivesConfigAndStopStart() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val activity = controller.get()
        assertNotNull(activity)
        assertTrue(activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        controller.pause().stop().start().resume()
        controller.pause().stop().destroy()
    }
}
