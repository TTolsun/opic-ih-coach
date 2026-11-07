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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.alarm.Notifications
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.PlanGenerator
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.TaskType
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.ProgressRow
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import java.time.LocalDate

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
) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val s by c.settings.flow.collectAsState(initial = Settings())
    val scripts by c.dao.scriptsFlow().collectAsState(initial = emptyList())
    val attempts by c.dao.attemptsFlow().collectAsState(initial = emptyList())
    val logs by c.dao.dailyLogsFlow().collectAsState(initial = emptyList())
    val mocks by c.dao.mocksFlow().collectAsState(initial = emptyList())
    val pending by c.dao.pendingRevisionsFlow().collectAsState(initial = emptyList())
    var permTick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    val today = LocalDate.now()
    val todayDay = today.toEpochDay()
    val due = scripts.filter { it.dueEpochDay <= todayDay }.sortedBy { it.masteryScore }
    val mockDone = logs.any { it.mockDone && it.epochDay >= todayDay - 7 }
    val plan = PlanGenerator.today(today, s.examDate, s.studyLevel.minutes, due.size, mockDone)
    val log = logs.firstOrNull { it.epochDay == todayDay }
    val reviewGoal = plan.tasks.firstOrNull { it.type == TaskType.REVIEW }?.targetCount ?: 1
    val completion = ProgressCalculator.todayCompletion(log, s.studyLevel.minutes, reviewGoal)
    val streak = ProgressCalculator.streak(logs, todayDay)
    val readiness = ProgressCalculator.ihReadiness(scripts, attempts)
    val weak = ProgressCalculator.weakTopics(attempts, s.surveyTopics)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("첫 시험 ${s.examDate.monthValue}/${s.examDate.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(plan.dLabel, style = MaterialTheme.typography.displaySmall)
            }
            IconButton(onClick = onCoach) { Icon(Icons.AutoMirrored.Filled.Chat, "AI 코치") }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "설정") }
        }

        permTick.let { }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Banner("알림 권한이 없어 아침 알람이 표시되지 않습니다. 앱을 열면 홈 화면에서 오늘 미션을 확인할 수 있습니다.", "허용") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (!Notifications.canPost(context)) {
            Banner("시스템 설정에서 이 앱의 알림이 꺼져 있습니다.", "설정", onAction = onSettings)
        }
        if (s.alarmEnabled && s.useExactAlarm && !AlarmScheduler.canExact(context)) {
            Banner("정확한 알람 권한이 없어 알람이 몇 분 늦게 울릴 수 있습니다.", "설정", onAction = onSettings)
        }

        Button(onClick = onSpeak, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Icon(Icons.Filled.Mic, null); Spacer(Modifier.width(8.dp)); Text("지금 바로 말하기", style = MaterialTheme.typography.titleMedium)
        }

        SectionCard(title = "오늘의 훈련 · ${plan.focus.titleKo}", trailing = { Text("${(completion * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }) {
            Text(plan.focus.detailKo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            plan.tasks.forEach { t ->
                val done = when (t.type) {
                    TaskType.MORNING -> log?.morningDone == true
                    TaskType.REVIEW -> (log?.scriptsReviewed ?: 0) >= t.targetCount
                    TaskType.MOCK -> log?.mockDone == true
                    else -> (log?.attempts ?: 0) >= t.targetCount + (if ((log?.scriptsReviewed ?: 0) > 0) reviewGoal else 0)
                }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        when (t.type) { TaskType.MORNING -> onMorning(); TaskType.MOCK -> onMock(); TaskType.IMPROVE -> onScripts(); else -> onSpeak() }
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(t.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${t.minutes}분", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("완료율은 실제 발화 시간과 복습 기록으로만 계산합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        FilledTonalButton(onClick = onMorning, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.WbSunny, null); Spacer(Modifier.width(8.dp)); Text(if (log?.morningDone == true) "아침 표현 루틴 (완료)" else "아침 표현 루틴 시작 (5~10분)")
        }

        SectionCard(title = "IH 준비도", trailing = { SourceTag("학습용 참고") }) {
            ProgressRow("스크립트 숙련 + 최근 자유 발화 + 주제 범위", readiness / 100f, "$readiness / 100")
            Spacer(Modifier.height(8.dp))
            Row {
                Text("연속 학습 ", style = MaterialTheme.typography.bodyMedium)
                Text("${streak}일", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.weight(1f))
                Text("총 발화 ${attempts.sumOf { if (it.speechMs > 0) it.speechMs else it.durationMs } / 60000}분", style = MaterialTheme.typography.bodyMedium)
            }
            Text("이 수치는 공식 OPIc 등급 예측이 아닙니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (pending.isNotEmpty()) {
            Banner("승인 대기 중인 개선안 ${pending.size}개가 있습니다. 원본은 승인 전까지 그대로 유지됩니다.", "검토", tone = MaterialTheme.colorScheme.primary, onAction = onScripts)
        }

        SectionCard(title = "복습 예정 스크립트 ${due.size}개") {
            if (due.isEmpty()) Text("오늘 복습할 스크립트가 없습니다. 자유 발화로 응용력을 키우세요.", style = MaterialTheme.typography.bodyMedium)
            due.take(4).forEach { sc ->
                Row(Modifier.fillMaxWidth().clickable { onScript(sc.id) }.padding(vertical = 6.dp)) {
                    Text("${sc.topic} · ${sc.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(sc.mastery.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (weak.isNotEmpty()) SectionCard(title = "취약 주제") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { weak.forEach { Pill(it, false, onSpeak) } }
        }

        mocks.firstOrNull()?.let { m ->
            SectionCard(title = "최근 모의시험") {
                Text("${java.time.Instant.ofEpochMilli(m.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()} · ${m.questionCount}문항 · ${if (m.finishedAt != null) "완료" else "중단"}", style = MaterialTheme.typography.bodyMedium)
                Bullet("결과는 Mock Test 탭에서 확인합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
