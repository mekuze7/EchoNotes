package com.example.data.local.files

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.domain.model.Note
import com.example.domain.model.SubtitleCue
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteExporter {

    private fun getExportDir(context: Context): File {
        val dir = File(context.cacheDir, "exported_notes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFileName(title: String): String {
        val clean = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(30)
        return clean.ifBlank { "EchoNote" }
    }

    fun exportToPdf(context: Context, note: Note): Uri {
        val pdfDocument = PdfDocument()
        val pageWidth = 595 // A4 width in points
        val pageHeight = 842 // A4 height in points
        val marginLeft = 40f
        val marginRight = 555f
        val contentWidth = marginRight - marginLeft
        val marginTop = 40f
        val marginBottom = 802f

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.parseColor("#12141C")
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#64748B")
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val sectionPaint = Paint().apply {
            color = Color.parseColor("#6366F1") // Accent Indigo/Purple
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.parseColor("#E2E8F0")
            strokeWidth = 1f
            isAntiAlias = true
        }

        val bulletPaint = Paint().apply {
            color = Color.parseColor("#6366F1")
            isAntiAlias = true
        }

        var y = marginTop

        fun checkNewPage(neededHeight: Float) {
            if (y + neededHeight > marginBottom) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                y = marginTop
            }
        }

        // --- HEADER ---
        val dateStr = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(note.timestamp))
        canvas.drawText(note.title.ifBlank { "Untitled Note" }, marginLeft, y + 18f, titlePaint)
        y += 26f

        val subText = "Category: ${note.category}   |   Date: $dateStr   |   Duration: ${note.formattedDuration}"
        canvas.drawText(subText, marginLeft, y + 10f, subtitlePaint)
        y += 20f

        canvas.drawLine(marginLeft, y, marginRight, y, linePaint)
        y += 18f

        // Helper to draw wrapped text lines
        fun drawWrappedText(text: String, paint: Paint, lineSpacing: Float = 14f) {
            val words = text.split(Regex("\\s+"))
            var currentLine = ""
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                val measuredWidth = paint.measureText(testLine)
                if (measuredWidth > contentWidth) {
                    checkNewPage(lineSpacing)
                    canvas.drawText(currentLine, marginLeft, y, paint)
                    y += lineSpacing
                    currentLine = word
                } else {
                    currentLine = testLine
                }
            }
            if (currentLine.isNotEmpty()) {
                checkNewPage(lineSpacing)
                canvas.drawText(currentLine, marginLeft, y, paint)
                y += lineSpacing
            }
        }

        // --- EXECUTIVE SUMMARY ---
        if (note.summary.isNotBlank()) {
            checkNewPage(24f)
            canvas.drawText("EXECUTIVE SUMMARY", marginLeft, y, sectionPaint)
            y += 16f
            drawWrappedText(note.summary, bodyPaint)
            y += 12f
        }

        // --- KEY TAKEAWAYS ---
        if (note.keyTakeaways.isNotEmpty()) {
            checkNewPage(24f)
            canvas.drawText("KEY TAKEAWAYS", marginLeft, y, sectionPaint)
            y += 16f
            for (takeaway in note.keyTakeaways) {
                checkNewPage(16f)
                canvas.drawCircle(marginLeft + 4f, y - 3f, 2.5f, bulletPaint)
                val words = takeaway.split(Regex("\\s+"))
                var currentLine = ""
                val itemMarginLeft = marginLeft + 14f
                val itemWidth = marginRight - itemMarginLeft

                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (bodyPaint.measureText(testLine) > itemWidth) {
                        checkNewPage(14f)
                        canvas.drawText(currentLine, itemMarginLeft, y, bodyPaint)
                        y += 14f
                        currentLine = word
                    } else {
                        currentLine = testLine
                    }
                }
                if (currentLine.isNotEmpty()) {
                    checkNewPage(14f)
                    canvas.drawText(currentLine, itemMarginLeft, y, bodyPaint)
                    y += 16f
                }
            }
            y += 8f
        }

        // --- ACTION ITEMS ---
        if (note.actionItems.isNotEmpty()) {
            checkNewPage(24f)
            canvas.drawText("ACTION ITEMS", marginLeft, y, sectionPaint)
            y += 16f
            for (actionItem in note.actionItems) {
                checkNewPage(16f)
                val checkboxStr = if (actionItem.isCompleted) "[X] " else "[  ] "
                canvas.drawText(checkboxStr, marginLeft, y, if (actionItem.isCompleted) subtitlePaint else bodyPaint)
                
                val itemText = actionItem.title
                val words = itemText.split(Regex("\\s+"))
                var currentLine = ""
                val itemMarginLeft = marginLeft + 24f
                val itemWidth = marginRight - itemMarginLeft

                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (bodyPaint.measureText(testLine) > itemWidth) {
                        checkNewPage(14f)
                        canvas.drawText(currentLine, itemMarginLeft, y, bodyPaint)
                        y += 14f
                        currentLine = word
                    } else {
                        currentLine = testLine
                    }
                }
                if (currentLine.isNotEmpty()) {
                    checkNewPage(14f)
                    canvas.drawText(currentLine, itemMarginLeft, y, bodyPaint)
                    y += 16f
                }
            }
            y += 8f
        }

        // --- TRANSCRIPT ---
        if (note.transcript.isNotBlank()) {
            checkNewPage(24f)
            canvas.drawText("FULL TRANSCRIPT", marginLeft, y, sectionPaint)
            y += 16f
            drawWrappedText(note.transcript, bodyPaint)
        }

        pdfDocument.finishPage(page)

        val fileName = "${sanitizeFileName(note.title)}.pdf"
        val file = File(getExportDir(context), fileName)
        FileOutputStream(file).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportToMarkdown(context: Context, note: Note): Uri {
        val dateStr = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(note.timestamp))
        val content = buildString {
            appendLine("# ${note.title}")
            appendLine("**Category:** ${note.category} | **Date:** $dateStr | **Duration:** ${note.formattedDuration}")
            appendLine()
            if (note.summary.isNotBlank()) {
                appendLine("## Executive Summary")
                appendLine(note.summary)
                appendLine()
            }
            if (note.keyTakeaways.isNotEmpty()) {
                appendLine("## Key Takeaways")
                note.keyTakeaways.forEach { appendLine("- $it") }
                appendLine()
            }
            if (note.actionItems.isNotEmpty()) {
                appendLine("## Action Items")
                note.actionItems.forEach {
                    val check = if (it.isCompleted) "[x]" else "[ ]"
                    appendLine("- $check ${it.title}")
                }
                appendLine()
            }
            if (note.transcript.isNotBlank()) {
                appendLine("## Transcript")
                appendLine(note.transcript)
                appendLine()
            }
        }

        val fileName = "${sanitizeFileName(note.title)}.md"
        val file = File(getExportDir(context), fileName)
        file.writeText(content)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportToTxt(context: Context, note: Note): Uri {
        val dateStr = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(note.timestamp))
        val content = buildString {
            appendLine("=== ${note.title.uppercase()} ===")
            appendLine("Category: ${note.category}")
            appendLine("Date: $dateStr")
            appendLine("Duration: ${note.formattedDuration}")
            appendLine("----------------------------------------")
            appendLine()
            if (note.summary.isNotBlank()) {
                appendLine("--- EXECUTIVE SUMMARY ---")
                appendLine(note.summary)
                appendLine()
            }
            if (note.keyTakeaways.isNotEmpty()) {
                appendLine("--- KEY TAKEAWAYS ---")
                note.keyTakeaways.forEach { appendLine("• $it") }
                appendLine()
            }
            if (note.actionItems.isNotEmpty()) {
                appendLine("--- ACTION ITEMS ---")
                note.actionItems.forEach {
                    val status = if (it.isCompleted) "[DONE]" else "[TODO]"
                    appendLine("$status ${it.title}")
                }
                appendLine()
            }
            if (note.transcript.isNotBlank()) {
                appendLine("--- FULL TRANSCRIPT ---")
                appendLine(note.transcript)
                appendLine()
            }
        }

        val fileName = "${sanitizeFileName(note.title)}.txt"
        val file = File(getExportDir(context), fileName)
        file.writeText(content)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportToSrt(context: Context, note: Note, cues: List<SubtitleCue>): Uri {
        return exportSubtitleFile(context, note, "srt", formatSrtContent(cues))
    }

    fun exportToVtt(context: Context, note: Note, cues: List<SubtitleCue>): Uri {
        return exportSubtitleFile(context, note, "vtt", formatVttContent(cues))
    }

    internal fun formatSrtContent(cues: List<SubtitleCue>): String =
        cues.mapIndexed { index, cue ->
            "${index + 1}\n${formatSubtitleTime(cue.startMs, ',')} --> " +
                "${formatSubtitleTime(cue.endMs, ',')}\n${formatSubtitleText(cue)}\n"
        }.joinToString("\n")

    internal fun formatVttContent(cues: List<SubtitleCue>): String =
        buildString {
            appendLine("WEBVTT")
            appendLine()
            cues.forEach { cue ->
                appendLine("${formatSubtitleTime(cue.startMs, '.')} --> ${formatSubtitleTime(cue.endMs, '.')}")
                appendLine(formatSubtitleText(cue))
                appendLine()
            }
        }

    private fun exportSubtitleFile(context: Context, note: Note, extension: String, content: String): Uri {
        val file = File(getExportDir(context), "${sanitizeFileName(note.title)}.$extension")
        file.writeText(content)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun formatSubtitleTime(timeMs: Long, fractionSeparator: Char): String {
        val safeTimeMs = timeMs.coerceAtLeast(0L)
        val hours = safeTimeMs / 3_600_000
        val minutes = (safeTimeMs % 3_600_000) / 60_000
        val seconds = (safeTimeMs % 60_000) / 1_000
        val milliseconds = safeTimeMs % 1_000
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d%c%03d",
            hours,
            minutes,
            seconds,
            fractionSeparator,
            milliseconds
        )
    }

    private fun formatSubtitleText(cue: SubtitleCue): String {
        val speakerPrefix = cue.speakerLabel.takeIf(String::isNotBlank)?.let { "$it: " }.orEmpty()
        val text = "$speakerPrefix${cue.text}"
        return if (cue.translatedText.isBlank()) text else "$text\n${cue.translatedText}"
    }

    fun getAudioUri(context: Context, note: Note): Uri? {
        if (note.audioFilePath.isBlank()) return null
        val file = File(note.audioFilePath)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun audioMimeType(note: Note): String = when (File(note.audioFilePath).extension.lowercase(Locale.ROOT)) {
        "aif", "aiff" -> "audio/aiff"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mp3"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    fun shareFile(context: Context, uri: Uri, mimeType: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    fun shareText(context: Context, note: Note) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            putExtra(Intent.EXTRA_TEXT, "${note.title}\n\nExecutive Summary:\n${note.summary}\n\nTranscript:\n${note.transcript}")
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Note via Text"))
    }
}
