package com.opicih.coach.learning

import com.opicih.coach.analysis.TextNormalizer

/**
 * 문장 틀(sentence frame): 빈칸(___)이 있는 재사용 문장. 답 전체를 외우는 대신 몇 개의 틀을 자동화한 뒤
 * 처음 보는 질문에 붙여 쓰는 연습에 씁니다. 이 파일은 전부 순수 Kotlin이라 단위 테스트로 검증합니다.
 */
const val FRAME_BLANK = "___"

enum class FrameOrigin { BUILTIN, SCRIPT }

/** REJECTED: the learner deleted it. The row is kept so the same frame is not suggested again. */
enum class FrameStatus { PENDING, APPROVED, REJECTED }

/** 내장 틀 은행의 한 항목. [starter]는 학습자 스크립트의 문장에서 이 틀이 쓰였는지 찾는 정규식(소문자 기준)입니다. */
data class BankFrame(val pattern: String, val hintKo: String, val starter: Regex)

/** 저장 전의 틀 후보. [example]은 학습자 자신의 스크립트에서 찾은 문장입니다. */
data class FrameCandidate(
    val pattern: String,
    val hintKo: String,
    val origin: FrameOrigin,
    val example: String? = null,
    val sourceScriptId: Long? = null,
)

data class ScriptText(val id: Long, val text: String)

object FrameBank {
    private fun f(pattern: String, hintKo: String, starter: String) = BankFrame(pattern, hintKo, Regex(starter))

    val ALL: List<BankFrame> = listOf(
        f("The thing I like most about $FRAME_BLANK is $FRAME_BLANK because $FRAME_BLANK.", "가장 좋아하는 점과 이유", "\\bthe thing i like (the )?most about\\b"),
        f("Back when I was $FRAME_BLANK, I used to $FRAME_BLANK.", "예전 습관 말하기", "\\bback when i was\\b|\\bwhen i was (a kid|young|little)\\b"),
        f("What happened was $FRAME_BLANK. So I ended up $FRAME_BLANK.", "사건과 결과 말하기", "\\bwhat happened was\\b|\\bi ended up\\b"),
        f("Compared to $FRAME_BLANK, $FRAME_BLANK is much more $FRAME_BLANK these days.", "과거와 현재 비교", "\\bcompared to\\b"),
        f("One thing I remember clearly is $FRAME_BLANK.", "기억나는 장면 꺼내기", "\\bone thing i remember\\b|\\bi remember\\b"),
        f("I usually $FRAME_BLANK, and then I $FRAME_BLANK.", "습관을 순서대로 말하기", "\\bi usually\\b"),
        f("After that, I $FRAME_BLANK and $FRAME_BLANK.", "다음 행동 이어 가기", "\\bafter that\\b"),
        f("It makes me feel $FRAME_BLANK because $FRAME_BLANK.", "느낌과 이유", "\\bit makes me feel\\b|\\bmakes me (feel )?(happy|relaxed|proud)\\b"),
        f("I'm planning to $FRAME_BLANK next $FRAME_BLANK.", "미래 계획 말하기", "\\bi am planning to\\b|\\bi'm planning to\\b|\\bi'm going to\\b|\\bi am going to\\b"),
        f("The reason why I like $FRAME_BLANK is that $FRAME_BLANK.", "좋아하는 이유", "\\bthe reason (why )?i\\b"),
        f("To be honest, I $FRAME_BLANK.", "솔직한 의견 시작", "\\bto be honest\\b"),
        f("When I have free time, I like to $FRAME_BLANK.", "여가 활동 말하기", "\\bwhen i have (some )?free time\\b"),
        f("The first thing I do is $FRAME_BLANK.", "첫 행동 말하기", "\\bthe first thing i do\\b"),
        f("I have been $FRAME_BLANK for $FRAME_BLANK.", "지속 기간 말하기", "\\bi have been\\b|\\bi've been\\b"),
        f("Last $FRAME_BLANK, I went to $FRAME_BLANK with $FRAME_BLANK.", "지난 경험 시작", "\\blast (weekend|summer|year|month|week)\\b.*\\bi went\\b"),
        f("It was a $FRAME_BLANK experience because $FRAME_BLANK.", "경험 평가", "\\bit was a (great|fun|wonderful|special|memorable|good)\\b"),
        f("I think $FRAME_BLANK is important because $FRAME_BLANK.", "의견과 근거", "\\bi think .* is important\\b"),
        f("In my opinion, $FRAME_BLANK is better than $FRAME_BLANK because $FRAME_BLANK.", "비교 의견", "\\bin my opinion\\b"),
        f("Another reason is that $FRAME_BLANK.", "이유 덧붙이기", "\\banother reason\\b"),
        f("I was so $FRAME_BLANK that I $FRAME_BLANK.", "정도와 결과", "\\bi was so\\b .* \\bthat\\b"),
        f("If I get a chance, I would like to $FRAME_BLANK.", "바람 말하기", "\\bif i get a chance\\b|\\bi would like to\\b|\\bi'd like to\\b"),
        f("Overall, I $FRAME_BLANK and I would $FRAME_BLANK again.", "마무리 문장", "\\boverall\\b"),
    )
}

