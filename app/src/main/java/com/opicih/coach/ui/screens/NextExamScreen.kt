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
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationSource
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.learning.ExamPolicy
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.RetakeSummaries
import com.opicih.coach.learning.RetakeSummary
import androidx.compose.material3.Switch
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

/**
 * Base date for the OPIc retake advice: the last COMPLETED OPIc sitting (month-only dates use the month's last day,
 * the conservative value). Other exam types (SAIL) never count. Falls back to old v2 exam rows, then to today.
 * The legacy Settings.examDate is no longer used: it could hold a recommendation, not a sitting.
 */
fun lastExamDate(s: Settings, exams: List<ExamEntity>, today: LocalDate, regs: List<ExamRegistrationEntity> = emptyList()): LocalDate {
    ExamSchedule.lastCompleted(regs, ExamType.OPIC)?.let { return it.date }
    val linkedNonOpic = regs.filter { it.type != ExamType.OPIC }.mapNotNull { it.legacyExamId }.toSet()
    return exams.filter { it.id !in linkedNonOpic }.map { LocalDate.ofEpochDay(it.epochDay) }.filter { !it.isAfter(today) }.maxOrNull() ?: today
}

/** Most recent exam day (any type) that is over or today, for the debrief card. */
fun lastSatDate(regs: List<ExamRegistrationEntity>, today: LocalDate): LocalDate? =
    regs.filter { it.state == RegistrationStatus.REGISTERED || it.state == RegistrationStatus.COMPLETED }
        .mapNotNull { it.date }.filter { !it.isAfter(today) }.maxOrNull()

/** OPIc advice: official retake rule (25 blocked days, exam day excluded); waiver only when one is available and the user plans to use it. */
fun rulesOf(s: Settings, waiverAvailable: Boolean = false) = RetestAdvisor.Rules(
    retakeDays = ExamPolicy.opic().blockedDays, verified = true, resultLagDays = s.resultLagDays, useWaiver = s.planWithWaiver && waiverAvailable,
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
    val regs by c.exams.flow.collectAsState(initial = emptyList())
    var message by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val last = lastExamDate(s, exams, today, regs)
    val sinceMs = last.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val readiness = ProgressCalculator.readiness(attempts, logs, last.toEpochDay(), sinceMs)
    val retake = RetakeSummaries.of(s, regs, today)
    val advice = RetestAdvisor.advise(today, last, rulesOf(s, retake.waiverAvailable), readiness)
    val debriefDate = lastSatDate(regs, today) ?: last
    val examRow = exams.firstOrNull { it.epochDay == debriefDate.toEpochDay() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        message?.let { Banner(it, "닫기", tone = MaterialTheme.colorScheme.primary) { message = null } }

        SectionCard(title = "다음 시험 추천", trailing = { SourceTag("학습용 판단") }) {
            Text(advice.verdict.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("기준 OPIc 응시", "${last.monthValue}/${last.dayOfMonth}", hint = ExamSchedule.lastCompleted(regs, ExamType.OPIC)?.let { if (it.exact) null else "월말로 가정" })
                MetricTile("재응시 가능", "${advice.earliest.monthValue}/${advice.earliest.dayOfMonth}", hint = if (rulesOf(s, retake.waiverAvailable).useWaiver) "Waiver 사용" else "공식 규정")
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
                            // A recommendation becomes a PLANNED (not registered) OPIc entry. It never overwrites a booking.
                            c.exams.save(
                                ExamRegistrationEntity(
                                    examType = ExamType.OPIC.name, dateLocal = rec.toString(), status = RegistrationStatus.PLANNED.name,
                                    memo = "학습 추천일을 계획으로 추가함. 실제 접수 가능 회차는 확인 전입니다.", sourceType = RegistrationSource.USER.name,
                                ),
                                "추천일을 계획으로 추가",
                            )
                            message = "$rec OPIc을 '접수 예정 (미확정)'으로 추가했습니다. 실제로 접수하면 시험 일정에서 '접수 완료'로 바꾸세요. 접수 완료 전에는 홈 D-day가 바뀌지 않습니다."
                        }
                    }) { Text("계획으로 추가") }
                }
            }
        }

        RetakePathsCard(retake, s) { v -> scope.launch { c.settings.update { it.copy(planWithWaiver = v) } } }

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
            Bullet("OPIc: 응시일 다음 날부터 25일 제한, D+26부터 응시 가능 (${ExamPolicy.OPIC_RULE_SOURCE})")
            Bullet("SAIL: 응시일 다음 날부터 ${s.retakeDays}일 제한으로 가정 · ${if (s.retakeVerified) "사용자 확인" else "사내 규정 미검증"} · OPIc과 ${if (s.sailSharesOpicLimit) "제한 공유" else "독립 계산"}")
            Bullet("결과 발표까지 ${s.resultLagDays}일 가정 · 접수는 응시 ${s.registerLeadDays}일 전까지로 가정")
            Bullet("등급 마감일 ${s.gradeDeadline ?: "없음"}")
            Bullet(if (s.examSlots.isEmpty()) "회차 제한 없음 (원하는 날짜 응시 가능으로 계산)" else "사내 회차: ${s.slotDates().joinToString()}")
        }

        val debriefReg = regs.firstOrNull { it.date == debriefDate && it.type != ExamType.UNKNOWN &&
            (it.state == RegistrationStatus.REGISTERED || it.state == RegistrationStatus.COMPLETED) }
        if (debriefReg?.type == ExamType.SAIL) SailDebriefCard(debriefReg, today, examRow)
        else DebriefCard(debriefDate, today, examRow, s)
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

