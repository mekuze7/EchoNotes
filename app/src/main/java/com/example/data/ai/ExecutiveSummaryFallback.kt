package com.example.data.ai

internal object ExecutiveSummaryFallback {
    private const val MAX_SUMMARY_LENGTH = 280

    fun summarize(transcript: String): String {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isBlank()) return ""

        val sentences = cleanTranscript
            .split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        val selected = sentences.take(2).joinToString(" ")
        val concise = if (selected.length > MAX_SUMMARY_LENGTH) {
            selected.take(MAX_SUMMARY_LENGTH - 3).substringBeforeLast(' ') + "..."
        } else {
            selected
        }

        return "Key points from the recording: $concise"
    }

    fun isLikelyDuplicate(summary: String, transcript: String): Boolean {
        val cleanSummary = normalizeForComparison(summary)
        val cleanTranscript = normalizeForComparison(transcript)

        if (cleanSummary.isEmpty() || cleanTranscript.isEmpty()) return false
        if (cleanSummary == cleanTranscript) return true

        val plainSummary = cleanSummary.removePrefix("key points from the recording:")
        if (plainSummary != cleanSummary && normalizeForComparison(plainSummary) == cleanTranscript) {
            return true
        }

        val summaryWords = cleanSummary.split(Regex("\\s+")).filter { it.isNotBlank() }
        val transcriptWords = cleanTranscript.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (summaryWords.size < 8 || transcriptWords.size < 8) return false

        val transcriptWordSet = transcriptWords.toSet()
        val overlap = summaryWords.count { transcriptWordSet.contains(it) }
        return overlap.toDouble() / transcriptWords.size > 0.9
    }

    private fun normalizeForComparison(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
