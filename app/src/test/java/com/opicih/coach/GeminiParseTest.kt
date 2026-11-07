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

class GeminiTranscriberTest {
    @Test fun requestCarriesInlineAudioAndVerbatimPrompt() {
        val body = com.opicih.coach.ai.GeminiTranscriber.requestBody("QUJD", "audio/wav").toString()
        assertTrue(body.contains("\"inline_data\""))
        assertTrue(body.contains("\"mime_type\":\"audio/wav\""))
        assertTrue(body.contains("\"data\":\"QUJD\""))
        assertTrue(body.contains("verbatim") && body.contains("filler"))
        assertTrue(body.contains("\"temperature\":0.0"))
    }

    @Test fun cleansWrappers() {
        assertEquals("Um, I went there.", com.opicih.coach.ai.GeminiTranscriber.clean("```\n\"Um, I went there.\"\n```"))
        assertEquals("", com.opicih.coach.ai.GeminiTranscriber.clean("  "))
    }
}

class EvalPromptMissionTest {
    private fun input(mission: String?) = com.opicih.coach.ai.EvalInput(
        question = "What do you usually do on your days off?",
        questionType = "ROUTINE",
        transcript = "Next month I am planning to buy a new sofa.",
        discourse = com.opicih.coach.analysis.DiscourseAnalyzer.analyze("Next month I am planning to buy a new sofa."),
        pauses = null,
        targetScript = null,
        transcriptFromDeviceStt = false,
        mission = mission,
    )

    @Test fun variationMissionReachesTheEvaluator() {
        val p = com.opicih.coach.ai.Prompts.evalUser(input("같은 이야기를 미래 계획으로 바꿔 말하세요."))
        org.junit.Assert.assertTrue(p.contains("Practice mission"))
        org.junit.Assert.assertTrue(p.contains("미래 계획으로 바꿔 말하세요"))
        org.junit.Assert.assertTrue(com.opicih.coach.ai.Prompts.EVAL_SYSTEM.contains("practice mission"))
    }

    @Test fun noMissionLineForOrdinaryAnswers() {
        org.junit.Assert.assertFalse(com.opicih.coach.ai.Prompts.evalUser(input(null)).contains("Practice mission"))
    }
}
