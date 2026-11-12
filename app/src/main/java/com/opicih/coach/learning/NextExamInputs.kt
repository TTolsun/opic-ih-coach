package com.opicih.coach.learning

import com.opicih.coach.data.db.ExamEntity
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamTrainingSessionEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.LearnerGrade
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.appJson
import java.time.LocalDate

/*
 * Inputs for the next-exam advice shared by Home, NextExam, Progress and the morning alarm.
 * They used to live in ui/screens, which made alarm/ depend on the UI package.
 */

/**
 * Base date for the OPIc retake advice: the last COMPLETED OPIc sitting (month-only dates use the month's last day,
 * the conservative value). Other exam types (SAIL) never count. Falls back to old v2 exam rows, then to today.
 * The legacy Settings.examDate is no longer used: it could hold a recommendation, not a sitting.
 */
fun lastExamDate(exams: List<ExamEntity>, today: LocalDate, regs: List<ExamRegistrationEntity> = emptyList()): LocalDate {
    ExamSchedule.lastCompleted(regs, ExamType.OPIC)?.let { return it.date }
    val linkedNonOpic = regs.filter { it.type != ExamType.OPIC }.mapNotNull { it.legacyExamId }.toSet()
    return exams.filter { it.id !in linkedNonOpic }.map { LocalDate.ofEpochDay(it.epochDay) }.filter { !it.isAfter(today) }.maxOrNull() ?: today
}

/**
 * 가장 최근에 끝난 OPIc 응시의 결과 등급(알아볼 수 있는 값만). 현재 등급 설정의 기본 후보로 씁니다.
 * 결과를 아직 입력하지 않았거나 "IM 2"처럼 알 수 없는 문자열이면 null입니다.
 */
fun latestResultGrade(regs: List<ExamRegistrationEntity>): LearnerGrade? =
    regs.filter { it.type == ExamType.OPIC && it.state == RegistrationStatus.COMPLETED }
        .mapNotNull { r -> r.latestDate?.let { d -> LearnerGrade.parse(r.resultGrade)?.let { d to it } } }
        .maxByOrNull { it.first }?.second

/** Most recent exam day (any type) that is over or today, for the debrief card. */
fun lastSatDate(regs: List<ExamRegistrationEntity>, today: LocalDate): LocalDate? =
    regs.filter { it.state == RegistrationStatus.REGISTERED || it.state == RegistrationStatus.COMPLETED }
        .mapNotNull { it.date }.filter { !it.isAfter(today) }.maxOrNull()

internal fun debriefRegistration(regs: List<ExamRegistrationEntity>, date: LocalDate, registrationId: Long? = null): ExamRegistrationEntity? =
    regs.firstOrNull {
        it.date == date && it.type != ExamType.UNKNOWN && (registrationId == null || it.id == registrationId) &&
            (it.state == RegistrationStatus.REGISTERED || it.state == RegistrationStatus.COMPLETED)
    }

internal fun debriefExamRow(exams: List<ExamEntity>, date: LocalDate, registration: ExamRegistrationEntity?): ExamEntity? {
    val rows = exams.filter { it.epochDay == date.toEpochDay() }
    return rows.firstOrNull { it.id == registration?.legacyExamId }
        ?: rows.firstOrNull { (it.type == ExamType.SAIL.name) == (registration?.type == ExamType.SAIL) }
}

/** OPIc advice rules: the official policy; a waiver only when one is available and the user plans to use it. */
fun rulesOf(s: Settings, waiverAvailable: Boolean = false) = RetestAdvisor.Rules(
    policy = ExamPolicy.opic(), resultLagDays = s.resultLagDays, useWaiver = s.planWithWaiver && waiverAvailable,
    deadline = s.gradeDeadline, slots = s.slotDates(), registerLeadDays = s.registerLeadDays,
    criteria = ReadinessCriteria.of(s.targetGrade),
)

/** Finished SAIL conversation reports stored in exam_training_sessions.summaryJson. */
fun conversationReports(sessions: List<ExamTrainingSessionEntity>): List<ConversationReport> =
    sessions.filter { it.finishedAt != null }.mapNotNull { s ->
        s.summaryJson?.let { runCatching { appJson.decodeFromString(ConversationReport.serializer(), it) }.getOrNull() }
    }.filter { it.answeredTurns > 0 }.take(10)
