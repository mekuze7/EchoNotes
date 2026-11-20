package com.example.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepgramMessageParserTest {
    @Test
    fun parsesFinalTranscriptWithAudioTiming() {
        val event = DeepgramMessageParser.parse(
            """{"type":"Results","start":1.25,"duration":2.5,"is_final":true,"channel":{"alternatives":[{"transcript":"Hello there."}]}}"""
        )

        assertEquals(
            DeepgramMessageEvent.Transcript(
                text = "Hello there.",
                startMs = 1_250,
                endMs = 3_750,
                isFinal = true
            ),
            event
        )
    }

    @Test
    fun parsesInterimTranscriptWithoutMarkingItFinal() {
        val event = DeepgramMessageParser.parse(
            """{"type":"Results","start":0.2,"duration":0.4,"is_final":false,"channel":{"alternatives":[{"transcript":"Good mor"}]}}"""
        )

        assertTrue(event is DeepgramMessageEvent.Transcript && !event.isFinal)
    }

    @Test
    fun exposesProviderErrorsAndIgnoresEmptyResults() {
        val providerError = DeepgramMessageParser.parse(
            """{"type":"Error","err_msg":"Unsupported language"}"""
        )
        val emptyResult = DeepgramMessageParser.parse(
            """{"type":"Results","channel":{"alternatives":[{"transcript":""}]}}"""
        )

        assertEquals(DeepgramMessageEvent.Error("Unsupported language"), providerError)
        assertEquals(DeepgramMessageEvent.Ignore, emptyResult)
    }
}
