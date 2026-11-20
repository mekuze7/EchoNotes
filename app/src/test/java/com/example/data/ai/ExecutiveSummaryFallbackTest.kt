package com.example.data.ai

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveSummaryFallbackTest {
    @Test
    fun fallbackSummaryIsClearlySeparateFromTranscript() {
        val transcript = "We discussed the launch plan. Maya will send the updated schedule tomorrow."

        val summary = ExecutiveSummaryFallback.summarize(transcript)

        assertNotEquals(transcript, summary)
        assertTrue(summary.startsWith("Key points from the recording:"))
        assertTrue(summary.contains("We discussed the launch plan."))
    }

    @Test
    fun duplicateDetectionRecognizesTranscriptCopy() {
        val transcript = "The team aligned on launch timing and assigned owners for testing."

        assertTrue(ExecutiveSummaryFallback.isLikelyDuplicate(transcript, transcript))
        assertTrue(ExecutiveSummaryFallback.isLikelyDuplicate("Key points from the recording: ${transcript}", transcript))
        assertTrue(ExecutiveSummaryFallback.isLikelyDuplicate("The team aligned on launch timing and assigned owners for testing.", transcript))
    }

    @Test
    fun fallbackSummaryIsConciseForLongTranscripts() {
        val transcript = (1..20).joinToString(" ") { "The team discussed project update number $it." }

        val summary = ExecutiveSummaryFallback.summarize(transcript)

        assertTrue(summary.length <= 310)
    }
}
