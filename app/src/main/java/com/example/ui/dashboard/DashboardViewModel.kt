package com.example.ui.dashboard

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.files.FileStorageManager
import com.example.data.local.preferences.UserPreferencesManager
import com.example.data.speech.BackgroundTranscriptionManager
import com.example.domain.model.ActionItem
import com.example.domain.model.Note
import com.example.domain.repository.AiIntelligenceRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class DashboardUiState(
    val notes: List<Note> = emptyList(),
    val selectedCategory: String = "All",
    val searchQuery: String = "",
    val isLoading: Boolean = false
)

data class AudioImportUiState(
    val isImporting: Boolean = false,
    val message: String = "",
    val isError: Boolean = false
)

class DashboardViewModel(
    private val context: Context,
    private val repository: NoteRepository,
    private val aiRepository: AiIntelligenceRepository
) : ViewModel() {

    private val prefs = context.getSharedPreferences("echonotes_prefs", Context.MODE_PRIVATE)
    private val fileStorageManager = FileStorageManager(context)
    private val _audioImportState = MutableStateFlow(AudioImportUiState())
    val audioImportState: StateFlow<AudioImportUiState> = _audioImportState.asStateFlow()

    private data class ImportedAudio(
        val file: File,
        val title: String,
        val durationSeconds: Long
    )

    private companion object {
        const val MAX_IMPORT_BYTES = 2L * 1024L * 1024L * 1024L
        val AUDIO_EXTENSIONS = setOf("aac", "aif", "aiff", "flac", "m4a", "mp3", "mp4", "ogg", "wav", "webm")
        val EXTENSION_BY_MIME_TYPE = mapOf(
            "audio/aac" to "aac",
            "audio/aiff" to "aiff",
            "audio/x-aiff" to "aiff",
            "audio/flac" to "flac",
            "audio/mp3" to "mp3",
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/ogg" to "ogg",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/x-flac" to "flac",
            "audio/webm" to "webm"
        )
    }

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val categories = listOf("All", "Meetings", "Ideas", "Tasks", "Personal")

    init {
        seedInitialStitchNotesIfEmpty()
    }

    private fun seedInitialStitchNotesIfEmpty() {
        val alreadySeeded = prefs.getBoolean("has_seeded_initial_notes", false)
        if (alreadySeeded) {
            return
        }

        viewModelScope.launch {
            if (repository.hasAnyNotes()) {
                prefs.edit().putBoolean("has_seeded_initial_notes", true).apply()
                return@launch
            }

            val note1 = Note(
                id = "stitch_llm_demo",
                title = "What Is An LLM?",
                transcript = "When you ask artificial intelligence, like ChatGPT, a question, like, \"What is the best way to slice bread?\", it provides an answer that seems like a human expert. It works because powerful computers are trained to look for common patterns between words and phrases across trillions of tokens.",
                summary = "When you ask artificial intelligence, like ChatGPT, a question, like, \"What is the best way to slice bread?\", it provides an answer that seems like a human expert. The AI appears to know about knife varieties, ergonomics, and culinary physics, not from real-world senses, but through high-dimensional token probabilities synthesized from trillion-word datasets.",
                keyTakeaways = listOf(
                    "When prompting artificial intelligence like ChatGPT with everyday queries (e.g., \"How to slice bread\"), output reads as authoritative human counsel.",
                    "LLMs do not possess physical perception of knives or heat; knowledge exists strictly as statistical relationships between tokens.",
                    "Contextual disambiguation (e.g., baseball bat vs. flying nocturnal mammal) is resolved mathematically via co-occurring phrases.",
                    "Responses inherit biases and misinformation from web crawl data; disciplined verification is necessary for technical use."
                ),
                actionItems = listOf(
                    ActionItem(id = "task_1", title = "Review recorded note points", isCompleted = true),
                    ActionItem(id = "task_2", title = "Follow up on discussed items", isCompleted = false),
                    ActionItem(id = "task_3", title = "Share synthesized summary with product team", isCompleted = false)
                ),
                durationSeconds = 158,
                category = "Ideas",
                timestamp = System.currentTimeMillis() - 3600000
            )

            val note2 = Note(
                id = "stitch_sprint_demo",
                title = "Sprint Retro & Q4 Roadmap",
                transcript = "Key takeaways from Sarah and Dev lead: The latency for audio chunk transcription dropped from 1.2s to 340ms after moving to the streaming pipeline. We agreed to prioritize the native sync engine before v2 launch.",
                summary = "Key takeaways from Sarah and Dev lead: The latency for audio chunk transcription dropped from 1.2s to 340ms after moving to the streaming pipeline. Team confirmed roadmap milestones.",
                keyTakeaways = listOf(
                    "Latency for audio chunk transcription dropped from 1.2s to 340ms after pipeline optimization.",
                    "Prioritize the native sync engine before v2 launch.",
                    "Conduct multi-device QA testing on Android 14 and edge emulators."
                ),
                actionItems = listOf(
                    ActionItem(id = "task_4", title = "Deploy streaming audio analyzer", isCompleted = true),
                    ActionItem(id = "task_5", title = "Conduct QA testing on edge devices", isCompleted = false),
                    ActionItem(id = "task_6", title = "Finalize v2 launch milestones", isCompleted = false),
                    ActionItem(id = "task_7", title = "Publish sprint retro summary", isCompleted = false)
                ),
                durationSeconds = 878,
                category = "Meetings",
                timestamp = System.currentTimeMillis() - 86400000
            )

            repository.saveNote(note1)
            repository.saveNote(note2)
            prefs.edit().putBoolean("has_seeded_initial_notes", true).apply()
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.getAllNotes(),
        _selectedCategory,
        _searchQuery
    ) { notes, category, query ->
        val filteredByCategory = if (category == "All") {
            notes
        } else {
            notes.filter { it.category.equals(category, ignoreCase = true) }
        }

        val filteredByQuery = if (query.isBlank()) {
            filteredByCategory
        } else {
            filteredByCategory.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.category.contains(query, ignoreCase = true) ||
                        it.transcript.contains(query, ignoreCase = true) ||
                        it.summary.contains(query, ignoreCase = true) ||
                        it.keyTakeaways.any { takeaway -> takeaway.contains(query, ignoreCase = true) } ||
                        it.actionItems.any { action -> action.title.contains(query, ignoreCase = true) }
            }
        }

        DashboardUiState(
            notes = filteredByQuery,
            selectedCategory = category,
            searchQuery = query,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(isLoading = true)
    )

    fun onCategorySelected(category: String) {
        _selectedCategory.value = category
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun importAudio(uri: Uri) {
        if (_audioImportState.value.isImporting) return
        if (!aiRepository.isKeyConfigured()) {
            _audioImportState.value = AudioImportUiState(
                message = "Add a Gemini API key in Settings before importing audio for transcription.",
                isError = true
            )
            return
        }

        _audioImportState.value = AudioImportUiState(
            isImporting = true,
            message = "Importing audio..."
        )
        viewModelScope.launch {
            var importedFile: File? = null
            try {
                val importedAudio = withContext(Dispatchers.IO) {
                    copySelectedAudio(uri, UUID.randomUUID().toString())
                }
                importedFile = importedAudio.file
                val noteId = importedAudio.file.nameWithoutExtension
                val category = "Meetings"
                val note = Note(
                    id = noteId,
                    title = importedAudio.title,
                    transcript = "",
                    audioFilePath = importedAudio.file.absolutePath,
                    durationSeconds = importedAudio.durationSeconds,
                    category = category,
                    timestamp = System.currentTimeMillis()
                )
                repository.saveNote(note)
                BackgroundTranscriptionManager.startBackgroundTranscription(
                    context = context,
                    noteId = noteId,
                    durationSeconds = importedAudio.durationSeconds,
                    category = category,
                    liveTranscript = "",
                    audioFilePath = importedAudio.file.absolutePath,
                    translationLanguage = UserPreferencesManager.getInstance(context).getTranslationLanguage(),
                    noteRepository = repository,
                    aiRepository = aiRepository,
                    fileStorageManager = fileStorageManager
                )
                _audioImportState.value = AudioImportUiState(
                    message = "Audio added. Transcription and summary are processing."
                )
            } catch (error: CancellationException) {
                importedFile?.delete()
                throw error
            } catch (error: Exception) {
                importedFile?.delete()
                _audioImportState.value = AudioImportUiState(
                    message = error.localizedMessage ?: "Could not import this audio file.",
                    isError = true
                )
            }
        }
    }

    fun dismissAudioImportMessage() {
        if (!_audioImportState.value.isImporting) {
            _audioImportState.value = AudioImportUiState()
        }
    }

    private fun copySelectedAudio(uri: Uri, noteId: String): ImportedAudio {
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) cursor.getString(nameColumn) else null
            } else {
                null
            }
        }?.takeIf(String::isNotBlank) ?: "Imported voice note"
        val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        val nameExtension = displayName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in AUDIO_EXTENSIONS }
        val extension = nameExtension
            ?: mimeType?.let(EXTENSION_BY_MIME_TYPE::get)
            ?: throw IOException("Unsupported audio format. Choose MP3, M4A, WAV, AAC, OGG, FLAC, AIFF, or WebM.")
        val targetFile = fileStorageManager.getImportedAudioFile(noteId, extension)

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open the selected audio file.")
            input.use { source ->
                targetFile.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (totalBytes > MAX_IMPORT_BYTES) {
                            throw IOException("Gemini supports audio files up to 2 GB. Choose a smaller file.")
                        }
                        destination.write(buffer, 0, read)
                    }
                    if (totalBytes == 0L) throw IOException("The selected audio file is empty.")
                }
            }
            val durationSeconds = mediaDurationSeconds(targetFile)
            val title = displayName.substringBeforeLast('.', displayName)
                .trim()
                .ifBlank { "Imported voice note" }
                .take(80)
            return ImportedAudio(targetFile, title, durationSeconds)
        } catch (error: Exception) {
            targetFile.delete()
            throw error
        }
    }

    private fun mediaDurationSeconds(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { (it + 999L) / 1_000L }
                ?.takeIf { it > 0L }
                ?: 0L
        } catch (_: RuntimeException) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: RuntimeException) {
            }
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
        }
    }
}
