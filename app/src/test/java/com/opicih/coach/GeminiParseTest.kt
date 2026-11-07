package com.opicih.coach

import com.opicih.coach.ai.AiException
import com.opicih.coach.ai.GeminiAiCoach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiParseTest {
    @Test fun joinsTextParts() {
        val body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"score\": "},{"text":"70}"}]},"finishReason":"STOP"}]}"""
        assertEquals("{\"score\": 70}", GeminiAiCoach.parseText(body))
    }

    @Test fun blockedPromptIsReported() {
        val body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        val e = runCatching { GeminiAiCoach.parseText(body) }.exceptionOrNull()
        assertTrue(e is AiException && e.message!!.contains("SAFETY") && !e.retryable)
    }

    @Test fun emptyCandidateExplainsFinishReason() {
        val body = """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}"""
        val e = runCatching { GeminiAiCoach.parseText(body) }.exceptionOrNull()
        assertTrue(e is AiException && e.message!!.contains("잘렸"))
    }

    @Test fun listsOnlyTextGenerationModels() {
        val body = """{"models":[
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]}
        ]}"""
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), GeminiAiCoach.parseModels(body))
    }
}
