package com.opicih.coach.learning

import com.opicih.coach.data.db.ExamType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Focus(val titleKo: String, val detailKo: String, val priority: Int) {
    DIAGNOSE("현재 실력 진단", "자유 발화 2개로 기준점을 녹음하고, 기존 스크립트 개선안을 검토합니다.", 3),
    CORE("핵심 주제 스크립트 보완", "Survey 주제의 IH 개선안을 승인하고 Keyword → Blind로 말해 봅니다.", 4),
    PAST_COMPARE("과거 경험·비교·묘사", "과거 시제 서사와 과거 vs 현재 비교 답변을 집중 연습합니다.", 6),
    ROLEPLAY("Role-play·돌발", "질문하기, 문제 해결(대안 2~3개), 돌발 주제 답변을 연습합니다.", 5),
    FREE("자유 발화·약점 보완", "스크립트 없이 답하고, 반복 오류와 약한 주제를 보완합니다.", 7),
    MOCK("실전 모의시험", "15문항 모의시험을 시간 안에 끝까지 진행하고 리포트를 확인합니다.", 9),
    LIGHT("취약 표현 복습·컨디션", "틀린 표현과 시작 문장만 가볍게 복습하고 일찍 쉽니다.", 8),
    LONG_FREE("자유 발화 길이·연결성", "같은 질문을 4/3/2 방식으로 반복하며 길이와 연결을 늘립니다.", 5),
    LONG_UNEXPECTED("돌발 질문 대응", "AI/은행 질문으로 처음 보는 질문에 스토리 뱅크를 응용합니다.", 5),
    LONG_STRUCTURE("문장 구조 다양화", "비교·가정·관계사 문장을 넣어 같은 이야기를 다시 말합니다.", 5),
    LONG_ERRORS("반복 오류 감소", "오류 노트 상위 3개를 집중 교정합니다.", 5),
    LONG_FLUENCY("발음·유창성", "Shadowing과 녹음 비교로 속도와 끊김을 다듬습니다.", 5),
    // SAIL (learning simulation; the official SAIL item format is not public)
    SAIL_QUICK("SAIL 즉답 훈련", "짧게 생각하고 20~40초 안에 답합니다. 첫 문장을 2초 안에 시작하는 것이 목표입니다.", 6),
    SAIL_FOLLOWUP("SAIL 추가 질문 대응", "한 질문에 이어지는 추가 질문 2~3개에 구체적인 예시로 답합니다.", 6),
    SAIL_MOCK("SAIL 실전 대화 연습", "처음 보는 질문과 추가 질문을 시험처럼 끊지 않고 이어서 답합니다.", 9),
    SAIL_LIGHT("SAIL 가벼운 복습·컨디션", "틀린 표현과 자신 있는 시작 문장만 복습하고 일찍 쉽니다.", 8),
    SAIL_WARMUP("시험 당일 워밍업", "10분 이내로 시작 문장을 소리 내어 읽고 짧은 즉답 2개만 합니다.", 10),
}

enum class TaskType { MORNING, REVIEW, SPEAK, FREE, ROLEPLAY, MOCK, IMPROVE }

/** [mode] = PracticeMode name the Speaking screen should open with (null = keep the screen's default). */
data class PlanTask(val type: TaskType, val title: String, val minutes: Int, val targetCount: Int = 1, val mode: String? = null)

data class DayPlan(
    val date: LocalDate,
    val dLabel: String,
    val focus: Focus,
    val tasks: List<PlanTask>,
    /** Exam this plan is built for; null = no registered exam (long-term cycle). */
    val examType: ExamType? = null,
    /** Measured weakness this plan targets (shown with its evidence); null = none applied. */
    val weakness: Weakness? = null,
) {
    val totalMinutes: Int get() = tasks.sumOf { it.minutes }
}

/**
 * Pure plan generator. Recomputed every day from "days left", so missed days never push the plan back:
 * the remaining days keep the most important focuses (mock test, light review, free speaking first).
 */
object PlanGenerator {
    private val sprint = listOf(Focus.DIAGNOSE, Focus.CORE, Focus.PAST_COMPARE, Focus.ROLEPLAY, Focus.FREE, Focus.MOCK, Focus.LIGHT)
    private val longCycle = listOf(Focus.LONG_FREE, Focus.LONG_UNEXPECTED, Focus.LONG_STRUCTURE, Focus.LONG_ERRORS, Focus.LONG_FLUENCY, Focus.FREE, Focus.MOCK)

    fun dLabel(today: LocalDate, exam: LocalDate): String {
        val d = ChronoUnit.DAYS.between(today, exam)
        return when {
            d > 0 -> "D-$d"
            d == 0L -> "D-Day"
            else -> "D+${-d}"
        }
    }

