package com.opicih.coach.importer

import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.QuestionType

data class ParsedScript(
    val topic: String,
    val title: String,
    val question: String,
    val answer: String,
) {
    val questionType: QuestionType get() = QuestionClassifier.infer(title + " " + question)
    val estSeconds: Int get() = (TextNormalizer.words(answer).size / 2.2).toInt().coerceAtLeast(10)
}

/**
 * Heuristic parser for OPIc script documents. Supports:
 *  - the user's TXT export ("N. Topic" / "N. Title: \"Question\"" / "* answer" with arbitrary indentation)
 *  - the Google Docs markdown export ("Topic:", "Question:", "- Answer:" with ** and \ escapes)
 *  - simple "Q: ... / A: ..." and markdown "# Topic" documents
 * Unknown structures fall back to one script containing the whole text, so nothing is lost.
 */
object ScriptParser {
    private data class Line(val text: String, val num: Boolean, val bullet: Boolean, val heading: Boolean)

    private val numPrefix = Regex("^\\s*\\d{1,2}[.)]\\s+")
    private val bulletPrefix = Regex("^\\s*([*■•▪◦-])\\s+")
    private val topicLabel = Regex("^topic\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val questionLabel = Regex("^(question|q)\\s*[:.)]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val answerLabel = Regex("^(answer|a)\\b\\s*[:.)]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val titledQuestion = Regex("^(.{1,70}?)\\s*:\\s*[\"“](.+?)[\"”]\\s*$")
    private val quoted = Regex("[\"“](.+?)[\"”]")

    fun parse(raw: String, defaultTopic: String = "My Scripts", fallbackTitle: String = "가져온 스크립트"): List<ParsedScript> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { clean(it) }.filter { it.text.isNotBlank() }
        val out = mutableListOf<ParsedScript>()

        var topic = defaultTopic
        var title: String? = null
        var question: String? = null
        val answer = StringBuilder()
        var inAnswer = false
        var awaitingQuestion = false

        fun flush() {
            if (question != null && answer.isNotBlank()) {
                out += ParsedScript(topic, title ?: question!!.take(40), question!!, tidy(answer.toString()))
            }
            title = null; question = null; answer.clear(); inAnswer = false; awaitingQuestion = false
        }

        fun setQuestion(t: String) {
            val m = titledQuestion.find(t)
            when {
                m != null -> { title = m.groupValues[1].trim(); question = m.groupValues[2].trim() }
                quoted.containsMatchIn(t) -> {
                    question = quoted.find(t)!!.groupValues[1].trim()
                    title = t.substringBefore('"').substringBefore('“').trim().trimEnd(':').ifBlank { question!!.take(40) }
                }
                else -> { question = t.trim(); title = t.trim().take(40) }
            }
        }

        for ((i, l) in lines.withIndex()) {
            val t = l.text
            val tm = topicLabel.find(t)
            if (tm != null) { flush(); topic = cleanTopic(tm.groupValues[1]); continue }

            if (l.heading && questionLabel.find(t) == null && !t.startsWith("answer", ignoreCase = true)) {
                flush(); topic = cleanTopic(t); continue
            }

            val q = questionLabel.find(t)
            if (q != null && !(q.groupValues[1].equals("q", true) && t.length < 3)) {
                flush()
                val rest = q.groupValues[2].trim()
                if (rest.isNotBlank()) setQuestion(rest) else awaitingQuestion = true
                continue
            }

            val a = answerLabel.find(t)
            val isAnswerLabel = a != null && (a.groupValues[1].equals("answer", true) || t.matches(Regex("^[Aa]\\s*[:.)].*")))
            if (l.bullet || isAnswerLabel) {
                val body = if (isAnswerLabel) a!!.groupValues[2] else t
                if (question != null) {
                    inAnswer = true
                    if (body.isNotBlank()) appendLine(answer, body)
                    continue
                }
                if (inAnswer) { appendLine(answer, body); continue }
            }

            if (awaitingQuestion) { setQuestion(t); awaitingQuestion = false; continue }

            if (l.num) {
                val next = lines.getOrNull(i + 1)
                val looksLikeTopic = !t.contains('"') && !t.contains('“') && !t.trimEnd().endsWith("?") &&
                    next != null && !next.bullet && (next.num || questionLabel.containsMatchIn(next.text))
                flush()
                if (looksLikeTopic) topic = cleanTopic(t) else setQuestion(t)
                continue
            }

            if (inAnswer) { appendLine(answer, t); continue }
            // Text before the first question (document title, dates) is ignored on purpose.
        }
        flush()

        if (out.isEmpty() && raw.isNotBlank()) {
            out += ParsedScript(defaultTopic, fallbackTitle, "(질문 미지정: 편집에서 질문을 입력하세요)", tidy(raw))
        }
        return out
    }

