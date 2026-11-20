package com.example.domain.model

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translatedText: String = "",
    val speakerLabel: String = ""
)

data class SpeechTranscriptionResult(
    val transcript: String
)
