package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.files.FileStorageManager
import com.example.data.local.files.PendingRecordingRecovery
import com.example.data.repository.NoteRepositoryImpl
import com.example.domain.model.ActionItem
import com.example.domain.model.Note
import com.example.domain.model.SubtitleCue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `verify app name string resource`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("EchoNotes", appName)
    }

    @Test
    fun `file storage manager saves and reads transcript file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileStorage = FileStorageManager(context)
        val noteId = "test_note_123"
        val sampleTranscript = "Today we agreed to launch the new feature on Friday."

        val path = fileStorage.saveTranscript(noteId, sampleTranscript)
        assertTrue(path.endsWith("${noteId}_transcript.txt"))

        val readBack = fileStorage.readTranscript(noteId)
        assertEquals(sampleTranscript, readBack)
    }

    @Test
    fun `file storage manager preserves translated subtitle cues`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileStorage = FileStorageManager(context)
        val noteId = "translated_subtitle_test"
        val cue = SubtitleCue(
            startMs = 1_000L,
            endMs = 2_500L,
            text = "Good morning",
            translatedText = "እንደምን አደርክ"
        )

        fileStorage.saveSubtitles(noteId, listOf(cue))

        assertEquals(listOf(cue), fileStorage.readSubtitles(noteId))
    }

    @Test
    fun `file storage manager preserves speaker labels on subtitle cues`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileStorage = FileStorageManager(context)
        val noteId = "speaker_subtitle_test"
        val cue = SubtitleCue(
            startMs = 1_000L,
            endMs = 2_500L,
            text = "Good morning",
            speakerLabel = "Speaker 1"
        )

        fileStorage.saveSubtitles(noteId, listOf(cue))

        assertEquals(listOf(cue), fileStorage.readSubtitles(noteId))
    }

    @Test
    fun `interrupted live recording can be recovered with a valid wav header`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recovery = PendingRecordingRecovery(context)
        recovery.clear()
        val file = File(context.cacheDir, "interrupted_recording_test.wav")
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(44L + 3_200L)
            output.seek(0)
            output.writeBytes("RIFF")
            output.writeInt(0)
            output.writeBytes("WAVE")
        }
        recovery.markStarted("interrupted_test", file, "Meetings", isLiveCapture = true)

        assertEquals("interrupted_test", recovery.getPending()?.noteId)
        assertTrue(recovery.repairInterruptedWav(file))
        RandomAccessFile(file, "r").use { input ->
            input.seek(4)
            val riffSize = Integer.reverseBytes(input.readInt())
            input.seek(40)
            val dataSize = Integer.reverseBytes(input.readInt())
            assertEquals(3_236, riffSize)
            assertEquals(3_200, dataSize)
        }
        recovery.clear()
        assertEquals(null, recovery.getPending())
        file.delete()
    }

    @Test
    fun `note repository persists and retrieves note reactive flow`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = NoteRepositoryImpl(context)

        val note = Note(
            id = "test_repo_note",
            title = "Sprint Planning Meeting",
            transcript = "Team discussed backlog items and assigned tasks.",
            summary = "Sprint planning completed.",
            keyTakeaways = listOf("Focus on core features", "Deliver by Friday"),
            actionItems = listOf(
                ActionItem(id = "task_1", title = "Write unit tests", isCompleted = false)
            ),
            durationSeconds = 60,
            category = "Meetings"
        )

        repository.saveNote(note)

        val retrieved = repository.getNoteByIdSync("test_repo_note")
        assertNotNull(retrieved)
        assertEquals("Sprint Planning Meeting", retrieved?.title)
        assertEquals(1, retrieved?.actionItems?.size)
        assertEquals("Write unit tests", retrieved?.actionItems?.first()?.title)
    }
}
