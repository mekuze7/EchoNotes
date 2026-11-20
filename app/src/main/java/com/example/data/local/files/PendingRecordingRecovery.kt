package com.example.data.local.files

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

data class PendingRecording(
    val noteId: String,
    val filePath: String,
    val category: String,
    val startedAtMillis: Long,
    val durationSeconds: Long,
    val isLiveCapture: Boolean
)

class PendingRecordingRecovery(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pending_recording_recovery", Context.MODE_PRIVATE)

    fun markStarted(
        noteId: String,
        file: File,
        category: String,
        isLiveCapture: Boolean
    ) {
        val saved = preferences.edit()
            .putString(KEY_NOTE_ID, noteId)
            .putString(KEY_FILE_PATH, file.absolutePath)
            .putString(KEY_CATEGORY, category)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putLong(KEY_DURATION, 0L)
            .putBoolean(KEY_IS_LIVE_CAPTURE, isLiveCapture)
            .commit()
        if (!saved) throw IllegalStateException("Could not save recording recovery state.")
    }

    fun getPending(): PendingRecording? {
        val noteId = preferences.getString(KEY_NOTE_ID, null) ?: return null
        val filePath = preferences.getString(KEY_FILE_PATH, null) ?: return null
        return PendingRecording(
            noteId = noteId,
            filePath = filePath,
            category = preferences.getString(KEY_CATEGORY, "Meetings") ?: "Meetings",
            startedAtMillis = preferences.getLong(KEY_STARTED_AT, 0L),
            durationSeconds = preferences.getLong(KEY_DURATION, 0L),
            isLiveCapture = preferences.getBoolean(KEY_IS_LIVE_CAPTURE, false)
        )
    }

    fun updateDuration(durationSeconds: Long) {
        preferences.edit().putLong(KEY_DURATION, durationSeconds).apply()
    }

    fun clear() {
        if (!preferences.edit().clear().commit()) {
            throw IllegalStateException("Could not clear recording recovery state.")
        }
    }

    fun repairInterruptedWav(file: File): Boolean {
        if (!file.isFile || file.length() <= WAV_HEADER_SIZE) return false
        val dataSize = file.length() - WAV_HEADER_SIZE
        if (dataSize > Int.MAX_VALUE) return false
        RandomAccessFile(file, "rw").use { output ->
            output.seek(0)
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(dataSize.toInt() + WAV_HEADER_SIZE - 8))
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE * BYTES_PER_SAMPLE))
            output.writeShort(java.lang.Short.reverseBytes(BYTES_PER_SAMPLE.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize.toInt()))
        }
        return true
    }

    private companion object {
        const val KEY_NOTE_ID = "note_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_CATEGORY = "category"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_DURATION = "duration_seconds"
        const val KEY_IS_LIVE_CAPTURE = "is_live_capture"
        const val WAV_HEADER_SIZE = 44
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
    }
}
