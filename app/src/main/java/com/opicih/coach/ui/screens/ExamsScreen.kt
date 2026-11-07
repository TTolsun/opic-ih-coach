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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.DatePrecision
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamType
import com.opicih.coach.data.db.RegistrationSource
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.repo.ExamRepository
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@Composable
fun statusColor(s: RegistrationStatus): Color = when (s) {
    RegistrationStatus.REGISTERED -> MaterialTheme.colorScheme.primary
    RegistrationStatus.PLANNED -> MaterialTheme.colorScheme.tertiary
    RegistrationStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
    RegistrationStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    RegistrationStatus.NO_SHOW -> MaterialTheme.colorScheme.error
}

/** PLANNED is shown as "미확정" everywhere so it is never mistaken for a booking. */
fun statusLabel(s: RegistrationStatus): String = if (s == RegistrationStatus.PLANNED) "접수 예정 (미확정)" else s.label

/** Exam schedule: every registration with its status. Bookings, plans and history are listed separately. */
@Composable
fun ExamsScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val regs by c.exams.flow.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<ExamRegistrationEntity?>(null) }
    val now = ExamRepository.nowKst()

    val review = ExamSchedule.needsReview(regs)
    val awaiting = ExamSchedule.awaitingConfirmation(regs, now)
    val upcoming = regs.filter { !it.needsReview && it.endsAt()?.isAfter(now) == true &&
        (it.state == RegistrationStatus.REGISTERED || it.state == RegistrationStatus.PLANNED) }
    val past = regs.filter { it !in review && it !in awaiting && it !in upcoming }.sortedByDescending { it.latestDate }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Button(onClick = { editing = ExamRegistrationEntity(examType = ExamType.OPIC.name, dateLocal = "", status = RegistrationStatus.PLANNED.name) }, modifier = Modifier.fillMaxWidth()) {
            Text("시험 추가")
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

        SectionCard(title = "예정된 시험") {
            if (upcoming.isEmpty()) Text("등록된 예정 시험이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            upcoming.forEach { RegistrationRow(it) { editing = it } }
            Spacer(Modifier.height(4.dp))
            Text("'접수 완료'만 홈의 D-day와 오늘의 훈련 계획에 반영됩니다. '접수 예정'은 실제 접수 전 계획입니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (past.isNotEmpty()) SectionCard(title = "지난 시험·취소") {
            past.forEach { RegistrationRow(it) { editing = it } }
            Text("취소된 시험은 재응시 제한 계산에 쓰지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { r -> RegistrationDialog(r, onDismiss = { editing = null }) }
}

@Composable
private fun RegistrationRow(r: ExamRegistrationEntity, onClick: () -> Unit) {
    val st = r.state
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${r.type.label} · ${ExamSchedule.formatWhen(r)}",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                textDecoration = if (st == RegistrationStatus.CANCELLED) TextDecoration.LineThrough else null,
            )
            SourceTag(statusLabel(st), statusColor(st))
        }
        val sub = listOfNotNull(r.location.ifBlank { null }, r.resultGrade?.let { "결과 $it" }, RegistrationSource.of(r.sourceType).label).joinToString(" · ")
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.memo.isNotBlank()) Text(r.memo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegistrationDialog(r: ExamRegistrationEntity, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(if (r.type == ExamType.UNKNOWN) null else r.type) }
    var date by remember { mutableStateOf(r.dateLocal) }
    var start by remember { mutableStateOf(r.startTimeLocal.orEmpty()) }
    var end by remember { mutableStateOf(r.endTimeLocal.orEmpty()) }
    var location by remember { mutableStateOf(r.location) }
    var status by remember { mutableStateOf(r.state) }
    var grade by remember { mutableStateOf(r.resultGrade.orEmpty()) }
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
                OutlinedTextField(date, { date = it }, label = { Text("날짜 (YYYY-MM-DD, 일자를 모르면 YYYY-MM)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("시작 (HH:MM)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it }, label = { Text("종료") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(location, { location = it }, label = { Text("장소") }, singleLine = true)
                Text("상태", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RegistrationStatus.entries.forEach { s -> Pill(statusLabel(s), status == s) { status = s } }
                }
                if (status == RegistrationStatus.COMPLETED) OutlinedTextField(grade, { grade = it }, label = { Text("결과 등급 (나오면 입력)") }, singleLine = true)
                OutlinedTextField(memo, { memo = it }, label = { Text("메모") }, minLines = 2)
                Text("시간은 한국 시간(KST) 기준으로 저장합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
                    location = location.trim(), status = status.name, resultGrade = grade.trim().ifBlank { null }, memo = memo.trim(),
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
