package com.example.ui.recorder

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.provider.CalendarContract
import android.provider.OpenableColumns
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.AudioRecorderManager
import com.example.data.audio.GlobalAudioPlayer
import com.example.data.audio.LiveAudioCapture
import com.example.data.local.preferences.UserPreferencesManager
import com.example.data.local.files.FileStorageManager
import com.example.data.local.files.PendingRecording
import com.example.data.local.files.PendingRecordingRecovery
import com.example.data.speech.DeepgramLiveTranscriber
import com.example.data.speech.LiveSpeechRecognizer
import com.example.data.speech.VoiceCommand
import com.example.data.speech.VoiceCommandParser
import com.example.domain.model.Note
import com.example.domain.model.SubtitleCue
import com.example.domain.repository.AiIntelligenceRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class RecordingState {
    IDLE,
    CONNECTING,
    RECORDING,
    PAUSED,
    PROCESSING
}

enum class TranscriptionMode {
    LIVE_STREAMING,
    ACCURATE_STT
}

data class RecorderUiState(
    val state: RecordingState = RecordingState.IDLE,
    val durationSeconds: Long = 0L,
    val transcriptionMode: TranscriptionMode = TranscriptionMode.ACCURATE_STT,
    val liveTranscript: String = "",
    val partialTranscript: String = "",
    val amplitudes: List<Float> = emptyList(),
    val selectedCategory: String = "Meetings",
    val statusMessage: String = "",
    val completedNoteId: String? = null,
    val subtitleLanguage: String = "auto",
    val liveCaptionProvider: String = "gemini",
    val translationEnabled: Boolean = false,
    val translationLanguage: String = "en",
    val translatedTranscript: String = "",
    val translatedPartialTranscript: String = "",
    val pendingRecording: PendingRecording? = null
) {
    val formattedDuration: String
        get() {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
}

class RecorderViewModel(
    private val context: Context,
    private val noteRepository: NoteRepository,
    private val aiRepository: AiIntelligenceRepository,
    private val fileStorageManager: FileStorageManager = FileStorageManager(context)
) : ViewModel() {

    private companion object {
        const val MAX_UPLOADED_AUDIO_BYTES = 2L * 1024L * 1024L * 1024L
    }

    private sealed interface LiveChunkWork {
        data class Chunk(
            val value: LiveAudioCapture.TranscriptionChunk,
            val language: String
        ) : LiveChunkWork
        data class Flush(val completed: CompletableDeferred<Unit>) : LiveChunkWork
    }

    private sealed interface TranslationWork {
        data class Cue(val index: Int, val text: String, val targetLanguage: String) : TranslationWork
        data class Flush(val completed: CompletableDeferred<Unit>) : TranslationWork
    }

    private val audioRecorder = AudioRecorderManager(context)
    private val liveAudioCapture = LiveAudioCapture()
    private val liveSpeechRecognizer = LiveSpeechRecognizer(context)
    private val liveAudioChunks = Channel<LiveChunkWork>(Channel.UNLIMITED)
    private val translationWork = Channel<TranslationWork>(Channel.UNLIMITED)
    private val preferences = UserPreferencesManager.getInstance(context)
    private val recordingRecovery = PendingRecordingRecovery(context)

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var currentNoteId: String = ""
    private var currentAudioFile: File? = null
    private var isUsingCloudLiveCapture = false
    @Volatile private var isUsingDeepgram = false
    private var autoDetectCaptionLanguage = false
    private var isConnectingAutoDeepgram = false
    @Volatile private var deepgramTranscriber: DeepgramLiveTranscriber? = null
    private val cloudTranscriptBuilder = StringBuilder()
    private val subtitleCues = mutableListOf<SubtitleCue>()
    private val translatedCueTexts = mutableMapOf<Int, String>()
    private val partialTranslationVersion = AtomicLong(0)
    private val handledVoiceCommands = mutableSetOf<String>()
    private val spokenCommandPhrases = linkedSetOf<String>()
    private var pendingReminderAtMillis: Long? = null
    private var partialTranslationJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(
            subtitleLanguage = preferences.getSubtitleLanguage(),
            liveCaptionProvider = preferences.getLiveCaptionProvider(),
            translationEnabled = preferences.isLiveTranslationEnabled(),
            translationLanguage = preferences.getTranslationLanguage(),
            pendingRecording = recordingRecovery.getPending()
        )
        viewModelScope.launch {
            liveSpeechRecognizer.liveTranscript.collect { transcript ->
                if (_uiState.value.liveCaptionProvider == "android" && transcript.isNotBlank()) {
                    _uiState.value = _uiState.value.copy(
                        liveTranscript = transcript,
                        translatedPartialTranscript = ""
                    )
                    if (_uiState.value.translationEnabled) {
                        scheduleFinalTranslation(transcript)
                    }
                }
            }
        }
        viewModelScope.launch {
            liveSpeechRecognizer.partialTranscript.collect { transcript ->
                if (_uiState.value.liveCaptionProvider == "android") {
                    _uiState.value = _uiState.value.copy(partialTranscript = transcript)
                    if (transcript.isNotBlank()) schedulePartialTranslation(transcript)
                }
            }
        }
        viewModelScope.launch {
            liveSpeechRecognizer.statusMessage.collect { message ->
                if (!message.isNullOrBlank() && _uiState.value.liveCaptionProvider == "android") {
                    _uiState.value = _uiState.value.copy(statusMessage = message)
                }
            }
        }
        viewModelScope.launch {
            for (work in liveAudioChunks) {
                when (work) {
                    is LiveChunkWork.Flush -> work.completed.complete(Unit)
                    is LiveChunkWork.Chunk -> {
                        val chunk = work.value
                        try {
                            if (isUsingDeepgram) {
                                continue
                            }
                            if (!aiRepository.isKeyConfigured()) {
                                _uiState.value = _uiState.value.copy(
                                    statusMessage = "Add a Gemini API key in Settings to use live captions."
                                )
                                continue
                            }

                            val result = aiRepository.transcribeAudioChunk(
                                audioFilePath = chunk.file.absolutePath,
                                preferredLanguage = work.language,
                                startMs = chunk.startMs,
                                endMs = chunk.endMs
                            )
                            if (result.transcript.isBlank()) {
                                _uiState.value = _uiState.value.copy(
                                    statusMessage = "No speech detected in the latest audio chunk."
                                )
                            } else {
                                if (autoDetectCaptionLanguage) {
                                    when (detectCaptionLanguage(result.transcript)) {
                                        "am" -> {
                                            autoDetectCaptionLanguage = false
                                            _uiState.value = _uiState.value.copy(
                                                statusMessage = "Amharic detected. Using Gemini for live captions."
                                            )
                                        }
                                        "en" -> switchAutoDetectedEnglishToDeepgram()
                                    }
                                }
                                val cueIndex = synchronized(subtitleCues) {
                                    subtitleCues.add(
                                        SubtitleCue(
                                        startMs = chunk.startMs,
                                        endMs = chunk.endMs,
                                        text = result.transcript
                                        )
                                    )
                                    subtitleCues.lastIndex
                                }
                                queueCaptionTranslation(cueIndex, result.transcript)
                                cloudTranscriptBuilder.append(
                                    if (cloudTranscriptBuilder.isNotEmpty()) " " else ""
                                ).append(result.transcript)
                                val transcript = cloudTranscriptBuilder.toString().trim()
                                _uiState.value = _uiState.value.copy(
                                    liveTranscript = transcript,
                                    partialTranscript = "",
                                    statusMessage = if (isConnectingAutoDeepgram) {
                                        "English detected. Connecting to Deepgram..."
                                    } else {
                                        "Live caption updated"
                                    }
                                )
                                handleVoiceCommands(transcript)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val statusMessage = when (e) {
                                is HttpException -> when (e.code()) {
                                    429 -> "Gemini is rate-limiting captions or the API quota is exhausted. Wait briefly and check Gemini API usage/billing."
                                    403 -> "Gemini rejected the API key. Check the key and its Gemini API access in Settings."
                                    404 -> "No available Gemini transcription model was found for this API key."
                                    else -> "Gemini request failed (HTTP ${e.code()}). Check your connection and API settings."
                                }
                                else -> "Live caption failed: ${e.localizedMessage ?: "Check your connection."}"
                            }
                            _uiState.value = _uiState.value.copy(
                                statusMessage = statusMessage
                            )
                        } finally {
                            chunk.file.delete()
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            val translationJobs = mutableSetOf<Job>()
            for (work in translationWork) {
                when (work) {
                    is TranslationWork.Flush -> {
                        translationJobs.toList().joinAll()
                        translationJobs.clear()
                        work.completed.complete(Unit)
                    }
                    is TranslationWork.Cue -> {
                        translationJobs.removeAll { it.isCompleted }
                        if (translationJobs.size >= 2) {
                            translationJobs.first().join()
                            translationJobs.removeAll { it.isCompleted }
                        }
                        translationJobs += viewModelScope.launch {
                            try {
                                val translated = aiRepository.translateText(work.text, work.targetLanguage)
                                if (_uiState.value.translationEnabled &&
                                    _uiState.value.translationLanguage == work.targetLanguage
                                ) {
                                    synchronized(subtitleCues) {
                                        subtitleCues.getOrNull(work.index)?.let { cue ->
                                            subtitleCues[work.index] = cue.copy(translatedText = translated)
                                        }
                                        translatedCueTexts[work.index] = translated
                                    }
                                    _uiState.value = _uiState.value.copy(
                                        translatedTranscript = synchronized(subtitleCues) {
                                            translatedCueTexts.toSortedMap().values.joinToString(" ")
                                        },
                                        statusMessage = "Caption translated"
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                _uiState.value = _uiState.value.copy(
                                    statusMessage = "Translation failed: ${e.localizedMessage ?: "Check the Gemini API key and connection."}"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Collect amplitudes for dynamic waveform visualizer
        viewModelScope.launch {
            audioRecorder.amplitudeFlow.collect { amp ->
                if (_uiState.value.state == RecordingState.RECORDING) {
                    val currentList = _uiState.value.amplitudes.takeLast(40).toMutableList()
                    currentList.add(amp)
                    _uiState.value = _uiState.value.copy(amplitudes = currentList)
                }
            }
        }
        viewModelScope.launch {
            liveAudioCapture.amplitudeFlow.collect { amp ->
                if (_uiState.value.state == RecordingState.RECORDING) {
                    val currentList = _uiState.value.amplitudes.takeLast(40).toMutableList()
                    currentList.add(amp)
                    _uiState.value = _uiState.value.copy(amplitudes = currentList)
                }
            }
        }
    }

    fun setTranscriptionMode(mode: TranscriptionMode) {
        if (_uiState.value.state != RecordingState.IDLE) return
        val prevMode = _uiState.value.transcriptionMode
        if (prevMode == mode) return

        _uiState.value = _uiState.value.copy(transcriptionMode = mode)
    }

    fun uploadAndTranscribe(uri: Uri) {
        if (_uiState.value.state != RecordingState.IDLE) return
        if (!aiRepository.isKeyConfigured()) {
            _uiState.value = _uiState.value.copy(statusMessage = "Add a Gemini API key in Settings before uploading audio.")
            return
        }

        _uiState.value = _uiState.value.copy(
            state = RecordingState.PROCESSING,
            liveTranscript = "",
            partialTranscript = "",
            translatedTranscript = "",
            translatedPartialTranscript = "",
            statusMessage = "Uploading and transcribing audio..."
        )

        viewModelScope.launch {
            var uploadedFile: File? = null
            try {
                val noteId = UUID.randomUUID().toString()
                uploadedFile = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }.orEmpty()
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        .takeIf { it in setOf("wav", "mp3", "m4a", "mp4", "aac", "flac", "ogg", "aiff", "aif", "webm") }
                        ?: when (context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)) {
                            "audio/wav", "audio/x-wav" -> "wav"
                            "audio/mpeg" -> "mp3"
                            "audio/mp4", "video/mp4" -> "mp4"
                            "audio/aac" -> "aac"
                            "audio/flac" -> "flac"
                            "audio/ogg" -> "ogg"
                            "audio/aiff", "audio/x-aiff" -> "aiff"
                            "audio/webm" -> "webm"
                            else -> throw IllegalArgumentException("Unsupported audio format. Select WAV, MP3, M4A, AAC, FLAC, OGG, AIFF, or WebM.")
                        }
                    val file = fileStorageManager.getImportedAudioFile(noteId, extension)
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Could not open the selected audio file.")
                        input.use { source ->
                            file.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var totalBytes = 0L
                                while (true) {
                                    val read = source.read(buffer)
                                    if (read < 0) break
                                    totalBytes += read
                                    if (totalBytes > MAX_UPLOADED_AUDIO_BYTES) {
                                        throw IllegalArgumentException("Gemini supports audio files up to 2 GB. Select a smaller file.")
                                    }
                                    output.write(buffer, 0, read)
                                }
                                if (totalBytes == 0L) throw IllegalArgumentException("The selected audio file is empty.")
                            }
                        }
                        file
                    } catch (e: Exception) {
                        file.delete()
                        throw e
                    }
                }

                val audioFile = uploadedFile ?: throw IllegalStateException("The selected audio file could not be saved.")
                val fileMb = (audioFile.length() / (1024 * 1024)).coerceAtLeast(1)
                _uiState.value = _uiState.value.copy(
                    statusMessage = if (audioFile.length() > 20L * 1024L * 1024L) {
                        "Uploading & processing $fileMb MB audio file with Gemini AI..."
                    } else {
                        "Transcribing audio file ($fileMb MB)..."
                    }
                )
                val transcript = aiRepository.transcribeAudioFallback(audioFile.absolutePath).trim()
                if (transcript.isBlank()) throw IllegalStateException("No speech was detected in the uploaded audio.")

                var translated = ""
                var translationWarning: String? = null
                val state = _uiState.value
                if (state.translationEnabled) {
                    _uiState.value = state.copy(statusMessage = "Translating transcript...")
                    try {
                        translated = translateLongTranscript(transcript, state.translationLanguage)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        translationWarning = e.localizedMessage ?: "Check the Gemini API key and connection."
                    }
                }

                val duration = withContext(Dispatchers.IO) { mediaDurationSeconds(audioFile) } ?: 0L
                val firstSentence = transcript.split(Regex("[.!?\\n]")).firstOrNull()?.trim().orEmpty()
                val title = if (firstSentence.length in 5..35) firstSentence
                    else if (firstSentence.length > 35) firstSentence.take(32) + "..."
                    else transcript.take(28) + "..."
                val transcriptPath = withContext(Dispatchers.IO) {
                    fileStorageManager.saveTranscript(noteId, transcript)
                }

                noteRepository.saveNote(
                    Note(
                        id = noteId,
                        title = title.ifBlank { "Uploaded Voice Note" },
                        transcript = transcript,
                        transcriptFilePath = transcriptPath,
                        summary = "",
                        keyTakeaways = emptyList(),
                        actionItems = emptyList(),
                        summaryFilePath = "",
                        audioFilePath = audioFile.absolutePath,
                        durationSeconds = duration,
                        category = _uiState.value.selectedCategory,
                        timestamp = System.currentTimeMillis()
                    )
                )

                _uiState.value = _uiState.value.copy(
                    state = RecordingState.IDLE,
                    durationSeconds = duration,
                    liveTranscript = transcript,
                    translatedTranscript = translated,
                    statusMessage = translationWarning?.let {
                        "Audio transcribed, but translation failed: $it"
                    } ?: "Audio uploaded and transcribed successfully.",
                    completedNoteId = noteId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uploadedFile?.delete()
                _uiState.value = _uiState.value.copy(
                    state = RecordingState.IDLE,
                    statusMessage = "Upload transcription failed: ${e.localizedMessage ?: "Check the Gemini API key and file format."}"
                )
            }
        }
    }

    private suspend fun translateLongTranscript(transcript: String, targetLanguage: String): String {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < transcript.length) {
            var end = minOf(start + 2_500, transcript.length)
            if (end < transcript.length) {
                val boundary = transcript.lastIndexOf(' ', end)
                if (boundary > start) end = boundary
            }
            chunks += transcript.substring(start, end).trim()
            start = end
            while (start < transcript.length && transcript[start].isWhitespace()) start++
        }
        return chunks.filter(String::isNotBlank)
            .map { aiRepository.translateText(it, targetLanguage).trim() }
            .joinToString(" ")
    }

    fun setCategory(category: String) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }

    fun setSubtitleLanguage(language: String) {
        preferences.setSubtitleLanguage(language)
        _uiState.value = _uiState.value.copy(subtitleLanguage = language)
    }

    fun setLiveTranslation(enabled: Boolean, targetLanguage: String = _uiState.value.translationLanguage) {
        preferences.setLiveTranslationEnabled(enabled)
        preferences.setTranslationLanguage(targetLanguage)
        val languageChanged = targetLanguage != _uiState.value.translationLanguage
        if (languageChanged) {
            synchronized(subtitleCues) {
                translatedCueTexts.clear()
                subtitleCues.indices.forEach { index ->
                    subtitleCues[index] = subtitleCues[index].copy(translatedText = "")
                }
            }
        }
        _uiState.value = _uiState.value.copy(
            translationEnabled = enabled,
            translationLanguage = targetLanguage,
            translatedPartialTranscript = "",
            translatedTranscript = when {
                !enabled -> ""
                languageChanged -> ""
                else -> synchronized(subtitleCues) {
                    translatedCueTexts.toSortedMap().values.joinToString(" ")
                }
            }
        )
        if (!enabled || languageChanged) {
            partialTranslationVersion.incrementAndGet()
            partialTranslationJob?.cancel()
            partialTranslationJob = null
        } else if (_uiState.value.partialTranscript.isNotBlank()) {
            schedulePartialTranslation(_uiState.value.partialTranscript)
        }
        if (enabled) {
            synchronized(subtitleCues) {
                subtitleCues.forEachIndexed { index, cue ->
                    if (cue.translatedText.isBlank() || languageChanged) {
                        translationWork.trySend(TranslationWork.Cue(index, cue.text, targetLanguage))
                    }
                }
            }
        }
    }

    private fun queueCaptionTranslation(index: Int, text: String) {
        val state = _uiState.value
        if (state.translationEnabled) {
            translationWork.trySend(TranslationWork.Cue(index, text, state.translationLanguage))
        }
    }

    private var finalTranslationJob: Job? = null

    private fun scheduleFinalTranslation(transcript: String) {
        val state = _uiState.value
        if (!state.translationEnabled || transcript.isBlank()) return

        finalTranslationJob?.cancel()
        finalTranslationJob = viewModelScope.launch {
            try {
                val translated = aiRepository.translateText(transcript, state.translationLanguage)
                val latestState = _uiState.value
                if (latestState.state == RecordingState.RECORDING &&
                    latestState.translationEnabled &&
                    latestState.translationLanguage == state.translationLanguage
                ) {
                    _uiState.value = latestState.copy(
                        translatedTranscript = translated,
                        translatedPartialTranscript = ""
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignore transient live translation network glitches
            }
        }
    }

    private fun schedulePartialTranslation(transcript: String) {
        val state = _uiState.value
        if (!state.translationEnabled || transcript.isBlank()) return

        val requestVersion = partialTranslationVersion.incrementAndGet()
        partialTranslationJob?.cancel()
        partialTranslationJob = viewModelScope.launch {
            delay(100)
            if (requestVersion != partialTranslationVersion.get()) return@launch
            try {
                val translated = aiRepository.translateText(transcript, state.translationLanguage)
                val latestState = _uiState.value
                if (requestVersion == partialTranslationVersion.get() &&
                    latestState.state == RecordingState.RECORDING &&
                    latestState.translationEnabled &&
                    latestState.translationLanguage == state.translationLanguage
                ) {
                    _uiState.value = latestState.copy(translatedPartialTranscript = translated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestVersion == partialTranslationVersion.get()) {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Translation failed: ${e.localizedMessage ?: "Check the Gemini API key and connection."}"
                    )
                }
            }
        }
    }

    private fun handleVoiceCommands(transcript: String) {
        VoiceCommandParser.findCommands(transcript).forEach { command ->
            val key = "${command::class.simpleName}:${command.phrase.lowercase(Locale.ROOT)}"
            if (!handledVoiceCommands.add(key)) return@forEach
            spokenCommandPhrases.add(command.phrase)
            val updatedTranscript = if (isUsingCloudLiveCapture) {
                val index = cloudTranscriptBuilder.lastIndexOf(command.phrase, ignoreCase = true)
                if (index >= 0) {
                    cloudTranscriptBuilder.delete(index, index + command.phrase.length)
                    val normalized = cloudTranscriptBuilder.toString()
                        .replace(Regex("\\s{2,}"), " ")
                        .trim()
                    cloudTranscriptBuilder.setLength(0)
                    cloudTranscriptBuilder.append(normalized)
                }
                cloudTranscriptBuilder.toString().trim()
            } else {
                _uiState.value.liveTranscript
            }
            when (command) {
                is VoiceCommand.Tag -> {
                    _uiState.value = _uiState.value.copy(
                        liveTranscript = updatedTranscript,
                        selectedCategory = command.category,
                        statusMessage = "Tagged as ${command.category}"
                    )
                }
                is VoiceCommand.Reminder -> {
                    pendingReminderAtMillis = command.triggerAtMillis
                    val whenText = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
                        .format(Date(command.triggerAtMillis))
                    _uiState.value = _uiState.value.copy(
                        liveTranscript = updatedTranscript,
                        statusMessage = "Reminder ready for $whenText. Finish recording to add it to Calendar."
                    )
                }
            }
        }
    }

    fun startRecording() {
        if (_uiState.value.state != RecordingState.IDLE) return
        // Stop any audio playing in the app immediately
        GlobalAudioPlayer.stopAndRelease()

        val isLiveMode = _uiState.value.transcriptionMode == TranscriptionMode.LIVE_STREAMING
        val preferredProvider = preferences.getLiveCaptionProvider()
        val subtitleLanguage = _uiState.value.subtitleLanguage
        val geminiAvailable = !isLiveMode || aiRepository.isKeyConfigured()
        val deepgramApiKey = if (isLiveMode) {
            try {
                preferences.getCustomDeepgramApiKey()
            } catch (e: IllegalStateException) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = e.message ?: "Could not read the Deepgram API key."
                )
                return
            }
        } else {
            ""
        }
        val deepgramAvailable = isLiveMode && deepgramApiKey.isNotBlank()
        autoDetectCaptionLanguage = isLiveMode &&
            subtitleLanguage == "auto" &&
            geminiAvailable &&
            deepgramAvailable
        isConnectingAutoDeepgram = false
        val selectedProvider = when {
            !isLiveMode -> preferredProvider
            isLiveMode && !geminiAvailable && !deepgramAvailable -> "android"
            autoDetectCaptionLanguage -> "gemini"
            subtitleLanguage == "en" && deepgramAvailable -> "deepgram"
            subtitleLanguage == "en" && geminiAvailable -> "gemini"
            subtitleLanguage == "en" -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "English live captions require Deepgram or Gemini. Add an API key in Settings."
                )
                return
            }
            subtitleLanguage == "am" && geminiAvailable -> "gemini"
            subtitleLanguage == "am" -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Amharic live captions require Gemini. Add a Gemini API key in Settings."
                )
                return
            }
            preferredProvider == "deepgram" && deepgramAvailable -> "deepgram"
            preferredProvider == "gemini" && geminiAvailable -> "gemini"
            deepgramAvailable -> "deepgram"
            geminiAvailable -> "gemini"
            else -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Add a Gemini or Deepgram API key in Settings to use live captions."
                )
                return
            }
        }
        val providerFallbackMessage = when {
            selectedProvider == "android" ->
                "Using the on-device Android speech recognizer. No API key is required."
            autoDetectCaptionLanguage ->
                "Detecting spoken language. English uses Deepgram; Amharic uses Gemini."
            subtitleLanguage == "am" && preferredProvider == "deepgram" && selectedProvider == "gemini" ->
                "Deepgram does not support Amharic here; using Gemini."
            subtitleLanguage == "en" && selectedProvider == "gemini" ->
                "Deepgram key unavailable; using Gemini for English captions."
            selectedProvider != preferredProvider && preferredProvider == "deepgram" ->
                "Deepgram key unavailable; using Gemini."
            selectedProvider != preferredProvider ->
                "Gemini key unavailable; using Deepgram."
            selectedProvider == "gemini" && preferredProvider == "deepgram" ->
                "Deepgram does not support Amharic here; using Gemini."
            else -> null
        }

        currentNoteId = UUID.randomUUID().toString()
        isUsingCloudLiveCapture = isLiveMode
        isUsingDeepgram = isUsingCloudLiveCapture && selectedProvider == "deepgram"
        deepgramTranscriber = null
        _uiState.value = _uiState.value.copy(liveCaptionProvider = selectedProvider)
        currentAudioFile = if (isUsingCloudLiveCapture) {
            fileStorageManager.getLiveAudioFile(currentNoteId)
        } else {
            fileStorageManager.getAudioFile(currentNoteId)
        }
        handledVoiceCommands.clear()
        spokenCommandPhrases.clear()
        pendingReminderAtMillis = null
        cloudTranscriptBuilder.setLength(0)
        subtitleCues.clear()
        translatedCueTexts.clear()
        partialTranslationVersion.incrementAndGet()
        partialTranslationJob?.cancel()
        partialTranslationJob = null

        if (isUsingDeepgram) {
            val transcriber = createDeepgramTranscriber()
            deepgramTranscriber = transcriber
            _uiState.value = _uiState.value.copy(
                state = RecordingState.CONNECTING,
                statusMessage = "Connecting to Deepgram..."
            )
            viewModelScope.launch {
                try {
                    transcriber.connect(deepgramApiKey, subtitleLanguage)
                    if (_uiState.value.state != RecordingState.CONNECTING) {
                        transcriber.cancel()
                        return@launch
                    }
                    if (startLiveAudioCapture(transcriber)) {
                        beginRecordingUi(providerFallbackMessage ?: "Live captions connected to Deepgram.")
                    } else {
                        transcriber.cancel()
                        deepgramTranscriber = null
                        _uiState.value = _uiState.value.copy(
                            state = RecordingState.IDLE,
                            statusMessage = "Could not access microphone."
                        )
                    }
                } catch (e: CancellationException) {
                    transcriber.cancel()
                    throw e
                } catch (e: Exception) {
                    transcriber.cancel()
                    deepgramTranscriber = null
                    if (geminiAvailable && startLiveAudioCapture()) {
                        isUsingDeepgram = false
                        _uiState.value = _uiState.value.copy(liveCaptionProvider = "gemini")
                        beginRecordingUi(
                            "Deepgram unavailable; automatically switched to Gemini. ${e.localizedMessage ?: ""}".trim()
                        )
                    } else {
                        currentAudioFile?.delete()
                        _uiState.value = _uiState.value.copy(
                            state = RecordingState.IDLE,
                            statusMessage = e.localizedMessage ?: "Could not connect to Deepgram."
                        )
                    }
                }
            }
            return
        }

        val started = if (selectedProvider == "android") {
            liveSpeechRecognizer.startListening(subtitleLanguage)
            true
        } else if (isUsingCloudLiveCapture) {
            startLiveAudioCapture()
        } else {
            audioRecorder.startRecording(currentAudioFile!!)
        }
        if (!started) {
            _uiState.value = _uiState.value.copy(statusMessage = "Could not access microphone.")
            return
        }

        beginRecordingUi(
            when {
                providerFallbackMessage != null -> providerFallbackMessage
                isUsingCloudLiveCapture -> "Listening for live captions..."
                else -> "Recording high-fidelity audio..."
            }
        )
    }

    private fun startLiveAudioCapture(deepgram: DeepgramLiveTranscriber? = null): Boolean {
        val audioFile = currentAudioFile ?: return false
        val sendFailureReported = AtomicBoolean(false)
        return liveAudioCapture.startRecording(
            outputFile = audioFile,
            chunkDirectory = fileStorageManager.getLiveTranscriptionChunkDirectory(currentNoteId),
            onChunkReady = if (deepgram == null) {
                { chunk ->
                    if (isUsingDeepgram || liveAudioChunks.trySend(
                            LiveChunkWork.Chunk(chunk, _uiState.value.subtitleLanguage)
                        ).isFailure
                    ) {
                        chunk.file.delete()
                    }
                }
            } else {
                null
            },
            onPcmAudio = if (deepgram != null) {
                { audio ->
                    if (!deepgram.sendAudio(audio) && sendFailureReported.compareAndSet(false, true)) {
                        viewModelScope.launch {
                            _uiState.value = _uiState.value.copy(
                                statusMessage = "Deepgram connection lost. Audio recording continues, but captions may be incomplete."
                            )
                        }
                    }
                }
            } else {
                { audio ->
                    val transcriber = deepgramTranscriber
                    if (transcriber != null && !transcriber.sendAudio(audio) &&
                        sendFailureReported.compareAndSet(false, true)
                    ) {
                        viewModelScope.launch {
                            _uiState.value = _uiState.value.copy(
                                statusMessage = "Deepgram connection lost. Audio recording continues, but captions may be incomplete."
                            )
                        }
                    }
                }
            },
            emitTranscriptionChunks = deepgram == null
        )
    }

    private fun detectCaptionLanguage(transcript: String): String? {
        var hasLatinLetter = false
        for (character in transcript) {
            if (!character.isLetter()) continue
            when (Character.UnicodeScript.of(character.code)) {
                Character.UnicodeScript.ETHIOPIC -> return "am"
                Character.UnicodeScript.LATIN -> hasLatinLetter = true
                else -> return null
            }
        }
        return "en".takeIf { hasLatinLetter }
    }

    private fun switchAutoDetectedEnglishToDeepgram() {
        autoDetectCaptionLanguage = false
        if (isConnectingAutoDeepgram || isUsingDeepgram) return
        val apiKey = try {
            preferences.getCustomDeepgramApiKey()
        } catch (e: IllegalStateException) {
            _uiState.value = _uiState.value.copy(
                statusMessage = e.message ?: "Could not read the Deepgram API key."
            )
            return
        }
        if (apiKey.isBlank()) return

        isConnectingAutoDeepgram = true
        _uiState.value = _uiState.value.copy(
            statusMessage = "English detected. Connecting to Deepgram..."
        )
        viewModelScope.launch {
            val transcriber = createDeepgramTranscriber()
            try {
                transcriber.connect(apiKey, "en")
                if (_uiState.value.state != RecordingState.RECORDING) {
                    transcriber.cancel()
                    isConnectingAutoDeepgram = false
                    return@launch
                }
                deepgramTranscriber = transcriber
                isUsingDeepgram = true
                isConnectingAutoDeepgram = false
                _uiState.value = _uiState.value.copy(
                    liveCaptionProvider = "deepgram",
                    partialTranscript = "",
                    translatedPartialTranscript = "",
                    statusMessage = "English detected. Switched live captions to Deepgram."
                )
            } catch (e: CancellationException) {
                transcriber.cancel()
                isConnectingAutoDeepgram = false
                throw e
            } catch (e: Exception) {
                transcriber.cancel()
                isConnectingAutoDeepgram = false
                _uiState.value = _uiState.value.copy(
                    statusMessage = "English detected, but Deepgram could not connect. Continuing with Gemini: ${e.localizedMessage ?: "Check the connection and API key."}"
                )
            }
        }
    }

    private fun createDeepgramTranscriber() = DeepgramLiveTranscriber(
        onFinalTranscript = { text, startMs, endMs ->
            viewModelScope.launch {
                partialTranslationVersion.incrementAndGet()
                partialTranslationJob?.cancel()
                partialTranslationJob = null
                val cueIndex: Int
                synchronized(cloudTranscriptBuilder) {
                    cueIndex = synchronized(subtitleCues) {
                        subtitleCues.add(SubtitleCue(startMs, endMs, text))
                        subtitleCues.lastIndex
                    }
                    cloudTranscriptBuilder.append(if (cloudTranscriptBuilder.isNotEmpty()) " " else "").append(text)
                    val transcript = cloudTranscriptBuilder.toString().trim()
                    _uiState.value = _uiState.value.copy(
                        liveTranscript = transcript,
                        partialTranscript = "",
                        translatedPartialTranscript = "",
                        statusMessage = "Live caption updated"
                    )
                    handleVoiceCommands(transcript)
                }
                queueCaptionTranslation(cueIndex, text)
            }
        },
        onInterimTranscript = { transcript ->
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    partialTranscript = transcript,
                    translatedPartialTranscript = if (transcript.isBlank()) "" else _uiState.value.translatedPartialTranscript
                )
                if (transcript.isBlank()) {
                    partialTranslationVersion.incrementAndGet()
                    partialTranslationJob?.cancel()
                    partialTranslationJob = null
                } else {
                    schedulePartialTranslation(transcript)
                }
            }
        },
        onStatus = { status ->
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(statusMessage = status)
            }
        }
    )

    private fun beginRecordingUi(statusMessage: String) {
        try {
            recordingRecovery.markStarted(
                noteId = currentNoteId,
                file = currentAudioFile ?: throw IllegalStateException("Recording file is unavailable."),
                category = _uiState.value.selectedCategory,
                isLiveCapture = isUsingCloudLiveCapture
            )
        } catch (e: Exception) {
            if (isUsingCloudLiveCapture) {
                liveAudioCapture.stopRecordingNow()
                deepgramTranscriber?.cancel()
                deepgramTranscriber = null
            } else {
                audioRecorder.stopRecording()
            }
            currentAudioFile?.delete()
            _uiState.value = _uiState.value.copy(
                state = RecordingState.IDLE,
                statusMessage = "Could not save recording recovery state: ${e.localizedMessage ?: "Storage unavailable."}"
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            state = RecordingState.RECORDING,
            durationSeconds = 0L,
            liveTranscript = "",
            partialTranscript = "",
            translatedTranscript = "",
            translatedPartialTranscript = "",
            amplitudes = emptyList(),
            statusMessage = statusMessage
        )

        startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        partialTranslationVersion.incrementAndGet()
        partialTranslationJob?.cancel()
        partialTranslationJob = null
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (_uiState.value.state == RecordingState.RECORDING) {
                    recordingRecovery.updateDuration(_uiState.value.durationSeconds + 1)
                    _uiState.value = _uiState.value.copy(
                        durationSeconds = _uiState.value.durationSeconds + 1
                    )
                }
            }
        }
    }

    fun pauseRecording() {
        if (_uiState.value.state != RecordingState.RECORDING) return
        if (_uiState.value.liveCaptionProvider == "android") {
            liveSpeechRecognizer.pauseListening()
        } else if (isUsingCloudLiveCapture) {
            liveAudioCapture.pauseRecording()
            deepgramTranscriber?.setPaused(true)
        } else {
            audioRecorder.pauseRecording()
        }
        _uiState.value = _uiState.value.copy(
            state = RecordingState.PAUSED,
            statusMessage = "Recording paused"
        )
    }

    fun resumeRecording() {
        if (_uiState.value.state != RecordingState.PAUSED) return
        if (_uiState.value.liveCaptionProvider == "android") {
            liveSpeechRecognizer.resumeListening()
        } else if (isUsingCloudLiveCapture) {
            liveAudioCapture.resumeRecording()
            deepgramTranscriber?.setPaused(false)
        } else {
            audioRecorder.resumeRecording()
        }
        _uiState.value = _uiState.value.copy(
            state = RecordingState.RECORDING,
            statusMessage = if (isUsingCloudLiveCapture) {
                "Listening. Live captions will appear shortly..."
            } else {
                "Recording high-fidelity audio..."
            }
        )
    }

    fun finishRecording(onFinished: (String) -> Unit = {}) {
        if (_uiState.value.state !in setOf(RecordingState.RECORDING, RecordingState.PAUSED)) return
        val totalDuration = _uiState.value.durationSeconds
        timerJob?.cancel()
        partialTranslationVersion.incrementAndGet()
        partialTranslationJob?.cancel()
        partialTranslationJob = null
        val noteId = currentNoteId
        val audioFile = currentAudioFile ?: if (isUsingCloudLiveCapture) {
            fileStorageManager.getLiveAudioFile(noteId)
        } else {
            fileStorageManager.getAudioFile(noteId)
        }
        val category = _uiState.value.selectedCategory
        val reminderAtMillis = pendingReminderAtMillis
        val commandsToRemove = spokenCommandPhrases.toList()
        val usingCloudLiveCapture = isUsingCloudLiveCapture
        _uiState.value = _uiState.value.copy(
            state = RecordingState.PROCESSING,
            statusMessage = "Saving recording..."
        )

        viewModelScope.launch {
            if (_uiState.value.liveCaptionProvider == "android") {
                val nativeTranscript = liveSpeechRecognizer.stopListening()
                if (nativeTranscript.isNotBlank()) {
                    cloudTranscriptBuilder.setLength(0)
                    cloudTranscriptBuilder.append(nativeTranscript)
                }
            }
            if (usingCloudLiveCapture) {
                liveAudioCapture.stopRecording()
                deepgramTranscriber?.finish()
                deepgramTranscriber = null
                val flush = CompletableDeferred<Unit>()
                liveAudioChunks.send(LiveChunkWork.Flush(flush))
                flush.await()
                val translationFlush = CompletableDeferred<Unit>()
                translationWork.send(TranslationWork.Flush(translationFlush))
                translationFlush.await()
                val savedCues = synchronized(subtitleCues) {
                    subtitleCues.sortedBy(SubtitleCue::startMs)
                }
                fileStorageManager.saveSubtitles(noteId, savedCues)
            } else {
                audioRecorder.stopRecording()
            }

            val finalLiveTranscript = if (usingCloudLiveCapture) {
                cloudTranscriptBuilder.toString().trim()
            } else {
                ""
            }
            val immediateTitle = if (finalLiveTranscript.isNotBlank() && finalLiveTranscript.length > 5) {
                val firstSentence = finalLiveTranscript.split(Regex("[.!?\\n]")).firstOrNull()?.trim() ?: ""
                if (firstSentence.length in 5..35) firstSentence
                else if (firstSentence.length > 35) firstSentence.take(32) + "..."
                else finalLiveTranscript.take(28) + "..."
            } else {
                "$category Voice Note"
            }

            // Save initial note entity immediately so audio player and note detail view open without delay
            val initialNote = Note(
                id = noteId,
                title = immediateTitle,
                transcript = finalLiveTranscript,
                transcriptFilePath = "",
                summary = "",
                keyTakeaways = emptyList(),
                actionItems = emptyList(),
                summaryFilePath = "",
                audioFilePath = audioFile.absolutePath,
                durationSeconds = totalDuration,
                category = category,
                timestamp = System.currentTimeMillis()
            )

            noteRepository.saveNote(initialNote)
            recordingRecovery.clear()

            // Kick off background speech-to-text transcription and AI executive summary
            com.example.data.speech.BackgroundTranscriptionManager.startBackgroundTranscription(
                context = context,
                noteId = noteId,
                durationSeconds = totalDuration,
                category = category,
                liveTranscript = finalLiveTranscript,
                spokenCommands = commandsToRemove,
                audioFilePath = audioFile.absolutePath,
                noteRepository = noteRepository,
                aiRepository = aiRepository,
                fileStorageManager = fileStorageManager
            )

            _uiState.value = _uiState.value.copy(
                state = RecordingState.IDLE,
                completedNoteId = noteId,
                statusMessage = "Ready"
            )

            onFinished(noteId)
            reminderAtMillis?.let(::openReminderInCalendar)
        }
    }

    private fun openReminderInCalendar(triggerAtMillis: Long) {
        val endTime = triggerAtMillis + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "EchoNotes voice reminder")
            putExtra(CalendarContract.Events.DESCRIPTION, "Reminder created by a voice command while recording in EchoNotes.")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, triggerAtMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No calendar app is available for this reminder.", Toast.LENGTH_LONG).show()
        }
    }

    fun cancelRecording() {
        if (_uiState.value.state !in setOf(
                RecordingState.CONNECTING,
                RecordingState.RECORDING,
                RecordingState.PAUSED,
                RecordingState.PROCESSING
            )
        ) return
        timerJob?.cancel()
        partialTranslationVersion.incrementAndGet()
        partialTranslationJob?.cancel()
        partialTranslationJob = null
        deepgramTranscriber?.cancel()
        deepgramTranscriber = null
        if (_uiState.value.liveCaptionProvider == "android") {
            liveSpeechRecognizer.stopListening()
        } else if (isUsingCloudLiveCapture) {
            liveAudioCapture.stopRecordingNow()
        } else {
            audioRecorder.stopRecording()
        }
        currentAudioFile?.delete()
        recordingRecovery.clear()
        _uiState.value = RecorderUiState(
            subtitleLanguage = preferences.getSubtitleLanguage(),
            liveCaptionProvider = preferences.getLiveCaptionProvider(),
            translationEnabled = preferences.isLiveTranslationEnabled(),
            translationLanguage = preferences.getTranslationLanguage(),
            pendingRecording = recordingRecovery.getPending()
        )
    }

    fun recoverInterruptedRecording() {
        val pending = _uiState.value.pendingRecording ?: return
        if (_uiState.value.state != RecordingState.IDLE) return
        if (_uiState.value.statusMessage.startsWith("Recovering")) return
        _uiState.value = _uiState.value.copy(statusMessage = "Recovering interrupted recording...")
        viewModelScope.launch {
            try {
                val file = File(pending.filePath)
                val fileIsUsable = if (pending.isLiveCapture) {
                    withContext(Dispatchers.IO) {
                        recordingRecovery.repairInterruptedWav(file)
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        mediaDurationSeconds(file) != null
                    }
                }
                if (!fileIsUsable) {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "The interrupted audio file is incomplete or unavailable. You can discard it."
                    )
                    return@launch
                }

                val durationFromFile = if (pending.isLiveCapture) {
                    ((file.length() - 44L).coerceAtLeast(0L) / 32_000L)
                } else {
                    withContext(Dispatchers.IO) { mediaDurationSeconds(file) } ?: 0L
                }
                val duration = maxOf(pending.durationSeconds, durationFromFile)
                val recoveredNote = Note(
                    id = pending.noteId,
                    title = "Recovered ${pending.category} recording",
                    transcript = "",
                    audioFilePath = file.absolutePath,
                    durationSeconds = duration,
                    category = pending.category,
                    timestamp = pending.startedAtMillis
                )
                noteRepository.saveNote(recoveredNote)
                recordingRecovery.clear()
                com.example.data.speech.BackgroundTranscriptionManager.startBackgroundTranscription(
                    context = context,
                    noteId = pending.noteId,
                    durationSeconds = duration,
                    category = pending.category,
                    liveTranscript = "",
                    audioFilePath = file.absolutePath,
                    noteRepository = noteRepository,
                    aiRepository = aiRepository,
                    fileStorageManager = fileStorageManager
                )
                _uiState.value = _uiState.value.copy(
                    pendingRecording = null,
                    completedNoteId = pending.noteId,
                    statusMessage = "Recording recovered. Transcription is processing."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Could not recover recording: ${e.localizedMessage ?: "Storage unavailable."}"
                )
            }
        }
    }

    fun discardInterruptedRecording() {
        if (_uiState.value.statusMessage.startsWith("Recovering")) return
        val pending = _uiState.value.pendingRecording ?: return
        File(pending.filePath).delete()
        if (pending.isLiveCapture) {
            val chunkDir = fileStorageManager.getLiveTranscriptionChunkDirectory(pending.noteId)
            chunkDir.listFiles()?.forEach(File::delete)
            chunkDir.delete()
        }
        recordingRecovery.clear()
        _uiState.value = _uiState.value.copy(
            pendingRecording = null,
            statusMessage = "Interrupted recording discarded."
        )
    }

    private fun mediaDurationSeconds(file: File): Long? {
        if (!file.isFile || file.length() <= 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { (it + 999L) / 1_000L }
                ?.takeIf { it > 0L }
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        liveAudioCapture.stopRecordingNow()
        deepgramTranscriber?.cancel()
        liveAudioChunks.close()
        translationWork.close()
    }
}
