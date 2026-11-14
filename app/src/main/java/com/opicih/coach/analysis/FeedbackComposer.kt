package com.opicih.coach.analysis

import com.opicih.coach.learning.ScoreKind
import kotlinx.serialization.Serializable

@Serializable
data class FixItem(val issue: String, val why: String = "", val better: String = "")

@Serializable
data class ErrorItem(val key: String, val category: String, val example: String, val correction: String)

@Serializable
data class Feedback(
    val strengths: List<String> = emptyList(),
    val fixes: List<FixItem> = emptyList(),
    val betterAnswer: String = "",
    val retryMission: String = "",
    /** Learning reference score 0..100. Never an ACTFL rating. */
    val score: Int = 0,
    /** LOCAL or AI */
    val source: String = "LOCAL",
    /** AI engine name when source == AI (e.g. Gemini). */
    val engine: String = "",
    val relevance: Int? = null,
    val specificity: Int? = null,
    val connectedness: Int? = null,
    val errors: List<ErrorItem> = emptyList(),
    /** What could not be measured with the current capture mode. */
    val limits: List<String> = emptyList(),
    /** AI-counted tense errors in past-time context; null when the AI did not report it. */
    val tenseErrors: Int? = null,
    val abandonedSentences: Int? = null,
    val selfCorrections: Int? = null,
    /** False when there is no usable observation for the displayed score. */
    val scoreMeasured: Boolean = true,
)

data class AnalysisInput(
    val questionType: String,
    val usesScript: Boolean,
    val transcript: String,
    val discourse: DiscourseMetrics,
    val pauses: PauseMetrics?,
    val compare: CompareResult?,
    val transcriptCorrectedBySystem: Boolean,
    val requiredTense: String? = null,
    /**
     * 암기 점수만 스크립트 coverage를 내용 항목에 반영합니다. 말하기 점수는 같은 스크립트를 비교하더라도
     * coverage를 쓰지 않아서, 원문과 똑같이 말한다고 점수가 오르지 않습니다.
     */
    val scoreKind: ScoreKind = if (usesScript) ScoreKind.MEMORIZATION else ScoreKind.SPEAKING,
    val question: String = "",
    val mission: String? = null,
    val missionQuestion: String? = null,
) {
    internal fun answerTask() = AnswerTask(question, questionType, mission, missionQuestion, requiredTense)
}

/** Rule table that turns metrics into "잘한 점 2 / 수정 ≤3 / 미션". Labelled "로컬 분석(AI 아님)" in the UI. */
object FeedbackComposer {
    /** Cuts at a word boundary so the UI never shows half a word. */
    fun shorten(s: String, max: Int = 60): String =
        if (s.length <= max) s else s.take(max).substringBeforeLast(' ') + "…"

    fun wpm(wordCount: Int, speechMs: Long, totalMs: Long): Int {
        val ms = if (speechMs > 0) speechMs else totalMs
        return if (ms <= 0) 0 else (wordCount * 60_000L / ms).toInt()
    }

    fun score(input: AnalysisInput): Int {
        if (input.answerTask().isQuestionEcho(input.transcript)) return 0
        if (input.scoreKind == ScoreKind.SPEAKING) return speakingFlow(input) ?: 0

        val d = input.discourse
        val speechMs = input.pauses?.speechMs ?: 0L
        val lengthPart = maxOf(
            (speechMs / 60_000f * 25f).coerceAtMost(25f),
            (d.wordCount / 110f * 25f).coerceAtMost(25f),
        )
        val w = wpm(d.wordCount, speechMs, input.pauses?.totalMs ?: 0)
        var fluency = when {
            input.pauses == null -> 15f
            w == 0 -> 5f
            w in 90..170 -> 25f
            w in 70..89 || w in 171..200 -> 18f
            else -> 10f
        }
        fluency -= (input.pauses?.longPauseCount ?: 0) * 4f
        fluency = fluency.coerceIn(0f, 25f)
        val structure = (d.connectorCount.coerceAtMost(6) / 6f * 15f + d.sentenceCount.coerceAtMost(8) / 8f * 10f)
        val content = if (input.scoreKind == ScoreKind.MEMORIZATION && input.usesScript && input.compare != null) {
            input.compare.coverage * 25f
        } else {
            (d.timeFrames * 6f + (if (d.experienceMarkers > 0) 4f else 0f) + (if (d.feelingWords > 0) 3f else 0f)).coerceAtMost(25f)
        }
        return (lengthPart + fluency + structure + content).toInt().coerceIn(0, 100)
    }

