package com.opicih.coach.ui.screens

import com.opicih.coach.learning.lastExamDate
import com.opicih.coach.learning.lastSatDate
import com.opicih.coach.learning.debriefExamRow
import com.opicih.coach.learning.debriefRegistration
import com.opicih.coach.learning.rulesOf
import com.opicih.coach.learning.conversationReports
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opicih.coach.App
import com.opicih.coach.data.db.ContentSource
import java.time.Instant
import com.opicih.coach.data.db.DailyLogEntity
import com.opicih.coach.data.db.ExamEntity
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.MockTestEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.ExamRepository
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.DayPlan
import com.opicih.coach.learning.PlanGenerator
import com.opicih.coach.learning.PlanTask
import com.opicih.coach.learning.RetestAdvisor
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.TaskStatus
import com.opicih.coach.learning.TodayProgress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class WeakTopic(val topic: String, val scriptId: Long?)

/** Compact next-exam recommendation for the home card. Details live on the next-exam screen. */
data class NextExamSummary(
    val verdict: String,
    val recommended: LocalDate?,
    val registerBy: LocalDate?,
    val gatesPassed: Int,
    val gatesTotal: Int,
)

data class HomeState(
    val loaded: Boolean = false,
    val plan: DayPlan? = null,
    val tasks: List<TaskStatus> = emptyList(),
    val next: PlanTask? = null,
    val completion: Float = 0f,
    val reviewIds: List<Long> = emptyList(),
    val weak: List<WeakTopic> = emptyList(),
    val pendingRevisions: Int = 0,
    val lastMock: MockTestEntity? = null,
    val alarmNeedsExact: Boolean = false,
    /** Date of the last exam still waiting for a debrief (within 7 days), else null. */
    val debriefDue: LocalDate? = null,
    /** Exam type label of [debriefDue] (e.g. "SAIL"), empty when unknown. */
    val debriefType: String = "",
    val debriefRegistrationId: Long? = null,
    val nextExam: NextExamSummary? = null,
    /** Nearest registered exam (header). Null = none registered. */
    val focusExam: ExamRegistrationEntity? = null,
    val regs: List<ExamRegistrationEntity> = emptyList(),
    val now: ZonedDateTime = ExamRepository.nowKst(),
    val settings: Settings = Settings(),
    val examsToReview: Int = 0,
    /** A registered exam that has ended but is not confirmed as taken yet. */
    val awaitingConfirm: ExamRegistrationEntity? = null,
    /** Weakest measured competencies (max 3) with evidence. */
    val weaknesses: List<com.opicih.coach.learning.Weakness> = emptyList(),
)

