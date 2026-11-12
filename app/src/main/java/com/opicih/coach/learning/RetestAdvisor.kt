package com.opicih.coach.learning

import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.prefs.LearnerGrade
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Recommends when to sit the next exam and when to register for it.
 *
 * Two independent inputs:
 *  1. Calendar constraints (retake interval, result lag, grade deadline, optional fixed in-house slots).
 *     These come from the user and are shown as "검증됨/미검증" because the app cannot look them up.
 *  2. Readiness gates computed from real practice records since the last exam.
 *
 * The output is advice with reasons, never a grade prediction.
 */
object RetestAdvisor {

    data class Rules(
        /** Retake limit; the single source of the blocked-day rule (official OPIc: D+1..D+25 blocked, D+26 allowed). */
        val policy: ExamPolicy = ExamPolicy.opic(),
        /** Days from the exam until the result is visible. */
        val resultLagDays: Int = 10,
        /** The date the grade is needed by (result must be out by then). */
        val deadline: LocalDate? = null,
        /** Fixed exam sessions (e.g. in-house schedule). Empty = any day allowed. */
        val slots: List<LocalDate> = emptyList(),
        /** How many days before the exam registration should be done (heuristic, editable). */
        val registerLeadDays: Int = 7,
        /** Spend a waiver on the next sitting: allowed from the day after the last exam. */
        val useWaiver: Boolean = false,
        /** Readiness thresholds for the next exam's target grade. Default keeps the original IH values. */
        val criteria: ReadinessCriteria = ReadinessCriteria.of(LearnerGrade.IH),
    )

    data class Readiness(
        /** Days since the last exam with >= 10 minutes of real speaking. */
        val practiceDays: Int,
        val speakingMinutes: Int,
        /** Average words of recent 말하기 점수 answers (free / variation / mock / SAIL; never script-recall modes). */
        val avgWords: Int,
        /** Average long (2 s+) pauses per recent answer; null when not measured. */
        val avgLongPauses: Float?,
        /** Average local score from one capture method. Null when there is no comparable score. */
        val avgScore: Int?,
        /** Finished mocks, including mocks without enough scorable answers. */
        val mocksSinceExam: Int,
        val bestMockScore: Int?,
        val recentAnswers: Int,
        val scoredAnswers: Int = recentAnswers,
        val scoreCapture: String? = null,
        val scorableMockAnswers: Int = 0,
        val scoreIsFlow: Boolean = false,
    )

    data class Gate(val id: String, val label: String, val passed: Boolean, val value: String, val target: String, val daysToFix: Int)

    enum class Verdict(val label: String) {
        REGISTER_EARLIEST("가장 빠른 날짜로 신청"),
        REGISTER_LATER("준비 후 추천 날짜로 신청"),
        REGISTER_ANYWAY("준비가 덜 되었지만 마감 때문에 신청"),
        NO_WINDOW("마감 전 응시 가능한 날짜 없음"),
        WAIT_FIRST_EXAM("첫 시험 전: 잠정 계획"),
    }

    data class Advice(
        val verdict: Verdict,
        val lastExam: LocalDate,
        val earliest: LocalDate,
        /** Last exam date whose result is still out before the deadline. */
        val latestUseful: LocalDate?,
        val recommended: LocalDate?,
        val registerBy: LocalDate?,
        /** True when another retake would still fit before the deadline after the recommended one. */
        val secondChance: Boolean,
        val gates: List<Gate>,
        val reasons: List<String>,
        val cautions: List<String>,
    )

    /**
     * 준비도 조건. 길이·점수·모의시험 기준은 다음 시험 목표 등급([ReadinessCriteria])에 따라 달라지며,
     * 점수와 길이는 말하기 점수(즉석 답변) 기록만 근거로 합니다.
     */
    fun gates(r: Readiness, c: ReadinessCriteria = ReadinessCriteria.of(LearnerGrade.IH)): List<Gate> {
        val lengthPassed = r.recentAnswers >= 3 && r.avgWords >= c.minWords
        // A flow score already reflects the pauses used by the fluency gate.
        val scorePassed = !r.scoreIsFlow && r.scoredAnswers >= 3 && r.avgScore != null && r.avgScore >= c.minScore
        val captureLabel = when (r.scoreCapture) {
            CaptureMode.LIVE_STT.name -> " · ${CaptureMode.LIVE_STT.label}"
            CAPTURE_CLOUD_STT -> " · 녹음 전사"
            else -> ""
        }
        val mockValue = when {
            r.mocksSinceExam == 0 -> "없음"
            r.bestMockScore == null -> "${r.mocksSinceExam}회 · 평가 가능 답변 최대 ${r.scorableMockAnswers}개"
            else -> "${r.mocksSinceExam}회 · 최고 ${r.bestMockScore}점"
        }
        return listOf(
            Gate(
                "volume", "연습량", r.practiceDays >= 10 || r.speakingMinutes >= 150,
                "${r.practiceDays}일 · ${r.speakingMinutes}분", "10일 이상 또는 150분", (10 - r.practiceDays).coerceIn(0, 10),
            ),
            Gate(
                "length", "자유 발화 길이", lengthPassed,
                if (r.recentAnswers == 0) "기록 없음" else "평균 ${r.avgWords}단어",
                "평균 ${c.minWords}단어 이상", if (lengthPassed) 0 else 7,
            ),
            Gate(
                "fluency", "긴 무음", r.avgLongPauses != null && r.avgLongPauses <= 1f,
                r.avgLongPauses?.let { "답변당 %.1f회".format(it) } ?: "측정 기록 없음", "답변당 1회 이하", 5,
            ),
            Gate(
                "score", "로컬 말하기 점수", scorePassed,
                if (r.scoreIsFlow) "과업 수행 점수 없음" else
                    r.avgScore?.let { "평균 ${it}점 · ${r.scoredAnswers}개$captureLabel" } ?: "평가 기록 없음",
                if (r.scoreIsFlow) "과업 수행 평가" else "평균 ${c.minScore}점 이상",
                if (scorePassed || r.scoreIsFlow) 0 else 7,
            ),
            Gate(
                "mock", "모의시험", r.mocksSinceExam >= 1 && r.bestMockScore != null && r.bestMockScore >= c.minMockScore,
                mockValue, "1회 이상, ${c.minMockScore}점 이상", 3,
            ),
        )
    }

