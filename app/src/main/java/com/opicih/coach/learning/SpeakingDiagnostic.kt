package com.opicih.coach.learning

import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.analysis.VerbatimMarkup
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.QuestionType
import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticCriterion(val label: String) {
    TASK("질문에 맞게 답하기"),
    CONNECTION("내용 연결하기"),
    TIME("필요한 시간대 유지하기"),
    DELIVERY("끊긴 내용 이어가기"),
}

@Serializable
enum class DiagnosticStatus(val label: String) {
    OBSERVED("관찰됨"), PARTIAL("일부 관찰됨"), NOT_OBSERVED("관찰되지 않음"), UNASSESSED("판단 보류"),
}

@Serializable
data class DiagnosticObservation(val status: DiagnosticStatus, val quote: String? = null)

@Serializable
data class DiagnosticReport(
    val evaluator: String,
    val rubricVersion: Int,
    val observations: Map<DiagnosticCriterion, DiagnosticObservation>,
)

internal data class DiagnosticQuestion(val type: QuestionType, val text: String)

internal object SpeakingDiagnostic {
    const val RUBRIC_VERSION = 1
    const val MAX_RECORDING_MS = 90_000L
    val types = listOf(QuestionType.DESCRIPTION, QuestionType.ROUTINE, QuestionType.PAST_EXPERIENCE,
        QuestionType.COMPARISON, QuestionType.ROLEPLAY)

    // Parallel prompts stay outside regular practice. Each group asks for the same kind of work across topics.
    val questions = linkedMapOf(
        QuestionType.DESCRIPTION to listOf(
            "Describe a place where you go to wait for someone. What would I see there, and what part of the place stands out to you?",
            "Describe a place where you go to get some fresh air. What would I see there, and what part of the place stands out to you?",
            "Describe a place where you can sit on a quiet afternoon. What would I see there, and what part of the place stands out to you?",
            "Describe a place where you go to meet a friend. What would I see there, and what part of the place stands out to you?",
        ),
        QuestionType.ROUTINE to listOf(
            "What do you usually do when you have a free hour on a weekday? Describe the steps in order and say why you do one of them.",
            "What do you usually do before leaving home for a casual outing? Describe the steps in order and say why you do one of them.",
            "What do you usually do when you get home after a busy day? Describe the steps in order and say why you do one of them.",
            "What do you usually do to get ready for the next morning? Describe the steps in order and say why you do one of them.",
        ),
        QuestionType.PAST_EXPERIENCE to listOf(
            "Tell me about a time when you found something you had lost. What happened, what did you do, and how did it turn out?",
            "Tell me about a time when a familiar place was unexpectedly closed. What happened, what did you do, and how did it turn out?",
            "Tell me about a time when a small item broke while you were using it. What happened, what did you do, and how did it turn out?",
            "Tell me about a time when you went to the wrong place by mistake. What happened, what did you do, and how did it turn out?",
        ),
        QuestionType.COMPARISON to listOf(
            "Compare how you kept in touch with friends a few years ago and how you do it now. Explain one change and give an example.",
            "Compare how you spent free evenings a few years ago and how you spend them now. Explain one change and give an example.",
            "Compare how you found your way to new places a few years ago and how you do it now. Explain one change and give an example.",
            "Compare how you chose what to read or watch a few years ago and how you do it now. Explain one change and give an example.",
        ),
        QuestionType.ROLEPLAY to listOf(
            "A friend calls to say your usual meeting place is closed today. Explain the problem to the rest of the group and suggest two alternatives.",
            "A delivery company says your package will arrive after you leave home. Explain the problem to the company and suggest two alternatives.",
            "The owner of a venue calls to say the room your group booked is unavailable. Explain the problem to the owner and suggest two alternatives.",
            "A repair shop calls to say it cannot finish your repair before you need the item. Explain the problem to the shop and suggest two alternatives.",
        ),
    )

    fun remaining(answered: Set<QuestionType>, exposedTexts: Iterable<String>): List<DiagnosticQuestion>? {
        val exposed = exposedTexts.mapTo(mutableSetOf(), TextNormalizer::normalize)
        return types.filterNot { it in answered }.map { type ->
            val prompt = questions.getValue(type).firstOrNull { TextNormalizer.normalize(it) !in exposed } ?: return null
            DiagnosticQuestion(type, prompt)
        }
    }

    fun hasUsableTranscript(transcript: String): Boolean =
        TextNormalizer.normalize(VerbatimMarkup.parse(transcript).clean).isNotBlank() &&
            !unclearAudio.containsMatchIn(transcript)

    fun validate(
        type: QuestionType,
        transcript: String,
        evaluator: String,
        proposed: Map<DiagnosticCriterion, DiagnosticObservation>,
    ): DiagnosticReport {
        val verbatim = VerbatimMarkup.parse(transcript)
        val text = verbatim.clean.replace(whitespace, " ")
        val usable = hasUsableTranscript(transcript)
        val observations = DiagnosticCriterion.entries.associateWith { criterion ->
            val observation = proposed[criterion]
            val quote = observation?.quote?.trim()?.replace(whitespace, " ")
            val applicable = when (criterion) {
                DiagnosticCriterion.TIME -> type in timeTasks
                DiagnosticCriterion.DELIVERY -> verbatim.abandoned > 0 || verbatim.selfCorrections > 0
                else -> true
            }
            if (usable && applicable && observation != null && observation.status != DiagnosticStatus.UNASSESSED &&
                !quote.isNullOrBlank() && quote.length <= 240 && text.contains(quote)
            ) {
                observation.copy(quote = quote)
            } else DiagnosticObservation(DiagnosticStatus.UNASSESSED)
        }
        return DiagnosticReport(evaluator, RUBRIC_VERSION, observations)
    }

    fun compare(current: DiagnosticReport?, previous: DiagnosticReport?, criterion: DiagnosticCriterion): DiagnosticStatus? {
        if (current == null || previous == null || current.evaluator != previous.evaluator ||
            current.rubricVersion != previous.rubricVersion) return null
        if (current.observations[criterion]?.status in listOf(null, DiagnosticStatus.UNASSESSED)) return null
        return previous.observations[criterion]?.status?.takeUnless { it == DiagnosticStatus.UNASSESSED }
    }

    private val whitespace = Regex("\\s+")
    private val unclearAudio = Regex("\\[\\s*(?:inaudible|unclear|unintelligible|미인식|불명확)[^]]*]", RegexOption.IGNORE_CASE)
    val timeTasks = setOf(QuestionType.PAST_EXPERIENCE, QuestionType.COMPARISON)
}

internal data class DiagnosticSession(val id: Long, val answers: Map<QuestionType, PracticeAttemptEntity>) {
    val complete get() = SpeakingDiagnostic.types.all(answers::containsKey)
    val startedAt get() = answers.values.minOfOrNull { it.createdAt } ?: 0L

    companion object {
        fun from(attempts: List<PracticeAttemptEntity>): List<DiagnosticSession> = attempts
            .mapNotNull { attempt -> attempt.trainingSessionId?.let { it to attempt } }
            .groupBy({ it.first }, { it.second })
            .map { (id, rows) -> DiagnosticSession(id, rows.sortedByDescending { it.id }.associateBy { QuestionType.of(it.questionType) }) }
            .sortedWith(compareByDescending<DiagnosticSession> { it.startedAt }.thenByDescending { it.id })
    }
}
