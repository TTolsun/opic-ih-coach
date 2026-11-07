package com.opicih.coach.analysis

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
    val relevance: Int? = null,
    val specificity: Int? = null,
    val connectedness: Int? = null,
    val errors: List<ErrorItem> = emptyList(),
    /** What could not be measured with the current capture mode. */
    val limits: List<String> = emptyList(),
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
)

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
        val content = if (input.usesScript && input.compare != null) {
            input.compare.coverage * 25f
        } else {
            (d.timeFrames * 6f + (if (d.experienceMarkers > 0) 4f else 0f) + (if (d.feelingWords > 0) 3f else 0f)).coerceAtMost(25f)
        }
        return (lengthPart + fluency + structure + content).toInt().coerceIn(0, 100)
    }

    /** Recording without any transcript: only acoustic facts can be reported. */
    fun acousticOnly(p: PauseMetrics): Feedback {
        if (p.speechMs < 1500) {
            return Feedback(
                strengths = listOf("녹음은 정상으로 저장되었습니다. 다시 들어 보며 목소리가 들리는지 확인하세요.", "질문을 듣고 바로 시도했습니다."),
                fixes = listOf(FixItem("말소리가 거의 감지되지 않았습니다.", "마이크가 가려졌거나 목소리가 주변 소음보다 작았을 수 있습니다.", "휴대폰 아래쪽 마이크를 입에서 20cm 정도에 두고 평소보다 조금 크게 말해 보세요.")),
                retryMission = "같은 질문에 다시 답하세요. 첫 문장은 \"Let me tell you about...\"으로 시작합니다.",
                score = 0, source = "LOCAL",
                limits = listOf("텍스트 전사가 없어 내용·문법·시제 분석은 하지 않았습니다."),
            )
        }
        val strengths = mutableListOf<String>()
        val fixes = mutableListOf<FixItem>()
        if (p.speechMs >= 60_000) strengths += "실제 발화 시간이 ${p.speechMs / 1000}초로 충분합니다."
        if (p.longPauseCount == 0 && p.speechMs > 20_000) strengths += "2초 이상 멈춘 구간이 없습니다."
        if (strengths.isEmpty()) strengths += "끝까지 녹음했습니다. 녹음을 다시 듣고 TTS와 비교해 보세요."
        if (p.speechMs < 45_000) fixes += FixItem("실제 발화 시간이 ${p.speechMs / 1000}초로 짧습니다.", "IH 답변은 보통 1분 이상 이어집니다.", "경험 하나와 느낌 한 문장을 더 붙여 보세요.")
        if (p.longPauseCount >= 2) fixes += FixItem("2초 이상 멈춘 구간이 ${p.longPauseCount}회 있습니다.", "긴 침묵은 유창성에 불리합니다.", "막힐 때 \"Let me think...\"로 시간을 버세요.")
        if (p.leadingSilenceMs > 5000) fixes += FixItem("첫 마디까지 ${p.leadingSilenceMs / 1000}초 걸렸습니다.", "시작이 늦으면 전체 발화가 줄어듭니다.", "\"That's a good question. Let me tell you about...\"로 바로 시작하세요.")
        return Feedback(
            strengths = strengths.take(2), fixes = fixes.take(3),
            retryMission = "녹음을 0.75배속으로 들으면서 막힌 부분을 찾고, 그 부분만 다시 말해 보세요.",
            score = 0, source = "LOCAL",
            limits = listOf("텍스트 전사가 없어 내용·문법·시제 분석은 하지 않았습니다. 설정에서 클라우드 전사를 켜거나 '실시간 인식' 모드를 사용하세요.", "점수는 전사가 있을 때만 계산합니다."),
        )
    }

    fun compose(input: AnalysisInput): Feedback {
        val d = input.discourse
        val p = input.pauses
        val type = input.questionType
        val strengths = mutableListOf<String>()
        val fixes = mutableListOf<FixItem>()
        val errors = mutableListOf<ErrorItem>()
        val limits = mutableListOf<String>()

        if (d.wordCount == 0) {
            return Feedback(
                fixes = listOf(FixItem("인식된 발화가 없습니다.", "마이크가 막혀 있거나 인식이 실패했을 수 있습니다.", "조용한 곳에서 휴대폰을 입에서 20cm 정도 두고 다시 말해 보세요.")),
                retryMission = "첫 문장만이라도 소리 내어 말해 보세요: \"Let me tell you about...\"",
                score = 0,
                limits = listOf("인식 결과가 비어 있어 분석하지 못했습니다."),
            )
        }

        // ---- Strengths (pick the best 2)
        val candidates = mutableListOf<Pair<Int, String>>()
        if (d.wordCount >= 100) candidates += 9 to "답변 길이가 충분합니다 (${d.wordCount}단어). IH는 문단 수준의 연결 발화가 핵심입니다."
        if (d.connectorCount >= 5) candidates += 8 to "연결 표현을 ${d.connectorCount}회 사용해 문장을 이어 갔습니다 (${d.connectorsUsed.take(3).joinToString()})."
        if (d.timeFrames >= 2) candidates += 7 to "두 가지 이상의 시제(과거 ${d.pastCount}, 현재 ${d.presentCount}, 미래 ${d.futureCount})를 사용했습니다."
        if (d.experienceMarkers > 0) candidates += 7 to "구체적인 경험 표지(I remember, last year 등)로 이야기를 시작했습니다."
        if (d.feelingWords > 0) candidates += 6 to "감정 표현을 넣어 이야기가 생생합니다."
        if (p != null && p.longPauseCount == 0 && p.speechMs > 20_000) candidates += 8 to "2초 이상 멈춘 구간 없이 말을 이어 갔습니다."
        if (input.compare != null && input.compare.coverage >= 0.7f) candidates += 9 to "스크립트 핵심 내용의 ${(input.compare.coverage * 100).toInt()}%를 전달했습니다."
        if (d.lexicalDiversity >= 0.6f && d.wordCount >= 50) candidates += 5 to "같은 단어를 반복하지 않고 다양한 어휘를 썼습니다."
        // Always show two strengths: fall back to effort-based ones when no metric stands out.
        candidates += 1 to "끝까지 영어로 답변을 시도했습니다. 반복 횟수가 실력을 만듭니다."
        if (d.sentenceCount >= 2) candidates += 0 to "${d.sentenceCount}개 문장으로 답을 나눠 말했습니다."
        else candidates += 0 to "질문을 듣고 바로 답을 시작했습니다."
        candidates.sortedByDescending { it.first }.take(2).forEach { strengths += it.second }

        // ---- Fixes (max 3, most important first)
        val fixCandidates = mutableListOf<Pair<Int, FixItem>>()
        if (d.wordCount < 60) fixCandidates += 10 to FixItem(
            "답변이 짧습니다 (${d.wordCount}단어).",
            "IH는 짧은 문장 나열이 아니라 문단 단위로 이야기를 이어 가는 능력을 봅니다.",
            "시작 → 구체적 설명 2개 → 경험 1개 → 느낌 → 마무리 순서로 5~7문장을 목표로 하세요.",
        )
        if (type == "PAST_EXPERIENCE" && d.pastCount < 3) {
            fixCandidates += 9 to FixItem("과거 경험 질문인데 과거 시제가 적습니다.", "경험 서사는 과거 시제의 일관성이 핵심 평가 요소입니다.", "\"Last summer, I went... I saw... I felt...\"처럼 동사를 과거형으로 유지하세요.")
            errors += ErrorItem("past_tense_missing", "GRAMMAR", "과거 경험을 현재형으로 설명", "went / had / was 등 과거형 유지")
        }
        if (type == "COMPARISON" && (d.pastCount == 0 || d.presentCount == 0)) {
            fixCandidates += 9 to FixItem("비교 질문인데 과거와 현재가 모두 나오지 않았습니다.", "비교 질문은 과거→현재→이유→느낌 구조가 필요합니다.", "\"When I was a kid, ... But these days, ... I think it's because ...\"")
            errors += ErrorItem("comparison_frame_missing", "CONTENT", "비교 구조 없이 한쪽만 설명", "In the past ... / These days ... / The biggest difference is ...")
        }
        if (type == "ROUTINE" && d.presentCount == 0 && d.pastCount > d.presentCount) {
            fixCandidates += 7 to FixItem("루틴 질문인데 과거형으로 말했습니다.", "습관은 현재 시제로 말해야 자연스럽습니다.", "\"I usually..., then I..., after that I...\"")
            errors += ErrorItem("routine_in_past", "GRAMMAR", "루틴을 과거형으로 설명", "usually + 현재형")
        }
        if (d.futureCount == 0 && d.wordCount >= 40) fixCandidates += 5 to FixItem(
            "미래 표현이 없습니다.", "IH는 여러 시간대를 넘나드는 능력을 봅니다.",
            "마지막에 한 문장 추가: \"Next weekend, I'm planning to...\" 또는 \"I hope I can... someday.\"",
        )
        if (d.connectorCount < 3) fixCandidates += 8 to FixItem(
            "문장 연결이 부족합니다 (연결 표현 ${d.connectorCount}회).", "연결 표현이 없으면 문장 나열(IM 수준)로 들립니다.",
            "because / so / after that / actually / on the other hand 중 3개 이상을 넣어 보세요.",
        )
        if (p != null && p.longPauseCount >= 2) fixCandidates += 8 to FixItem(
            "2초 이상 멈춘 구간이 ${p.longPauseCount}회 있습니다 (최대 ${p.maxPauseMs / 1000}초).",
            "긴 침묵은 유창성 평가에 불리합니다.",
            "막힐 때는 침묵 대신 \"Let me think...\", \"How can I put it...\" 같은 표현으로 시간을 버세요.",
        )
        if (p != null && p.leadingSilenceMs > 5000) fixCandidates += 6 to FixItem(
            "첫 마디까지 ${p.leadingSilenceMs / 1000}초 걸렸습니다.", "시작이 늦으면 전체 발화 시간이 줄어듭니다.",
            "첫 문장을 고정해 두세요: \"Oh, that's a good question. Let me tell you about...\"",
        )
        if (d.experienceMarkers == 0 && type in setOf("PAST_EXPERIENCE", "DESCRIPTION", "COMPARISON")) fixCandidates += 6 to FixItem(
            "구체적인 개인 경험이 없습니다.", "구체적 디테일(언제, 어디서, 누구와)이 IH 답변의 설득력을 만듭니다.",
            "\"One thing I remember clearly is...\"로 짧은 에피소드 하나를 넣어 보세요.",
        )
        if (input.compare != null && input.compare.coverage < 0.5f) fixCandidates += 9 to FixItem(
            "스크립트 핵심 내용의 ${(input.compare.coverage * 100).toInt()}%만 말했습니다.",
            "빠진 문장: ${input.compare.sentences.filter { !it.covered }.take(2).joinToString(" / ") { shorten(it.sentence) }}",
            "Keyword 모드로 빠진 부분의 키워드만 보고 다시 말해 보세요.",
        )
        if (d.repeatedWords >= 3) fixCandidates += 4 to FixItem("같은 단어를 연달아 반복했습니다 (${d.repeatedWords}회).", "반복은 머뭇거림으로 들립니다.", "천천히 말하더라도 한 번에 말하는 연습을 하세요.")
        if (input.requiredTense == "FUTURE" && d.futureCount == 0) fixCandidates += 10 to FixItem("응용 미션(미래 계획)인데 미래 표현이 없습니다.", "미션 조건을 지키지 못했습니다.", "I'm going to / I'm planning to / I hope to 로 다시 말하세요.")
        if (input.requiredTense == "PAST" && d.pastCount < 3) fixCandidates += 10 to FixItem("응용 미션(과거 경험)인데 과거 시제가 부족합니다.", "미션 조건을 지키지 못했습니다.", "Last year, I ... 로 시작해 과거형을 유지하세요.")
        fixCandidates.sortedByDescending { it.first }.take(3).forEach { fixes += it.second }

        // ---- Limits (honest reporting)
        if (input.transcriptCorrectedBySystem) {
            limits += "기기 음성 인식은 문법과 단어를 자동 보정할 수 있습니다. 실제 발화보다 정확해 보일 수 있습니다."
            limits += "필러(um, uh)는 인식기가 지우는 경우가 많아 개수가 과소 측정될 수 있습니다."
        }
        if (p == null) limits += "이번 캡처 방식에서는 무음 구간을 측정하지 못했습니다."
        limits += "발음·억양·강세는 이 분석에서 측정하지 않습니다. 녹음과 TTS를 직접 비교해 들어 보세요."

        val mission = when {
            input.compare != null && input.compare.coverage < 0.5f -> "빠진 문장 2개를 포함해서 같은 질문에 다시 답하세요."
            fixes.isNotEmpty() -> "다시 말하기: " + fixes.first().better
            else -> "4/3/2 훈련: 같은 답을 90초 → 60초 → 45초 안에 다시 말해 보세요."
        }

        return Feedback(
            strengths = strengths,
            fixes = fixes,
            betterAnswer = "",
            retryMission = mission,
            score = score(input),
            source = "LOCAL",
            errors = errors,
            limits = limits,
        )
    }
}
