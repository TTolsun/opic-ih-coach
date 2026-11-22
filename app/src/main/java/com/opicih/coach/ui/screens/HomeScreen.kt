package com.opicih.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.alarm.Notifications
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.MockTestEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.ExamRepository
import java.time.LocalDate
import com.opicih.coach.learning.DayPlan
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.PlanTask
import com.opicih.coach.learning.TaskType
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onRecall: () -> Unit,
    onSpeak: () -> Unit,
    onMorning: () -> Unit,
    onScript: (Long) -> Unit,
    onMock: () -> Unit,
    onRecentMock: (MockTestEntity) -> Unit,
    onScriptReview: (List<Long>, PracticeMode) -> Unit,
    onReviewRevisions: () -> Unit,
    onCoach: () -> Unit,
    onSettings: () -> Unit,
    onNextExam: () -> Unit,
    onDebrief: (LocalDate, Long?) -> Unit,
    onExams: () -> Unit = {},
    onDiagnostic: () -> Unit,
    /** Opens speaking with the task's PracticeMode name (null = default; SAIL_CONVERSATION = SAIL screen). */
    onSpeakMode: (String?) -> Unit = { onSpeak() },
    onSpeakingReview: (Long) -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val reviews by vm.speakingReviews.collectAsStateWithLifecycle(initialValue = emptyList())
    var today by remember { mutableLongStateOf(ExamRepository.nowKst().toLocalDate().toEpochDay()) }
    LifecycleResumeEffect(Unit) {
        today = ExamRepository.nowKst().toLocalDate().toEpochDay()
        onPauseOrDispose {}
    }
    val plan = st.plan
    fun open(t: PlanTask) = when (t.type) {
        TaskType.MORNING -> onMorning()
        TaskType.MOCK -> onMock()
        TaskType.IMPROVE -> onReviewRevisions()
        TaskType.REVIEW -> onScriptReview(st.reviewIds, t.mode?.let(PracticeMode::of) ?: PracticeMode.READ_LISTEN)
        TaskType.FREE -> onSpeakMode(t.mode ?: PracticeMode.FREE.name)
        TaskType.ROLEPLAY -> onSpeakMode(t.mode ?: PracticeMode.HOTEL_ROLEPLAY.name)
        TaskType.SPEAK -> onSpeakMode(t.mode ?: PracticeMode.BLIND.name)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("내 답변 연습", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "설정") }
        }
        SectionCard(
            title = plan?.let { "오늘의 훈련 · ${it.focus.titleKo}" } ?: "오늘의 훈련",
            trailing = { if (plan != null) Text("${(st.completion * 100).toInt()}%", style = MaterialTheme.typography.labelLarge) },
        ) {
            if (plan == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            st.tasks.forEach { ts ->
                val next = ts.task === st.next
                val resumingReview = ts.task.type == TaskType.REVIEW && st.reviewIds.size in 1 until ts.task.targetCount
                val title = if (resumingReview) "남은 대본 ${st.reviewIds.size}개 이어서 복습" else ts.task.title
                val colors = MaterialTheme.colorScheme
                val foreground = when {
                    next -> colors.onPrimary
                    ts.done -> colors.onSurfaceVariant
                    else -> colors.onSurface
                }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(if (next) colors.primary else Color.Transparent)
                        .clickable(enabled = ts.task.type != TaskType.REVIEW || st.reviewIds.isNotEmpty(), role = Role.Button) { open(ts.task) }
                        .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (ts.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (ts.done) "완료" else null, tint = foreground,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (next) "다음 · $title" else title,
                        style = if (next) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f), color = foreground,
                    )
                    if (!resumingReview) Text("${ts.task.minutes}분", style = MaterialTheme.typography.labelMedium, color = foreground)
                }
            }
        }
        OutlinedButton(onClick = onRecall, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("질문 듣고 답하기") }

        PermissionBanners(st.alarmNeedsExact, onSettings)
        ExamReviewBanner(st.examsToReview, onExams)
        st.awaitingConfirm?.let { r ->
            Banner("${r.type.label} ${ExamSchedule.formatWhen(r)} 시험에 응시했나요? 확인하면 다음 시험 기준으로 계획이 바뀝니다.", "확인", tone = MaterialTheme.colorScheme.secondary, onAction = onExams)
        }

        if (plan == null) return@Column
        reviews.firstOrNull { (it.speakingReviewDueEpochDay ?: Long.MAX_VALUE) <= today }?.let { review ->
            Banner(
                "다시 말할 질문이 있습니다.", "다시 말하기",
                onAction = { onSpeakingReview(review.id) },
            )
        }

        st.debriefDue?.let { d ->
            Banner("${d.monthValue}/${d.dayOfMonth} ${st.debriefType} 시험 복기 (5분)".replace("  ", " "), "복기", tone = MaterialTheme.colorScheme.secondary) {
                onDebrief(d, st.debriefRegistrationId)
            }
        }

        if (st.pendingRevisions > 0) {
            Banner("승인 대기 개선안 ${st.pendingRevisions}개", "검토", tone = MaterialTheme.colorScheme.primary, onAction = onReviewRevisions)
        }

        SectionCard(title = "시험 관리와 추가 연습", collapsible = true, initiallyExpanded = false) {
            TextButton(onClick = onDiagnostic) { Text(PracticeMode.DIAGNOSTIC.label) }
            TextButton(onClick = onMock) { Text("모의시험") }
            TextButton(onClick = onCoach) { Text("AI 코치") }
            ExamStatusCard(st.regs, st.now, st.settings, plan, st.focusExam, onExams)

            st.nextExam?.let { n ->
                // Same rule as the 다음 OPIc 추천 screen: a booked or planned OPIc replaces the suggested date.
                val booked = ExamSchedule.nextOfType(st.regs, ExamType.OPIC, st.now)?.date
                SectionCard(Modifier.clickable(onClick = onNextExam), title = "다음 OPIc 추천", trailing = { SourceTag(if (booked != null) "학습용" else n.verdict) }) {
                    val rec = n.recommended
                    Text(
                        if (booked != null) "${booked.monthDay()} OPIc 준비 중 · 준비도 ${n.gatesPassed}/${n.gatesTotal}"
                        else if (rec != null) "추천 ${rec.monthValue}/${rec.dayOfMonth} · 접수 ${n.registerBy?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "-"}까지 · 준비도 ${n.gatesPassed}/${n.gatesTotal}"
                        else "응시 가능한 날짜가 없습니다",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (st.weaknesses.isNotEmpty()) SectionCard(title = "가장 부족한 역량", trailing = { SourceTag("최근 14일 기록") }) {
            st.weaknesses.forEachIndexed { i, w ->
                Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onSpeakMode(w.competency.practiceMode) }.padding(vertical = 4.dp)) {
                    Text("${i + 1}. ${w.competency.label}", style = MaterialTheme.typography.bodyLarge)
                    Text(w.evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            st.plan?.weakness?.let { Text("오늘 계획의 '${it.competency.practiceTitle}' 항목이 1번 약점을 겨냥합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            Text("기기 음성 인식과 멈춤 감지로 잰 학습용 신호이며 등급 평가가 아닙니다. 발음은 측정하지 않습니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (st.weak.isNotEmpty()) SectionCard(title = "취약 주제") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                st.weak.forEach { w -> Pill(w.topic, false) { w.scriptId?.let(onScript) ?: onSpeak() } }
            }
        }

        st.lastMock?.let { m ->
            SectionCard(Modifier.clickable { onRecentMock(m) }, title = "최근 모의시험", trailing = { SourceTag(if (m.reportJson != null) "결과 보기" else "다시 시작") }) {
                val date = Instant.ofEpochMilli(m.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Text("$date · ${m.questionCount}문항 · ${if (m.finishedAt != null) "완료" else "중단"}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionBanners(alarmNeedsExact: Boolean, onSettings: () -> Unit) {
    val context = LocalContext.current
    var permTick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    // key(permTick) re-runs the permission checks after the system dialog closes.
    key(permTick) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        Banner("아침 알람을 받으려면 알림을 허용하세요.", "허용") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    } else if (!Notifications.canPost(context)) {
        Banner("알림이 꺼져 있습니다.", "설정", onAction = onSettings)
    }
    if (alarmNeedsExact && !AlarmScheduler.canExact(context)) {
        Banner("알람이 몇 분 늦을 수 있습니다.", "설정", onAction = onSettings)
    }
    }
}

/** Top of Home: the nearest registered exam. Nothing here is a recommendation. */
@Composable
private fun NextExamHeader(r: ExamRegistrationEntity?, dLabel: String, planned: ExamRegistrationEntity?, today: java.time.LocalDate, modifier: Modifier) {
    Column(modifier) {
        val plannedDate = planned?.date
        if (r == null && planned != null && plannedDate != null) {
            Text("${planned.state.label}: ${planned.type.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${plannedDate.monthDay()} · D-${plannedDate.toEpochDay() - today.toEpochDay()}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            return@Column
        }
        if (r == null) {
            Text("접수 완료 일정 없음", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text("Next Exam: ${r.type.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ExamSchedule.formatWhen(r), style = MaterialTheme.typography.titleMedium)
        Text(dLabel, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        if (r.location.isNotBlank()) Text(r.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val start = r.startTime
        if (r.type == ExamType.SAIL && start != null) {
            Text("입실 ${start.minusMinutes(10)}까지 (시작 10분 전)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** Facts and unverified items, labelled as such. */
@Composable
private fun ExamStatusCard(regs: List<ExamRegistrationEntity>, now: java.time.ZonedDateTime, s: Settings, plan: DayPlan, focused: ExamRegistrationEntity?, onExams: () -> Unit) {
    val today = now.toLocalDate()
    SectionCard(Modifier.clickable(onClick = onExams), title = "시험 현황", trailing = { SourceTag("일정 보기") }) {
        val planned = if (focused == null) ExamType.selectable.mapNotNull { ExamSchedule.nextOfType(regs, it, now) }
            .filter { it.date != null }.minByOrNull { it.date!! } else null
        NextExamHeader(focused, plan.dLabel, planned, today, Modifier.fillMaxWidth())
        for (type in ExamType.selectable) {
            val next = ExamSchedule.nextOfType(regs, type, now)
            next?.takeUnless { it.id == (focused ?: planned)?.id }?.let { Bullet("${type.label} ${it.date?.monthDay() ?: it.dateLocal} · ${it.state.label}") }
        }
        val retake = com.opicih.coach.learning.RetakeSummaries.of(s, regs, today)
        ExamSchedule.lastCompleted(regs, ExamType.SAIL)?.let { last ->
            last.registration.resultGrade?.let { Bullet("${last.date.monthDay()} SAIL 성적 $it") }
        }
        retake.sailEarliest?.takeIf { !it.isBefore(today) }?.let { Bullet("SAIL 일반 재응시 ${it.monthDay()}부터") }
        s.gradeDeadline?.let { d ->
            Bullet("다음 시험 목표 ${s.targetGrade.code} · 최종 목표 ${s.profile.finalGoal.code} (${d.monthDay()}까지)")
        }
        val examMinutes = plan.tasks.filter { it.type != TaskType.MORNING && it.type != TaskType.REVIEW }.sumOf { it.minutes }
        val shared = plan.totalMinutes - examMinutes
        val who = plan.examType?.label ?: "장기 학습"
        Bullet("오늘 권장 ${plan.totalMinutes}분 · $who 유형 훈련 ${examMinutes}분, 공통(표현·스크립트) ${shared}분")
    }
}
