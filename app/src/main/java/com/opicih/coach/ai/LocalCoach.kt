package com.opicih.coach.ai

import com.opicih.coach.analysis.AnalysisInput
import com.opicih.coach.analysis.Feedback
import com.opicih.coach.analysis.FeedbackComposer
import com.opicih.coach.analysis.ScriptComparator
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.learning.LocalFollowUp

/** Offline, rule-based fallback. Everything it produces is labelled "로컬 분석(AI 아님)". */
class LocalCoach : AiCoach {
    override val isRealAi = false

    override suspend fun improveScript(input: ScriptInput): Result<ScriptVersions> {
        val sentences = TextNormalizer.sentences(input.original)
        val short = sentences.take(3).joinToString(" ")
        val keywords = sentences.take(6).map { s ->
            TextNormalizer.words(s).filter { it !in TextNormalizer.stopwords && it.length > 3 }.take(3).joinToString(" ")
        }.filter { it.isNotBlank() }
        return Result.success(
            ScriptVersions(
                short = short,
                keywords = keywords.ifEmpty { ScriptComparator.pickKeywords(input.original) },
                reasonKo = "- 로컬 규칙으로 만든 Short/Keywords입니다 (AI 아님).\n- Natural/Target은 설정에서 AI를 켜면 생성할 수 있습니다.",
            )
        )
    }

    override suspend fun evaluate(input: EvalInput): Result<Feedback> {
        val compare = input.targetScript?.let { ScriptComparator.compare(it, input.transcript) }
        return Result.success(
            FeedbackComposer.compose(
                AnalysisInput(input.questionType, input.targetScript != null, input.transcript, input.discourse, input.pauses, compare, input.transcriptFromDeviceStt)
            )
        )
    }

    /** Rule-based follow-up. Callers that keep template state use LocalFollowUp directly. */
    override suspend fun followUp(history: List<ConvExchange>): Result<String> {
        val last = history.lastOrNull() ?: return Result.failure(AiException("대화 기록이 없습니다.", retryable = false))
        return Result.success(LocalFollowUp.next(last.answer, emptySet(), last.question).text)
    }

    override suspend fun summarizeConversation(history: List<ConvExchange>, localFindings: String): Result<String> =
        Result.failure(AiException("AI가 꺼져 있어 로컬 분석만 표시합니다.", retryable = false))

    override suspend fun chat(history: List<ChatTurn>, context: CoachContext): Result<String> {
        val q = history.lastOrNull { it.fromUser }?.text.orEmpty()
        val reply = when {
            "돌발" in q || "질문" in q -> "AI가 꺼져 있어 내장 질문 은행에서 골랐습니다 (AI 생성 연습 문제):\n" + OfflineQuestions.random()
            "반복" in q || "문법" in q || "틀리" in q ->
                if (context.frequentErrors.isEmpty()) "아직 기록된 반복 오류가 없습니다. 말하기 연습을 몇 번 하면 자동으로 쌓입니다."
                else "자주 나온 오류:\n" + context.frequentErrors.take(5).joinToString("\n") { "- $it" }
            "약" in q || "취약" in q -> if (context.weakTopics.isEmpty()) "취약 주제를 판단할 기록이 아직 부족합니다." else "지금 약한 주제: ${context.weakTopics.joinToString()}"
            else -> "지금은 AI가 꺼져 있어서 자유 대화는 할 수 없습니다. 설정 > AI에서 Gemini API 키를 입력하고 텍스트 전송에 동의하면 사용할 수 있습니다.\n오프라인으로 가능한 요청: \"돌발 질문 하나 내줘\", \"내가 반복해서 틀리는 문법이 뭐야?\", \"내 취약 주제가 뭐야?\""
        }
        return Result.success(reply)
    }
}

object OfflineQuestions {
    private val list = listOf(
        "Tell me about the technology you use every day. How has it changed your life compared to the past?",
        "What do people in your country do to stay healthy? Tell me about your own routine.",
        "Tell me about a time when something you bought was broken. What did you do?",
        "Describe a holiday that is important in your country. What do people usually do?",
        "Tell me about the recycling system in your area. How do you separate the trash?",
        "What are some issues people talk about regarding smartphones these days?",
        "Describe your favorite restaurant. What do you usually order and why?",
        "Tell me about a time you had a problem with a reservation or an appointment.",
    )
    fun random(): String = list.random()
}
