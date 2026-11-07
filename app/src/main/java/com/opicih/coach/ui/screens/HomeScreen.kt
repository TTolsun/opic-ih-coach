package com.opicih.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.alarm.Notifications
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.DayPlan
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.PlanTask
import com.opicih.coach.learning.TaskType
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.ProgressRow
import com.opicih.coach.ui.components.InfoButton
import com.opicih.coach.ui.components.InfoTopics
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import java.time.Instant
import java.time.ZoneId

/**
 * Home answers one question: "what do I do now?". The first unfinished task is the single primary button;
 * everything else is status or a shortcut to a concrete script.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onSpeak: () -> Unit,
    onMorning: () -> Unit,
    onScript: (Long) -> Unit,
    onMock: () -> Unit,
    onScripts: () -> Unit,
    onCoach: () -> Unit,
    onSettings: () -> Unit,
    onNextExam: () -> Unit,
    onExams: () -> Unit = {},
    /** Opens speaking with the task's PracticeMode name (null = default; SAIL_CONVERSATION = SAIL screen). */
    onSpeakMode: (String?) -> Unit = { onSpeak() },
) {
    val vm: HomeViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val plan = st.plan
    fun open(t: PlanTask) = when (t.type) {
        TaskType.MORNING -> onMorning()
        TaskType.MOCK -> onMock()
        TaskType.IMPROVE -> onScripts()
        TaskType.REVIEW, TaskType.SPEAK, TaskType.FREE, TaskType.ROLEPLAY -> onSpeakMode(t.mode)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            NextExamHeader(st.focusExam, plan?.dLabel.orEmpty(), Modifier.weight(1f).clickable(onClick = onExams))
            IconButton(onClick = onCoach) { Icon(Icons.AutoMirrored.Filled.Chat, "AI 코치") }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "설정") }
        }

        PermissionBanners(st.alarmNeedsExact, onSettings)

        ExamReviewBanner(st.examsToReview, onExams)
        st.awaitingConfirm?.let { r ->
            Banner("${r.type.label} ${ExamSchedule.formatWhen(r)} 시험에 응시했나요? 확인하면 다음 시험 기준으로 계획이 바뀝니다.", "확인", tone = MaterialTheme.colorScheme.secondary, onAction = onExams)
        }

        if (plan == null) return@Column

        // 1) The one thing to do now.
        NextActionButton(st.next, onClick = { st.next?.let { open(it) } ?: onSpeak() })

        // 2) Today's checklist (same tasks, with progress). No duplicate shortcut buttons below it.
        SectionCard(
            title = "오늘의 훈련 · ${plan.focus.titleKo}",
            trailing = { Text("${(st.completion * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) },
        ) {
            st.tasks.forEach { ts ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open(ts.task) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (ts.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (ts.done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        ts.task.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                        color = if (ts.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("${ts.task.minutes}분", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        st.debriefDue?.let { d ->
            Banner("${d.monthValue}/${d.dayOfMonth} ${st.debriefType} 시험 복기 (5분)".replace("  ", " "), "복기", tone = MaterialTheme.colorScheme.secondary, onAction = onNextExam)
        }

        if (st.pendingRevisions > 0) {
            Banner("승인 대기 개선안 ${st.pendingRevisions}개", "검토", tone = MaterialTheme.colorScheme.primary, onAction = onScripts)
        }

        // 3) Status: one card, one caveat.
        SectionCard(title = "내 상태", trailing = { InfoButton(InfoTopics.MEASUREMENT) }) {
            Row {
                Stat("연속 학습", "${st.streak}일", Modifier.weight(1f))
                Stat("누적 발화", "${st.totalSpeakingMin}분", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            ProgressRow("IH 준비도", st.readiness / 100f, "${st.readiness} / 100")
        }

        ExamStatusCard(st.regs, st.now, st.settings, plan, onExams)

        st.nextExam?.let { n ->
            SectionCard(Modifier.clickable(onClick = onNextExam), title = "다음 OPIc 추천 (학습용)", trailing = { SourceTag(n.verdict) }) {
                val rec = n.recommended
                Text(
                    if (rec != null) "추천 ${rec.monthValue}/${rec.dayOfMonth} · 접수 ${n.registerBy?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "-"}까지 · 준비도 ${n.gatesPassed}/${n.gatesTotal}"
                    else "응시 가능한 날짜가 없습니다",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // 4) Concrete shortcuts: each opens a specific script.
        if (st.due.isNotEmpty()) SectionCard(title = "복습 예정 스크립트 ${st.due.size}개") {
            st.due.take(4).forEach { sc ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onScript(sc.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${sc.topic} · ${sc.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(sc.mastery.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SectionCard(Modifier.clickable(onClick = onMock), title = "최근 모의시험", trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "결과 보기") }) {
                val date = Instant.ofEpochMilli(m.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Text("$date · ${m.questionCount}문항 · ${if (m.finishedAt != null) "완료" else "중단"}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NextActionButton(next: PlanTask?, onClick: () -> Unit) {
    val (icon, label) = when (next?.type) {
        null -> Icons.Filled.Mic to "자유 발화 더 하기"
        TaskType.MORNING -> Icons.Filled.WbSunny to "${next.title} 시작 · ${next.minutes}분"
        TaskType.MOCK -> Icons.Filled.Assignment to "${next.title} 시작"
        TaskType.IMPROVE -> Icons.AutoMirrored.Filled.MenuBook to "${next.title} · ${next.minutes}분"
        else -> Icons.Filled.Mic to "${next.title} · ${next.minutes}분"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (next == null) "오늘 완료" else "지금 할 일", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigButton(icon, label, onClick)
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
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
private fun NextExamHeader(r: ExamRegistrationEntity?, dLabel: String, modifier: Modifier) {
    Column(modifier) {
        if (r == null) {
            Text("등록된 다음 시험 없음", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("시험 추가", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
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
private fun ExamStatusCard(regs: List<ExamRegistrationEntity>, now: java.time.ZonedDateTime, s: Settings, plan: DayPlan, onExams: () -> Unit) {
    val today = now.toLocalDate()
    SectionCard(Modifier.clickable(onClick = onExams), title = "시험 현황", trailing = { SourceTag("일정 관리") }) {
        val nextOpic = ExamSchedule.nextOfType(regs, ExamType.OPIC, now)
        Bullet("다음 OPIc 일정: " + (nextOpic?.let { "${ExamSchedule.formatWhen(it)} · ${statusLabel(it.state)}" } ?: "미정"))
        val retake = com.opicih.coach.learning.RetakeSummaries.of(s, regs, today)
        Bullet("OPIc Waiver: " + when (val n = retake.opicWaiver.available) { null -> "입력 안 됨"; else -> "사용 가능 ${n}회 (사용자 입력 기준)" })
        retake.opicPaths.firstOrNull { !it.useWaiver }?.earliest?.let { Bullet("OPIc 응시 가능: ${it.monthValue}/${it.dayOfMonth}부터 (공식 규정, 실제 회차 확인 전)") }
        ExamSchedule.lastCompleted(regs, ExamType.OPIC)?.let { last ->
            Bullet("마지막 OPIc 응시: ${if (last.exact) last.date.toString() else "${last.registration.dateLocal} (일자 미입력)"}")
        }
        Bullet("다음 SAIL 응시 가능일: " + (retake.sailEarliest?.let { "${it.monthValue}/${it.dayOfMonth}부터로 가정 (사내 규정 미검증)" } ?: "규정 검증 필요"))
        s.gradeDeadline?.let { d ->
            val left = java.time.temporal.ChronoUnit.DAYS.between(today, d)
            Bullet("목표 IH 기한 ${d.monthValue}/${d.dayOfMonth}까지 ${left}일")
        }
        val examMinutes = plan.tasks.filter { it.type != TaskType.MORNING && it.type != TaskType.REVIEW }.sumOf { it.minutes }
        val shared = plan.totalMinutes - examMinutes
        val who = plan.examType?.label ?: "장기 학습"
        Bullet("오늘 권장 ${plan.totalMinutes}분 · $who 유형 훈련 ${examMinutes}분, 공통(표현·스크립트) ${shared}분")
    }
}
