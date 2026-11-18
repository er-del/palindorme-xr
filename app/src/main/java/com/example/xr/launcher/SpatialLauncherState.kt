package com.example.xr.launcher

import android.content.pm.ApplicationInfo
import com.example.xr.apps.LaunchableApp

enum class LauncherTab {
    APPS,
    ENVIRONMENTS,
    WINDOWS,
    SETTINGS
}

enum class LauncherAppCategory {
    ALL,
    SYSTEM,
    MEDIA,
    TOOLS
}

fun classifyApp(isSystemApp: Boolean, packageCategory: Int): LauncherAppCategory {
    if (isSystemApp) return LauncherAppCategory.SYSTEM

    return when (packageCategory) {
        ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_IMAGE -> LauncherAppCategory.MEDIA
        else -> LauncherAppCategory.TOOLS
    }
}

fun filterApps(
    apps: List<LaunchableApp>,
    category: LauncherAppCategory
): List<LaunchableApp> =
    if (category == LauncherAppCategory.ALL) apps else apps.filter { it.category == category }

fun togglePinnedApp(
    current: Set<String>,
    packageName: String,
    maxPins: Int = 6
): Set<String> {
    require(maxPins >= 0) { "maxPins cannot be negative" }
    require(packageName.isNotBlank()) { "packageName cannot be blank" }
    if (packageName in current) return current - packageName
    if (current.size >= maxPins) return current
    return current + packageName
}

fun reconcilePinnedApps(
    pinned: Set<String>,
    apps: List<LaunchableApp>
): Set<String> {
    val installedPackages = apps.mapTo(mutableSetOf()) { it.packageName }
    return pinned intersect installedPackages
}
