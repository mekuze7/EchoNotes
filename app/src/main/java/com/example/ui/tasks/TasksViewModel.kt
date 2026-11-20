package com.example.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(
    private val noteRepository: NoteRepository
) : ViewModel() {

    val notes = noteRepository.getAllNotes().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun toggleActionItem(noteId: String, actionItemId: String, isCompleted: Boolean) {
        viewModelScope.launch {
            noteRepository.updateActionItem(noteId, actionItemId, isCompleted)
        }
    }

    fun setDueDate(noteId: String, actionItemId: String, dueDate: String?) {
        viewModelScope.launch {
            noteRepository.updateActionItemDueDate(noteId, actionItemId, dueDate)
        }
    }
}
