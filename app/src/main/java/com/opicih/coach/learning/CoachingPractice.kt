package com.opicih.coach.learning

import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.analysis.VerbatimMarkup
import com.opicih.coach.data.db.QuestionType
import kotlinx.serialization.Serializable

@Serializable
enum class CoachingStage { FIRST, RETRY, DELAYED }

@Serializable
data class CoachingTrack(val id: String, val promptId: String, val stage: CoachingStage)

@Serializable
data class CoachingRecord(
    val track: CoachingTrack,
    val evidence: CoachingEvidence?,
    val receivedCorrection: Boolean,
    val questionListens: Int,
    val day: Long,
    val dueDay: Long? = null,
    val assessmentId: String? = null,
    val opened: Int = 0,
    val assessmentSubmitted: Boolean = false,
) {
    val seenBefore: Boolean get() = track.stage == CoachingStage.DELAYED && opened > 1

    fun delayedExposure(previousSubmissions: Int): Int =
        maxOf(opened, previousSubmissions + 1, if (assessmentSubmitted) 2 else 1)

    val usableForIndependentAssessment: Boolean
        get() = evidence != null && !receivedCorrection && !seenBefore
}

@Serializable
data class CoachingEvidence(val parts: List<String?>, val sample: String)

data class CoachingPrompt(
    val id: String,
    val type: QuestionType,
    val label: String,
    val question: String,
    val actions: List<String>,
    val cues: List<Regex>,
) {
    fun evidence(transcript: String, capture: String, durationMs: Long): CoachingEvidence? {
        val text = VerbatimMarkup.parse(transcript).clean
        val words = TextNormalizer.words(text)
        if (capture !in setOf("LIVE_STT", CAPTURE_CLOUD_STT) || durationMs <= 0 || words.size < 5) return null
        if (words == TextNormalizer.words(question)) return null
        val boundary = if (type == QuestionType.PAST_EXPERIENCE) storyBoundary else comparisonBoundary
        val clauses = TextNormalizer.sentences(text)
            .flatMap { it.split(boundary) }
            .map(String::trim)
            .filter { TextNormalizer.words(it).size >= 3 }
        if (clauses.isEmpty()) return null
        val normalized = clauses.map(TextNormalizer::normalize)
        val parts = if (type == QuestionType.PAST_EXPERIENCE) {
            // Observe an event sequence only when each later clue follows an observed earlier one.
            // An isolated action or outcome marker does not identify the event it belongs to.
            var previous = -1
            cues.mapIndexed { step, cue ->
                val index = if (step == 0 || previous >= 0) {
                    ((previous + 1)..normalized.lastIndex).firstOrNull { cue.containsMatchIn(normalized[it]) }
                } else null
                previous = index ?: -1
                index?.let { clauses[it].take(MAX_QUOTE) }
            }
        } else {
            val sides = cues.take(2).map { cue -> normalized.indexOfFirst { cue.containsMatchIn(it) } }
            val difference = if (sides.all { it >= 0 }) normalized.indexOfFirst { cues.last().containsMatchIn(it) } else -1
            (sides + difference).map { clauses.getOrNull(it)?.take(MAX_QUOTE) }
        }
        return CoachingEvidence(parts, clauses.first().take(MAX_QUOTE)).takeIf { parts.any { it != null } }
    }

    fun nextAction(evidence: CoachingEvidence): String =
        actions.getOrNull(evidence.parts.indexOfFirst { it == null }) ?: "세 내용을 이어서 한 번 더 말해 보세요."

    companion object {
        private const val MAX_QUOTE = 180
        private val storyBoundary = Regex(
            """[,;]|\s+(?=(?:so i|and then|then i|after that|afterwards|as a result|in the end)\b)""",
            RegexOption.IGNORE_CASE,
        )
        private val comparisonBoundary = Regex(
            """[,;]|\s+(?=(?:but|whereas|nowadays|now i|these days)\b)""",
            RegexOption.IGNORE_CASE,
        )
    }
}

object CoachingPrompts {
    const val DELAY_DAYS = 3L

    private fun cue(pattern: String) = Regex("\\b(?:$pattern)\\b")
    private val past = "as a (?:kid|child|teenager)|when i was (?:a kid|a child|young|a teenager|in school)|back then|growing up|used to|childhood|in school"
    private val present = "now|nowadays|today|these days|currently"
    private val contrast = "unlike|whereas|instead|while|compared|rather than|different|difference|same|than|but"
    private val action = "called|asked|told|went to|took|moved|switched|reported|emailed|checked|showed|spoke to|talked to|requested|bought|found a|tried|visited|drank|rested|sent"
    private val outcome = "recovered|got better|felt better|fixed|solved|resolved|worked|could follow|could watch|found my shift|did not help|still could not|sent the right|went away|was gone|got worse|missed the|could read|could hear|could see|started working|finished the"

