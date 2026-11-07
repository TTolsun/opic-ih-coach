package com.opicih.coach.ai

import com.opicih.coach.analysis.DiscourseMetrics
import com.opicih.coach.analysis.Feedback
import com.opicih.coach.analysis.PauseMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ScriptVersions(
    val natural: String = "",
    val ihTarget: String = "",
    val short: String = "",
    val keywords: List<String> = emptyList(),
    val reasonKo: String = "",
    val koreanMeaning: String = "",
)

data class ScriptInput(val topic: String, val question: String, val original: String, val storyHints: String = "")

data class ChatTurn(val fromUser: Boolean, val text: String)

data class CoachContext(
    val examDday: String,
    val surveyTopics: Set<String>,
    val weakTopics: List<String>,
    val frequentErrors: List<String>,
    val scriptTitles: List<String>,
    val recentScores: List<Int>,
)

data class EvalInput(
    val question: String,
    val questionType: String,
    val transcript: String,
    val discourse: DiscourseMetrics,
    val pauses: PauseMetrics?,
    val targetScript: String?,
    val transcriptFromDeviceStt: Boolean,
)

/** AI features behind one interface. ClaudeAiCoach is the real AI; LocalCoach is the offline rule-based fallback. */
interface AiCoach {
    val isRealAi: Boolean
    /** Shown in the UI next to AI results ("Gemini", "Claude"). */
    val engineName: String get() = if (isRealAi) "AI" else "로컬"
    suspend fun improveScript(input: ScriptInput): Result<ScriptVersions>
    suspend fun evaluate(input: EvalInput): Result<Feedback>
    suspend fun chat(history: List<ChatTurn>, context: CoachContext): Result<String>
}

class AiException(message: String, val retryable: Boolean = true) : Exception(message)

object AiJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    /** Extracts the first {...} block from a model reply (models sometimes wrap JSON in prose or fences). */
    fun extractObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("AI 응답에서 JSON을 찾지 못했습니다.")
        return text.substring(start, end + 1)
    }
}
