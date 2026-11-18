package com.example.xr

import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.view.MotionEvent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.xr.apps.LaunchableApp
import com.example.xr.hand.HandTrackingTelemetry
import com.example.xr.launcher.LauncherAppCategory
import com.example.xr.launcher.LauncherTab
import com.example.xr.model.XRSettings
import com.example.xr.model.VirtualEnvironmentType
import com.example.xr.perception.ArPerceptionTelemetry
import com.example.xr.system.PerformanceTelemetry
import com.example.xr.tracking.TrackingTelemetry
import com.example.xr.windows.DockActionId
import com.example.xr.windows.SpatialWindowAction
import com.example.xr.windows.SpatialWindowState
import com.example.xr.windows.VirtualContentHost
import com.example.xr.windows.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpatialLauncherViewTest {

    @Test
    fun appLibraryShowsAllSectionsAndAnEmptyAppsState() {
        val container = FrameLayout(ApplicationProvider.getApplicationContext())
        val host = createHost(container)

        host.updateSnapshots(
            settings = XRSettings(),
            apps = emptyList(),
            perf = PerformanceTelemetry(),
            tracking = TrackingTelemetry(),
            hand = HandTrackingTelemetry(),
            ar = ArPerceptionTelemetry(),
            galleryUris = emptyList(),
            launcherTab = LauncherTab.APPS,
            launcherCategory = LauncherAppCategory.ALL,
            windows = listOf(SpatialWindowState(id = "win_apps", type = WindowType.APP_LIBRARY)),
            focusedWindowId = "win_apps"
        )
        host.ensureViewWindowHosted(SpatialWindowState(id = "win_apps", type = WindowType.APP_LIBRARY))
        shadowOf(Looper.getMainLooper()).idle()

        val root = container.getChildAt(0)
        assertNotNull(root.findViewWithTag<Button>("launcher_tab_apps"))
        assertNotNull(root.findViewWithTag<Button>("launcher_tab_environments"))
        assertNotNull(root.findViewWithTag<Button>("launcher_tab_windows"))
        assertNotNull(root.findViewWithTag<Button>("launcher_tab_settings"))
        assertNotNull(root.findViewWithTag<TextView>("launcher_empty_apps"))

        host.releaseAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun windowListRoutesFocusActionToTheSelectedWindow() {
        val container = FrameLayout(ApplicationProvider.getApplicationContext())
        val host = createHost(container)
        var received: Pair<String, SpatialWindowAction>? = null
        host.onWindowAction = { windowId, action -> received = windowId to action }
        val windows = listOf(
            SpatialWindowState(id = "win_apps", type = WindowType.APP_LIBRARY),
            SpatialWindowState(id = "win_browser", type = WindowType.BROWSER)
        )

        host.updateSnapshots(
            settings = XRSettings(),
            apps = emptyList(),
            perf = PerformanceTelemetry(),
            tracking = TrackingTelemetry(),
            hand = HandTrackingTelemetry(),
            ar = ArPerceptionTelemetry(),
            galleryUris = emptyList(),
            launcherTab = LauncherTab.WINDOWS,
            launcherCategory = LauncherAppCategory.ALL,
            windows = windows,
            focusedWindowId = "win_apps"
        )
        host.ensureViewWindowHosted(SpatialWindowState(id = "win_apps", type = WindowType.APP_LIBRARY))
        shadowOf(Looper.getMainLooper()).idle()

        container.getChildAt(0)
            .findViewWithTag<Button>("window_focus_win_browser")
            .performClick()

        assertEquals("win_browser" to SpatialWindowAction.FOCUS, received)

        host.releaseAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun spatialDockShowsActionsClockAndPinnedAppsInStableOrder() {
        val container = FrameLayout(ApplicationProvider.getApplicationContext())
        val host = createHost(container)
        val launchedApps = mutableListOf<String>()
        val dockActions = mutableListOf<DockActionId>()
        host.onLaunchAppRequested = { launchedApps += it.packageName }
        host.onDockActionRequested = { dockActions += it }

        host.updateSnapshots(
            settings = XRSettings(favoriteApps = setOf("pkg.zulu", "pkg.alpha")),
            apps = listOf(
                LaunchableApp("pkg.zulu", "Zulu", null),
                LaunchableApp("pkg.alpha", "Alpha", null)
            ),
            perf = PerformanceTelemetry(),
            tracking = TrackingTelemetry(),
            hand = HandTrackingTelemetry(),
            ar = ArPerceptionTelemetry(),
            galleryUris = emptyList(),
            windows = listOf(SpatialWindowState(id = "win_dock", type = WindowType.SPATIAL_DOCK))
        )
        host.ensureViewWindowHosted(SpatialWindowState(id = "win_dock", type = WindowType.SPATIAL_DOCK))
        shadowOf(Looper.getMainLooper()).idle()

        val root = container.getChildAt(0)
        val pinnedApps = root.findViewWithTag<LinearLayout>("dock_pinned_apps")
        assertEquals("dock_app_pkg.alpha", pinnedApps.getChildAt(0).tag)
        assertNotNull(root.findViewWithTag<TextView>("spatial_dock_clock"))
        assertNotNull(root.findViewWithTag<Button>("dock_action_home"))
        assertNotNull(root.findViewWithTag<Button>("dock_action_control_center"))
        assertNotNull(root.findViewWithTag<Button>("dock_action_hands_off"))

        DockActionId.entries.forEach { action ->
            val button = root.findViewWithTag<Button>("dock_action_${action.name.lowercase()}")
            assertNotNull(button)
            button.performClick()
        }
        pinnedApps.getChildAt(0).performClick()
        assertEquals(DockActionId.entries, dockActions)
        assertEquals(listOf("pkg.alpha"), launchedApps)

        host.releaseAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun environmentSectionMapsPresetAndImmersionControlsToNativeSettings() {
        val container = FrameLayout(ApplicationProvider.getApplicationContext())
        val host = createHost(container)
        var updatedSettings: XRSettings? = null
        host.onSettingsMutated = { transform ->
            updatedSettings = transform(XRSettings())
        }

        host.updateSnapshots(
            settings = XRSettings(),
            apps = emptyList(),
            perf = PerformanceTelemetry(),
            tracking = TrackingTelemetry(),
            hand = HandTrackingTelemetry(),
            ar = ArPerceptionTelemetry(),
            galleryUris = emptyList(),
            launcherTab = LauncherTab.ENVIRONMENTS,
            windows = listOf(SpatialWindowState(id = "win_apps", type = WindowType.APP_LIBRARY))
        )
        host.ensureViewWindowHosted(SpatialWindowState(id = "win_apps", type = WindowType.APP_LIBRARY))
        shadowOf(Looper.getMainLooper()).idle()

        val root = container.getChildAt(0)
        root.findViewWithTag<Button>("launcher_environment_${VirtualEnvironmentType.SPACE_STATION.id}")
            .performClick()
        assertEquals(VirtualEnvironmentType.SPACE_STATION, updatedSettings?.environment?.type)
        val immersion = root.findViewWithTag<SeekBar>("launcher_immersion")
        assertNotNull(immersion)
        val slider = requireNotNull(immersion)
        slider.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(64, android.view.View.MeasureSpec.EXACTLY)
        )
        slider.layout(0, 0, 600, 64)
        val now = SystemClock.uptimeMillis()
        slider.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 540f, 32f, 0))
        slider.onTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, 24f, 32f, 0))
        slider.onTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 24f, 32f, 0))
        assertEquals(0f, updatedSettings?.immersionLevel ?: -1f, 0.02f)

        host.releaseAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun controlCenterPreservesCoordinatorCommandRouting() {
        val container = FrameLayout(ApplicationProvider.getApplicationContext())
        val host = createHost(container)
        val commands = mutableListOf<String>()
        host.onActionCommand = commands::add
        val window = SpatialWindowState(id = "win_control", type = WindowType.CONTROL_CENTER)
        host.updateSnapshots(
            settings = XRSettings(),
            apps = emptyList(),
            perf = PerformanceTelemetry(),
            tracking = TrackingTelemetry(),
            hand = HandTrackingTelemetry(),
            ar = ArPerceptionTelemetry(),
            galleryUris = emptyList(),
            windows = listOf(window)
        )
        host.ensureViewWindowHosted(window)
        shadowOf(Looper.getMainLooper()).idle()

        container.getChildAt(0)
            .findViewWithTag<Button>("control_center_toggle_passthrough")
            .performClick()

        assertEquals(listOf("toggle_passthrough"), commands)

        host.releaseAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createHost(container: FrameLayout) =
        VirtualContentHost(ApplicationProvider.getApplicationContext(), container)
}