/** Pure: everything the home screen shows, derived from stored data. No Android types, so it is unit-tested. */
fun buildHomeState(
    today: LocalDate,
    s: Settings,
    scripts: List<ScriptEntity>,
    attempts: List<PracticeAttemptEntity>,
    logs: List<DailyLogEntity>,
    mocks: List<MockTestEntity>,
    pendingRevisions: Int,
    exams: List<ExamEntity> = emptyList(),
    /** Exam registrations (v3). Null = legacy single exam date from settings. */
    regs: List<ExamRegistrationEntity>? = null,
    now: ZonedDateTime = today.atTime(12, 0).atZone(ExamRepository.KST),
    errors: List<com.opicih.coach.data.db.ErrorPatternEntity> = emptyList(),
    conversations: List<com.opicih.coach.learning.ConversationReport> = emptyList(),
    studyZone: ZoneId = now.zone,
): HomeState {
    val weaknesses = com.opicih.coach.learning.WeaknessAnalyzer.analyze(attempts, errors, conversations, now.toInstant().toEpochMilli())
    // Daily logs and SRS dates use the device's calendar. Exam schedules use Korea's calendar.
    val studyDate = now.withZoneSameInstant(studyZone).toLocalDate()
    val day = studyDate.toEpochDay()
    val personal = scripts.filter { it.source != ContentSource.AI_SAMPLE.name && it.studyText.isNotBlank() }
    val reviewedToday = personal.filter { script ->
        script.lastReviewedAt?.let { Instant.ofEpochMilli(it).atZone(studyZone).toLocalDate() } == studyDate
    }.map { it.id }.toSet()
    val due = personal.filter { it.dueEpochDay <= day && it.id !in reviewedToday }
        .sortedWith(compareBy<ScriptEntity> { it.masteryScore }.thenBy { it.dueEpochDay }.thenBy { it.id })
    val reviewPoolSize = due.size + reviewedToday.size
    val mockDone = logs.any { it.mockDone && it.epochDay >= day - 7 }
    val reg = regs.orEmpty()
    val focusExam = ExamSchedule.focus(reg, now)
    val plan = if (regs == null) PlanGenerator.today(today, s.examDate, s.studyLevel.minutes, reviewPoolSize, mockDone)
    else PlanGenerator.forExam(today, focusExam?.type, focusExam?.date, s.studyLevel.minutes, reviewPoolSize, mockDone, weaknesses)
    val log = logs.firstOrNull { it.epochDay == day }
    val tasks = TodayProgress.statuses(plan, log, reviewedToday.size)
    val weak = ProgressCalculator.weakTopics(attempts, s.surveyTopics).map { t ->
        // Weakest script of that topic, so a tap opens something concrete to practice.
        WeakTopic(t, scripts.filter { it.topic.equals(t, true) }.minByOrNull { it.masteryScore }?.id)
    }
    val last = lastExamDate(exams, today, reg)
    // Debrief the most recent exam of any type; on exam day it appears only after the exam has ended.
    val sat = lastSatDate(reg, today) ?: last
    val debriefReg = debriefRegistration(reg, sat)
    val satRow = debriefExamRow(exams, sat, debriefReg)
    val debriefDue = sat.takeIf {
        !today.isBefore(it) && satRow?.debriefAt == null && today.toEpochDay() - it.toEpochDay() <= 7 && (it != today || focusExam?.date != today)
    }
    val sinceMs = last.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val retake = com.opicih.coach.learning.RetakeSummaries.of(s, reg, today)
    val advice = RetestAdvisor.advise(today, last, rulesOf(s, retake.waiverAvailable), ProgressCalculator.readiness(attempts, logs, last.toEpochDay(), sinceMs, mocks))
    val nextExam = NextExamSummary(advice.verdict.label, advice.recommended, advice.registerBy, advice.gates.count { it.passed }, advice.gates.size)
    return HomeState(
        loaded = true,
        plan = plan,
        tasks = tasks,
        next = TodayProgress.next(tasks),
        completion = ProgressCalculator.todayCompletion(log?.copy(scriptsReviewed = reviewedToday.size), s.studyLevel.minutes, TodayProgress.reviewGoal(plan)),
        reviewIds = due.take((TodayProgress.reviewGoal(plan) - reviewedToday.size).coerceAtLeast(0)).map { it.id },
        weak = weak,
        pendingRevisions = pendingRevisions,
        lastMock = mocks.firstOrNull(),
        alarmNeedsExact = s.alarmEnabled && s.useExactAlarm,
        debriefDue = debriefDue,
        debriefType = debriefReg?.type?.label.orEmpty(),
        debriefRegistrationId = debriefReg?.id,
        nextExam = nextExam,
        focusExam = focusExam,
        regs = reg,
        now = now,
        settings = s,
        examsToReview = ExamSchedule.needsReview(reg).size,
        awaitingConfirm = ExamSchedule.awaitingConfirmation(reg, now).firstOrNull(),
        weaknesses = weaknesses.take(3),
    )
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container
    val speakingReviews = c.dao.speakingReviewsFlow()

    private val stored = combine(c.dao.scriptsFlow(), c.dao.attemptsFlow(), c.dao.dailyLogsFlow()) { s, a, l -> Triple(s, a, l) }
    private val extra = combine(c.dao.mocksFlow(), c.dao.pendingRevisionsFlow(), c.dao.examsFlow()) { m, p, e -> Triple(m, p.size, e) }
    private val evidence = combine(c.dao.errorsFlow(), c.dao.trainingSessionsFlow()) { e, t -> e to conversationReports(t) }

    val state: StateFlow<HomeState> = combine(c.settings.flow, stored, extra, c.exams.flow, evidence) { s, (scripts, attempts, logs), (mocks, pending, exams), regs, (errors, convs) ->
        val now = ExamRepository.nowKst()
        buildHomeState(now.toLocalDate(), s, scripts, attempts, logs, mocks, pending, exams, regs, now, errors, convs, studyZone = ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