    /** Scale the existing four-point long-pause penalty out of 25 to a 0–100 flow score. */
    private fun speakingFlow(input: AnalysisInput): Int? = input.pauses
        ?.takeIf { it.speechMs > 0 && input.discourse.wordCount > 0 }
        ?.let { (100L - 16L * it.longPauseCount).coerceIn(0, 100).toInt() }

    /** Recording without any transcript: only acoustic facts can be reported. */
    fun acousticOnly(p: PauseMetrics): Feedback {
        if (p.speechMs < 1500) {
            return Feedback(
                strengths = listOf("녹음은 정상으로 저장되었습니다. 다시 들어 보며 목소리가 들리는지 확인하세요.", "질문을 듣고 바로 시도했습니다."),
                fixes = listOf(FixItem("말소리가 거의 감지되지 않았습니다.", "마이크가 가려졌거나 목소리가 주변 소음보다 작았을 수 있습니다.", "휴대폰 아래쪽 마이크를 입에서 20cm 정도에 두고 평소보다 조금 크게 말해 보세요.")),
                retryMission = "같은 질문에 다시 답하세요. 첫 문장은 \"Let me tell you about...\"으로 시작합니다.",
                score = 0, source = "LOCAL", scoreMeasured = false,
                limits = listOf("텍스트 전사가 없어 내용·문법·시제 분석은 하지 않았습니다."),
            )
        }
        val strengths = mutableListOf<String>()
        val fixes = mutableListOf<FixItem>()
        if (p.speechMs >= 60_000) strengths += "실제 발화 시간이 ${p.speechMs / 1000}초로 충분합니다."
        if (p.longPauseCount == 0 && p.speechMs > 20_000) strengths += "2초 이상 멈춘 구간이 없습니다."
        if (strengths.isEmpty()) strengths += "끝까지 녹음했습니다. 녹음을 다시 듣고 TTS와 비교해 보세요."
        if (p.longPauseCount >= 2) fixes += FixItem("2초 이상 멈춘 구간이 ${p.longPauseCount}회 있습니다.", "긴 침묵은 유창성에 불리합니다.", "막힐 때 \"Let me think...\"로 시간을 버세요.")
        if (p.leadingSilenceMs > 5000) fixes += FixItem("첫 마디까지 ${p.leadingSilenceMs / 1000}초 걸렸습니다.", "시작이 늦으면 전체 발화가 줄어듭니다.", "\"That's a good question. Let me tell you about...\"로 바로 시작하세요.")
        return Feedback(
            strengths = strengths.take(2), fixes = fixes.take(3),
            retryMission = "녹음을 0.75배속으로 들으면서 막힌 부분을 찾고, 그 부분만 다시 말해 보세요.",
            score = 0, source = "LOCAL", scoreMeasured = false,
            limits = listOf("텍스트 전사가 없어 내용·문법·시제 분석은 하지 않았습니다. 설정에서 클라우드 전사를 켜거나 '실시간 인식' 모드를 사용하세요.", "점수는 전사가 있을 때만 계산합니다."),
        )
    }

