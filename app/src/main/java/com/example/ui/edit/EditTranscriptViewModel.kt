package com.example.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.Note
import com.example.domain.repository.AiIntelligenceRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EditTranscriptUiState(
    val note: Note? = null,
    val title: String = "",
    val transcript: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null
)

class EditTranscriptViewModel(
    private val noteId: String,
    private val noteRepository: NoteRepository,
    private val aiRepository: AiIntelligenceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditTranscriptUiState())
    val uiState: StateFlow<EditTranscriptUiState> = _uiState.asStateFlow()

    init {
        loadNote()
    }

    private fun loadNote() {
        viewModelScope.launch {
            val note = noteRepository.getNoteByIdSync(noteId)
            if (note != null) {
                _uiState.value = EditTranscriptUiState(
                    note = note,
                    title = note.title,
                    transcript = note.transcript,
                    isLoading = false
                )
            }
        }
    }

    fun onTitleChanged(newTitle: String) {
        _uiState.value = _uiState.value.copy(title = newTitle)
    }

    fun onTranscriptChanged(newTranscript: String) {
        _uiState.value = _uiState.value.copy(transcript = newTranscript)
    }

    fun saveChanges(regenerateAi: Boolean = true, onComplete: () -> Unit) {
        val currentNote = _uiState.value.note ?: return
        if (_uiState.value.isSaving) return
        val newTitle = _uiState.value.title.trim().ifEmpty { currentNote.title }
        val newTranscript = _uiState.value.transcript.trim()

        val transcriptChanged = newTranscript != currentNote.transcript
        _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)

        viewModelScope.launch {
            var transcriptSaved = false
            try {
                val editedNote = currentNote.copy(title = newTitle, transcript = newTranscript)
                noteRepository.saveNote(editedNote)
                transcriptSaved = true
                if (transcriptChanged) {
                    noteRepository.clearSubtitleCues(noteId)
                }
                if (regenerateAi && transcriptChanged) {
                    try {
                        val aiResult = aiRepository.generateAnalysis(newTranscript, newTitle)
                        noteRepository.saveNote(
                            editedNote.copy(
                                summary = aiResult.executiveSummary,
                                keyTakeaways = aiResult.keyTakeaways,
                                actionItems = aiResult.actionItems
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.value = _uiState.value.copy(
                            note = editedNote,
                            isSaving = false,
                            errorMessage = "Transcript saved, but AI analysis could not be refreshed: " +
                                (e.localizedMessage ?: "Check your connection and API settings.")
                        )
                        return@launch
                    }
                }
                _uiState.value = _uiState.value.copy(
                    note = editedNote,
                    isSaving = false,
                    isSaved = true
                )
                onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = if (transcriptSaved) {
                        "Transcript saved, but timed captions could not be updated: " +
                            (e.localizedMessage ?: "Please try again.")
                    } else {
                        "Could not save your changes: ${e.localizedMessage ?: "Please try again."}"
                    }
                )
            }
        }
    }
}
