package com.opicih.coach.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.DatePrecision
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationSource
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.ExamRepository
import com.opicih.coach.learning.ExamResults
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.PlanEligibility
import com.opicih.coach.learning.PlannedRetake
import com.opicih.coach.learning.RetakeSummaries
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.InfoButton
import com.opicih.coach.ui.components.InfoTopics
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
private fun statusColor(s: RegistrationStatus): Color = when (s) {
    RegistrationStatus.REGISTERED -> MaterialTheme.colorScheme.primary
    RegistrationStatus.PLANNED -> MaterialTheme.colorScheme.tertiary
    RegistrationStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
    RegistrationStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    RegistrationStatus.NO_SHOW -> MaterialTheme.colorScheme.error
}

private val compactDate = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
internal fun LocalDate.monthDay(): String = "${monthValue}월 ${dayOfMonth}일"

/** Exam schedule: every registration with its status. Bookings, plans and history are listed separately. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExamsScreen(onDebrief: (LocalDate, Long) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val regs by c.exams.flow.collectAsState(initial = emptyList())
    val settings by c.settings.flow.collectAsState(initial = Settings())
    var editing by remember { mutableStateOf<ExamRegistrationEntity?>(null) }
    val now = ExamRepository.nowKst()
    val today = now.toLocalDate()
    val retake = remember(settings, regs, today) { RetakeSummaries.of(settings, regs, today) }
    val plans = remember(settings, regs, today) { RetakeSummaries.plannedRetakes(settings, regs, today) }
    val latestResult = regs.filter { it.state == RegistrationStatus.COMPLETED && !it.resultGrade.isNullOrBlank() }
        .maxByOrNull { it.latestDate ?: LocalDate.MIN }

    val review = ExamSchedule.needsReview(regs)
    val awaiting = ExamSchedule.awaitingConfirmation(regs, now)
    val upcoming = regs.filter { !it.needsReview && it.endsAt()?.isAfter(now) == true &&
        (it.state == RegistrationStatus.REGISTERED || it.state == RegistrationStatus.PLANNED) }.sortedBy { it.earliestDate }
    val past = regs.filter { it !in review && it !in awaiting && it !in upcoming }.sortedByDescending { it.latestDate }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("목표 IH", style = MaterialTheme.typography.labelLarge)
                        settings.gradeDeadline?.let { deadline ->
                            Text("${deadline.monthDay()}까지 성적 확보", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    InfoButton(InfoTopics.EXAM_SCHEDULE)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("웨이버 보유", style = MaterialTheme.typography.bodyMedium)
                    Text("OPIc ${retake.opicWaiver.available?.let { "${it}회" } ?: "미입력"} · SAIL ${retake.sailWaiver.available?.let { "${it}회" } ?: "미입력"}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                latestResult?.let { r ->
                    Text("최근 성적 · ${r.date?.monthDay() ?: r.dateLocal} ${r.type.label} ${r.resultGrade}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (awaiting.isNotEmpty()) SectionCard(title = "응시 여부 확인") {
            Text("시험 시간이 지났습니다. 실제로 응시했는지 앱이 추측하지 않으므로 직접 골라 주세요.", style = MaterialTheme.typography.bodySmall)
            awaiting.forEach { r ->
                Spacer(Modifier.height(8.dp))
                Text("${r.type.label} · ${ExamSchedule.formatWhen(r)}", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { c.exams.setStatus(r.id, RegistrationStatus.COMPLETED, "사용자 확인: 응시함") } }) { Text("응시함") }
                    OutlinedButton(onClick = { scope.launch { c.exams.setStatus(r.id, RegistrationStatus.NO_SHOW, "사용자 확인: 결시") } }) { Text("결시") }
                    OutlinedButton(onClick = { scope.launch { c.exams.setStatus(r.id, RegistrationStatus.CANCELLED, "사용자 확인: 취소됨") } }) { Text("취소됨") }
                }
            }
        }

        if (review.isNotEmpty()) SectionCard(title = "확인이 필요한 기록 ${review.size}건") {
            Text("이전 버전에서 옮긴 기록입니다. 어떤 시험인지 앱이 알 수 없어 학습 계획에 쓰지 않습니다. 눌러서 유형과 상태를 고르거나 삭제하세요.",
                style = MaterialTheme.typography.bodySmall)
            review.forEach { RegistrationRow(it) { editing = it } }
        }

        SectionCard(title = "다가오는 시험", trailing = {
            TextButton(onClick = { editing = ExamRegistrationEntity(examType = ExamType.OPIC.name, dateLocal = "") }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("시험 추가")
            }
        }) {
            if (upcoming.isEmpty()) Text("다가오는 시험 일정이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            upcoming.forEachIndexed { index, r ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RegistrationRow(r, plans[r.id]) { editing = r }
            }
        }

        if (past.isNotEmpty()) SectionCard(title = "시험 기록") {
            past.forEachIndexed { index, r ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RegistrationRow(r) { editing = r }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { r ->
        RegistrationDialog(r, plans[r.id], onDismiss = { editing = null }, onDebrief = {
            editing = null
            r.date?.let { onDebrief(it, r.id) }
        })
    }
}

@Composable
private fun RegistrationRow(r: ExamRegistrationEntity, plan: PlannedRetake? = null, onClick: () -> Unit) {
    val st = r.state
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val whenText = r.date?.let { it.format(compactDate) + r.startTimeLocal?.let { time -> " $time" }.orEmpty() }
        ?: ExamSchedule.formatWhen(r)
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "시험 수정", onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("$whenText · ${r.type.label}", style = MaterialTheme.typography.titleSmall)
            if (largeText) RegistrationStatusLabel(st)
            val details = when {
                st == RegistrationStatus.COMPLETED && !r.resultGrade.isNullOrBlank() -> "성적 ${r.resultGrade}"
                st in listOf(RegistrationStatus.PLANNED, RegistrationStatus.REGISTERED, RegistrationStatus.COMPLETED) ->
                    resultTiming(r)
                else -> null
            }
            details?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val waiver = if (st == RegistrationStatus.PLANNED) waiverPlanLabel(r, plan) else null
            waiver?.let {
                val needsReview = plan?.eligibility in setOf(PlanEligibility.WAIVER_REQUIRED, PlanEligibility.NO_WAIVER_LEFT, PlanEligibility.SAME_DAY, PlanEligibility.BALANCE_UNKNOWN)
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (needsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.location.isNotBlank()) Text(r.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!largeText) {
            RegistrationStatusLabel(st, Modifier.weight(0.34f), textAlign = TextAlign.End)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RegistrationStatusLabel(
    status: RegistrationStatus,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(status.label, modifier, color = statusColor(status), style = MaterialTheme.typography.labelMedium, textAlign = textAlign)
}

private fun resultTiming(r: ExamRegistrationEntity): String? {
    r.resultPublishedAt?.takeIf { it.isNotBlank() }?.let { return "성적 공개 $it" }
    return ExamResults.expectedOn(r)?.let { date ->
        "발표 예정 ${date.monthDay()}" + if (r.type == ExamType.OPIC) " 오후 1시 이후" else ""
    }
}

private fun waiverPlanLabel(r: ExamRegistrationEntity, plan: PlannedRetake?): String? = when (plan?.eligibility) {
    PlanEligibility.SAME_DAY -> "같은 날 중복 응시 불가"
    PlanEligibility.WAIVER_REQUIRED -> "웨이버 필요 · 일반 응시 ${plan.regularFrom?.monthDay()}부터"
    PlanEligibility.BALANCE_UNKNOWN -> "웨이버 보유 수 확인 필요"
    PlanEligibility.NO_WAIVER_LEFT -> "사용 가능한 웨이버 없음"
    else -> when (r.waiverPlanned) {
        true -> "웨이버 1회 사용 예정"
        false -> "웨이버 사용 안 함"
        null -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegistrationDialog(r: ExamRegistrationEntity, plan: PlannedRetake?, onDismiss: () -> Unit, onDebrief: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(if (r.type == ExamType.UNKNOWN) null else r.type) }
    var date by remember { mutableStateOf(r.dateLocal) }
    var start by remember { mutableStateOf(r.startTimeLocal.orEmpty()) }
    var end by remember { mutableStateOf(r.endTimeLocal.orEmpty()) }
    var location by remember { mutableStateOf(r.location) }
    var status by remember { mutableStateOf(r.state) }
    var grade by remember { mutableStateOf(r.resultGrade.orEmpty()) }
    var waiverPlanned by remember { mutableStateOf(r.waiverPlanned) }
    var waiverUsed by remember { mutableStateOf(r.waiverUsed) }
    var memo by remember { mutableStateOf(r.memo) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (r.id == 0L) "시험 추가" else "시험 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("시험 유형", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ExamType.selectable.forEach { t -> Pill(t.label, type == t) { type = t } } }
                OutlinedTextField(date, { date = it }, label = { Text("시험일 (YYYY-MM-DD)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("시작 (HH:MM)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it }, label = { Text("종료") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(location, { location = it }, label = { Text("장소") }, singleLine = true)
                Text("상태", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RegistrationStatus.entries.forEach { s -> Pill(s.label, status == s) { status = s } }
                }
                if (status == RegistrationStatus.COMPLETED) OutlinedTextField(grade, { grade = it }, label = { Text("결과 등급 (나오면 입력)") }, singleLine = true)
                Text(if (status == RegistrationStatus.PLANNED) "웨이버 사용 계획" else "실제 웨이버 사용", style = MaterialTheme.typography.labelLarge)
                if (status == RegistrationStatus.PLANNED && date == r.dateLocal && type == r.type) {
                    // A past date ("3월 26일부터" in November) reads as a stale warning, so only future limits are shown.
                    plan?.regularFrom?.takeIf { it.isAfter(ExamRepository.nowKst().toLocalDate()) }?.let {
                        Text("일반 재응시 ${it.monthDay()}부터", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val value = if (status == RegistrationStatus.PLANNED) waiverPlanned else waiverUsed
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val yes = if (status == RegistrationStatus.PLANNED) "사용 예정" else "사용함"
                    for ((option, label) in listOf(null to "미입력", false to "사용 안 함", true to yes)) {
                        Pill(label, value == option) {
                            if (status == RegistrationStatus.PLANNED) waiverPlanned = option else waiverUsed = option
                        }
                    }
                }
                OutlinedTextField(memo, { memo = it }, label = { Text("메모") }, minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (r.state == RegistrationStatus.COMPLETED && r.date != null && r.type != ExamType.UNKNOWN) {
                    TextButton(onClick = onDebrief) { Text("복기 열기") }
                }
                if (r.id != 0L) TextButton(onClick = { confirmDelete = true }) { Text("이 기록 삭제", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = type ?: run { error = "시험 유형을 고르세요."; return@TextButton }
                val d = date.trim()
                val precision = when {
                    runCatching { LocalDate.parse(d) }.isSuccess -> DatePrecision.DAY
                    runCatching { YearMonth.parse(d) }.isSuccess -> DatePrecision.MONTH
                    else -> { error = "날짜 형식이 올바르지 않습니다."; return@TextButton }
                }
                fun time(v: String): String? = v.trim().ifBlank { null }?.let { runCatching { LocalTime.parse(if (it.length == 4) "0$it" else it).toString() }.getOrNull() ?: "" }
                val st = time(start); val en = time(end)
                if (st == "" || en == "") { error = "시간은 19:20 형식으로 입력하세요."; return@TextButton }
                if (precision == DatePrecision.MONTH && status == RegistrationStatus.REGISTERED) { error = "접수 완료 시험은 정확한 날짜가 필요합니다."; return@TextButton }
                val updated = r.copy(
                    examType = t.name, dateLocal = d, datePrecision = precision.name, startTimeLocal = st, endTimeLocal = en,
                    location = location.trim(), status = status.name,
                    resultGrade = grade.trim().takeIf { status != RegistrationStatus.PLANNED && it.isNotBlank() },
                    waiverPlanned = waiverPlanned,
                    waiverUsed = if (status == RegistrationStatus.PLANNED) null else waiverUsed,
                    memo = memo.trim(),
                    needsReview = false,
                    sourceType = if (r.id == 0L) RegistrationSource.USER.name else r.sourceType,
                )
                scope.launch { c.exams.save(updated, "사용자 수정"); onDismiss() }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("기록 삭제") },
        text = { Text("${r.type.label} ${ExamSchedule.formatWhen(r)} 기록과 상태 이력을 삭제합니다. 되돌릴 수 없습니다. 취소한 시험이라면 삭제 대신 상태를 '취소'로 두는 것을 권합니다.") },
        confirmButton = { TextButton(onClick = { scope.launch { c.exams.delete(r.id); confirmDelete = false; onDismiss() } }) { Text("삭제", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
}

@Composable
fun ExamReviewBanner(count: Int, onOpen: () -> Unit) {
    if (count > 0) Banner("확인이 필요한 시험 기록 ${count}건이 있습니다. 시험 유형을 지정해야 계획에 반영됩니다.", "확인", onAction = onOpen)
}