    fun advise(today: LocalDate, lastExam: LocalDate, rules: Rules, readiness: Readiness): Advice {
        // Official counting excludes the exam day: 6/1 exam -> 6/2..6/26 blocked -> 6/27 (D+26) first day.
        val earliest = if (rules.useWaiver) rules.policy.earliestWithWaiver(lastExam) else rules.policy.earliestWithoutWaiver(lastExam)
        val latestUseful = rules.deadline?.minusDays(rules.resultLagDays.toLong())
        val gates = gates(readiness, rules.criteria)
        val cautions = mutableListOf<String>()
        if (rules.policy.verification == Verification.UNVERIFIED) cautions += "재응시 제한 ${rules.policy.blockedDays}일은 확인되지 않은 값입니다. 사내 공지나 접수 사이트에서 확인한 뒤 설정에서 '확인함'으로 바꾸세요."
        if (rules.useWaiver) cautions += "Waiver를 쓴다고 가정했습니다. 접수할 때 Waiver가 실제로 적용되는지 확인하세요."
        cautions += "접수 마감은 응시일 ${rules.registerLeadDays}일 전으로 가정했습니다. 실제 접수 기간을 확인하세요."

        fun slotOnOrAfter(d: LocalDate): LocalDate? =
            if (rules.slots.isEmpty()) d else rules.slots.filter { !it.isBefore(d) }.minOrNull()

        val firstPossible = slotOnOrAfter(maxOf(earliest, today.plusDays(1)))
        if (firstPossible == null || (latestUseful != null && firstPossible.isAfter(latestUseful))) {
            return Advice(
                Verdict.NO_WINDOW, lastExam, earliest, latestUseful, null, null, false, gates,
                listOf("재응시 가능일(${earliest})이 결과가 마감 전에 나오는 마지막 응시일(${latestUseful ?: "-"})보다 늦습니다."),
                cautions + "웨이버(재응시 제한 예외)가 있는지 확인하세요.",
            )
        }

        val missing = gates.filter { !it.passed }
        val daysNeeded = missing.maxOfOrNull { it.daysToFix } ?: 0
        val ready = missing.size <= 1
        val reasons = mutableListOf<String>()

        val preExam = !today.isAfter(lastExam)
        val verdict: Verdict
        val target: LocalDate
        when {
            preExam -> {
                verdict = Verdict.WAIT_FIRST_EXAM
                target = firstPossible
                reasons += "${lastExam} 시험 결과는 약 ${lastExam.plusDays(rules.resultLagDays.toLong())}에 나옵니다. 재응시는 ${earliest}부터 가능합니다."
                reasons += "IH가 나오지 않을 경우를 대비해 ${firstPossible} 회차를 잠정 목표로 잡고, 시험 직후 복기를 기록하세요."
            }
            ready -> {
                verdict = Verdict.REGISTER_EARLIEST
                target = firstPossible
                reasons += "준비도 조건 ${gates.size - missing.size}/${gates.size}개를 통과했습니다. 가장 빠른 날짜에 응시해도 됩니다."
            }
            else -> {
                val wanted = slotOnOrAfter(maxOf(firstPossible, today.plusDays(daysNeeded.toLong()))) ?: firstPossible
                if (latestUseful != null && wanted.isAfter(latestUseful)) {
                    verdict = Verdict.REGISTER_ANYWAY
                    target = slotOnOrAfter(firstPossible) ?: firstPossible
                    reasons += "미통과 조건(${missing.joinToString { it.label }})을 채우려면 약 ${daysNeeded}일이 더 필요하지만, 그러면 마감 전에 결과가 나오지 않습니다."
                    reasons += "마감 전 기회를 놓치지 않도록 ${target}에 신청하고, 남은 기간은 미통과 조건에 집중하세요."
                } else {
                    verdict = Verdict.REGISTER_LATER
                    target = wanted
                    reasons += "미통과 조건: ${missing.joinToString { it.label }}. 약 ${daysNeeded}일 집중 연습 후 응시하는 편이 안전합니다."
                }
            }
        }
        val secondChance = latestUseful != null && !rules.policy.earliestWithoutWaiver(target).isAfter(latestUseful)
        if (latestUseful != null) {
            reasons += if (secondChance) "이 날짜 뒤에도 마감 전에 한 번 더 응시할 수 있습니다."
            else "이 날짜가 마감 전 사실상 마지막 기회입니다. 실패에 대비한 추가 회차가 없습니다."
        }
        val registerBy = target.minusDays(rules.registerLeadDays.toLong()).let { if (it.isBefore(today)) today else it }
        return Advice(verdict, lastExam, earliest, latestUseful, target, registerBy, secondChance, gates, reasons, cautions)
    }
}
