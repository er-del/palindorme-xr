package com.example.xr.apps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VoiceCommandAction {
    data object OpenBrowser : VoiceCommandAction
    data object OpenMedia : VoiceCommandAction
    data object OpenApps : VoiceCommandAction
    data object OpenGallery : VoiceCommandAction
    data object OpenSettings : VoiceCommandAction
    data object Recenter : VoiceCommandAction
    data object TogglePassthrough : VoiceCommandAction
    data object HandsOff : VoiceCommandAction
    data object CloseFocusedWindow : VoiceCommandAction
}

data class VoiceStatus(
    val isListening: Boolean = false,
    val isOnDevice: Boolean = false,
    val lastTranscript: String = "",
    val statusNote: String = "Voice commands disabled (opt-in)"
)

/**
 * Opt-in speech command manager preferring on-device recognizer on API 33+.
 */
class VoiceCommandManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val _status = MutableStateFlow(VoiceStatus())
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    var onAction: ((VoiceCommandAction) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListeningOnce() {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            _status.value = _status.value.copy(statusNote = "Microphone permission required")
            return
        }

        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        val sr = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull()

        if (sr == null) {
            _status.value = _status.value.copy(statusNote = "Speech service unavailable on this device")
            return
        }

        recognizer?.destroy()
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _status.value = _status.value.copy(
                    isListening = true,
                    isOnDevice = onDevice,
                    statusNote = if (onDevice) "Listening (on-device)" else "Listening (system speech service)"
                )
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                _status.value = _status.value.copy(isListening = false)
            }
            override fun onError(error: Int) {
                _status.value = _status.value.copy(
                    isListening = false,
                    statusNote = "Speech idle (code $error)"
                )
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _status.value = _status.value.copy(
                    isListening = false,
                    lastTranscript = text,
                    statusNote = "Heard: \"$text\""
                )
                parseAndDispatch(text)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { sr.startListening(intent) }
    }

    internal fun parseAndDispatch(transcript: String) {
        val lower = transcript.lowercase()
        val action = when {
            "browser" in lower || "web" in lower -> VoiceCommandAction.OpenBrowser
            "media" in lower || "video" in lower || "player" in lower -> VoiceCommandAction.OpenMedia
            "app" in lower || "library" in lower -> VoiceCommandAction.OpenApps
            "gallery" in lower || "photo" in lower -> VoiceCommandAction.OpenGallery
            "setting" in lower || "optic" in lower -> VoiceCommandAction.OpenSettings
            "recenter" in lower || "center" in lower -> VoiceCommandAction.Recenter
            "passthrough" in lower || "camera" in lower -> VoiceCommandAction.TogglePassthrough
            "hands off" in lower || "stop hand" in lower -> VoiceCommandAction.HandsOff
            "close" in lower -> VoiceCommandAction.CloseFocusedWindow
            else -> null
        }
        action?.let { onAction?.invoke(it) }
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        _status.value = _status.value.copy(isListening = false)
    }
}
