package com.opicih.coach.learning

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
}

enum class TaskType { MORNING, REVIEW, SPEAK, FREE, ROLEPLAY, MOCK, IMPROVE }

data class PlanTask(val type: TaskType, val title: String, val minutes: Int, val targetCount: Int = 1)

data class DayPlan(val date: LocalDate, val dLabel: String, val focus: Focus, val tasks: List<PlanTask>) {
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

    fun upcoming(today: LocalDate, exam: LocalDate, mockDone: Boolean): List<Pair<LocalDate, Focus>> {
        val daysLeft = ChronoUnit.DAYS.between(today, exam).toInt()
        return sprintFocuses(daysLeft, mockDone).mapIndexed { i, f -> today.plusDays(i.toLong()) to f }
    }

    fun tasksFor(focus: Focus, minutes: Int, dueScripts: Int): List<PlanTask> {
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
        }
        return listOf(morning, review) + main
    }
}

/** OPIc retake rule as provided by the user (25 days, waiver exception). Must be verified on opic.or.kr. */
object RetestCalculator {
    const val WAIT_DAYS = 25L
    const val NOTICE = "재응시 제한(25일)과 Waiver 규정은 사용자 제공 정보입니다. 접수 가능 여부는 반드시 opic.or.kr에서 확인하세요."

    fun nextEligible(lastExam: LocalDate, waiver: Boolean = false): LocalDate =
        if (waiver) lastExam.plusDays(1) else lastExam.plusDays(WAIT_DAYS)
}
