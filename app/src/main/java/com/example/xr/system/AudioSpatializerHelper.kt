package com.example.xr.system

import android.content.Context
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build

data class SpatialAudioStatus(
    val apiSupported: Boolean,
    val isAvailable: Boolean,
    val isEnabled: Boolean,
    val isHeadTrackerAvailable: Boolean,
    val label: String
)

class AudioSpatializerHelper(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun readStatus(): SpatialAudioStatus {
        val am = audioManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2 || am == null) {
            return SpatialAudioStatus(
                apiSupported = false,
                isAvailable = false,
                isEnabled = false,
                isHeadTrackerAvailable = false,
                label = "Android Spatializer requires API 32+ (stereo audio active)"
            )
        }
        return runCatching {
            val sp = am.spatializer
            val available = sp.isAvailable
            val enabled = sp.isEnabled
            val headTracker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sp.isHeadTrackerAvailable
            } else {
                false
            }
            val level = sp.immersiveAudioLevel
            val label = when {
                level == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE -> "Device reports no Spatializer hardware"
                enabled && headTracker -> "Spatializer Active (Head-Tracked)"
                available -> "Spatializer Available (${if (enabled) "On" else "Disabled in Android Settings"})"
                else -> "Spatializer Unavailable on Current Audio Output"
            }
            SpatialAudioStatus(
                apiSupported = true,
                isAvailable = available,
                isEnabled = enabled,
                isHeadTrackerAvailable = headTracker,
                label = label
            )
        }.getOrElse {
            SpatialAudioStatus(
                apiSupported = true,
                isAvailable = false,
                isEnabled = false,
                isHeadTrackerAvailable = false,
                label = "Spatializer query unavailable"
            )
        }
    }

    companion object {
        fun query(context: Context): SpatialAudioStatus = AudioSpatializerHelper(context).readStatus()
    }
}
