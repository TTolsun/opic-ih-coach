package com.opicih.coach.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opicih.coach.analysis.FixItem
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.repo.AnalyzedAttempt
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.appJson
import com.opicih.coach.learning.ScoreKind
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.MetricTile
import com.opicih.coach.ui.components.InfoButton
import com.opicih.coach.ui.components.InfoTopics
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.components.formatMs
import java.time.LocalDate

/**
 * Result after one answer. Learning rule: one fix, then speak again right away.
 * The top shows only the score, change since last time, the single most important fix and the retry button.
 * Everything else (all metrics, other fixes, missing sentences, model answer, transcript, limits) is folded
 * into "자세히 보기" so the learner speaks again instead of reading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResultPanel(r: AnalyzedAttempt, ui: SpeakingUi, vm: SpeakingViewModel, onRetry: () -> Unit) {
    val fb = r.feedback
    val a = r.attempt
    val isAi = fb.source == "AI"
    var details by rememberSaveable(a.id) { mutableStateOf(false) }

    SectionCard(
        title = "결과",
        trailing = { SourceTag(if (isAi) "AI 분석 (${fb.engine.ifBlank { "AI" }})" else "로컬 분석 (AI 아님)", if (isAi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        if (a.transcript.isNotBlank() || fb.score > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${fb.score}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(ScoreKind.of(a.mode).label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                InfoButton(InfoTopics.MEASUREMENT)
            }
        }
        r.previous?.let { p ->
            val dScore = a.score - p.score
            val previousPauses = remember(p.metricsJson) {
                runCatching { appJson.decodeFromString(AttemptMetrics.serializer(), p.metricsJson).pauses }.getOrNull()
            }
            Banner(
                buildString {
                    append("지난번보다 점수 ${signed(dScore)} · 단어 ${signed(a.wordCount - p.wordCount)}")
                    if (a.captureMode == p.captureMode && r.metrics.pauses != null && previousPauses != null) {
                        append(" · 긴 무음 ${signed(a.longPauseCount - p.longPauseCount)}")
                    }
                },
                tone = if (dScore >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(10.dp))
        }

        fb.strengths.firstOrNull()?.let {
            Text("잘한 점", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            Bullet(it)
            Spacer(Modifier.height(8.dp))
        }

        fb.fixes.firstOrNull()?.let { FocusFix(it) }
        // The retry mission usually asks to add missing sentences, so the first two stay visible here.
        val missing = r.metrics.compare?.sentences?.filter { !it.covered }.orEmpty()
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("빠진 내용", style = MaterialTheme.typography.titleSmall)
            missing.take(2).forEach { Bullet(it.sentence, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        visibleMission(fb)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Filled.Mic, null); Spacer(Modifier.width(8.dp)); Text("같은 질문 다시 말하기", style = MaterialTheme.typography.titleMedium)
        }
        // FlowRow, not Row: with a large system font the three buttons do not fit one line, and a Row squeezed
        // "다음 질문" into a one-character-wide column. Here it moves to the next line instead.
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (a.audioPath != null) {
                FilledTonalButton(onClick = { vm.playRecording(1.0f) }) { Icon(Icons.Filled.PlayArrow, null); Text("내 녹음", maxLines = 1) }
                OutlinedButton(onClick = { vm.playRecording(0.75f) }) { Text("0.75x", maxLines = 1) }
            }
            OutlinedButton(onClick = { vm.next() }) { Icon(Icons.Filled.SkipNext, null); Text("다음 질문", maxLines = 1) }
            if (a.mode == PracticeMode.FREE.name && !ui.transferUnavailable) {
                OutlinedButton(onClick = vm::nextSameType) { Text("같은 유형 새 질문") }
            }
        }
        if (ui.transferUnavailable) {
            Text("같은 유형의 새 질문이 없습니다.", style = MaterialTheme.typography.bodySmall)
        }
        if (a.mode == PracticeMode.FREE.name) {
            val due = ui.reviewDueEpochDay?.let(LocalDate::ofEpochDay)
            if (due != null) {
                Text("다시 말하기 예정 · ${due.monthValue}/${due.dayOfMonth}", style = MaterialTheme.typography.bodyMedium)
            } else if (a.wordCount > 0 || a.speechMs > 0) {
                TextButton(onClick = vm::scheduleReview) { Text("3일 뒤 다시 말하기") }
            }
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { details = !details },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (details) "자세히 접기" else "자세히 보기", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (details) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (details) Details(r, ui, vm)
    }
}

@Composable
private fun FocusFix(f: FixItem) {
    Text("고칠 점", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
    Spacer(Modifier.height(4.dp))
    Text(f.issue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
    if (f.why.isNotBlank()) Text(f.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (f.better.isNotBlank()) Text("→ ${f.better}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(r: AnalyzedAttempt, ui: SpeakingUi, vm: SpeakingViewModel) {
    val fb = r.feedback
    val a = r.attempt
    val m = r.metrics
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Tiles without a measurement (typed answers have no audio) are hidden instead of showing "-".
            m.pauses?.let {
                MetricTile("발화 시간", formatMs(it.speechMs))
                MetricTile("2초+ 무음", "${it.longPauseCount}회")
            }
            // Without a transcript (record-only mode, no STT) the text metrics would all be zero.
            if (a.transcript.isNotBlank()) {
                MetricTile("단어 수", "${m.discourse.wordCount}")
                if (a.wpm > 0) MetricTile("속도", "${a.wpm} wpm")
                MetricTile("시제", "과${m.discourse.pastCount}·현${m.discourse.presentCount}·미${m.discourse.futureCount}")
                MetricTile("연결 표현", "${m.discourse.connectorCount}회")
            } else {
                MetricTile("텍스트", "전사 없음")
            }
            m.compare?.let { MetricTile("내용 일치", "${(it.coverage * 100).toInt()}%") }
            // Self-corrections and abandoned sentences are only visible in a verbatim transcript.
            m.errorCounts?.takeIf { it.verbatim }?.let { e ->
                e.tense?.let { MetricTile("시제 오류", "${it}회") }
                e.abandoned?.let { MetricTile("끝맺지 못함", "${it}회") }
                e.selfCorrection?.let { MetricTile("자기 수정", "${it}회") }
            }
        }
        if (fb.strengths.size > 1) {
            Text("잘한 점 (더 보기)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            fb.strengths.drop(1).forEach { Bullet(it) }
        }
        if (fb.fixes.size > 1) {
            Text("다음에 고칠 점", style = MaterialTheme.typography.titleSmall)
            fb.fixes.drop(1).forEach { f ->
                Column {
                    Text(f.issue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    if (f.why.isNotBlank()) Text(f.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (f.better.isNotBlank()) Text("→ ${f.better}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        m.compare?.let { cmp ->
            // The first two are already shown above the retry button.
            val missing = cmp.sentences.filter { !it.covered }.drop(2)
            if (missing.isNotEmpty()) {
                Text("빠진 내용 (더)", style = MaterialTheme.typography.titleSmall)
                missing.take(4).forEach { Bullet(it.sentence, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        // The full study text repeats the missing sentences above, so it is shown only when AI wrote a new answer.
        if (fb.betterAnswer.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("더 자연스러운 답변 예시 (AI)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.speakText(fb.betterAnswer, 0.9f) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "듣기") }
            }
            Text(fb.betterAnswer, style = MaterialTheme.typography.bodyMedium)
        } else ui.question?.script?.studyText?.takeIf { it.isNotBlank() }?.let { study ->
            TextLink("학습본 듣기") { vm.speakText(study, 0.9f) }
        }
        if (a.transcript.isNotBlank()) {
            Text("인식된 내 답변", style = MaterialTheme.typography.titleSmall)
            Text(a.transcript, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (fb.limits.isNotEmpty()) {
            Text("측정 한계", style = MaterialTheme.typography.titleSmall)
            fb.limits.forEach { Bullet(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

/**
 * The retry mission, unless it only repeats the first fix's suggestion that is already shown right above it.
 * Local feedback builds the mission as "다시 말하기: " + that suggestion, and saved records keep that text,
 * so the check is done here rather than only when the feedback is composed.
 */
internal fun visibleMission(fb: com.opicih.coach.analysis.Feedback): String? {
    val mission = fb.retryMission.trim().ifBlank { return null }
    val shownBetter = fb.fixes.firstOrNull()?.better?.trim().orEmpty()
    return if (shownBetter.isNotEmpty() && mission.contains(shownBetter)) null else mission
}
