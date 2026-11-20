package com.example.data.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.local.files.FileStorageManager
import com.example.domain.model.AiAnalysisResult
import com.example.domain.model.Note
import com.example.domain.repository.AiIntelligenceRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class BackgroundProcessingTask(
    val noteId: String,
    val noteTitle: String,
    val status: String,
    val isComplete: Boolean = false,
    val isFailed: Boolean = false
)

/**
 * Singleton service that manages asynchronous background speech-to-text transcription,
 * AI executive summary, and note synthesis.
 * This runs completely off the UI thread and does not block the user from navigating or using any screen.
 * Displays notifications / in-app alerts when processing finishes.
 */
object BackgroundTranscriptionManager {
    private const val TAG = "BackgroundTranscription"
    private const val CHANNEL_ID = "echonotes_transcription_channel"
    private const val NOTIFICATION_ID_BASE = 2000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _activeTasks = MutableStateFlow<Map<String, BackgroundProcessingTask>>(emptyMap())
    val activeTasks: StateFlow<Map<String, BackgroundProcessingTask>> = _activeTasks.asStateFlow()

    private val _completionEvents = MutableSharedFlow<BackgroundProcessingTask>(replay = 1)
    val completionEvents: SharedFlow<BackgroundProcessingTask> = _completionEvents.asSharedFlow()

    private val runningJobs = ConcurrentHashMap<String, Boolean>()
    private val retryRequests = ConcurrentHashMap<String, RetryRequest>()

    private data class RetryRequest(
        val context: Context,
        val durationSeconds: Long,
        val category: String,
        val liveTranscript: String,
        val spokenCommands: List<String>,
        val audioFilePath: String?,
        val translationLanguage: String?,
        val noteRepository: NoteRepository,
        val aiRepository: AiIntelligenceRepository,
        val fileStorageManager: FileStorageManager
    )

