package com.opicih.coach.analysis

/** Checks only task requirements that can be identified from the question or the assigned mission. */
internal class AnswerTask(
    val question: String,
    private val questionType: String,
    private val mission: String?,
    private val missionQuestion: String?,
    private val requiredTense: String?,
) {
    private val effectiveQuestion = missionQuestion ?: question
    private val timeRequirement = requiredTense ?: when {
        !mission.isNullOrBlank() && missionQuestion == null -> null
        historicalComparison.containsMatchIn(effectiveQuestion) ||
            (pastComparisonCue.containsMatchIn(effectiveQuestion) && presentComparisonCue.containsMatchIn(effectiveQuestion)) ->
            "PAST_AND_PRESENT"
        futureQuestion.containsMatchIn(effectiveQuestion) -> "FUTURE"
        pastQuestion.containsMatchIn(effectiveQuestion) || (missionQuestion == null && questionType == "PAST_EXPERIENCE") -> "PAST"
        else -> null
    }

    val requiresPastAndPresent: Boolean get() = timeRequirement == "PAST_AND_PRESENT"

    fun isQuestionEcho(transcript: String): Boolean {
        val answer = TextNormalizer.normalize(transcript)
        return answer.isNotEmpty() && listOfNotNull(question, missionQuestion).any { TextNormalizer.normalize(it) == answer }
    }

    fun missingTimeFrame(transcript: String, discourse: DiscourseMetrics): FixItem? {
        val suggestion = when (timeRequirement) {
            "PAST" -> "어떤 일이 있었고 어떻게 끝났는지 과거형으로 말하세요.".takeIf { discourse.pastCount == 0 }
            "FUTURE" -> "무엇을 할 계획인지와 그 이유를 말하세요. 예: I’m going to ... because ...".takeUnless {
                futureAnswer.containsMatchIn(TextNormalizer.normalize(transcript))
            }
            "PAST_AND_PRESENT" -> "같은 기준으로 예전과 지금을 각각 한 문장으로 설명하세요."
                .takeIf { discourse.pastCount == 0 || discourse.presentCount == 0 }
            else -> null
        }
        return suggestion?.let {
            val frame = when (timeRequirement) {
                "PAST" -> "과거 경험"
                "FUTURE" -> "미래 계획"
                else -> "과거와 현재의 비교"
            }
            val source = if (mission.isNullOrBlank()) "질문" else "미션"
            FixItem("$frame 표현을 확인하기 어렵습니다.", "${source}에서 요구한 시간대를 드러내 보세요.", it)
        }
    }

    fun retryMission(): String = when {
        timeRequirement == "FUTURE" -> "계획 한 가지를 먼저 말하고, 그 계획을 세운 이유를 설명하세요."
        timeRequirement == "PAST_AND_PRESENT" -> "예전과 지금의 차이 한 가지를 말하고, 무엇이 달라졌는지 설명하세요."
        timeRequirement == "PAST" -> "무슨 일이 있었는지 먼저 말하고, 그 뒤에 어떻게 됐는지 설명하세요."
        missionQuestion != null -> "새 질문에 먼저 직접 답하고, 그 답을 뒷받침하는 예를 하나 말하세요."
        !mission.isNullOrBlank() -> "미션에 맞는 답을 첫 문장에 말하고, 관련된 세부 사항을 하나 덧붙이세요."
        questionType == "COMPARISON" -> "두 대상의 공통점이나 차이점 하나를 같은 기준으로 설명하세요."
        questionType == "ROUTINE" -> "평소 하는 일을 먼저 말하고, 그다음에 하는 일이나 이유를 설명하세요."
        questionType == "ROLEPLAY" -> "상대에게 필요한 요청이나 질문을 먼저 말하고, 상황에 맞는 조건을 하나 덧붙이세요."
        questionType == "ISSUE" -> "의견을 먼저 말하고, 그 이유를 보여 주는 예를 하나 들어 보세요."
        else -> "질문에 바로 답하고, 답을 뒷받침하는 구체적인 내용 한 가지를 말하세요."
    }

    private companion object {
        val historicalComparison = Regex(
            """\b(then and now|past and present|over (the )?(years|time)|how (has|have) .+ changed)\b""",
            RegexOption.IGNORE_CASE,
        )
        val pastComparisonCue = Regex(
            """\b(childhood|in the past|used to|when you were|years ago|back then|before)\b""",
            RegexOption.IGNORE_CASE,
        )
        val presentComparisonCue = Regex(
            """\b(now|today|these days|currently|at present)\b""",
            RegexOption.IGNORE_CASE,
        )
        val futureQuestion = Regex(
            """\b(will you|are you (going|planning) to|your (future )?plans|in the future|next (weekend|week|month|year|summer|winter))\b""",
            RegexOption.IGNORE_CASE,
        )
        val pastQuestion = Regex(
            """\b(what happened|what did you do|how did you|you had|when you were|the last time you)\b""",
            RegexOption.IGNORE_CASE,
        )
        val futureAnswer = Regex(
            """\b(will\s+\w+|going to (?!the\b|a\b|an\b)\w+|plan(?:ning)? to \w+|tomorrow|next (weekend|week|month|year|summer|winter)|in the future)\b""",
        )
    }
}

internal fun ErrorItem.taskCorrection(transcript: String): FixItem? {
    val issue = when (key) {
        "off_topic_response" -> "답변이 질문이나 미션의 주제에서 벗어났습니다."
        "task_incomplete" -> "질문이나 미션이 요구한 내용이 일부 빠졌습니다."
        "question_echo" -> "질문 문장이 그대로 인식됐습니다."
        else -> return null
    }
    val quote = TextNormalizer.normalize(example)
    val grounded = quote.isNotEmpty() && " ${TextNormalizer.normalize(transcript)} ".contains(" $quote ")
    val why = if (grounded) "답변에 \"${FeedbackComposer.shorten(example, 80)}\"라는 표현이 있습니다." else ""
    return FixItem(issue, why, correction)
}
