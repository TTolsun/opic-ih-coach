package com.opicih.coach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.repo.CoachingEntry
import com.opicih.coach.data.repo.CoachingRun
import com.opicih.coach.data.repo.coachingRuns
import com.opicih.coach.learning.CoachingEvidence
import com.opicih.coach.learning.CoachingPrompts
import com.opicih.coach.learning.CoachingRecord
import com.opicih.coach.learning.CoachingStage
import com.opicih.coach.ui.components.SectionCard
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoachingHomeCard(entries: List<CoachingEntry>, today: Long, onOpen: (String, String) -> Unit) {
    val runs = entries.coachingRuns()
    val awaiting = runs.filter { it.delayed == null && it.retry?.record?.assessmentId != null }
        .sortedBy { it.retry?.record?.dueDay }
    val completed = runs.filter { it.delayed != null }.maxByOrNull { it.delayed!!.attempt.createdAt }
    SectionCard(title = "교정 연습") {
        awaiting.forEach { run ->
            val record = checkNotNull(run.retry).record
            val due = checkNotNull(record.dueDay)
            val prompt = CoachingPrompts.prompt(run.first.record.track.promptId) ?: return@forEach
            if (due <= today) {
                Button(onClick = { onOpen("delayed", record.track.id) }) { Text("새 질문 재검사 · ${prompt.type.label}") }
            } else {
                Text("${prompt.type.label} 재검사 · ${checkDate(due)}부터", style = MaterialTheme.typography.bodySmall)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachingPrompts.practice.forEach { prompt ->
                val unfinished = runs.firstOrNull { it.first.record.track.promptId == prompt.id && it.retry == null }
                if (unfinished == null) {
                    OutlinedButton(onClick = { onOpen(prompt.id, UUID.randomUUID().toString()) }) { Text(prompt.label) }
                } else {
                    OutlinedButton(onClick = { onOpen("retry", unfinished.first.record.track.id) }) { Text("${prompt.label} 이어서") }
                }
            }
        }
        completed?.let {
            TextButton(onClick = { onOpen("delayed", it.first.record.track.id) }) { Text("최근 재검사 결과") }
        }
    }
}

@Composable
internal fun CoachingResultPanel(
    record: CoachingRecord?, run: CoachingRun?, offerDeviceRecognition: Boolean,
    onExit: () -> Unit, onRetry: (Boolean) -> Unit,
) {
    record ?: return
    val prompt = CoachingPrompts.prompt(record.track.promptId) ?: return
    val evidence = record.evidence
    SectionCard(title = when (record.track.stage) {
        CoachingStage.FIRST -> "첫 무힌트 답변"
        CoachingStage.RETRY -> "같은 질문 개선"
        CoachingStage.DELAYED -> if (record.seenBefore) "이미 본 질문 다시 답하기" else "새 질문에서도 해냈는가"
    }) {
        if (evidence == null) {
            Text("질문의 과업을 판단할 발화 근거가 부족해 결과를 보류했습니다.")
            Button(onClick = { onRetry(offerDeviceRecognition) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (offerDeviceRecognition) "기기 음성 인식으로 재시도" else "다시 녹음하기")
            }
        } else {
            val first = run?.first?.record?.evidence
            val focus = first?.parts?.indexOfFirst { it == null } ?: -1
            val focusLabel = focusLabel(prompt.type, focus)
            when (record.track.stage) {
                CoachingStage.FIRST -> Quote(evidence, label = "첫 답변")
                CoachingStage.RETRY -> {
                    Text(focusLabel, style = MaterialTheme.typography.titleSmall)
                    if (first != null) Quote(first, "첫 답변", focus)
                    Text(if (evidence.supports(focus)) "교정한 내용의 발화 근거를 확인했습니다." else "교정한 내용의 발화 근거가 부족합니다.")
                    Quote(evidence, "재답변 · 교정 안내 제공", focus)
                    val due = record.dueDay
                    Text(if (due != null) "새 질문 재검사 · ${checkDate(due)}부터" else "처음 보는 재검사 문항이 남아 있지 않습니다.")
                }
                CoachingStage.DELAYED -> {
                    val retry = run?.retry?.record
                    retry?.let { Text("같은 질문을 다시 말한 뒤 ${record.day - it.day}일이 지났습니다.") }
                    Text(focusLabel, style = MaterialTheme.typography.titleSmall)
                    retry?.evidence?.let {
                        Text(if (it.supports(focus)) "같은 질문에서 교정한 내용의 발화 근거를 확인했습니다." else
                            "같은 질문에서 교정한 내용의 발화 근거가 부족합니다.")
                        Quote(it, "재답변 · 교정 안내 제공", focus)
                    }
                    if (record.seenBefore) {
                        Text("새 질문에 적용했는지는 판단을 보류했습니다.")
                    } else {
                        Text(if (evidence.supports(focus)) "재검사에서도 같은 행동의 발화 근거를 확인했습니다." else
                            "재검사에서는 같은 행동의 발화 근거가 부족합니다.")
                    }
                    Quote(evidence, "재검사 · 안내 없이 답변", focus)
                }
            }
            Text(prompt.nextAction(evidence), style = MaterialTheme.typography.titleSmall)
            if (record.track.stage == CoachingStage.FIRST) {
                Button(onClick = { onRetry(false) }, modifier = Modifier.fillMaxWidth()) { Text("같은 질문 다시 말하기") }
            }
        }
        if (record.track.stage != CoachingStage.FIRST) {
            TextButton(onClick = onExit) { Text("홈으로") }
        }
    }
}

@Composable
private fun Quote(evidence: CoachingEvidence, label: String, focus: Int = -1) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    val quotes = if (focus >= 0) listOf(evidence.parts.getOrNull(focus) ?: evidence.sample)
        else evidence.parts.filterNotNull().distinct().ifEmpty { listOf(evidence.sample) }
    quotes.forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
}

private fun checkDate(day: Long): String = LocalDate.ofEpochDay(day).let { "${it.monthValue}/${it.dayOfMonth}" }

private fun CoachingEvidence.supports(focus: Int) =
    if (focus < 0) parts.all { it != null } else parts.getOrNull(focus) != null

private fun focusLabel(type: QuestionType, focus: Int): String {
    val labels = if (type == QuestionType.COMPARISON) listOf("과거의 모습", "현재의 모습", "두 시기의 차이")
        else listOf("문제가 생긴 상황", "문제에 대한 첫 행동", "첫 행동 뒤에 생긴 결과")
    return labels.getOrNull(focus) ?: "세 가지 과업 연결"
}
