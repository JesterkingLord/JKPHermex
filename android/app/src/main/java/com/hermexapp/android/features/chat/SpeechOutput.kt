package com.hermexapp.android.features.chat

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Text-to-speech for the "Listen" action — the Android counterpart of the iOS
 * `AVSpeechSynthesizer` path. No server call and no new dependency; uses the
 * platform [TextToSpeech] engine.
 */
class SpeechController(
    val speak: (String) -> Unit,
    val stop: () -> Unit,
)

@Composable
fun rememberSpeechController(): SpeechController {
    val context = LocalContext.current
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.getDefault()
            }
        }
        engine
    }

    DisposableEffect(tts) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    return SpeechController(
        speak = { text ->
            if (text.isNotBlank()) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermex-listen")
            }
        },
        stop = { tts.stop() },
    )
}