    fun startBackgroundTranscription(
        context: Context,
        noteId: String,
        durationSeconds: Long,
        category: String,
        liveTranscript: String,
        spokenCommands: List<String> = emptyList(),
        audioFilePath: String? = null,
        translationLanguage: String? = null,
        noteRepository: NoteRepository,
        aiRepository: AiIntelligenceRepository,
        fileStorageManager: FileStorageManager
    ) {
        if (runningJobs.putIfAbsent(noteId, true) != null) {
            return
        }
        retryRequests[noteId] = RetryRequest(
            context.applicationContext,
            durationSeconds,
            category,
            liveTranscript,
            spokenCommands,
            audioFilePath,
            translationLanguage,
            noteRepository,
            aiRepository,
            fileStorageManager
        )

        initNotificationChannel(context)

        val initialTitle = if (liveTranscript.isNotBlank() && liveTranscript.length > 5) {
            val firstSentence = liveTranscript.split(Regex("[.!?\\n]")).firstOrNull()?.trim() ?: ""
            if (firstSentence.length in 5..35) firstSentence
            else if (firstSentence.length > 35) firstSentence.take(32) + "..."
            else liveTranscript.take(28) + "..."
        } else {
            "$category Voice Note"
        }

        updateTask(
            BackgroundProcessingTask(
                noteId = noteId,
                noteTitle = initialTitle,
                status = "Transcribing audio in background...",
                isComplete = false
            )
        )

        showNotification(context, noteId, initialTitle, "Transcribing audio in background...")

        scope.launch {
            var currentPhase = "transcription"
            try {
                val audioFile = audioFilePath?.let(::File) ?: fileStorageManager.getAudioFile(noteId)
                var resolvedTranscript = liveTranscript.trim()

                // Re-transcribe only when live chunk captions did not produce any text.
                if (liveTranscript.isBlank() && audioFile.exists() && audioFile.length() > 0) {
                    updateTask(
                        BackgroundProcessingTask(
                            noteId = noteId,
                            noteTitle = initialTitle,
                            status = "Transcribing speech in its original language...",
                            isComplete = false
                        )
                    )
                    showNotification(context, noteId, initialTitle, "Transcribing speech in its original language...")

                    val audioTranscript = retryNetworkRequest {
                        aiRepository.transcribeAudioFallback(audioFile.absolutePath)
                    }
                    if (audioTranscript.isNotBlank()) {
                        resolvedTranscript = VoiceCommandParser.removeCommandPhrases(
                            audioTranscript.trim(),
                            spokenCommands
                        )
                    }
                }
                resolvedTranscript = VoiceCommandParser.removeCommandPhrases(resolvedTranscript, spokenCommands)
                retryRequests.computeIfPresent(noteId) { _, request ->
                    request.copy(liveTranscript = resolvedTranscript)
                }
                var translatedTranscript = ""
                var translationFailure: String? = null
                if (translationLanguage != null && resolvedTranscript.isNotBlank()) {
                    currentPhase = "translation"
                    updateTask(
                        BackgroundProcessingTask(
                            noteId = noteId,
                            noteTitle = initialTitle,
                            status = "Translating transcript to ${translationLanguage.uppercase()}...",
                            isComplete = false
                        )
                    )
                    try {
                        translatedTranscript = translateTranscript(
                            aiRepository,
                            resolvedTranscript,
                            translationLanguage
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        translationFailure = e.localizedMessage ?: "Check your internet connection and API settings."
                        Log.w(TAG, "Translation failed for imported note $noteId.", e)
                    }
                }

                currentPhase = "saving transcript"
                // Dynamic topic title extraction grounded directly in the transcript
                val finalTitle = when {
                    resolvedTranscript.isNotBlank() && resolvedTranscript.length > 5 -> {
                        val firstSentence = resolvedTranscript.split(Regex("[.!?\\n]")).firstOrNull()?.trim() ?: ""
                        if (firstSentence.length in 5..35) {
                            firstSentence
                        } else if (firstSentence.length > 35) {
                            firstSentence.take(32) + "..."
                        } else {
                            resolvedTranscript.take(28) + "..."
                        }
                    }
                    else -> initialTitle
                }

                updateTask(
                    BackgroundProcessingTask(
                        noteId = noteId,
                        noteTitle = finalTitle,
                        status = if (resolvedTranscript.isNotBlank()) {
                            "Synthesizing AI summary & key takeaways..."
                        } else {
                            "Saving recording; no transcript was available..."
                        },
                        isComplete = false
                    )
                )

                // Save transcript file (.txt)
                val transcriptFilePath = fileStorageManager.saveTranscript(noteId, resolvedTranscript)
                if (translatedTranscript.isNotBlank()) {
                    fileStorageManager.saveTranslation(noteId, translatedTranscript)
                }

                noteRepository.saveNote(
                    Note(
                        id = noteId,
                        title = finalTitle,
                        transcript = resolvedTranscript,
                        transcriptFilePath = transcriptFilePath,
                        audioFilePath = audioFile.absolutePath,
                        durationSeconds = durationSeconds,
                        category = category,
                        timestamp = System.currentTimeMillis()
                    )
                )

                currentPhase = "AI summary"
                // Generate AI Executive Summary & Takeaways
                val aiResult = if (resolvedTranscript.isNotBlank()) {
                    retryNetworkRequest {
                        aiRepository.generateAnalysis(resolvedTranscript, finalTitle, category)
                    }
                } else {
                    AiAnalysisResult(
                        executiveSummary = "",
                        keyTakeaways = emptyList(),
                        actionItems = emptyList()
                    )
                }

                // Save summary markdown file (.md)
                val summaryFilePath = fileStorageManager.saveSummary(
                    noteId = noteId,
                    title = finalTitle,
                    summaryText = aiResult.executiveSummary,
                    takeaways = aiResult.keyTakeaways,
                    actionItems = aiResult.actionItems.map { it.title }
                )

                // Save fully processed note to database
                val processedNote = Note(
                    id = noteId,
                    title = finalTitle,
                    transcript = resolvedTranscript,
                    transcriptFilePath = transcriptFilePath,
                    summary = aiResult.executiveSummary,
                    keyTakeaways = aiResult.keyTakeaways,
                    actionItems = aiResult.actionItems,
                    summaryFilePath = summaryFilePath,
                    audioFilePath = audioFile.absolutePath,
                    durationSeconds = durationSeconds,
                    category = category,
                    timestamp = System.currentTimeMillis()
                )

                noteRepository.saveNote(processedNote)

                val completedTask = BackgroundProcessingTask(
                    noteId = noteId,
                    noteTitle = finalTitle,
                    status = if (resolvedTranscript.isNotBlank()) {
                        if (translationFailure == null) {
                            "Transcription & analysis complete"
                        } else {
                            "Transcription complete; translation failed: $translationFailure"
                        }
                    } else {
                        "Recording saved; no transcript was available"
                    },
                    isComplete = true,
                    isFailed = translationFailure != null
                )

                updateTask(completedTask)
                if (translationFailure == null) retryRequests.remove(noteId)
                showNotification(
                    context,
                    noteId,
                    finalTitle,
                    if (translationFailure != null) {
                        "Transcript is ready, but translation failed. Open EchoNotes to retry."
                    } else if (resolvedTranscript.isNotBlank()) {
                        "Transcription and AI summary ready!"
                    } else {
                        "Recording saved, but no transcript was available."
                    }
                )

                _completionEvents.emit(completedTask)
                Log.d(TAG, "Background processing completed successfully for note: $noteId")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Background processing failed for note: $noteId", e)
                val failureMessage = if (isNetworkResolutionFailure(e) && currentPhase == "transcription") {
                    "Cannot reach Google's transcription service. Check internet access and Android Private DNS/VPN settings, then tap Retry."
                } else if (isNetworkResolutionFailure(e)) {
                    "Network error during $currentPhase. Check your connection, then tap Retry."
                } else {
                    "$currentPhase failed: ${e.localizedMessage ?: "Unknown error"}"
                }
                val failedTask = BackgroundProcessingTask(
                    noteId = noteId,
                    noteTitle = initialTitle,
                    status = failureMessage,
                    isComplete = true,
                    isFailed = true
                )
                updateTask(failedTask)
            } finally {
                runningJobs.remove(noteId)
            }
        }
    }

    fun dismissTask(noteId: String) {
        val current = _activeTasks.value.toMutableMap()
        current.remove(noteId)
        _activeTasks.value = current
        retryRequests.remove(noteId)
    }

    fun retryTask(noteId: String): Boolean {
        val request = retryRequests[noteId] ?: return false
        dismissTask(noteId)
        startBackgroundTranscription(
            context = request.context,
            noteId = noteId,
            durationSeconds = request.durationSeconds,
            category = request.category,
            liveTranscript = request.liveTranscript,
            spokenCommands = request.spokenCommands,
            audioFilePath = request.audioFilePath,
            translationLanguage = request.translationLanguage,
            noteRepository = request.noteRepository,
            aiRepository = request.aiRepository,
            fileStorageManager = request.fileStorageManager
        )
        return true
    }

    private suspend fun translateTranscript(
        aiRepository: AiIntelligenceRepository,
        transcript: String,
        targetLanguage: String
    ): String {
        val language = targetLanguage.takeIf { it in setOf("en", "am") }
            ?: throw IllegalArgumentException("Unsupported translation language: $targetLanguage")
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < transcript.length) {
            var end = minOf(start + TRANSLATION_CHUNK_CHARS, transcript.length)
            if (end < transcript.length) {
                val boundary = transcript.lastIndexOf(' ', end)
                if (boundary > start) end = boundary
            }
            chunks += transcript.substring(start, end).trim()
            start = end
            while (start < transcript.length && transcript[start].isWhitespace()) start++
        }
        return chunks.filter(String::isNotBlank)
            .map { chunk -> retryNetworkRequest { aiRepository.translateText(chunk, language).trim() } }
            .filter(String::isNotBlank)
            .joinToString(" ")
    }

    private suspend fun <T> retryNetworkRequest(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!isNetworkResolutionFailure(error) || attempt >= NETWORK_RETRY_DELAYS_MS.size) {
                    throw error
                }
                delay(NETWORK_RETRY_DELAYS_MS[attempt])
                attempt++
            }
        }
    }

    private fun isNetworkResolutionFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current.message?.let { message ->
                    message.contains("resolve host", ignoreCase = true) ||
                        message.contains("no address associated with hostname", ignoreCase = true) ||
                        message.contains("failed to connect", ignoreCase = true) ||
                        message.contains("network is unreachable", ignoreCase = true)
                } == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun updateTask(task: BackgroundProcessingTask) {
        val current = _activeTasks.value.toMutableMap()
        current[task.noteId] = task
        _activeTasks.value = current
    }

    private val NETWORK_RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 12_000, 20_000)
    private const val TRANSLATION_CHUNK_CHARS = 2_500

    private fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transcription & AI Processing",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when background speech-to-text and AI summaries complete"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showNotification(context: Context, noteId: String, title: String, content: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val notificationId = NOTIFICATION_ID_BASE + (noteId.hashCode() and 0x7FFF)
            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post system notification: ${e.message}")
        }
    }
}
