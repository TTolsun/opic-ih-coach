package com.opicih.coach.learning

import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.SegmentKind
import java.security.MessageDigest

/**
 * 영어 학습본을 코드로 먼저 문장·문단으로 나눈 결과. AI에는 [sentences]만 번역시키므로
 * 한국어는 영어 조각과 항상 1:1로 대응한다(정렬을 AI에게 맡기지 않는다).
 *
 * @property paragraphs 문장 번호 범위(포함). 문단이 하나뿐이면 전체 답변과 같아서 비워 둔다.
 */
data class SegmentPlan(val sentences: List<String>, val paragraphs: List<IntRange>)

/** 번역까지 끝난 조각 하나. */
data class SegmentRow(val kind: SegmentKind, val idx: Int, val en: String, val ko: String)

object ScriptSegmenter {
    /** 빈 줄로 나뉜 문단이 없을 때 한 문단에 넣을 문장 수의 상한. */
    private const val MAX_SENTENCES_PER_PARAGRAPH = 3

    /** 이 약어로 끝난 조각은 문장 끝이 아니므로 다음 조각과 합친다. */
    private val ABBREVIATIONS = setOf("mr.", "mrs.", "ms.", "dr.", "st.", "vs.", "e.g.", "i.e.")

    fun plan(text: String): SegmentPlan {
        val rawParagraphs = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val sentences = mutableListOf<String>()
        val ranges = mutableListOf<IntRange>()
        for (p in rawParagraphs) {
            val s = mergeAbbreviations(TextNormalizer.sentences(p))
            if (s.isEmpty()) continue
            ranges += sentences.size until sentences.size + s.size
            sentences += s
        }
        val paragraphs = when {
            sentences.isEmpty() -> emptyList()
            ranges.size >= 2 -> ranges
            else -> balancedGroups(sentences.size)
        }
        // 문단이 하나뿐이면 "답변 전체"와 같은 말이 되므로 문단 단계는 만들지 않는다.
        return SegmentPlan(sentences, if (paragraphs.size >= 2) paragraphs else emptyList())
    }

    /** n개 문장을 한 문단이 [MAX_SENTENCES_PER_PARAGRAPH]개 이하가 되도록 고르게 나눈다. */
    private fun balancedGroups(n: Int): List<IntRange> {
        val groups = (n + MAX_SENTENCES_PER_PARAGRAPH - 1) / MAX_SENTENCES_PER_PARAGRAPH
        val out = mutableListOf<IntRange>()
        var start = 0
        for (g in 0 until groups) {
            // 남는 문장을 앞쪽 문단부터 하나씩 더 나눠 준다.
            val size = n / groups + if (g < n % groups) 1 else 0
            out += start until start + size
            start += size
        }
        return out
    }

    private fun mergeAbbreviations(parts: List<String>): List<String> {
        val out = mutableListOf<String>()
        var carry = ""
        for (p in parts) {
            val cur = if (carry.isEmpty()) p else "$carry $p"
            if (ABBREVIATIONS.any { cur.lowercase().endsWith(it) }) carry = cur else { out += cur; carry = "" }
        }
        if (carry.isNotEmpty()) out += carry
        return out
    }

    /** 질문과 학습본이 같으면 같은 값. 달라지면 저장된 번역은 낡은 것이다. */
    fun sourceHash(question: String, text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${question.trim()}\u0000${text.trim()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /**
     * 번역된 문장([sentenceKo])을 같은 영어 조각 순서대로 짝지어 행을 만든다.
     * 문단과 전체 답변의 한국어는 속한 문장 번역을 이어 붙인 것이라서 영어와 항상 같은 범위를 가리킨다.
     */
    fun assemble(question: String, plan: SegmentPlan, questionKo: String, sentenceKo: List<String>): List<SegmentRow> {
        require(sentenceKo.size == plan.sentences.size) { "번역 개수(${sentenceKo.size})가 문장 수(${plan.sentences.size})와 다릅니다." }
        val rows = mutableListOf<SegmentRow>()
        rows += SegmentRow(SegmentKind.QUESTION, 0, question.trim(), questionKo.trim())
        rows += SegmentRow(SegmentKind.FULL, 0, plan.sentences.joinToString(" "), sentenceKo.joinToString(" ") { it.trim() })
        plan.paragraphs.forEachIndexed { i, r ->
            rows += SegmentRow(
                SegmentKind.PARAGRAPH, i,
                plan.sentences.slice(r).joinToString(" "),
                sentenceKo.slice(r).joinToString(" ") { it.trim() },
            )
        }
        plan.sentences.forEachIndexed { i, en -> rows += SegmentRow(SegmentKind.SENTENCE, i, en, sentenceKo[i].trim()) }
        return rows
    }
}
