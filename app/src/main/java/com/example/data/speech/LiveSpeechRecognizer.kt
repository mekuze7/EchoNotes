package com.example.data.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Live speech recognition engine that captures real microphone audio
 * via Android's SpeechRecognizer.
 * Strictly avoids hallucinated or synthetic phrases so only actual spoken words appear.
 */
class LiveSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "LiveSpeechRecognizer"
        private const val EXTRA_ENABLE_LANGUAGE_DETECTION =
            "android.speech.extra.ENABLE_LANGUAGE_DETECTION"
        private const val EXTRA_ENABLE_LANGUAGE_SWITCH =
            "android.speech.extra.ENABLE_LANGUAGE_SWITCH"
        private const val LANGUAGE_SWITCH_BALANCED = "balanced"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isIntentionallyStopped = false
    private var requestedLanguage = "auto"
    private val scope = CoroutineScope(Dispatchers.Main)

    private val accumulatedTextBuilder = StringBuilder()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _hasError = MutableStateFlow(false)
    val hasError: StateFlow<Boolean> = _hasError.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isAvailable = MutableStateFlow(true)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    fun isRecognitionAvailable(): Boolean {
        return try {
            val available = SpeechRecognizer.isRecognitionAvailable(context)
            _isAvailable.value = available
            available
        } catch (e: Exception) {
            _isAvailable.value = false
            false
        }
    }

    fun startListening(language: String = "auto") {
        stopListening()
        requestedLanguage = language
        accumulatedTextBuilder.clear()
        _liveTranscript.value = ""
        _partialTranscript.value = ""
        startListeningWithoutReset()
    }

    fun pauseListening() {
        isIntentionallyStopped = true
        isListening = false
        _partialTranscript.value = ""
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing speech recognizer: ${e.message}")
        } finally {
            speechRecognizer = null
        }
    }

    fun resumeListening() {
        if (isListening) return
        _partialTranscript.value = ""
        startListeningWithoutReset()
    }

    private fun startListeningWithoutReset() {
        _hasError.value = false
        _statusMessage.value = null
        isIntentionallyStopped = false
        isListening = true

        val speechAvailable = isRecognitionAvailable()
        if (!speechAvailable) {
            _hasError.value = true
            _statusMessage.value = "Live speech recognition is unavailable. Enable a speech recognition service in Android settings."
            return
        }
        initNativeRecognizer()
    }

    private fun initNativeRecognizer() {
        if (!isListening || isIntentionallyStopped) return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "onReadyForSpeech")
                        _hasError.value = false
                        _statusMessage.value = "Listening... Speak to see live transcription."
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "onBeginningOfSpeech")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "onEndOfSpeech")
                    }

                    override fun onError(error: Int) {
                        Log.d(TAG, "Native SpeechRecognizer reported code $error")
                        val errorDescription = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording issue. In emulator settings, enable Virtual Microphone -> Host audio input."
                            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client reconnecting..."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                            SpeechRecognizer.ERROR_NETWORK -> "Network issue while connecting to speech service."
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network timeout."
                            SpeechRecognizer.ERROR_NO_MATCH -> null // Natural pause
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy, resetting..."
                            SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null // Natural pause
                            11 -> "Speech server disconnected. Reconnecting..."
                            else -> "Speech recognizer status code $error"
                        }

                        if (errorDescription != null && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            _statusMessage.value = errorDescription
                            _hasError.value = true
                        }

                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            _hasError.value = true
                            return
                        }

                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                            try {
                                speechRecognizer?.cancel()
                            } catch (_: Throwable) {}
                        }

                        // Automatically restart recognition if user is still actively recording
                        if (isListening && !isIntentionallyStopped) {
                            scope.launch {
                                kotlinx.coroutines.delay(if (error == SpeechRecognizer.ERROR_CLIENT) 600 else 300)
                                if (isListening && !isIntentionallyStopped) {
                                    if (error == SpeechRecognizer.ERROR_CLIENT) {
                                        initNativeRecognizer()
                                    } else {
                                        restartNativeListening()
                                    }
                                }
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.let(::appendRecognizedText)

                        // Continue listening immediately for next spoken sentence
                        if (isListening && !isIntentionallyStopped) {
                            scope.launch {
                                kotlinx.coroutines.delay(20)
                                if (isListening && !isIntentionallyStopped) {
                                    restartNativeListening()
                                }
                            }
                        }
                    }

                    override fun onSegmentResults(segmentResults: Bundle) {
                        segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.let(::appendRecognizedText)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val interim = matches[0].trim()
                            if (interim.isNotEmpty()) {
                                val committed = synchronized(accumulatedTextBuilder) {
                                    accumulatedTextBuilder.toString().trim()
                                }
                                _partialTranscript.value = if (
                                    committed.isNotEmpty() &&
                                    interim.startsWith(committed, ignoreCase = true)
                                ) {
                                    interim.drop(committed.length).trim()
                                } else {
                                    interim
                                }
                            }
                        }
                    }

                    override fun onLanguageDetection(results: Bundle) {
                        val languageTag = results.getString("android.speech.extra.DETECTED_LANGUAGE")
                        if (!languageTag.isNullOrBlank()) {
                            val language = Locale.forLanguageTag(languageTag)
                                .getDisplayLanguage(Locale.getDefault())
                            _statusMessage.value = "Detected language: $language"
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            restartNativeListening()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not start native SpeechRecognizer: ${e.message}")
            _hasError.value = true
            _statusMessage.value = "Failed to start speech recognizer: ${e.message}"
        }
    }

    private fun restartNativeListening() {
        if (!isListening || isIntentionallyStopped) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra("android.speech.extra.DICTATION_MODE", true)
                // Low-latency latency optimizations
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                        RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY
                    )
                    putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
                }

                when (requestedLanguage) {
                    "am" -> {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "am-ET")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "am-ET")
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("am-ET", "en-US"))
                    }
                    "en" -> {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                    }
                    else -> {
                        // "auto" mode: prefer Amharic and English multi-language recognition
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "am-ET")
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("am-ET", "en-US", Locale.getDefault().toLanguageTag()))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            putExtra(EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                            putExtra(EXTRA_ENABLE_LANGUAGE_SWITCH, LANGUAGE_SWITCH_BALANCED)
                        }
                    }
                }
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start listening intent: ${e.message}")
            _hasError.value = true
        }
    }

    private fun appendRecognizedText(text: String) {
        val chunk = text.trim()
        if (chunk.isEmpty()) return

        val transcript = synchronized(accumulatedTextBuilder) {
            val committed = accumulatedTextBuilder.toString().trim()
            when {
                committed.isEmpty() -> accumulatedTextBuilder.append(chunk)
                chunk.equals(committed, ignoreCase = true) ||
                    committed.endsWith(" $chunk", ignoreCase = true) -> Unit
                chunk.startsWith("$committed ", ignoreCase = true) -> {
                    accumulatedTextBuilder.setLength(0)
                    accumulatedTextBuilder.append(chunk)
                }
                else -> accumulatedTextBuilder.append(" ").append(chunk)
            }
            accumulatedTextBuilder.toString().trim()
        }
        _liveTranscript.value = transcript
        _partialTranscript.value = ""
    }


    fun onAudioAmplitudeReceived(amplitude: Float) {}

    fun removeRecognizedPhrase(phrase: String): String {
        val updatedTranscript = synchronized(accumulatedTextBuilder) {
            val index = accumulatedTextBuilder.lastIndexOf(phrase, ignoreCase = true)
            if (index >= 0) {
                accumulatedTextBuilder.delete(index, index + phrase.length)
                val normalized = accumulatedTextBuilder.toString()
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
                accumulatedTextBuilder.setLength(0)
                accumulatedTextBuilder.append(normalized)
            }
            accumulatedTextBuilder.toString().trim()
        }
        _liveTranscript.value = updatedTranscript
        _partialTranscript.value = ""
        return updatedTranscript
    }

    fun stopListening(): String {
        isIntentionallyStopped = true
        isListening = false

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up speech recognizer: ${e.message}")
        } finally {
            speechRecognizer = null
        }

        return _liveTranscript.value.trim()
    }
}
