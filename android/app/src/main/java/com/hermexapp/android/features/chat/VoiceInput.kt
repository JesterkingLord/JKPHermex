package com.hermexapp.android.features.chat

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * On-device dictation via Android's [SpeechRecognizer] — the voice-input slice.
 * Populates the composer only (server chat behavior is unchanged), mirroring the
 * iOS voice-input contract. Returns a controller the composer's mic button
 * drives; requests RECORD_AUDIO on first use.
 */
class VoiceInputController(
    val isListening: Boolean,
    val isAvailable: Boolean,
    val start: () -> Unit,
    val stop: () -> Unit,
)

@Composable
fun rememberVoiceInputController(onText: (String) -> Unit): VoiceInputController {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    val recognizer = remember {
        if (available) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) listening = true
    }

    fun beginListening() {
        val r = recognizer ?: return
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) onText(text)
                listening = false
            }

            override fun onError(error: Int) { listening = false }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        r.startListening(intent)
    }

    // React to a listening flip driven by the button / permission grant.
    DisposableEffect(listening) {
        if (listening) beginListening()
        onDispose { if (!listening) recognizer?.stopListening() }
    }

    return VoiceInputController(
        isListening = listening,
        isAvailable = available,
        start = {
            if (hasPermission) listening = true
            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        stop = {
            listening = false
            recognizer?.stopListening()
        },
    )
}