    fun compose(input: AnalysisInput): Feedback {
        val d = input.discourse
        val p = input.pauses
        val task = input.answerTask()
        val scriptCompare = input.compare.takeIf { input.scoreKind == ScoreKind.MEMORIZATION }

        if (d.wordCount == 0) {
            return Feedback(
                fixes = listOf(FixItem("인식된 발화가 없습니다.", "녹음에 목소리가 들리는지 확인하세요.", "같은 질문에 한 문장으로 먼저 답해 보세요.")),
                retryMission = task.retryMission(),
                scoreMeasured = false,
                limits = listOf("인식 결과가 비어 있어 분석하지 못했습니다."),
            )
        }

        val echoed = task.isQuestionEcho(input.transcript)
        val missingFrame = if (!echoed && input.scoreKind == ScoreKind.SPEAKING) {
            task.missingTimeFrame(input.transcript, d)
        } else {
            null
        }
        val score = when {
            echoed && input.scoreKind == ScoreKind.SPEAKING -> null
            input.scoreKind == ScoreKind.SPEAKING -> speakingFlow(input)
            else -> score(input)
        }
        val slips = ArticleSlips.find(input.transcript)
        val strengths = buildList {
            if (scriptCompare != null && scriptCompare.coverage >= 0.7f) {
                add("스크립트 핵심 내용의 ${(scriptCompare.coverage * 100).toInt()}%를 전달했습니다.")
            }
            if (p != null && p.longPauseCount == 0 && p.speechMs > 20_000) {
                add("2초 이상 멈춘 구간 없이 말을 이어 갔습니다.")
            }
            add("영어 답변을 시도했습니다.")
            if (d.sentenceCount >= 2) add("답변이 ${d.sentenceCount}개 문장으로 인식됐습니다.")
        }.take(2)

        val fixes = buildList {
            if (echoed) {
                add(FixItem("질문 문장이 그대로 인식됐습니다.", "질문의 요점에 답하는 문장이 필요합니다.", task.retryMission()))
            }
            missingFrame?.let { add(it) }
            // A known repeated slip comes before coverage and pauses: it is one word to fix on the retry.
            if (slips.isNotEmpty()) {
                add(FixItem("관사 a가 빠졌습니다: \"${slips.first().found}\"", "셀 수 있는 명사 하나를 말할 때는 앞에 a를 붙입니다.",
                    slips.joinToString(" / ") { it.correction }))
            }
            if (scriptCompare != null && scriptCompare.coverage < 0.5f) {
                val missed = scriptCompare.sentences.filter { !it.covered }.take(2).joinToString(" / ") { shorten(it.sentence) }
                add(FixItem("스크립트 핵심 내용의 ${(scriptCompare.coverage * 100).toInt()}%를 말했습니다.", missed,
                    "빠진 내용의 키워드를 보고 같은 질문에 다시 답하세요."))
            }
            if (p != null && p.longPauseCount >= 2) {
                add(FixItem("2초 이상 멈춘 구간이 ${p.longPauseCount}회 있습니다.",
                    "멈추기 전까지 전달한 내용을 들어 보세요.", "막힌 부분을 더 쉬운 표현으로 바꿔 다시 말하세요."))
            }
            if (p != null && p.leadingSilenceMs > 5000) {
                add(FixItem("첫 마디까지 ${p.leadingSilenceMs / 1000}초 걸렸습니다.",
                    "첫 문장에 답의 요점을 정해 두면 시작하기 쉽습니다.", task.retryMission()))
            }
        }.take(3)

        val limits = buildList {
            if (!echoed && input.scoreKind == ScoreKind.SPEAKING) add(LOCAL_RELEVANCE_LIMIT)
            if (input.transcriptCorrectedBySystem) add("기기 음성 인식이 문법과 단어를 보정하거나 필러를 지울 수 있습니다.")
            if (input.scoreKind == ScoreKind.SPEAKING && (p == null || p.speechMs <= 0)) add("발화 흐름을 측정하지 못했습니다.")
            add("발음·억양·강세는 이 분석에서 측정하지 않습니다.")
        }

        return Feedback(
            strengths = strengths,
            fixes = fixes,
            retryMission = fixes.firstOrNull()?.better ?: task.retryMission(),
            score = score ?: 0,
            relevance = 0.takeIf { echoed },
            errors = buildList {
                if (echoed) add(ErrorItem("question_echo", "CONTENT", shorten(input.transcript, 80), task.retryMission()))
                slips.forEach { add(ErrorItem(ArticleSlips.KEY, "GRAMMAR", it.found, it.correction)) }
                if (missingFrame != null && task.requiresPastAndPresent) {
                    add(ErrorItem("comparison_frame_missing", "CONTENT", missingFrame.issue, missingFrame.better))
                }
            },
            limits = limits,
            scoreMeasured = score != null,
        )
    }

    internal const val LOCAL_RELEVANCE_LIMIT = "질문 적합성은 로컬 분석에서 평가하지 않습니다."

}
