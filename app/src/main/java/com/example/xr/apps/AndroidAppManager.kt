package com.example.xr.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.example.xr.system.NotificationHelper
import com.example.xr.launcher.LauncherAppCategory
import com.example.xr.launcher.classifyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val category: LauncherAppCategory = LauncherAppCategory.TOOLS
)

/**
 * Discovers launchable Android applications via targeted <queries> (no QUERY_ALL_PACKAGES).
 */
class AndroidAppManager(private val context: Context) {

    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()

    suspend fun refreshInstalledApps() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        val myPkg = context.packageName
        val list = resolved
            .filter { it.activityInfo.packageName != myPkg }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                LaunchableApp(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    category = classifyApp(
                        isSystemApp = info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        packageCategory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            info.activityInfo.applicationInfo.category
                        } else {
                            ApplicationInfo.CATEGORY_UNDEFINED
                        }
                    )
                )
            }
            .sortedBy { it.label.lowercase() }

        _apps.value = list
    }

    fun launchApp(app: LaunchableApp, postReturnNotification: Boolean): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(app.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (postReturnNotification) {
            NotificationHelper.postReturnToXrNotification(context, app.label)
        }
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }
}
