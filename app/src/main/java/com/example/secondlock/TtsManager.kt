package com.example.secondlock

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

object TtsManager {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (tts != null) return
        synchronized(this) {
            if (tts == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        try { tts?.language = Locale.getDefault() } catch (_: Exception) {}
                        try {
                            val attrs = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                            tts?.setAudioAttributes(attrs)
                        } catch (_: Exception) {}
                        initialized = true
                    }
                }
            }
        }
    }

    fun prewarm() {
        // Optionally issue a no-op speak to warm caches
        if (initialized) {
            try { tts?.speak(" ", TextToSpeech.QUEUE_FLUSH, null, "tts_prewarm") } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        initialized = false
    }
}
