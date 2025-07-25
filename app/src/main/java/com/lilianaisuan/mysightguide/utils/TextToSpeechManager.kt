package com.lilianaisuan.mysightguide.utils

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.*

class TextToSpeechManager(private val context: Context, private val onReady: () -> Unit) {

    private lateinit var ttsEngine: TextToSpeech
    private val utteranceId = this.hashCode().toString()
    private val textQueue = LinkedList<String>()
    var isSpeaking = false
        private set

    init {
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                setupTts()
                onReady()
            } else {
                Log.e("TTS", "Initialization failed: $status")
            }
        }
    }

    private fun setupTts() {
        // Set default language as a fallback
        ttsEngine.language = Locale.US

        // *** THIS IS THE NEW, SMARTER VOICE SELECTION LOGIC ***
        val bestVoice = findBestVoice()
        if (bestVoice != null) {
            ttsEngine.voice = bestVoice
            Log.i("TTS", "SUCCESS: Using high-quality voice: ${bestVoice.name}")
        } else {
            Log.w("TTS", "Could not find a high-quality US/GB English voice, using default.")
        }

        // Set speech parameters for a more natural, storytelling pace
        ttsEngine.setSpeechRate(0.95f)
        ttsEngine.setPitch(1.0f)

        ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                if (textQueue.isNotEmpty()) {
                    speakNextChunk()
                } else {
                    isSpeaking = false
                    (context as? UtteranceCompletionListener)?.onUtteranceCompleted()
                }
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                textQueue.clear()
            }
        })
    }

    private fun findBestVoice(): Voice? {
        val connectivityHelper = ConnectivityHelper(context)
        val voices = ttsEngine.voices

        if (connectivityHelper.isOnline()) {
            // If ONLINE, prioritize high-quality NETWORK voices
            Log.i("TTS", "Device is online. Searching for network voices.")
            return voices.firstOrNull { it.locale == Locale.US && it.quality >= Voice.QUALITY_HIGH && it.isNetworkConnectionRequired }
                ?: voices.firstOrNull { it.locale == Locale.UK && it.quality >= Voice.QUALITY_HIGH && it.isNetworkConnectionRequired }
                ?: voices.firstOrNull { it.locale.language == "en" && it.quality >= Voice.QUALITY_HIGH }
        } else {
            // If OFFLINE, find the best available local voice
            Log.i("TTS", "Device is offline. Searching for best local voice.")
            return voices.firstOrNull { it.locale == Locale.US && !it.isNetworkConnectionRequired }
                ?: voices.firstOrNull { it.locale == Locale.UK && !it.isNetworkConnectionRequired }
                ?: voices.firstOrNull { it.locale.language == "en" && !it.isNetworkConnectionRequired }
        }
    }

    fun speak(text: String) {
        if (!::ttsEngine.isInitialized) {
            Log.e("TTS", "speak() called before TTS initialized")
            return
        }
        stop()
        val chunks = text.split(Regex("(?<=[.?!])\\s*"))
        textQueue.addAll(chunks.filter { it.isNotBlank() })
        speakNextChunk()
    }

    private fun speakNextChunk() {
        if (textQueue.isNotEmpty()) {
            val text = textQueue.poll()
            if (text != null) {
                ttsEngine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        }
    }

    fun stop() {
        if (::ttsEngine.isInitialized) {
            isSpeaking = false
            textQueue.clear()
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