    /**
     * Focus sequence for the days from today until the day before the exam.
     * @param mockDone true if a mock test was already completed during this sprint
     */
    fun sprintFocuses(daysLeft: Int, mockDone: Boolean = false): List<Focus> {
        if (daysLeft <= 0) return emptyList()
        if (daysLeft >= sprint.size) {
            return List(daysLeft - sprint.size) { Focus.CORE } + sprint
        }
        // Fewer days than the template: keep LIGHT at D-1 and MOCK at D-2, then fill by priority.
        val tail = mutableListOf<Focus>()
        if (daysLeft >= 1) tail += Focus.LIGHT
        if (daysLeft >= 2 && !mockDone) tail.add(0, Focus.MOCK)
        val slots = daysLeft - tail.size
        val candidates = sprint.filter { it != Focus.LIGHT && it != Focus.MOCK }.sortedByDescending { it.priority }
        val chosen = candidates.take(slots).sortedBy { sprint.indexOf(it) }
        val head = if (slots > chosen.size) chosen + List(slots - chosen.size) { Focus.FREE } else chosen
        return head + tail
    }

    fun today(
        today: LocalDate,
        exam: LocalDate,
        minutes: Int,
        dueScripts: Int,
        mockDoneThisSprint: Boolean,
    ): DayPlan {
        val daysLeft = ChronoUnit.DAYS.between(today, exam).toInt()
        val focus = when {
            daysLeft > 0 -> sprintFocuses(daysLeft, mockDoneThisSprint).first()
            daysLeft == 0 -> Focus.LIGHT
            else -> longCycle[((-daysLeft) - 1).mod(longCycle.size)]
        }
        return DayPlan(today, dLabel(today, exam), focus, tasksFor(focus, minutes, dueScripts))
    }

    /**
     * Exam-aware entry point (v3). [examDate] is the nearest registered exam; null = none registered.
     * OPIc keeps the original sprint. SAIL uses its own countdown:
     * D-8+ shared basics, D-7..D-4 quick answers / follow-ups, D-3..D-2 conversation mock, D-1 light, D-Day warm-up.
     */
    fun forExam(
        today: LocalDate,
        examType: ExamType?,
        examDate: LocalDate?,
        minutes: Int,
        dueScripts: Int,
        mockDoneThisSprint: Boolean,
        /** Measured weaknesses, most severe first (WeaknessAnalyzer). Empty = plan by schedule only. */
        weaknesses: List<Weakness> = emptyList(),
    ): DayPlan {
        val base = if (examDate == null || examType == null) {
            // No exam registered: the weakest competency picks the focus; without data, rotate as before.
            val focus = WeaknessPlanner.longCycleFocus(weaknesses) ?: longCycle[today.toEpochDay().mod(longCycle.size)]
            DayPlan(today, "시험 미정", focus, tasksFor(focus, minutes, dueScripts), null)
        } else if (examType != ExamType.SAIL) {
            today(today, examDate, minutes, dueScripts, mockDoneThisSprint).copy(examType = examType)
        } else {
            val daysLeft = ChronoUnit.DAYS.between(today, examDate).toInt()
            val focus = sailFocus(daysLeft)
            DayPlan(today, dLabel(today, examDate), focus, tasksFor(focus, minutes, dueScripts), ExamType.SAIL)
        }
        return WeaknessPlanner.apply(base, weaknesses)
    }

    fun sailFocus(daysLeft: Int): Focus = when {
        daysLeft <= 0 -> Focus.SAIL_WARMUP
        daysLeft == 1 -> Focus.SAIL_LIGHT
        daysLeft <= 3 -> Focus.SAIL_MOCK
        daysLeft <= 7 -> if (daysLeft % 2 == 0) Focus.SAIL_QUICK else Focus.SAIL_FOLLOWUP
        else -> if (daysLeft % 2 == 0) Focus.CORE else Focus.FREE
    }

    fun upcoming(today: LocalDate, exam: LocalDate, mockDone: Boolean): List<Pair<LocalDate, Focus>> {
        val daysLeft = ChronoUnit.DAYS.between(today, exam).toInt()
        return sprintFocuses(daysLeft, mockDone).mapIndexed { i, f -> today.plusDays(i.toLong()) to f }
    }

