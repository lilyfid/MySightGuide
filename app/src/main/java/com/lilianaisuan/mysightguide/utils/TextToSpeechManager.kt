package com.lilianaisuan.mysightguide.utils

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class TextToSpeechManager(context: Context, private val onReady: () -> Unit) {

    private lateinit var ttsEngine: TextToSpeech
    private val utteranceId = this.hashCode().toString()
    var isSpeaking = false
        private set

    init {
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine.language = Locale.getDefault()
                ttsEngine.setSpeechRate(1.0f)
                Log.i("TTS", "TextToSpeech initialized successfully.")

                ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        (context as? UtteranceCompletionListener)?.onUtteranceCompleted()
                    }
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                    }
                })
                onReady()
            } else {
                Log.e("TTS", "Initialization failed: $status")
            }
        }
    }

    fun speak(text: String) {
        if (::ttsEngine.isInitialized) {
            val bundle = Bundle()
            bundle.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
        } else {
            Log.e("TTS", "speak() called before TTS initialized")
        }
    }

    fun stop() {
        if (::ttsEngine.isInitialized) {
            isSpeaking = false
            ttsEngine.stop()
        }
    }

    fun shutdown() {
        if (::ttsEngine.isInitialized) {
            ttsEngine.stop()
            ttsEngine.shutdown()
        }
    }
}

interface UtteranceCompletionListener {
    fun onUtteranceCompleted()
}