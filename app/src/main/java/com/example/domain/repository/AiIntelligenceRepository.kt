package com.example.domain.repository

import com.example.domain.model.AiAnalysisResult
import com.example.domain.model.SpeechTranscriptionResult

interface AiIntelligenceRepository {
    suspend fun generateAnalysis(
        transcript: String,
        noteTitle: String,
        category: String? = null
    ): AiAnalysisResult
    suspend fun answerQuestion(transcript: String, noteTitle: String, question: String, chatHistory: List<Pair<Boolean, String>>): String
    suspend fun transcribeAudioFallback(audioFilePath: String): String
    suspend fun transcribeAudioChunk(
        audioFilePath: String,
        preferredLanguage: String,
        startMs: Long,
        endMs: Long
    ): SpeechTranscriptionResult
    suspend fun translateText(text: String, targetLanguage: String): String
    suspend fun testConnection(testKey: String? = null): Result<String>
    fun getKeySourceDescription(): String
    fun isKeyConfigured(): Boolean
}