    private fun clean(raw: String): Line {
        var s = raw.replace("&#10;", " ").replace(Regex("[\\u200B-\\u200D\\uFEFF\\u00A0]"), " ")
        val num = numPrefix.containsMatchIn(s)
        if (num) s = s.replaceFirst(numPrefix, "")
        val bullet = !s.trimStart().startsWith("**") && bulletPrefix.containsMatchIn(s)
        if (bullet) s = s.replaceFirst(bulletPrefix, "")
        s = s.replace("\\", "").replace(Regex("\\*{2,}"), " ").replace(Regex("(^\\s*\\*)|(\\*\\s*$)"), " ")
        val heading = s.trimStart().startsWith("#")
        s = s.trimStart().trimStart('#').replace(Regex("\\s{2,}"), " ").trim()
        // A line like "## *날짜: ...*" keeps stray single asterisks; drop them.
        s = s.trim('*', ' ')
        return Line(s, num, bullet, heading)
    }

    private fun cleanTopic(t: String): String =
        t.replace(Regex("^topic\\s*:\\s*", RegexOption.IGNORE_CASE), "").substringBefore("(").trim().trim(':').ifBlank { "기타" }

    private fun appendLine(sb: StringBuilder, line: String) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line.trim())
    }

    private fun tidy(s: String): String =
        s.lines().map { it.replace(Regex("\\s{2,}"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

object QuestionClassifier {
    fun infer(text: String): QuestionType {
        val t = text.lowercase()
        return when {
            listOf("ask me", "ask three", "ask two", "call ", "act it out", "leave a message", "situation", "role").any { it in t } -> QuestionType.ROLEPLAY
            listOf("compare", "comparison", "different from", "changed", "change ", "past vs", "how has", "used to be").any { it in t } -> QuestionType.COMPARISON
            listOf("memorable", "unexpected", "experience", "happened", "recent", "last time", "remember", "first time", "childhood").any { it in t } -> QuestionType.PAST_EXPERIENCE
            listOf("routine", "usually", "typical", "what do you do", "prepare", "how do you").any { it in t } -> QuestionType.ROUTINE
            listOf("issue", "concern", "problem people", "society", "trend").any { it in t } -> QuestionType.ISSUE
            listOf("introduce yourself", "about yourself").any { it in t } -> QuestionType.SELF_INTRO
            listOf("describe", "tell me about", "what does", "what kind", "favorite", "who is", "look like").any { it in t } -> QuestionType.DESCRIPTION
            else -> QuestionType.OTHER
        }
    }
}

/** Links duplicate or near-duplicate scripts (question Jaccard ≥ 0.6 or answer ≥ 0.7). */
object DuplicateLinker {
    data class Item(val id: Long, val question: String, val answer: String)

    fun groups(items: List<Item>): Map<Long, String> {
        val parent = items.associate { it.id to it.id }.toMutableMap()
        fun find(x: Long): Long { var r = x; while (parent[r] != r) r = parent[r]!!; return r }
        val qs = items.map { TextNormalizer.contentLemmas(it.question).toSet() }
        val ans = items.map { TextNormalizer.contentLemmas(it.answer).toSet() }
        for (i in items.indices) for (j in i + 1 until items.size) {
            val qj = TextNormalizer.jaccard(qs[i], qs[j])
            val aj = TextNormalizer.jaccard(ans[i], ans[j])
            if ((qs[i].size >= 3 && qj >= 0.6) || (ans[i].size >= 8 && aj >= 0.7)) {
                parent[find(items[j].id)] = find(items[i].id)
            }
        }
        val roots = items.groupBy { find(it.id) }.filter { it.value.size > 1 }
        return roots.flatMap { (root, members) -> members.map { it.id to "G$root" } }.toMap()
    }
}
