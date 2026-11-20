package com.example.ui.tasks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ActionItem
import com.example.domain.model.Note
import com.example.ui.theme.StitchBackground
import com.example.ui.theme.StitchCardOutline
import com.example.ui.theme.StitchCyan
import com.example.ui.theme.StitchPurple
import com.example.ui.theme.StitchSurface
import com.example.ui.theme.StitchTextMuted
import com.example.ui.theme.StitchTextPrimary
import com.example.ui.theme.StitchTextSecondary
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class NoteActionItem(
    val note: Note,
    val actionItem: ActionItem
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToNote: (String) -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val context = LocalContext.current
    var selectedTask by remember { mutableStateOf<NoteActionItem?>(null) }
    val tasks = remember(notes) {
        notes.flatMap { note -> note.actionItems.map { NoteActionItem(note, it) } }
            .sortedWith(
                compareBy<NoteActionItem> { it.actionItem.isCompleted }
                    .thenBy { it.actionItem.dueDate ?: "9999-12-31" }
                    .thenBy { it.note.timestamp }
            )
    }
    val completedCount = tasks.count { it.actionItem.isCompleted }

    Scaffold(
        containerColor = StitchBackground,
        topBar = {
            TopAppBar(
                title = { Text("Action checklist", color = StitchTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = StitchTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StitchBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            Text(
                text = "$completedCount of ${tasks.size} tasks completed",
                color = StitchTextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (tasks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.EventAvailable, contentDescription = null, tint = StitchCyan, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No action items yet", color = StitchTextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("Tasks extracted from your notes will appear here.", color = StitchTextSecondary)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    items(tasks, key = { "${it.note.id}:${it.actionItem.id}" }) { entry ->
                        ActionChecklistCard(
                            entry = entry,
                            onToggle = {
                                viewModel.toggleActionItem(
                                    entry.note.id,
                                    entry.actionItem.id,
                                    !entry.actionItem.isCompleted
                                )
                            },
                            onChooseDueDate = { selectedTask = entry },
                            onOpenNote = { onNavigateToNote(entry.note.id) },
                            onAddToCalendar = {
                                val beginTime = ActionDateUtils.toUtcMillis(entry.actionItem.dueDate)
                                if (beginTime == null) {
                                    Toast.makeText(context, "Choose a valid due date first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    val intent = Intent(Intent.ACTION_INSERT).apply {
                                        data = CalendarContract.Events.CONTENT_URI
                                        putExtra(CalendarContract.Events.TITLE, entry.actionItem.title)
                                        putExtra(
                                            CalendarContract.Events.DESCRIPTION,
                                            "Action item from EchoNotes: ${entry.note.title}"
                                        )
                                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
                                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, beginTime + ActionDateUtils.ONE_DAY_MS)
                                        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                                        putExtra(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(context, "No calendar app is available.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    selectedTask?.let { entry ->
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = ActionDateUtils.toUtcMillis(entry.actionItem.dueDate)
                ?: ActionDateUtils.todayUtcMillis()
        )
        DatePickerDialog(
            onDismissRequest = { selectedTask = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            viewModel.setDueDate(
                                entry.note.id,
                                entry.actionItem.id,
                                ActionDateUtils.fromUtcMillis(millis)
                            )
                        }
                        selectedTask = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (entry.actionItem.dueDate != null) {
                        TextButton(
                            onClick = {
                                viewModel.setDueDate(entry.note.id, entry.actionItem.id, null)
                                selectedTask = null
                            }
                        ) { Text("Remove date") }
                    }
                    TextButton(onClick = { selectedTask = null }) { Text("Cancel") }
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun ActionChecklistCard(
    entry: NoteActionItem,
    onToggle: () -> Unit,
    onChooseDueDate: () -> Unit,
    onOpenNote: () -> Unit,
    onAddToCalendar: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StitchSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StitchCardOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (entry.actionItem.isCompleted) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = if (entry.actionItem.isCompleted) "Mark incomplete" else "Mark complete",
                        tint = if (entry.actionItem.isCompleted) StitchCyan else StitchTextMuted
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.actionItem.title,
                        color = if (entry.actionItem.isCompleted) StitchTextMuted else StitchTextPrimary,
                        textDecoration = if (entry.actionItem.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.note.title,
                        color = StitchTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenNote)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 38.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StitchBackground,
                    modifier = Modifier.clickable(onClick = onChooseDueDate)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = StitchCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = entry.actionItem.dueDate?.let(ActionDateUtils::display) ?: "Set due date",
                            color = StitchCyan,
                            fontSize = 12.sp
                        )
                    }
                }
                if (entry.actionItem.dueDate != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StitchPurple.copy(alpha = 0.14f),
                        modifier = Modifier.clickable(onClick = onAddToCalendar)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Event, contentDescription = null, tint = StitchPurple, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Add to calendar", color = StitchPurple, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private object ActionDateUtils {
    const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    fun toUtcMillis(date: String?): Long? {
        if (date.isNullOrBlank()) return null
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val parsed = format.parse(date, position) ?: return null
        return parsed.time.takeIf { position.index == date.length && format.format(parsed) == date }
    }

    fun fromUtcMillis(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))

    fun display(date: String): String {
        val millis = toUtcMillis(date) ?: return date
        return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))
    }

    fun todayUtcMillis(): Long {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return toUtcMillis(today) ?: System.currentTimeMillis()
    }
}
