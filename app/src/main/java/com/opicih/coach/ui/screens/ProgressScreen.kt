package com.opicih.coach.ui.screens

import com.opicih.coach.learning.lastExamDate
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.ExamEntity
import com.opicih.coach.data.db.MasteryState
import com.opicih.coach.data.db.PracticeMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.ExamPolicy
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.MetricTile
import com.opicih.coach.ui.components.ProgressRow
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressScreen(onNextExam: () -> Unit = {}) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<com.opicih.coach.data.db.PracticeAttemptEntity?>(null) }
    val s by c.settings.flow.collectAsState(initial = Settings())
    val attempts by c.dao.attemptsFlow().collectAsState(initial = emptyList())
    val regs by c.exams.flow.collectAsState(initial = emptyList())
    val scripts by c.dao.scriptsFlow().collectAsState(initial = emptyList())
    val logs by c.dao.dailyLogsFlow().collectAsState(initial = emptyList())
    val errors by c.dao.errorsFlow().collectAsState(initial = emptyList())
    val exams by c.dao.examsFlow().collectAsState(initial = emptyList())
    var showExam by remember { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("기록", style = MaterialTheme.typography.headlineMedium)

        val totalSpeak = attempts.sumOf { it.effectiveSpeechMs }
        val withText = attempts.filter { it.wordCount > 0 }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("누적 발화", "${totalSpeak / 60000}분")
            MetricTile("연습 횟수", "${attempts.size}회")
            MetricTile("평균 답변", "${if (withText.isEmpty()) 0 else withText.map { it.wordCount }.average().toInt()}단어")
            MetricTile("자유 발화 비율", "${(ProgressCalculator.freeSpeakingRatio(attempts) * 100).toInt()}%")
            MetricTile("주간 목표", "${(ProgressCalculator.weeklyGoalRate(logs, today, s.studyLevel.minutes) * 100).toInt()}%")
            MetricTile("연속", "${ProgressCalculator.streak(logs, today)}일")
        }

        SectionCard(title = "최근 7일 발화 시간 (분)") {
            val days = (today - 6..today).map { d -> (logs.firstOrNull { it.epochDay == d }?.speakingMs ?: 0L) / 60000f }
            BarChart(days, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHighest)
            Row(Modifier.fillMaxWidth()) { (6 downTo 0).forEach { Text(if (it == 0) "오늘" else "-$it", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)) } }
        }

        SectionCard(title = "최근 답변 추세 (최대 12회)") {
            val recent = withText.take(12).reversed()
            if (recent.isEmpty()) Text("아직 기록이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            else {
                Text("점수", style = MaterialTheme.typography.labelMedium)
                BarChart(recent.map { it.score.toFloat() }, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHighest, max = 100f)
                Spacer(Modifier.height(8.dp))
                Text("2초 이상 무음 (회)", style = MaterialTheme.typography.labelMedium)
                BarChart(recent.map { it.longPauseCount.toFloat() }, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        }

        SectionCard(title = "스크립트 암기 숙련도") {
            MasteryState.entries.forEach { st ->
                val n = scripts.count { it.masteryState == st.name }
                ProgressRow(st.label, if (scripts.isEmpty()) 0f else n.toFloat() / scripts.size, "${n}개")
                Spacer(Modifier.height(6.dp))
            }
        }

        SectionCard(title = "주제별 연습") {
            val stats = ProgressCalculator.topicStats(attempts)
            if (stats.isEmpty()) Text("아직 기록이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            stats.forEach { t -> Bullet("${t.topic}: ${t.attempts}회 · 평균 ${t.avgScore}점 · ${t.avgWords}단어") }
        }

        SectionCard(title = "반복 오류 노트") {
            if (errors.isEmpty()) Text("기록된 오류가 없습니다.", style = MaterialTheme.typography.bodyMedium)
            errors.take(10).forEach { e ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row { SourceTag(e.category); Text("  ${e.count}회", style = MaterialTheme.typography.labelMedium) }
                    Text(e.example, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("→ ${e.correction}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        SectionCard(title = "최근 연습 기록") {
            if (attempts.isEmpty()) Text("아직 기록이 없습니다.", style = MaterialTheme.typography.bodyMedium)
            attempts.take(15).forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val t = java.time.Instant.ofEpochMilli(a.createdAt).atZone(java.time.ZoneId.systemDefault())
                        Text("%d/%d %02d:%02d · %s · %s".format(t.monthValue, t.dayOfMonth, t.hour, t.minute, PracticeMode.of(a.mode).label, a.topic),
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (a.transcript.isBlank()) "전사 없음 · ${a.speechMs / 1000}초" else "${a.wordCount}단어 · ${a.score}점 · ${a.transcript.take(40)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { toDelete = a }) { Icon(Icons.Filled.Delete, "기록 삭제") }
                }
            }
        }

        SectionCard(title = "시험 결과와 재응시") {
            exams.forEach { e -> Bullet("${LocalDate.ofEpochDay(e.epochDay)} · ${e.resultGrade ?: "결과 대기"} ${e.note}") }
            val last = lastExamDate(exams, LocalDate.now(), regs)
            Spacer(Modifier.height(6.dp))
            // OPIc's official rule, not the user-editable SAIL limit (s.retakeDays).
            val opic = ExamPolicy.opic()
            Text("OPIc 다음 응시 가능: ${opic.earliestWithoutWaiver(last)} (응시일 제외 ${opic.blockedDays}일 제한, 공식 시험관리규정 기준)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNextExam) { Text("다음 시험 추천") }
                androidx.compose.material3.OutlinedButton(onClick = { showExam = true }) { Text("결과 입력") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("연습 기록 삭제") },
            text = { Text("이 기록과 녹음 파일을 삭제하고, 그날 발화 시간에서 뺍니다. 스크립트 숙련도와 복습 일정은 그대로입니다. 되돌릴 수 없습니다.") },
            confirmButton = { TextButton(onClick = { toDelete = null; scope.launch { c.practice.deleteAttempt(a) } }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("취소") } },
        )
    }

    if (showExam) {
        var date by remember { mutableStateOf(LocalDate.now().toString()) }
        var grade by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showExam = false },
            title = { Text("시험 결과 입력") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(date, { date = it }, label = { Text("응시일 (YYYY-MM-DD)") }, singleLine = true)
                    OutlinedTextField(grade, { grade = it }, label = { Text("등급 (예: IM2, IM3, IH)") }, singleLine = true)
                    OutlinedTextField(note, { note = it }, label = { Text("메모 (나온 주제 등)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = runCatching { LocalDate.parse(date.trim()) }.getOrNull() ?: return@TextButton
                    showExam = false
                    scope.launch {
                        c.dao.insertExam(ExamEntity(epochDay = d.toEpochDay(), type = if (exams.isEmpty()) "FIRST" else "RETAKE", resultGrade = grade.ifBlank { null }, note = note))
                        // v3: the schedule lives in exam_registrations; a result no longer rewrites a single exam date.
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { showExam = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun BarChart(values: List<Float>, color: Color, track: Color, max: Float? = null) {
    val top = max ?: (values.maxOrNull()?.coerceAtLeast(1f) ?: 1f)
    Canvas(Modifier.fillMaxWidth().height(90.dp).padding(vertical = 6.dp)) {
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = slot * 0.6f
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - w) / 2
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(6f, 6f))
            val h = (v / top).coerceIn(0f, 1f) * size.height
            if (h > 0) drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(6f, 6f))
        }
    }
}