object FrameExtractor {
    const val MIN_TOTAL = 15
    const val MAX_TOTAL = 25

    /**
     * 학습자 스크립트의 문장에서 내장 틀이 이미 쓰인 곳을 찾고, 그 문장을 예시로 붙입니다. 모자라면 내장 틀로
     * [MIN_TOTAL]개 이상이 되도록 채웁니다(최대 [MAX_TOTAL]개). [existing]에 있는 틀은 다시 만들지 않습니다.
     */
    fun extract(scripts: List<ScriptText>, existing: Set<String> = emptySet(), max: Int = MAX_TOTAL): List<FrameCandidate> {
        val taken = existing.toMutableSet()
        val out = mutableListOf<FrameCandidate>()
        for (frame in FrameBank.ALL) {
            if (frame.pattern in taken || out.size >= max) continue
            val hit = scripts.firstNotNullOfOrNull { s ->
                TextNormalizer.sentences(s.text).firstOrNull { frame.starter.containsMatchIn(it.lowercase().replace('’', '\'')) }?.let { s to it }
            } ?: continue
            out += FrameCandidate(frame.pattern, frame.hintKo, FrameOrigin.SCRIPT, example = hit.second.trim(), sourceScriptId = hit.first.id)
            taken += frame.pattern
        }
        // 스크립트에서 찾은 틀이 적으면 내장 틀로 채웁니다. 승인은 사용자가 하므로 여기서는 후보일 뿐입니다.
        val target = (existing.size + out.size).let { if (it >= MIN_TOTAL) it else MIN_TOTAL }
        for (frame in FrameBank.ALL) {
            if (existing.size + out.size >= target || out.size >= max) break
            if (frame.pattern in taken) continue
            out += FrameCandidate(frame.pattern, frame.hintKo, FrameOrigin.BUILTIN)
            taken += frame.pattern
        }
        return out
    }
}

/** 답변에 틀이 쓰였는지 판정합니다. 빈칸 사이의 고정 문구가 순서대로 모두 나오면 쓴 것으로 봅니다. */
object FrameMatcher {
    private val CONTRACTIONS = listOf("i'm" to "i am", "i've" to "i have", "i'd" to "i would", "it's" to "it is", "i'll" to "i will")

    private fun norm(s: String): String {
        var t = s.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]"), " ")
        CONTRACTIONS.forEach { (a, b) -> t = t.replace(a, b) }
        return t.replace('\'', ' ').replace(Regex("\\s+"), " ").trim()
    }

    /** 틀에서 빈칸을 뺀 고정 문구 조각들(정규화됨). */
    fun fixedParts(pattern: String): List<String> = pattern.split(FRAME_BLANK).map { norm(it) }.filter { it.isNotEmpty() }

    fun used(pattern: String, transcript: String): Boolean {
        val parts = fixedParts(pattern)
        if (parts.isEmpty()) return false
        val text = " " + norm(transcript) + " "
        var from = 0
        for (p in parts) {
            val at = text.indexOf(" $p ", from)
            if (at < 0) return false
            from = at + p.length + 1
        }
        return true
    }
}
