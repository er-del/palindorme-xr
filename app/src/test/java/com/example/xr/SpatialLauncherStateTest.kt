package com.example.xr

import android.content.pm.ApplicationInfo
import com.example.xr.apps.LaunchableApp
import com.example.xr.launcher.LauncherAppCategory
import com.example.xr.launcher.classifyApp
import com.example.xr.launcher.filterApps
import com.example.xr.launcher.reconcilePinnedApps
import com.example.xr.launcher.togglePinnedApp
import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialLauncherStateTest {

    @Test
    fun classifyApp_systemPackagesTakePrecedenceOverMediaCategory() {
        assertEquals(
            LauncherAppCategory.SYSTEM,
            classifyApp(isSystemApp = true, packageCategory = ApplicationInfo.CATEGORY_AUDIO)
        )
    }

    @Test
    fun classifyApp_audioVideoAndImagePackagesAreMedia() {
        val mediaCategories = listOf(
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_IMAGE
        )

        assertEquals(
            listOf(
                LauncherAppCategory.MEDIA,
                LauncherAppCategory.MEDIA,
                LauncherAppCategory.MEDIA
            ),
            mediaCategories.map { classifyApp(isSystemApp = false, packageCategory = it) }
        )
        assertEquals(
            LauncherAppCategory.TOOLS,
            classifyApp(isSystemApp = false, packageCategory = ApplicationInfo.CATEGORY_UNDEFINED)
        )
    }

    @Test
    fun filterApps_allReturnsEveryPackageAndSpecificCategoryReturnsOnlyMatches() {
        val apps = listOf(
            app("browser", LauncherAppCategory.SYSTEM),
            app("photos", LauncherAppCategory.MEDIA),
            app("calculator", LauncherAppCategory.TOOLS)
        )

        assertEquals(apps, filterApps(apps, LauncherAppCategory.ALL))
        assertEquals(listOf(apps[1]), filterApps(apps, LauncherAppCategory.MEDIA))
    }

    @Test
    fun togglePinnedApp_addsRemovesAndRejectsPinsBeyondSix() {
        val sixPins = (1..6).map { "app$it" }.toSet()

        assertEquals(setOf("browser"), togglePinnedApp(emptySet(), "browser"))
        assertEquals(emptySet<String>(), togglePinnedApp(setOf("browser"), "browser"))
        assertEquals(sixPins, togglePinnedApp(sixPins, "seventh"))
    }

    @Test
    fun reconcilePinnedAppsRemovesPackagesNoLongerInstalled() {
        val installed = listOf(app("browser"), app("photos"))

        assertEquals(
            setOf("browser"),
            reconcilePinnedApps(setOf("browser", "uninstalled"), installed)
        )
    }

    private fun app(
        packageName: String,
        category: LauncherAppCategory = LauncherAppCategory.TOOLS
    ) = LaunchableApp(
        packageName = packageName,
        label = packageName,
        icon = null,
        category = category
    )
}
