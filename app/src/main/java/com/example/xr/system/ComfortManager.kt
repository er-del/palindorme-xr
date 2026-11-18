package com.example.xr.system

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ComfortState(
    val sessionMinutes: Int = 0,
    val showBreakPrompt: Boolean = false,
    val highAccelerationWarning: Boolean = false,
    val breakIntervalMinutes: Int = 30
)

class ComfortManager {
    private var sessionStartMs: Long = System.currentTimeMillis()
    private var lastBreakDismissedMinute: Int = 0

    private val _state = MutableStateFlow(ComfortState())
    val state: StateFlow<ComfortState> = _state.asStateFlow()

    fun tick(breakIntervalMinutes: Int, linearAccelG: Float, cameraActive: Boolean) {
        val elapsedMinutes = ((System.currentTimeMillis() - sessionStartMs) / 60_000L).toInt()
        val shouldPromptBreak = elapsedMinutes >= breakIntervalMinutes &&
            (elapsedMinutes - lastBreakDismissedMinute) >= breakIntervalMinutes

        val rapidMotion = cameraActive && linearAccelG > 0.65f

        _state.value = ComfortState(
            sessionMinutes = elapsedMinutes,
            showBreakPrompt = shouldPromptBreak,
            highAccelerationWarning = rapidMotion,
            breakIntervalMinutes = breakIntervalMinutes
        )
    }

    fun dismissBreakPrompt() {
        lastBreakDismissedMinute = _state.value.sessionMinutes
        _state.value = _state.value.copy(showBreakPrompt = false)
    }

    fun resetSession() {
        sessionStartMs = System.currentTimeMillis()
        lastBreakDismissedMinute = 0
        _state.value = _state.value.copy(sessionMinutes = 0, showBreakPrompt = false)
    }
}
