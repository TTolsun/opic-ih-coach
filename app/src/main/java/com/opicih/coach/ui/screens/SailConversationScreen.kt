package com.opicih.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opicih.coach.learning.ConversationReport
import com.opicih.coach.learning.ConvTurn
import com.opicih.coach.learning.QuestionOrigin
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.LevelBar
import com.opicih.coach.ui.components.MetricTile
import com.opicih.coach.ui.components.MicButton
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.components.formatMs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SailConversationScreen(vm: SailConversationViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingStart?.invoke()
        pendingStart = null
    }
    fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingStart = action; micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onBackground() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ui.error?.let { Banner(it) }
        ui.aiNote?.let { Banner(it, tone = MaterialTheme.colorScheme.secondary) }
        ui.ttsMessage?.let { Banner(it) }

        when (ui.phase) {
            ConvPhase.SETUP -> SectionCard(title = "SAIL 대화 연습", trailing = { SourceTag("학습용 시뮬레이션") }) {
                Text("영어 질문을 듣고 바로 답하면, 방금 한 답에서 나온 내용으로 추가 질문이 이어집니다. 공식 SAIL 문항 구조를 재현하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text("대화 길이", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (3..5).forEach { n -> Pill("${n}턴", ui.targetTurns == n) { vm.setTurns(n) } } }
                Spacer(Modifier.height(8.dp))
                Bullet(if (ui.aiAvailable) "추가 질문: Gemini AI (대화당 최대 6회 호출, 하루 상한은 설정값)" else "추가 질문: 오프라인 규칙 (AI 꺼짐, 비용 없음)")
                Bullet("질문이 끝나면 마이크가 자동으로 켜집니다. 답을 마치면 정지 버튼을 누르세요.")
                Bullet("이 화면은 녹음 파일을 남기지 않는 실시간 인식만 사용합니다.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = { withMic { vm.start() } }, modifier = Modifier.fillMaxWidth()) { Text("대화 시작") }
            }

            ConvPhase.DONE -> ui.report?.let { r ->
                ReportCard(r, ui.turns, ui.aiCalls)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { withMic { vm.start(repeatFirst = true) } }) { Text("같은 질문 다시 답하기") }
                    OutlinedButton(onClick = { vm.reset() }) { Text("새 대화") }
                }
            }

            else -> {
                SectionCard(
                    title = "질문 ${ui.turns.size + 1} / ${ui.targetTurns}",
                    trailing = { SourceTag(ui.origin.label, if (ui.origin == QuestionOrigin.AI) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                ) {
                    Text(
                        when (ui.phase) {
                            ConvPhase.ASKING -> "질문을 읽는 중입니다. 끝나면 바로 답하세요."
                            ConvPhase.LISTENING -> "듣는 중 · ${formatMs(ui.elapsedMs)}"
                            ConvPhase.THINKING -> "다음 질문을 준비하는 중입니다."
                            else -> "준비되면 마이크를 누르고 답하세요."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (ui.showQuestionText && ui.question != null) {
                        Spacer(Modifier.height(6.dp)); Text(ui.question!!, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row {
                        TextButton(onClick = vm::replayQuestion, enabled = ui.phase != ConvPhase.THINKING) { Text("질문 다시 듣기") }
                        TextButton(onClick = vm::toggleQuestionText) { Text(if (ui.showQuestionText) "질문 숨기기" else "질문 텍스트 보기") }
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    MicButton(
                        recording = ui.phase == ConvPhase.LISTENING,
                        enabled = ui.phase == ConvPhase.LISTENING || ui.phase == ConvPhase.READY,
                    ) { if (ui.phase == ConvPhase.LISTENING) vm.finishAnswer() else withMic { vm.listen() } }
                    if (ui.phase == ConvPhase.LISTENING) { Spacer(Modifier.height(8.dp)); LevelBar(ui.level, Modifier.fillMaxWidth(0.6f)) }
                    if (ui.liveText.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(ui.liveText, style = MaterialTheme.typography.bodyMedium) }
                }
                if (ui.showTyped) TypedAnswer(vm::submitTyped)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::skip, enabled = ui.phase != ConvPhase.THINKING) { Text("건너뛰기") }
                    OutlinedButton(onClick = vm::endNow) { Text("여기서 끝내기") }
                }
                if (ui.turns.isNotEmpty()) SectionCard(title = "지금까지의 대화") { ui.turns.forEach { TurnRow(it) } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TypedAnswer(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    SectionCard(title = "말한 내용 직접 입력") {
        OutlinedTextField(text, { text = it }, label = { Text("영어로 입력") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onSubmit(text) }) { Text("이 답변으로 계속") }
        Text("직접 입력한 답변은 답변 시작 시간과 무음 지표에서 제외됩니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TurnRow(t: ConvTurn) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row { Text("Q", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.padding(4.dp)); Text(t.question, style = MaterialTheme.typography.bodyMedium) }
        Text(t.answer.ifBlank { "(답변 없음)" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val m = t.metrics
        val meta = listOfNotNull(
            t.origin.label,
            m?.responseLatencyMs?.let { "시작 %.1f초".format(it / 1000f) },
            m?.let { "${it.wordCount}단어" },
            m?.takeIf { it.longPauses > 0 }?.let { "긴 멈춤 ${it.longPauses}회" },
            if (t.typed && t.answer.isNotBlank()) "직접 입력" else null,
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(r: ConversationReport, turns: List<ConvTurn>, aiCalls: Int) {
    SectionCard(title = "대화 피드백", trailing = { SourceTag("학습용 참고") }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("답변 시작", r.avgLatencyMs?.let { "%.1f초".format(it / 1000f) } ?: "측정 불가", hint = if (r.slowStarts > 0) "3초 초과 ${r.slowStarts}회" else null)
            MetricTile("평균 단어", "${r.avgWords}")
            MetricTile("긴 멈춤", "${r.longPauses}회")
            MetricTile("연결어", "%.1f/답변".format(r.connectorsPerAnswer))
            MetricTile("질문 대응", "${(r.onTopicRatio * 100).toInt()}%", hint = "단어 겹침 기준")
        }
        Spacer(Modifier.height(10.dp))
        Text("잘한 점", style = MaterialTheme.typography.labelLarge)
        r.strengths.forEach { Bullet(it, color = MaterialTheme.colorScheme.secondary) }
        Spacer(Modifier.height(6.dp))
        Text("우선 고칠 점", style = MaterialTheme.typography.labelLarge)
        if (r.fixes.isEmpty()) Bullet("이번 대화에서 규칙상 크게 걸린 항목은 없습니다.")
        r.fixes.forEach { Bullet(it, color = MaterialTheme.colorScheme.tertiary, mark = "!") }
    }
    r.aiSummary?.let { s ->
        SectionCard(title = "Gemini 종합 피드백", trailing = { SourceTag("AI · 호출 ${aiCalls}회") }) { Text(s, style = MaterialTheme.typography.bodyMedium) }
    }
    SectionCard(title = "대화 기록") { turns.forEach { TurnRow(it) } }
    SectionCard(title = "측정 한계") { r.limits.forEach { Bullet(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
