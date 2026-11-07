package com.opicih.coach.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.data.db.ExamEntity
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.RetestAdvisor
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.MetricTile
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Which exam the advice is about: the most recent exam on or before today, otherwise the upcoming first exam. */
fun lastExamDate(s: Settings, exams: List<ExamEntity>, today: LocalDate): LocalDate {
    val past = exams.map { LocalDate.ofEpochDay(it.epochDay) } + listOf(s.examDate)
    return past.filter { !it.isAfter(today) }.maxOrNull() ?: s.examDate
}

fun rulesOf(s: Settings) = RetestAdvisor.Rules(
    retakeDays = s.retakeDays, verified = s.retakeVerified, resultLagDays = s.resultLagDays,
    deadline = s.gradeDeadline, slots = s.slotDates(), registerLeadDays = s.registerLeadDays,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NextExamScreen(onOpenSettings: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState(initial = Settings())
    val exams by c.dao.examsFlow().collectAsState(initial = emptyList())
    val attempts by c.dao.attemptsFlow().collectAsState(initial = emptyList())
    val logs by c.dao.dailyLogsFlow().collectAsState(initial = emptyList())
    var message by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val last = lastExamDate(s, exams, today)
    val sinceMs = last.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val readiness = ProgressCalculator.readiness(attempts, logs, last.toEpochDay(), sinceMs)
    val advice = RetestAdvisor.advise(today, last, rulesOf(s), readiness)
    val examRow = exams.firstOrNull { it.epochDay == last.toEpochDay() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        message?.let { Banner(it, "닫기", tone = MaterialTheme.colorScheme.primary) { message = null } }

        SectionCard(title = "다음 시험 추천", trailing = { SourceTag("학습용 판단") }) {
            Text(advice.verdict.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("기준 시험", "${last.monthValue}/${last.dayOfMonth}")
                MetricTile("재응시 가능", "${advice.earliest.monthValue}/${advice.earliest.dayOfMonth}", hint = if (s.retakeVerified) "확인됨" else "미검증")
                advice.recommended?.let { MetricTile("추천 응시일", "${it.monthValue}/${it.dayOfMonth}") }
                advice.registerBy?.let { MetricTile("접수 권장", "${it.monthValue}/${it.dayOfMonth}까지") }
                advice.latestUseful?.let { MetricTile("마감 전 마지막", "${it.monthValue}/${it.dayOfMonth}", hint = "결과 ${s.resultLagDays}일 가정") }
            }
            Spacer(Modifier.height(10.dp))
            advice.reasons.forEach { Bullet(it) }
            // Rule assumptions (unverified retake interval, register lead time) are shown once, in the rules card below.
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val rec = advice.recommended
                val reg = advice.registerBy
                if (rec != null && reg != null) {
                    Button(onClick = {
                        val ok = AlarmScheduler.scheduleReminder(context, reg, "다음 시험 접수일입니다", "추천 응시일 $rec. 오늘 접수 가능한 회차를 확인하세요. (${advice.verdict.label})")
                        message = if (ok) "$reg 09:00에 접수 알림을 예약했습니다." else "접수 권장일이 오늘이거나 지났습니다. 바로 접수 회차를 확인하세요."
                    }) { Text("접수 알림 예약") }
                    if (advice.verdict != RetestAdvisor.Verdict.WAIT_FIRST_EXAM) OutlinedButton(onClick = {
                        scope.launch {
                            if (examRow == null) c.dao.insertExam(ExamEntity(epochDay = last.toEpochDay(), type = if (exams.isEmpty()) "FIRST" else "RETAKE"))
                            c.settings.update { it.copy(examDate = rec) }
                            message = "다음 시험을 $rec 로 설정했습니다. 홈의 D-day와 훈련 계획이 이 날짜 기준으로 바뀝니다."
                        }
                    }) { Text("이 날짜로 계획") }
                }
            }
        }

        SectionCard(title = "준비도 조건 (${advice.gates.count { it.passed }}/${advice.gates.size})") {
            Text("4개 이상 통과하면 가장 빠른 날짜를 권합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            advice.gates.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (g.passed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (g.passed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.label, style = MaterialTheme.typography.bodyMedium)
                        Text("현재 ${g.value} · 목표 ${g.target}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        SectionCard(title = "적용 중인 규칙", trailing = { OutlinedButton(onClick = onOpenSettings) { Text("수정") } }) {
            Bullet("재응시 간격 ${s.retakeDays}일 · ${if (s.retakeVerified) "확인함" else "미검증 (공개 OPIc 기준 2차 자료)"}")
            Bullet("결과 발표까지 ${s.resultLagDays}일 가정 · 접수는 응시 ${s.registerLeadDays}일 전까지로 가정")
            Bullet("등급 마감일 ${s.gradeDeadline ?: "없음"}")
            Bullet(if (s.examSlots.isEmpty()) "회차 제한 없음 (원하는 날짜 응시 가능으로 계산)" else "사내 회차: ${s.slotDates().joinToString()}")
        }

        DebriefCard(last, today, examRow, s)
        Spacer(Modifier.height(24.dp))
    }
}

/** Post-exam debrief: what came up, where it broke down. Feeds focus topics before the result is out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebriefCard(examDate: LocalDate, today: LocalDate, row: ExamEntity?, s: Settings) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    if (today.isBefore(examDate)) {
        SectionCard(title = "시험 직후 복기") {
            Text("${examDate} 시험이 끝나면 1시간 안에 남기세요.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val topics = remember { mutableStateListOf<String>().apply { row?.topicsSeen?.split(",")?.filter { it.isNotBlank() }?.let { addAll(it) } } }
    val stuck = remember { mutableStateListOf<String>() }
    var notes by remember(row?.id) { mutableStateOf(row?.stuckPoints.orEmpty()) }
    var feeling by remember(row?.id) { mutableIntStateOf(row?.feeling?.takeIf { it > 0 } ?: 3) }
    var level by remember(row?.id) { mutableIntStateOf(row?.selfLevelUsed?.takeIf { it > 0 } ?: s.selfLevel) }
    var grade by remember(row?.id) { mutableStateOf(row?.resultGrade.orEmpty()) }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(row?.debriefAt) { if (row?.debriefAt != null) saved = "저장된 복기가 있습니다. 수정 후 다시 저장할 수 있습니다." }
    val choices = (s.surveyTopics + listOf("돌발: 기술/인터넷", "돌발: 은행/예약", "롤플레이", "비교/이슈", "자기소개")).toList()

    SectionCard(title = "${examDate.monthValue}/${examDate.dayOfMonth} 시험 복기") {
        Text("나온 주제", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { t -> Pill(t, t in topics) { if (t in topics) topics.remove(t) else topics.add(t) } }
        }
        Spacer(Modifier.height(8.dp))
        Text("막히거나 짧게 끝난 주제 (다음 훈련에 우선 반영)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            topics.forEach { t -> Pill(t, t in stuck) { if (t in stuck) stuck.remove(t) else stuck.add(t) } }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(notes, { notes = it }, label = { Text("기억나는 질문, 막힌 순간, 시간 배분") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Text("체감 난이도 ${feeling} (1 매우 어려움 ~ 5 쉬움)", style = MaterialTheme.typography.labelLarge)
        Slider(feeling.toFloat(), { feeling = it.toInt() }, valueRange = 1f..5f, steps = 3)
        Text("고른 Self Assessment 단계 ${level}", style = MaterialTheme.typography.labelLarge)
        Slider(level.toFloat(), { level = it.toInt() }, valueRange = 1f..6f, steps = 4)
        OutlinedTextField(grade, { grade = it }, label = { Text("결과 등급 (나오면 입력: IM2, IM3, IH ...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                val base = row ?: ExamEntity(epochDay = examDate.toEpochDay(), type = if (c.dao.exams().isEmpty()) "FIRST" else "RETAKE")
                val updated = base.copy(topicsSeen = topics.joinToString(","), stuckPoints = notes, feeling = feeling, selfLevelUsed = level,
                    resultGrade = grade.trim().ifBlank { null }, debriefAt = System.currentTimeMillis())
                if (row == null) c.dao.insertExam(updated) else c.dao.updateExam(updated)
                if (stuck.isNotEmpty()) c.settings.update { it.copy(focusTopics = it.focusTopics + stuck) }
                saved = "복기를 저장했습니다." + if (stuck.isNotEmpty()) " 막힌 주제 ${stuck.size}개를 자유 발화·모의시험 우선 주제로 넣었습니다." else ""
            }
        }) { Text("복기 저장") }
        saved?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        Text("다음 시험에서도 Self Assessment와 Survey는 같게 고르세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
