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
import com.opicih.coach.learning.PlanTask
import com.opicih.coach.learning.TaskType
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.ProgressRow
import com.opicih.coach.ui.components.ReferenceNote
import com.opicih.coach.ui.components.SectionCard
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
) {
    val vm: HomeViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val plan = st.plan
    fun open(t: TaskType) = when (t) {
        TaskType.MORNING -> onMorning()
        TaskType.MOCK -> onMock()
        TaskType.IMPROVE -> onScripts()
        TaskType.REVIEW, TaskType.SPEAK, TaskType.FREE, TaskType.ROLEPLAY -> onSpeak()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("첫 시험 ${st.examDate.monthValue}/${st.examDate.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(plan?.dLabel.orEmpty(), style = MaterialTheme.typography.displaySmall)
            }
            IconButton(onClick = onCoach) { Icon(Icons.AutoMirrored.Filled.Chat, "AI 코치") }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "설정") }
        }

        PermissionBanners(st.alarmNeedsExact, onSettings)

        if (plan == null) return@Column

        // 1) The one thing to do now.
        NextActionButton(st.next, onClick = { st.next?.let { open(it.type) } ?: onSpeak() })

        // 2) Today's checklist (same tasks, with progress). No duplicate shortcut buttons below it.
        SectionCard(
            title = "오늘의 훈련 · ${plan.focus.titleKo}",
            trailing = { Text("${(st.completion * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) },
        ) {
            Text(plan.focus.detailKo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            st.tasks.forEach { ts ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open(ts.task.type) },
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

        if (st.pendingRevisions > 0) {
            Banner("승인 대기 중인 개선안 ${st.pendingRevisions}개가 있습니다. 원본은 승인 전까지 그대로 유지됩니다.", "검토", tone = MaterialTheme.colorScheme.primary, onAction = onScripts)
        }

        // 3) Status: one card, one caveat.
        SectionCard(title = "내 상태") {
            Row {
                Stat("연속 학습", "${st.streak}일", Modifier.weight(1f))
                Stat("누적 발화", "${st.totalSpeakingMin}분", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            ProgressRow("IH 준비도", st.readiness / 100f, "${st.readiness} / 100")
            Spacer(Modifier.height(6.dp))
            ReferenceNote("스크립트 숙련 + 최근 자유 발화 + 주제 범위로 계산한 학습용 참고 지표입니다. OPIc 공식 등급 예측이 아닙니다.")
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

        if (st.weak.isNotEmpty()) SectionCard(title = "취약 주제") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                st.weak.forEach { w -> Pill(w.topic, false) { w.scriptId?.let(onScript) ?: onSpeak() } }
            }
            Spacer(Modifier.height(6.dp))
            ReferenceNote("주제를 누르면 그 주제에서 가장 약한 스크립트를 엽니다.")
        }

        st.lastMock?.let { m ->
            SectionCard(Modifier.clickable(onClick = onMock), title = "최근 모의시험") {
                val date = Instant.ofEpochMilli(m.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Text("$date · ${m.questionCount}문항 · ${if (m.finishedAt != null) "완료" else "중단"}", style = MaterialTheme.typography.bodyMedium)
                Text("눌러서 결과 보기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun NextActionButton(next: PlanTask?, onClick: () -> Unit) {
    val (icon, label) = when (next?.type) {
        null -> Icons.Filled.Mic to "오늘 목표 완료 · 자유 발화 더 하기"
        TaskType.MORNING -> Icons.Filled.WbSunny to "${next.title} 시작 · ${next.minutes}분"
        TaskType.MOCK -> Icons.Filled.Assignment to "${next.title} 시작"
        TaskType.IMPROVE -> Icons.AutoMirrored.Filled.MenuBook to "${next.title} · ${next.minutes}분"
        else -> Icons.Filled.Mic to "${next.title} · ${next.minutes}분"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (next == null) "오늘 할 일을 모두 끝냈습니다" else "지금 할 일", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Banner("알림 권한이 없어 아침 알람이 표시되지 않습니다.", "허용") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    } else if (!Notifications.canPost(context)) {
        Banner("시스템 설정에서 이 앱의 알림이 꺼져 있습니다.", "설정", onAction = onSettings)
    }
    if (alarmNeedsExact && !AlarmScheduler.canExact(context)) {
        Banner("정확한 알람 권한이 없어 알람이 몇 분 늦게 울릴 수 있습니다.", "설정", onAction = onSettings)
    }
    }
}
