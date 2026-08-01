package com.hermexapp.android.features.chat

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Dictation through Android's [SpeechRecognizer]. API 31+ prefers the dedicated
 * on-device recognizer when available; otherwise Android's default recognition
 * provider is used and may process audio over a network. Hermex retains no
 * microphone recording and inserts results into the composer without sending.
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
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val recognitionMode = remember(context) {
        selectVoiceRecognitionMode(
            sdkInt = Build.VERSION.SDK_INT,
            onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
            systemAvailable = SpeechRecognizer.isRecognitionAvailable(context),
        )
    }

    val recognizer = remember(context, recognitionMode) {
        runCatching {
            when (recognitionMode) {
                VoiceRecognitionMode.ON_DEVICE ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        null
                    }
                VoiceRecognitionMode.SYSTEM_DEFAULT ->
                    SpeechRecognizer.createSpeechRecognizer(context)
                VoiceRecognitionMode.UNAVAILABLE -> null
            }
        }.getOrNull()
    }

    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (decideVoiceStart(recognizer != null, granted) == VoiceStartDecision.START) {
            listening = true
        }
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
        isAvailable = recognizer != null,
        start = {
            when (decideVoiceStart(recognizer != null, hasPermission)) {
                VoiceStartDecision.START -> listening = true
                VoiceStartDecision.REQUEST_PERMISSION ->
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                VoiceStartDecision.UNAVAILABLE -> Unit
            }
        },
        stop = {
            listening = false
            recognizer?.stopListening()
        },
    )
}