/** "Q: ..." lines + "[템포] ..." in ExamEntity.note. Kept as plain text so old app versions can still show it. */
object SailDebriefNote {
    fun encode(questions: String, tempo: String): String = buildString {
        questions.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { append("Q: ").append(it.removePrefix("Q:").trim()).append('\n') }
        if (tempo.isNotBlank()) append("[템포] ").append(tempo.trim())
    }.trim()

    fun questions(note: String): String = note.lines().filter { it.trim().startsWith("Q:") }.joinToString("\n") { it.trim().removePrefix("Q:").trim() }
    fun tempo(note: String): String = note.lines().firstOrNull { it.startsWith("[템포]") }?.removePrefix("[템포]")?.trim().orEmpty()
}

/**
 * SAIL debrief ("정찰 기록"): the real questions and tempo are the best data the app can get about SAIL,
 * because its item format is not public. Saved questions feed the SAIL conversation practice as openers.
 */
@Composable
private fun SailDebriefCard(reg: ExamRegistrationEntity, today: LocalDate, row: ExamEntity?) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val date = reg.date ?: return
    if (today.isBefore(date)) {
        SectionCard(title = "SAIL 시험 직후 복기") {
            Text("${date} 시험이 끝나면 1시간 안에 실제 질문을 적어 두세요. 적은 질문은 SAIL 대화 연습의 첫 질문으로 다시 나옵니다.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    var questions by remember(row?.id) { mutableStateOf(SailDebriefNote.questions(row?.note.orEmpty())) }
    var stuck by remember(row?.id) { mutableStateOf(row?.stuckPoints.orEmpty()) }
    var tempo by remember(row?.id) { mutableStateOf(SailDebriefNote.tempo(row?.note.orEmpty())) }
    var feeling by remember(row?.id) { mutableIntStateOf(row?.feeling?.takeIf { it > 0 } ?: 3) }
    var grade by remember(row?.id) { mutableStateOf(row?.resultGrade ?: reg.resultGrade.orEmpty()) }
    var saved by remember { mutableStateOf<String?>(if (row?.debriefAt != null) "저장된 복기가 있습니다. 수정 후 다시 저장할 수 있습니다." else null) }

    SectionCard(title = "${date.monthValue}/${date.dayOfMonth} SAIL 시험 복기", trailing = { SourceTag("정찰 기록") }) {
        OutlinedTextField(questions, { questions = it }, label = { Text("기억나는 질문 (한 줄에 하나, 영어 그대로)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(stuck, { stuck = it }, label = { Text("답하지 못했거나 막힌 질문") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tempo, { tempo = it }, label = { Text("형식·템포 (준비 시간, 추가 질문 수, 시험관 반응 등)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("체감 난이도 ${feeling} (1 매우 어려움 ~ 5 쉬움)", style = MaterialTheme.typography.labelLarge)
        Slider(feeling.toFloat(), { feeling = it.toInt() }, valueRange = 1f..5f, steps = 3)
        OutlinedTextField(grade, { grade = it }, label = { Text("결과 등급 (나오면 입력)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                val base = row ?: ExamEntity(epochDay = date.toEpochDay(), type = "SAIL")
                val updated = base.copy(topicsSeen = "SAIL", note = SailDebriefNote.encode(questions, tempo), stuckPoints = stuck.trim(), feeling = feeling,
                    resultGrade = grade.trim().ifBlank { null }, debriefAt = System.currentTimeMillis())
                val examId = if (row == null) c.dao.insertExam(updated) else { c.dao.updateExam(updated); row.id }
                // Writing a debrief means the user sat the exam.
                val status = if (reg.state == RegistrationStatus.REGISTERED) RegistrationStatus.COMPLETED.name else reg.status
                c.exams.save(reg.copy(status = status, resultGrade = grade.trim().ifBlank { reg.resultGrade }, legacyExamId = reg.legacyExamId ?: examId),
                    "복기 저장 (응시 완료)")
                val n = questions.lines().count { it.isNotBlank() }
                saved = "복기를 저장했습니다. 질문 ${n}개가 SAIL 대화 연습의 첫 질문 후보로 들어갑니다."
            }
        }) { Text("복기 저장") }
        saved?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
    }
}

/** Without-waiver vs with-waiver paths. Dates are rule-eligible days, not confirmed sessions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetakePathsCard(r: RetakeSummary, s: Settings, onPlanWithWaiver: (Boolean) -> Unit) {
    SectionCard(title = "OPIc 재응시 경로", trailing = { SourceTag("규정상 가능일") }) {
        val w = r.opicWaiver
        Text(
            "Waiver: " + when (w.available) { null -> "입력 안 됨"; 0 -> "사용 가능 0회"; else -> "사용 가능 ${w.available}회" } +
                (w.regrantOn?.let { " · 다시 생기는 날 $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("기준 응시: ${r.opicLast ?: "기록 없음"}" + (r.latestUseful?.let { " · 결과가 마감 전에 나오는 마지막 응시일 $it (결과 ${s.resultLagDays}일 가정)" } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        r.opicPaths.forEach { p ->
            Text(if (p.useWaiver) "Waiver 사용" else "Waiver 없이", style = MaterialTheme.typography.labelLarge)
            Text(
                if (p.earliest == null) "마감 전에 응시할 수 있는 날이 없습니다."
                else "가장 빠른 날 ${p.earliest} · 마감 전 기회 ${p.opportunities}회 (${p.eligibleDates.joinToString { "${it.monthValue}/${it.dayOfMonth}" }})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (r.opicPaths.none { it.useWaiver } && r.waiverAvailable) {
            Text("재응시 제한이 이미 지나서 지금은 Waiver를 쓸 필요가 없습니다. 다음 응시 직후에 의미가 생깁니다.", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("다음 OPIc 추천에 Waiver 사용", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = s.planWithWaiver, onCheckedChange = onPlanWithWaiver, enabled = r.waiverAvailable)
        }
        Spacer(Modifier.height(4.dp))
        (r.opicPaths.lastOrNull()?.assumptions.orEmpty() + r.opicWaiver.notes).distinct().forEach {
            Bullet(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
