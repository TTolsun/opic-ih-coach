package com.opicih.coach

import com.opicih.coach.ai.AiJson
import com.opicih.coach.ai.GeminiAiCoach
import com.opicih.coach.analysis.Feedback
import com.opicih.coach.analysis.PauseMetrics
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.prefs.LearnerProfile
import com.opicih.coach.data.repo.AnalyzedAttempt
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.data.repo.appJson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiEvaluationFallbackTest {
    private val request = PracticeRepository.Request(
        script = null, topic = "Library", question = "What did you do last weekend?",
        questionType = "PAST_EXPERIENCE", mode = PracticeMode.FREE,
        transcript = "Last weekend I go to the library and I saw a friend.", captureMode = "LIVE_STT",
        audioPath = null, durationMs = 8_000, pauses = PauseMetrics(8_000, 5_000, 1, 2_200, 400), transcriptFromDeviceStt = true,
    )
    private val evaluation = """
        {
          "score": 83, "relevance": 90, "specificity": 75, "connectedness": 77,
          "strengths": ["질문에 답했습니다.", "장소를 밝혔습니다."],
          "fixes": [{"issue":"과거 맥락의 시제","why":"지난 일을 설명합니다.","better":"I went to the library."}],
          "betterAnswer": "I went to the library and saw a friend.",
          "retryMission": "과거형으로 바꿔 말하세요.",
          "tenseErrors": 1, "abandonedSentences": 0, "selfCorrections": 0,
          "errors": [{"key":"tense_in_past_context","category":"GRAMMAR","example":"I go","correction":"I went"}]
        }
    """.trimIndent()

    @Test fun incompleteOrInconsistentEvaluationKeepsTheLocalResult() = runBlocking {
        val local = PracticeRepository.analyze(request, ai = null)
        val invalid = mapOf(
            "empty object" to "{}",
            "missing score" to evaluation.replace("\"score\": 83, ", ""),
            "missing dimension" to evaluation.replace("\"specificity\": 75, ", ""),
            "missing error count" to evaluation.replace("\"selfCorrections\": 0,", ""),
            "out of range score" to evaluation.replace("\"score\": 83", "\"score\": 101"),
            "out of range dimension" to evaluation.replace("\"connectedness\": 77", "\"connectedness\": -1"),
            "negative error count" to evaluation.replace("\"abandonedSentences\": 0", "\"abandonedSentences\": -1"),
            "null feedback" to evaluation.replace("\"과거형으로 바꿔 말하세요.\"", "null"),
            "incomplete fix" to evaluation.replace("\"why\":\"지난 일을 설명합니다.\",", ""),
            "missing tense evidence" to evaluation.replace(
                """[{"key":"tense_in_past_context","category":"GRAMMAR","example":"I go","correction":"I went"}]""", "[]",
            ),
            "conflicting tense evidence" to evaluation.replace("\"tenseErrors\": 1", "\"tenseErrors\": 0"),
        )
        for ((label, response) in invalid) {
            val result = PracticeRepository.analyze(request, coach(envelope(response)))
            assertLocalFallback(label, local, result)
        }
    }

    @Test fun httpFailureAndIncompleteFinishKeepTheLocalResult() = runBlocking {
        val local = PracticeRepository.analyze(request, ai = null)
        val httpFailure = PracticeRepository.analyze(request, coach("""{"error":{"message":"test quota"}}""", status = 429))
        assertLocalFallback("HTTP 429", local, httpFailure)
        assertTrue(httpFailure.aiError.orEmpty().contains("429"))

        val unfinished = PracticeRepository.analyze(request, coach(envelope(evaluation, finish = "MAX_TOKENS")))
        assertLocalFallback("truncated response", local, unfinished)
        assertTrue(unfinished.aiError.orEmpty().contains("잘렸"))
    }

    @Test fun completeResponseUsesAiFeedbackAndKeepsTheSeparateLocalScore() = runBlocking {
        val local = PracticeRepository.analyze(request, ai = null)
        val result = PracticeRepository.analyze(request, coach(envelope(evaluation)))
        assertNull(result.aiError)
        assertEquals("AI", result.attempt.feedbackSource)
        assertEquals(local.feedback.score, result.attempt.score)
        assertEquals(90, result.feedback.relevance)
        assertEquals(result.feedback, appJson.decodeFromString<Feedback>(result.attempt.feedbackJson))
        assertEquals(1, result.feedback.tenseErrors)
        assertEquals(local.feedback.score, AttemptMetrics.from(result.attempt)?.localScore)
    }

    @Test fun explicitZeroScoreAndEmptyFixesCanBeValid() = runBlocking {
        val response = evaluation.replace("\"score\": 83", "\"score\": 0")
            .replace("""[{"issue":"과거 맥락의 시제","why":"지난 일을 설명합니다.","better":"I went to the library."}]""", "[]")
        val result = PracticeRepository.analyze(request.copy(mode = PracticeMode.BLIND), coach(envelope(response)))
        assertNull(result.aiError)
        assertEquals("AI", result.attempt.feedbackSource)
        assertEquals(0, result.attempt.score)
        assertTrue(result.feedback.fixes.isEmpty())
    }

    private fun assertLocalFallback(label: String, local: AnalyzedAttempt, result: AnalyzedAttempt) {
        assertTrue(label, !result.aiError.isNullOrBlank())
        assertEquals(label, local.feedback, result.feedback)
        assertEquals(label, "LOCAL", result.attempt.feedbackSource)
        assertEquals(label, local.feedback.score, result.attempt.score)
        assertEquals(label, local.feedback, appJson.decodeFromString<Feedback>(result.attempt.feedbackJson))
        assertEquals(label, local.metrics.errorCounts, AttemptMetrics.from(result.attempt)?.errorCounts)
    }

    private fun envelope(text: String, finish: String = "STOP"): String =
        """{"candidates":[{"content":{"parts":[{"text":${AiJson.json.encodeToString(text)}}]},"finishReason":"$finish"}]}"""

    private fun coach(body: String, status: Int = 200): GeminiAiCoach {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Synthetic Gemini response").body(body.toResponseBody()).build()
        }.build()
        return GeminiAiCoach("test", "test", LearnerProfile.DEFAULT, client)
    }
}
