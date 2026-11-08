package com.opicih.coach.learning

import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptVersion
import kotlin.random.Random

/** 누가 말하는 단계인지. 질문과 답변은 서로 다른 목소리로 읽는다. */
enum class ListenRole { QUESTION, ANSWER }

enum class ListenLang(val label: String) { EN("영어"), KO("한국어") }

enum class ListenPart(val label: String, val role: ListenRole) {
    QUESTION("질문", ListenRole.QUESTION),
    FULL("답변 전체", ListenRole.ANSWER),
    PARAGRAPH("문단", ListenRole.ANSWER),
    SENTENCE("문장", ListenRole.ANSWER),
}

/** 영어 한 조각과 그 번역. 번역은 영어를 먼저 나눈 뒤 조각마다 만들어야 정렬이 어긋나지 않는다. */
data class ListenSegment(val en: String, val ko: String)

/** 스크립트 하나의 문단·문장 단위 영한 정렬 데이터. 아직 저장소가 없으면 null로 두고 해당 단계를 건너뛴다. */
data class ListenSegments(
    val paragraphs: List<ListenSegment>,
    val sentences: List<ListenSegment>,
    /** 질문과 답변 전체의 한국어. 같은 번역에서 나왔으므로 지금 학습본과 맞는다. */
    val questionKo: String? = null,
    val fullKo: String? = null,
)

data class ListenStep(val part: ListenPart, val lang: ListenLang, val text: String) {
    val role: ListenRole get() = part.role
    val label: String get() = "${part.label} · ${lang.label}"
}

/** 스크립트 하나를 한 번 재생하는 단계 목록(사이클). */
data class ListenCycle(val scriptId: Long, val title: String, val topic: String, val steps: List<ListenStep>)

object ListenCycleBuilder {
    /**
     * 질문(영·한) → 답변 전체(영·한) → 문단 하나(영·한) → 문장 하나(영·한) 순서로 단계를 만든다.
     * 한국어 텍스트가 비어 있으면 그 한국어 단계만 빠지고, 영어 단계는 그대로 남는다.
     */
    fun build(
        question: String,
        questionKo: String?,
        answer: String,
        answerKo: String?,
        segments: ListenSegments? = null,
        random: Random = Random.Default,
    ): List<ListenStep> {
        val steps = mutableListOf<ListenStep>()
        fun pair(part: ListenPart, en: String?, ko: String?) {
            if (en.isNullOrBlank()) return
            steps += ListenStep(part, ListenLang.EN, en.trim())
            if (!ko.isNullOrBlank()) steps += ListenStep(part, ListenLang.KO, ko.trim())
        }
        pair(ListenPart.QUESTION, question, questionKo)
        pair(ListenPart.FULL, answer, answerKo)
        segments?.paragraphs?.filter { it.en.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let {
            val p = it.random(random)
            pair(ListenPart.PARAGRAPH, p.en, p.ko)
        }
        segments?.sentences?.filter { it.en.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let {
            val s = it.random(random)
            pair(ListenPart.SENTENCE, s.en, s.ko)
        }
        return steps
    }

    /**
     * `koreanMeaning`은 스크립트 전체의 한국어 뜻 하나뿐이고, AI 개선 때 IH Target 번역으로 덮어써진다.
     * 그래서 지금 재생하는 학습본의 번역이라고 믿을 수 있을 때만 쓴다. 믿을 수 없으면 한국어 단계를 건너뛴다.
     */
    fun koreanMatchesStudyText(s: ScriptEntity): Boolean = when (s.active) {
        ScriptVersion.IH_TARGET -> s.ihTarget != null
        ScriptVersion.ORIGINAL -> s.ihTarget == null
        else -> false
    }

    fun forScript(s: ScriptEntity, questionKo: String?, segments: ListenSegments? = null, random: Random = Random.Default): ListenCycle =
        ListenCycle(
            scriptId = s.id,
            title = s.title,
            topic = s.topic,
            steps = build(
                question = s.question,
                questionKo = segments?.questionKo ?: questionKo,
                answer = s.studyText,
                answerKo = segments?.fullKo ?: s.koreanMeaning?.takeIf { koreanMatchesStudyText(s) },
                segments = segments,
                random = random,
            ),
        )
}

/**
 * 한 바퀴를 다 돌 때까지 같은 항목이 다시 나오지 않는 셔플 큐.
 * 새 바퀴의 첫 항목이 직전 바퀴의 마지막 항목과 같으면 다른 항목과 자리를 바꾼다.
 */
class ShuffleQueue(private val random: Random = Random.Default) {
    private val remaining = ArrayDeque<Long>()
    private var last: Long? = null

    fun next(ids: List<Long>): Long? {
        if (ids.isEmpty()) { remaining.clear(); return null }
        remaining.retainAll(ids.toSet())
        if (remaining.isEmpty()) refill(ids)
        return remaining.removeFirst().also { last = it }
    }

    private fun refill(ids: List<Long>) {
        val shuffled = ids.distinct().shuffled(random).toMutableList()
        if (shuffled.size > 1 && shuffled.first() == last) {
            val j = 1 + random.nextInt(shuffled.size - 1)
            val t = shuffled[0]; shuffled[0] = shuffled[j]; shuffled[j] = t
        }
        remaining.addAll(shuffled)
    }
}
