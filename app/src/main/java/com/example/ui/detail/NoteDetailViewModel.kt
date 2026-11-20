package com.example.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.AudioPlayerManager
import com.example.data.audio.AudioPlayerState
import com.example.data.local.files.FileStorageManager
import com.example.data.local.preferences.UserPreferencesManager
import com.example.domain.model.Note
import com.example.domain.model.SubtitleCue
import com.example.domain.repository.AiIntelligenceRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class NoteDetailUiState(
    val note: Note? = null,
    val isLoading: Boolean = true,
    val isRegeneratingAi: Boolean = false,
    val transcriptEditError: String? = null,
    val playerState: AudioPlayerState = AudioPlayerState(),
    val subtitleCues: List<SubtitleCue> = emptyList(),
    val translatedTranscript: String = "",
    val subtitleTextSizeSp: Float = 22f,
    val subtitlePosition: String = "bottom"
)

class NoteDetailViewModel(
    private val noteId: String,
    private val context: Context,
    private val noteRepository: NoteRepository,
    private val aiRepository: AiIntelligenceRepository
) : ViewModel() {

    private val audioPlayerManager = AudioPlayerManager(context)
    private val fileStorageManager = FileStorageManager(context)
    private val preferences = UserPreferencesManager.getInstance(context)

    private val _uiState = MutableStateFlow(NoteDetailUiState())
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(
            subtitleTextSizeSp = preferences.getSubtitleTextSizeSp(),
            subtitlePosition = preferences.getSubtitlePosition()
        )
        loadNote()
        observePlayerState()
    }

    private fun loadNote() {
        viewModelScope.launch {
            noteRepository.getNoteById(noteId).collect { note ->
                _uiState.value = _uiState.value.copy(
                    note = note,
                    isLoading = false,
                    subtitleCues = fileStorageManager.readSubtitles(noteId),
                    translatedTranscript = fileStorageManager.readTranslation(noteId)
                )
                if (note != null && note.audioFilePath.isNotEmpty()) {
                    val file = File(note.audioFilePath)
                    if (file.exists() && _uiState.value.playerState.currentFilePath != file.absolutePath) {
                        audioPlayerManager.loadAudio(file)
                    }
                }
            }
        }
    }

    private fun observePlayerState() {
        viewModelScope.launch {
            audioPlayerManager.playerState.collect { playerState ->
                _uiState.value = _uiState.value.copy(playerState = playerState)
            }
        }
    }

    fun togglePlayPause() {
        val currentNote = _uiState.value.note
        if (currentNote != null && currentNote.audioFilePath.isNotEmpty()) {
            val file = File(currentNote.audioFilePath)
            if (file.exists() && _uiState.value.playerState.currentFilePath != file.absolutePath) {
                audioPlayerManager.loadAudio(file)
            }
        }
        audioPlayerManager.togglePlayPause()
    }

    fun seekTo(progress: Float) {
        val duration = _uiState.value.playerState.durationMs
        if (duration > 0) {
            val targetMs = (progress * duration).toLong()
            audioPlayerManager.seekTo(targetMs)
        }
    }

    fun seekToSubtitle(cue: SubtitleCue) {
        audioPlayerManager.seekTo(cue.startMs)
    }

    fun playFromSubtitle(cue: SubtitleCue) {
        audioPlayerManager.seekTo(cue.startMs)
        audioPlayerManager.play()
    }

    fun updateSubtitleCue(index: Int, text: String, speakerLabel: String) {
        if (_uiState.value.isRegeneratingAi) return
        val currentNote = _uiState.value.note ?: return
        val cleanedText = text.trim()
        if (cleanedText.isBlank()) {
            _uiState.value = _uiState.value.copy(
                transcriptEditError = "Caption text cannot be empty."
            )
            return
        }
        val cues = _uiState.value.subtitleCues.toMutableList()
        if (index !in cues.indices) return
        val speaker = speakerLabel.trim()
        cues[index] = cues[index].copy(
            text = cleanedText,
            translatedText = if (cleanedText != cues[index].text) "" else cues[index].translatedText,
            speakerLabel = speaker
        )
        val updatedTranscript = cues.sortedBy(SubtitleCue::startMs)
            .joinToString(" ") { cue ->
                cue.speakerLabel.takeIf(String::isNotBlank)?.let { "$it: " }.orEmpty() + cue.text
            }
        _uiState.value = _uiState.value.copy(
            subtitleCues = cues,
            isRegeneratingAi = true,
            transcriptEditError = null
        )

        viewModelScope.launch {
            var transcriptSaved = false
            try {
                withContext(Dispatchers.IO) {
                    fileStorageManager.saveSubtitles(noteId, cues)
                }
                noteRepository.updateNote(currentNote.copy(transcript = updatedTranscript))
                transcriptSaved = true
                val analysis = aiRepository.generateAnalysis(updatedTranscript, currentNote.title)
                noteRepository.updateNote(
                    currentNote.copy(
                        transcript = updatedTranscript,
                        summary = analysis.executiveSummary,
                        keyTakeaways = analysis.keyTakeaways,
                        actionItems = analysis.actionItems
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    transcriptEditError = if (transcriptSaved) {
                        "Caption saved, but note analysis could not be refreshed: " +
                            (e.localizedMessage ?: "Check your connection and API settings.")
                    } else {
                        "Could not save the caption: ${e.localizedMessage ?: "Please try again."}"
                    }
                )
            } finally {
                _uiState.value = _uiState.value.copy(isRegeneratingAi = false)
            }
        }
    }

    fun setSubtitleTextSize(sizeSp: Float) {
        val updatedSize = sizeSp.coerceIn(16f, 36f)
        preferences.setSubtitleTextSizeSp(updatedSize)
        _uiState.value = _uiState.value.copy(subtitleTextSizeSp = updatedSize)
    }

    fun toggleSubtitlePosition() {
        val position = if (_uiState.value.subtitlePosition == "top") "bottom" else "top"
        preferences.setSubtitlePosition(position)
        _uiState.value = _uiState.value.copy(subtitlePosition = position)
    }

    fun toggleActionItem(actionItemId: String) {
        val currentNote = _uiState.value.note ?: return
        val item = currentNote.actionItems.find { it.id == actionItemId } ?: return
        val newStatus = !item.isCompleted

        viewModelScope.launch {
            noteRepository.updateActionItem(currentNote.id, actionItemId, newStatus)
        }
    }

    fun regenerateAiAnalysis() {
        val currentNote = _uiState.value.note ?: return
        if (_uiState.value.isRegeneratingAi) return
        _uiState.value = _uiState.value.copy(isRegeneratingAi = true)

        viewModelScope.launch {
            try {
                val result = aiRepository.generateAnalysis(currentNote.transcript, currentNote.title)
                val updatedNote = currentNote.copy(
                    summary = result.executiveSummary,
                    keyTakeaways = result.keyTakeaways,
                    actionItems = result.actionItems
                )
                noteRepository.saveNote(updatedNote)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    transcriptEditError = "AI analysis could not be refreshed: " +
                        (e.localizedMessage ?: "Check your connection and API settings.")
                )
            } finally {
                _uiState.value = _uiState.value.copy(isRegeneratingAi = false)
            }
        }
    }

    fun deleteNote(onDeleted: () -> Unit) {
        viewModelScope.launch {
            audioPlayerManager.release()
            noteRepository.deleteNote(noteId)
            onDeleted()
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayerManager.release()
    }
}