    fun tasksFor(focus: Focus, minutes: Int, dueScripts: Int): List<PlanTask> {
        sailTasks(focus, minutes)?.let { return it }
        val morning = PlanTask(TaskType.MORNING, "아침 표현 루틴", if (minutes <= 10) 4 else 8)
        val reviewCount = when {
            minutes <= 10 -> 1
            minutes <= 30 -> minOf(3, maxOf(1, dueScripts))
            else -> minOf(5, maxOf(2, dueScripts))
        }
        val review = PlanTask(TaskType.REVIEW, "복습 스크립트 말하기 ${reviewCount}개", reviewCount * 3, reviewCount)
        val rest = (minutes - morning.minutes - review.minutes).coerceAtLeast(3)
        val main = when (focus) {
            Focus.DIAGNOSE -> listOf(PlanTask(TaskType.FREE, "자유 발화 진단 2문항", rest * 2 / 3, 2), PlanTask(TaskType.IMPROVE, "개선안 검토·승인", rest / 3))
            Focus.CORE -> listOf(PlanTask(TaskType.IMPROVE, "Survey 주제 개선안 승인", rest / 3), PlanTask(TaskType.SPEAK, "Keyword → Blind 말하기", rest * 2 / 3, 3))
            Focus.PAST_COMPARE -> listOf(PlanTask(TaskType.SPEAK, "과거 경험·비교 질문 Blind", rest, 3))
            Focus.ROLEPLAY -> listOf(PlanTask(TaskType.ROLEPLAY, "Role-play 3단 세트", rest * 2 / 3, 3), PlanTask(TaskType.FREE, "돌발 질문 1개", rest / 3, 1))
            Focus.FREE, Focus.LONG_FREE, Focus.LONG_UNEXPECTED, Focus.LONG_STRUCTURE -> listOf(PlanTask(TaskType.FREE, "자유 발화 (4/3/2)", rest, 3))
            Focus.MOCK -> if (minutes >= 45) listOf(PlanTask(TaskType.MOCK, "모의시험 15문항", 40)) else listOf(PlanTask(TaskType.MOCK, "미니 모의시험 7문항", rest))
            Focus.LIGHT -> listOf(PlanTask(TaskType.SPEAK, "자신 있는 답변 2개만 Blind", rest.coerceAtMost(15), 2))
            Focus.LONG_ERRORS -> listOf(PlanTask(TaskType.SPEAK, "오류 노트 교정 말하기", rest, 3))
            Focus.LONG_FLUENCY -> listOf(PlanTask(TaskType.SPEAK, "Shadowing + 녹음 비교", rest, 3))
            else -> emptyList() // SAIL focuses are handled by sailTasks()
        }
        return listOf(morning, review) + main
    }

    /**
     * SAIL day plans. 30 minutes = 5 expressions + 5 script keywords + 10 quick answers & follow-ups
     * + 5 weak-point correction + 5 answering the same question again. Other lengths scale from this.
     */
    private fun sailTasks(focus: Focus, minutes: Int): List<PlanTask>? {
        fun part(share: Int) = (minutes * share / 30).coerceAtLeast(2)
        val expressions = PlanTask(TaskType.MORNING, "어제 틀린 표현 복습", part(5).coerceAtMost(10))
        return when (focus) {
            Focus.SAIL_QUICK, Focus.SAIL_FOLLOWUP -> {
                val core = if (focus == Focus.SAIL_QUICK) "SAIL 즉답 + 추가 질문" else "SAIL 추가 질문 이어 답하기"
                if (minutes <= 10) listOf(
                    PlanTask(TaskType.MORNING, "어제 틀린 표현 복습", 3),
                    PlanTask(TaskType.FREE, core, 5, 2, MODE_SAIL),
                    PlanTask(TaskType.FREE, "같은 질문 다시 답하기", 2, 1, MODE_FREE),
                ) else listOf(
                    expressions,
                    PlanTask(TaskType.REVIEW, "핵심 스크립트 키워드 말하기", part(5), 1, MODE_KEYWORD),
                    PlanTask(TaskType.FREE, core, part(10), 3, MODE_SAIL),
                    PlanTask(TaskType.SPEAK, "취약 문법·유창성 교정", part(5), 1, MODE_FREE),
                    PlanTask(TaskType.FREE, "같은 질문 다시 답하기", part(5), 1, MODE_FREE),
                )
            }
            Focus.SAIL_MOCK -> listOf(
                expressions,
                PlanTask(TaskType.FREE, "SAIL 실전 대화 (질문 + 추가 질문 3~5턴)", (minutes - expressions.minutes - part(5)).coerceAtLeast(5), 4, MODE_SAIL),
                PlanTask(TaskType.FREE, "막힌 질문 다시 답하기", part(5), 1, MODE_FREE),
            )
            Focus.SAIL_LIGHT -> listOf(
                PlanTask(TaskType.MORNING, "틀린 표현 가볍게 복습", 5),
                PlanTask(TaskType.SPEAK, "자신 있는 답변 시작 문장 2개", minutes.coerceIn(5, 10), 2, MODE_FREE),
            )
            Focus.SAIL_WARMUP -> listOf(
                PlanTask(TaskType.MORNING, "시작 문장 소리 내어 읽기", 3),
                PlanTask(TaskType.FREE, "짧은 대화 3턴 (워밍업)", 5, 2, MODE_SAIL),
            )
            else -> null
        }
    }

    private const val MODE_FREE = "FREE"
    private const val MODE_KEYWORD = "KEYWORD"
    private const val MODE_SAIL = "SAIL_CONVERSATION"
}

/** OPIc retake rule as provided by the user (25 days, waiver exception). Must be verified on opic.or.kr. */
object RetestCalculator {
    const val WAIT_DAYS = 25L
    const val NOTICE = "재응시 제한은 OPIc 시험관리규정 제5조 기준입니다(응시일 제외 25일, D+26부터 응시 가능). 실제 회차와 Waiver 적용은 접수 화면에서 확인하세요."

    /** Official counting: the exam day is D+0, D+1..D+25 are blocked, D+26 is the first allowed day. */
    fun nextEligible(lastExam: LocalDate, waiver: Boolean = false): LocalDate =
        if (waiver) lastExam.plusDays(1) else lastExam.plusDays(WAIT_DAYS + 1)
}