    private fun compare(
        id: String, label: String, question: String, detail: String, previous: String, current: String, difference: String,
    ) = CoachingPrompt(
        id, QuestionType.COMPARISON, label, question, listOf(previous, current, difference),
        listOf(
            Regex("^(?=.*\\b(?:$past)\\b)(?=.*\\b(?:$detail)\\b)"),
            Regex("^(?=.*\\b(?:$present)\\b)(?=.*\\b(?:$detail)\\b)"),
            cue(contrast),
        ),
    )

    private fun story(
        id: String, label: String, question: String, problem: String, issue: String, first: String, result: String,
    ) = CoachingPrompt(
        id, QuestionType.PAST_EXPERIENCE, label, question, listOf(issue, first, result),
        listOf(cue(problem), cue(action), cue(outcome)),
    )

    val practice = listOf(
        compare(
            "furniture", "가구 비교",
            "Compare a piece of furniture you had as a child with the same kind of furniture you have now. What is the main difference?",
            "desk|chair|table|bed|sofa|couch|bookshelf|shelf|wardrobe|dresser|cabinet|furniture|wooden|plastic|small|large|big|study|sleep|work|read",
            "어린 시절 가구의 특징을 한 문장으로 말해 보세요.", "지금 쓰는 가구의 특징을 한 문장으로 말해 보세요.",
            "두 가구의 가장 큰 차이를 한 문장으로 말해 보세요.",
        ),
        story(
            "travel", "해외여행 경험",
            "Think of a time you felt unwell on a trip abroad. What was the problem, what was the first thing you did about it, and how did it turn out?",
            "fever|headache|stomachache|stomach pain|dizzy|sick|ill|unwell|pain|nauseous|vomited|food poisoning|allergic",
            "여행 중 어디가 어떻게 아팠는지 한 문장으로 말해 보세요.", "몸이 아팠을 때 처음 한 행동을 말해 보세요.",
            "첫 행동 뒤에 몸 상태가 어떻게 달라졌는지 말해 보세요.",
        ),
    )

    // Reserved for delayed checks. These prompts never enter the ordinary question bank.
    val assessments = listOf(
        compare(
            "news", "뉴스 출처",
            "Think about when you were in school and how you get news now. Compare where you got news then with where you get it now. What is the main difference?",
            "newspapers?|television|tv|online|phone|apps?|websites?|social media|internet|radio|youtube|news",
            "학창 시절에 뉴스를 어디서 접했는지 말해 보세요.", "요즘 뉴스를 어디서 접하는지 말해 보세요.",
            "두 시기에 뉴스를 접하는 곳이 어떻게 달라졌는지 말해 보세요.",
        ),
        compare(
            "plans", "약속 기억 방법",
            "Think about when you were a teenager and how you keep track of your plans now. Compare how you remembered appointments then with how you remember them now. What is the main difference?",
            "calendars?|phone|apps?|notes?|reminders?|paper|planner|notebook|remembered|alarms?|memory",
            "청소년기에 약속을 어떻게 기억했는지 말해 보세요.", "요즘 약속을 어떻게 기억하는지 말해 보세요.",
            "두 시기에 약속을 기억하는 방법이 어떻게 달라졌는지 말해 보세요.",
        ),
        story(
            "theater", "극장 경험",
            "Think of a time when the sound or picture at a movie theater made it hard to follow the film. What was the problem, what was the first thing you did about it, and how did it turn out?",
            "muted|blurry|dark|fuzzy|frozen|too loud|too quiet|no sound|could not hear|could not see|could not follow|distorted|flickering|out of focus|broke",
            "영화를 보는 데 어떤 문제가 있었는지 말해 보세요.", "그 문제를 해결하려고 처음 한 행동을 말해 보세요.",
            "첫 행동 뒤에 화면이나 소리가 어떻게 됐는지 말해 보세요.",
        ),
        story(
            "document", "문서 전달 경험",
            "Think of a time when you received the wrong document for work or school. What problem did that cause, what was the first thing you did about it, and how did it turn out?",
            "wrong (?:document|file|version|schedule)|old (?:document|file|version|schedule)|last month|outdated|incorrect|could not|instead of",
            "잘못 받은 문서 때문에 어떤 문제가 생겼는지 말해 보세요.", "문제를 발견한 뒤 처음 한 행동을 말해 보세요.",
            "첫 행동 뒤에 문서 문제가 어떻게 됐는지 말해 보세요.",
        ),
    )

    fun prompt(id: String) = (practice + assessments).firstOrNull { it.id == id }

    fun reserve(type: QuestionType, reserved: Set<String>, seenQuestions: Set<String>): CoachingPrompt? =
        assessments.firstOrNull { it.type == type && it.id !in reserved && TextNormalizer.normalize(it.question) !in seenQuestions }
}
