package com.example.data.local.files

import android.content.Context
import com.example.domain.model.SubtitleCue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FileStorageManager(private val context: Context) {

    private val audioDir: File
        get() = File(context.filesDir, "audio").apply { if (!exists()) mkdirs() }

    private val transcriptDir: File
        get() = File(context.filesDir, "transcripts").apply { if (!exists()) mkdirs() }

    private val summaryDir: File
        get() = File(context.filesDir, "summaries").apply { if (!exists()) mkdirs() }

    fun getAudioFile(noteId: String): File {
        return File(audioDir, "${noteId}.m4a")
    }

    fun getLiveAudioFile(noteId: String): File {
        return File(audioDir, "${noteId}.wav")
    }

    fun getImportedAudioFile(noteId: String, extension: String): File {
        require(extension.matches(Regex("[a-z0-9]+"))) { "Unsupported audio file extension." }
        return File(audioDir, "$noteId.$extension")
    }

    fun getLiveTranscriptionChunkDirectory(noteId: String): File {
        return File(audioDir, "${noteId}_chunks")
    }

    fun getSubtitleFile(noteId: String): File {
        return File(transcriptDir, "${noteId}_subtitles.json")
    }

    fun saveSubtitles(noteId: String, cues: List<SubtitleCue>) {
        val content = JSONArray().apply {
            cues.forEach { cue ->
                put(
                    JSONObject()
                        .put("startMs", cue.startMs)
                        .put("endMs", cue.endMs)
                        .put("text", cue.text)
                        .put("translatedText", cue.translatedText)
                        .put("speakerLabel", cue.speakerLabel)
                )
            }
        }
        getSubtitleFile(noteId).writeText(content.toString())
    }

    fun readSubtitles(noteId: String): List<SubtitleCue> {
        val file = getSubtitleFile(noteId)
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONArray(file.readText())
            (0 until json.length()).mapNotNull { index ->
                val cue = json.optJSONObject(index) ?: return@mapNotNull null
                val startMs = cue.optLong("startMs", -1L)
                val endMs = cue.optLong("endMs", -1L)
                val text = cue.optString("text").trim()
                if (startMs >= 0L && endMs > startMs && text.isNotBlank()) {
                    SubtitleCue(
                        startMs,
                        endMs,
                        text,
                        cue.optString("translatedText").trim(),
                        cue.optString("speakerLabel").trim()
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Could not read saved subtitles for note $noteId", e)
        }
    }

    fun getTranscriptFile(noteId: String): File {
        return File(transcriptDir, "${noteId}_transcript.txt")
    }

    fun getTranslationFile(noteId: String): File {
        return File(transcriptDir, "${noteId}_translation.txt")
    }

    fun getSummaryFile(noteId: String): File {
        return File(summaryDir, "${noteId}_summary.md")
    }

    fun saveTranscript(noteId: String, transcriptText: String): String {
        val file = getTranscriptFile(noteId)
        file.writeText(transcriptText)
        return file.absolutePath
    }

    fun readTranscript(noteId: String): String {
        val file = getTranscriptFile(noteId)
        return if (file.exists()) file.readText() else ""
    }

    fun saveTranslation(noteId: String, translatedText: String): String {
        val file = getTranslationFile(noteId)
        file.writeText(translatedText)
        return file.absolutePath
    }

    fun readTranslation(noteId: String): String {
        val file = getTranslationFile(noteId)
        return if (file.exists()) file.readText() else ""
    }

    fun saveSummary(noteId: String, title: String, summaryText: String, takeaways: List<String>, actionItems: List<String>): String {
        val file = getSummaryFile(noteId)
        val content = buildString {
            appendLine("# $title")
            appendLine()
            appendLine("## Executive Summary")
            appendLine(summaryText)
            appendLine()
            if (takeaways.isNotEmpty()) {
                appendLine("## Key Takeaways")
                takeaways.forEach { appendLine("- $it") }
                appendLine()
            }
            if (actionItems.isNotEmpty()) {
                appendLine("## Action Items & Next Steps")
                actionItems.forEach { appendLine("- [ ] $it") }
                appendLine()
            }
        }
        file.writeText(content)
        return file.absolutePath
    }

    fun readSummary(noteId: String): String {
        val file = getSummaryFile(noteId)
        return if (file.exists()) file.readText() else ""
    }

    fun deleteNoteFiles(noteId: String) {
        audioDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$noteId.") }
            ?.forEach(File::delete)
        getLiveTranscriptionChunkDirectory(noteId).listFiles()?.forEach(File::delete)
        getLiveTranscriptionChunkDirectory(noteId).delete()
        getTranscriptFile(noteId).delete()
        getTranslationFile(noteId).delete()
        getSubtitleFile(noteId).delete()
        getSummaryFile(noteId).delete()
    }
}
