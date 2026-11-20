package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.ChatMessage
import com.example.domain.model.Note
import com.example.domain.repository.AiIntelligenceRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val note: Note? = null,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val suggestions: List<String> = listOf(
        "What were the main points?",
        "What tasks were assigned?",
        "Summarize this in one sentence",
        "What decisions were made?",
        "What deadlines were mentioned?"
    )
)

class ChatWithNoteViewModel(
    private val noteId: String,
    private val noteRepository: NoteRepository,
    private val aiRepository: AiIntelligenceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadNoteAndChat()
    }

    private fun loadNoteAndChat() {
        viewModelScope.launch {
            noteRepository.getNoteById(noteId)
                .catch { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Couldn't load this note.")
                    }
                }
                .collect { note ->
                    _uiState.update {
                        it.copy(
                            note = note,
                            errorMessage = when {
                                note == null -> "This note could not be found."
                                it.errorMessage == "This note could not be found." ||
                                    it.errorMessage == "This note is not available yet. Please try again." -> null
                                else -> it.errorMessage
                            }
                        )
                    }
                }
        }

        viewModelScope.launch {
            noteRepository.getChatMessages(noteId)
                .catch { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Couldn't load the chat history.")
                    }
                }
                .collect { messages -> _uiState.update { it.copy(messages = messages) } }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage(questionText: String? = null) {
        val currentState = _uiState.value
        val query = (questionText ?: currentState.inputText).trim()
        if (query.isBlank() || currentState.isSending) return

        val note = currentState.note
        if (note == null) {
            _uiState.update { it.copy(errorMessage = "This note is not available yet. Please try again.") }
            return
        }
        _uiState.update { it.copy(inputText = "", isSending = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                // Read persisted history before inserting this question so it cannot be sent twice.
                val history = noteRepository.getChatMessages(note.id).first()
                    .map { it.isUser to it.message }

                noteRepository.addChatMessage(note.id, isUser = true, message = query)

                val answer = aiRepository.answerQuestion(
                    transcript = note.transcript,
                    noteTitle = note.title,
                    question = query,
                    chatHistory = history
                )

                noteRepository.addChatMessage(note.id, isUser = false, message = answer)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Couldn't get a reply. Please try again.")
                }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }
}
