package com.example.data.local.files

import com.example.domain.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteExporterSubtitleTest {

    @Test
    fun formatSrtContentUsesCommaMillisecondSeparator() {
        val content = NoteExporter.formatSrtContent(
            listOf(
                SubtitleCue(startMs = 3_661_234L, endMs = 3_662_000L, text = "hello")
            )
        )

        assertEquals(
            "1\n01:01:01,234 --> 01:01:02,000\nhello\n",
            content
        )
    }

    @Test
    fun formatVttContentIncludesHeaderAndDotMillisecondSeparator() {
        val content = NoteExporter.formatVttContent(
            listOf(
                SubtitleCue(startMs = 1_234L, endMs = 2_500L, text = "hello")
            )
        )

        assertEquals(
            "WEBVTT\n\n00:00:01.234 --> 00:00:02.500\nhello\n\n",
            content
        )
    }

    @Test
    fun formatSrtContentIncludesTranslationWhenAvailable() {
        val content = NoteExporter.formatSrtContent(
            listOf(
                SubtitleCue(
                    startMs = 0L,
                    endMs = 1_000L,
                    text = "Hello",
                    translatedText = "ሰላም"
                )
            )
        )

        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nHello\nሰላም\n", content)
    }

    @Test
    fun formatSrtContentIncludesSpeakerLabel() {
        val content = NoteExporter.formatSrtContent(
            listOf(
                SubtitleCue(
                    startMs = 0L,
                    endMs = 1_000L,
                    text = "Hello",
                    speakerLabel = "Speaker 1"
                )
            )
        )

        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nSpeaker 1: Hello\n", content)
    }
}
