package com.opicih.coach.learning

import com.opicih.coach.analysis.AnswerTask
import com.opicih.coach.analysis.ArticleSlips
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.ErrorItem
import com.opicih.coach.analysis.TenseErrorDetector
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.QuestionType
import kotlinx.serialization.Serializable

@Serializable
data class ScenePrompt(val type: QuestionType, val text: String)

@Serializable
data class SceneDrill(val first: ScenePrompt, val second: ScenePrompt, val retryHint: String)

internal data class KeptExpression(val words: String, val why: String, val fromSecond: Boolean)

internal object SceneCoaching {
    private fun pattern(text: String) = Regex(text, RegexOption.IGNORE_CASE)

    private val phrases = listOf(
        pattern("""\b(?:when I was (?:a (?:kid|child|student)|younger)|back then|these days|nowadays)\b""") to "언제의 이야기인지 알려 주는 표현이에요.",
        pattern("""\b(?:after that|by the time|in the end|the next morning|as soon as)\b""") to "사건이 어떤 순서로 이어지는지 알려 주는 표현이에요.",
        pattern("""\b(?:I (?:usually|used to) (?:go|play|sit|run|take|listen|jump|spend|watch|buy|eat|visit|walk|use))\b""") to "자주 하던 행동을 구체적인 동사로 말했어요.",
        pattern("""\bI (?:felt|feel|was) (?:much |really |very )?(?:better|relieved|happy|nervous|comfortable|tired|relaxed|scared|excited|surprised)\b""") to "그때의 느낌을 직접 표현했어요.",
        pattern("""\b(?:because (?:I|we|it) (?:can|could|was|is|had|have|wanted|needed|like|love)|that's why)\b""") to "이유와 결과를 연결할 때 다시 쓸 수 있어요.",
        pattern("""\b(?:near my (?:home|house)|in my living room|with my family|on the way home|at the front desk|from the ladder)\b""") to "장면을 떠올릴 수 있는 구체적인 단서를 줬어요.",
    )
    private val vague = pattern("""\b(?:very good|really good|very nice|really nice|good thing|nice thing)\b""")

    private fun task(q: ScenePrompt) = AnswerTask(q.text, q.type.name, null, null, null)

    fun expression(drill: SceneDrill, first: String, second: String, glossary: Glossary? = null): KeptExpression? {
        fun find(text: String, q: ScenePrompt, fromSecond: Boolean): KeptExpression? {
            if (task(q).isQuestionEcho(text)) return null
            for ((regex, why) in phrases) {
                val quote = regex.find(text)?.value ?: continue
                return KeptExpression(quote, why, fromSecond)
            }
            glossary?.spans(text)?.firstOrNull { it.entry.isIdiom }?.let { span ->
                return KeptExpression(text.substring(span.start, span.end), "‘${span.entry.meaningKo}’라는 뜻으로 다시 쓸 수 있어요.", fromSecond)
            }
            // Quote a complete personal detail even when it uses different words from the script.
            val detail = TextNormalizer.sentences(text).firstOrNull { sentence ->
                sentence.length <= 160 && text.contains(sentence) && TextNormalizer.contentLemmas(sentence).distinct().size >= 2 &&
                    personalDetail.containsMatchIn(sentence) && ArticleSlips.find(sentence).isEmpty() &&
                    !sentence.endsWith("?") && !q.text.contains(sentence.trimEnd('.'), ignoreCase = true)
            }
            return detail?.let { KeptExpression(it, "자기 경험이나 취향을 직접 말한 부분이에요.", fromSecond) }
        }
        return find(second, drill.second, true) ?: find(first, drill.first, false)
    }

    fun correction(drill: SceneDrill, first: String, second: String, errors: List<ErrorItem> = emptyList()): String {
        val task = task(drill.second)
        val slip = ArticleSlips.find(second).firstOrNull()
        val ai = errors.firstOrNull { it.example.isNotBlank() && it.correction.isNotBlank() &&
            second.contains(it.example, ignoreCase = true) && !it.example.equals(it.correction, ignoreCase = true) }
        val tense = TenseErrorDetector.find(second).firstOrNull()
        val general = vague.find(second)?.value
        return when {
            task.isQuestionEcho(second) -> "질문을 그대로 되풀이했어요. " + drill.retryHint
            TextNormalizer.normalize(first) == TextNormalizer.normalize(second) ->
                "첫 답변과 같은 내용을 말했어요. " + drill.retryHint
            slip != null -> "“" + slip.found + "”에서 명사 앞에 a를 넣어 “" + slip.correction + "”라고 말해 보세요."
            ai != null -> "“" + ai.example + "” 부분을 다시 말해 보세요. " + ai.correction
            tense != null -> "지난 일을 말한 “" + tense.sentence + "”에서 " + tense.verb + "의 시제를 확인해 보세요."
            task.missingTimeFrame(second, DiscourseAnalyzer.analyze(second)) != null ->
                "답변에서 질문이 요구한 시간대를 짚기 어려웠어요. " + drill.retryHint
            general != null -> "“" + general + "”를 어떤 점이 그랬는지 보여 주는 표현으로 바꿔 보세요."
            else -> refinement(drill, second)
        }
    }

    private fun refinement(drill: SceneDrill, answer: String): String {
        val detail = TextNormalizer.sentences(answer).lastOrNull { sentence ->
            sentence.length <= 120 && answer.contains(sentence) && TextNormalizer.contentLemmas(sentence).distinct().size >= 2
        } ?: return drill.retryHint
        val next = when (drill.second.type) {
            QuestionType.COMPARISON -> "이 부분을 살려 예전과 지금의 가장 큰 차이를 한 문장으로 정리해 보세요."
            QuestionType.PAST_EXPERIENCE -> "이 장면에서 왜 그렇게 행동했는지 한마디 연결해 보세요."
            QuestionType.ROUTINE -> "이 행동을 평소에 하는 이유까지 연결해 보세요."
            else -> "이 부분이 눈에 그려지도록 구체적인 예 하나를 들어 보세요."
        }
        return "“$detail” $next"
    }

    private val personalDetail = pattern("""\b(?:I (?:like|love|prefer|enjoy|remember|went|visited|played|bought|took|used|usually|often|go|play|listen|run|live|have)|my (?:favorite|sofa|family|mom|mother|friends?|hotel|room|park))\b""")
}
