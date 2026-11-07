package com.opicih.coach.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.repo.AnalyzedAttempt
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.LevelBar
import com.opicih.coach.ui.components.MetricTile
import com.opicih.coach.ui.components.MicButton
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.components.formatMs
import com.opicih.coach.ui.theme.EnglishReading

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeakingScreen(scriptId: Long?, mode: PracticeMode?, onOpenSettings: () -> Unit) {
    val vm: SpeakingViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(scriptId, mode) { vm.configure(scriptId, mode) }

    // Stop the mic when the app goes to background (call, home button, screen off).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { vm.onBackground(); vm.stopAudio() } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var micDenied by rememberSaveable { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) vm.toggleMic()
    }
    fun onMic() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleMic()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    var showPicker by remember { mutableStateOf(false) }
    var showTyped by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Mode selector
        val modes = remember { PracticeMode.entries.filter { it != PracticeMode.MOCK } }
        val modeRow = rememberLazyListState()
        LaunchedEffect(ui.mode) { modeRow.animateScrollToItem(modes.indexOf(ui.mode).coerceAtLeast(0)) }
        LazyRow(state = modeRow, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(modes) { m -> Pill(m.label, ui.mode == m) { vm.setMode(m) } }
        }

        if (micDenied) Banner("마이크 권한이 거부되었습니다. 말하기 훈련에는 마이크가 필요합니다. 권한 없이도 '직접 입력'으로 분석할 수 있습니다.", "권한 설정") {
            context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        }

        val q = ui.question
        if (q == null) {
            SectionCard { Text("질문을 불러오는 중입니다. 스크립트가 없으면 Scripts 탭에서 추가하세요.") }
            return@Column
        }

        // Question card
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(q.topic, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SourceTag(QuestionType.of(q.type).label)
                SourceTag(q.sourceLabel)
            }
            Spacer(Modifier.height(10.dp))
            if (ui.mode == PracticeMode.BLIND && ui.phase != Phase.RESULT) {
                var reveal by remember(q.text) { mutableStateOf(false) }
                if (reveal) Text(q.text, style = EnglishReading)
                else Text("질문을 듣고 답하세요. (실제 시험처럼 텍스트 없이) · 탭해서 질문 보기", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { reveal = true })
            } else {
                Text(q.text, style = EnglishReading)
            }
            q.mission?.let {
                Spacer(Modifier.height(8.dp))
                Banner("미션: $it", tone = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { vm.playQuestion(1.0f) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, null); Spacer(Modifier.width(6.dp)); Text("질문 듣기") }
                TextButton(onClick = { vm.playQuestion(0.75f) }) { Text("0.75x") }
                Spacer(Modifier.weight(1f))
                if (ui.mode.usesScript) IconButton(onClick = { showPicker = true }) { Icon(Icons.Filled.List, "스크립트 선택") }
                IconButton(onClick = { vm.next() }) { Icon(Icons.Filled.SkipNext, "다음 질문") }
            }
            if (ui.replays > 1) Text("재청취 ${ui.replays - 1}회. 실제 시험에서는 질문 재청취가 제한적이니 첫 번에 듣는 연습도 하세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Mode-specific support panel
        ModePanel(ui, vm)

        // Mic area
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (ui.phase) {
                Phase.PROCESSING -> { CircularProgressIndicator(); Text("분석 중입니다...", style = MaterialTheme.typography.bodyMedium) }
                else -> {
                    MicButton(recording = ui.phase == Phase.RECORDING) { onMic() }
                    Text(
                        when (ui.phase) {
                            Phase.RECORDING -> "${formatMs(ui.elapsedMs)} · 다시 누르면 멈추고 분석"
                            Phase.RESULT -> "다시 말하기"
                            else -> "눌러서 바로 말하기 · ${ui.captureMode.label}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (ui.phase == Phase.RECORDING) {
                LevelBar(ui.level, Modifier.fillMaxWidth(0.6f))
                if (ui.liveText.isNotBlank()) Text(ui.liveText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.captureMode == CaptureMode.RECORD_ONLY) Text("녹음 우선 모드: 텍스트는 끝난 뒤 만들어집니다.", style = MaterialTheme.typography.labelSmall)
                if (ui.elapsedMs in 60_000..65_000) Text("1분 경과. IH 목표 답변은 1~2분입니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (ui.phase == Phase.IDLE || ui.showTypedFallback) {
                TextButton(onClick = { showTyped = true }) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(4.dp)); Text("인식 실패 시: 말한 내용 직접 입력") }
            }
        }

        ui.error?.let { Banner(it, "닫기", tone = MaterialTheme.colorScheme.error) { vm.dismissError() } }

        ui.result?.let { ResultPanel(it, ui, vm) }

        if (ui.captureMode == CaptureMode.LIVE_STT && ui.phase == Phase.IDLE && ui.result == null) {
            Text("실시간 인식은 녹음 파일을 남기지 않습니다. 녹음을 다시 들으려면 설정에서 '녹음 우선'을 선택하세요.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { onOpenSettings() })
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }) {
            LazyColumn(Modifier.padding(horizontal = 16.dp)) {
                items(ui.scripts, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().clickable { vm.setScript(s); showPicker = false }.padding(vertical = 10.dp)) {
                        Text("${s.topic} · ${s.title}", style = MaterialTheme.typography.titleSmall)
                        Text(s.mastery.label + " · 숙련도 ${s.masteryScore}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    if (showTyped) {
        var text by remember { mutableStateOf(ui.liveText) }
        AlertDialog(
            onDismissRequest = { showTyped = false },
            title = { Text("말한 내용 입력") },
            text = {
                Column {
                    Text("음성 인식이 실패했을 때 사용하세요. 입력한 텍스트로 내용·구조를 분석합니다. 유창성(무음)은 측정되지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, minLines = 5, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { showTyped = false; vm.submitTyped(text) }) { Text("분석") } },
            dismissButton = { TextButton(onClick = { showTyped = false }) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModePanel(ui: SpeakingUi, vm: SpeakingViewModel) {
    val s = ui.question?.script ?: return
    if (!ui.mode.usesScript) return
    val text = s.studyText
    when (ui.mode) {
        PracticeMode.READ_LISTEN -> SectionCard(title = "학습본 (${s.active.label})", trailing = { ModelButtons(vm, text) }) {
            Text(text, style = EnglishReading)
            s.koreanMeaning?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        PracticeMode.SHADOWING -> {
            val sentences = vm.shadowSentences()
            val cur = sentences.getOrNull(ui.shadowIndex).orEmpty()
            SectionCard(title = "Shadowing ${ui.shadowIndex + 1}/${sentences.size}") {
                Text(cur, style = EnglishReading)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shadowMove(-1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "이전 문장") }
                    FilledTonalButton(onClick = { vm.speakText(cur, 1.0f) }) { Icon(Icons.Filled.PlayArrow, null); Text("1.0x") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { vm.speakText(cur, 0.75f) }) { Text("0.75x") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.shadowMove(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "다음 문장") }
                }
                Text("듣고 바로 같은 속도로 따라 말한 뒤 마이크를 누르세요. 이 문장과 비교합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PracticeMode.FILL_BLANK -> {
            val words = remember(text) { text.split(Regex("(?<=\\s)|(?=\\s)")) }
            val revealed = remember(text) { mutableStateListOf<Int>() }
            SectionCard(title = "빈칸 채우기", trailing = { ModelButtons(vm, text) }) {
                FlowRow {
                    var contentIdx = 0
                    words.forEachIndexed { i, w ->
                        val core = w.trim().trim(',', '.', '!', '?', '\'')
                        val isContent = core.length >= 5 && core.lowercase() !in TextNormalizer.stopwords
                        val hide = isContent && (contentIdx++ % 3 == 0) && i !in revealed
                        if (w.isBlank()) Text(" ") else if (hide) {
                            Text("_".repeat(core.length.coerceAtMost(8)), style = EnglishReading, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { revealed.add(i) })
                        } else Text(w, style = EnglishReading)
                    }
                }
                Text("빈칸을 떠올리며 전체를 소리 내어 말하세요. 막히면 빈칸을 탭해서 확인합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PracticeMode.KEYWORD -> SectionCard(title = "키워드만 보고 말하기") {
            val kws = s.keywordList().ifEmpty { com.opicih.coach.analysis.ScriptComparator.pickKeywords(text, 5) }
            kws.forEachIndexed { i, k -> Bullet(k, mark = "${i + 1}") }
            if (s.keywords == null) Text("승인된 Keywords 버전이 없어 자동 추출했습니다. Scripts에서 Keywords 개선안을 승인하세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PracticeMode.BLIND, PracticeMode.RANDOM -> SectionCard {
            Text("스크립트 없이 답하세요. 순서가 달라도, 표현이 달라도 같은 내용이면 인정합니다.", style = MaterialTheme.typography.bodyMedium)
        }
        PracticeMode.VARIATION -> SectionCard {
            Text("암기한 내용을 바꿔서 응용합니다. 미션 조건(시제/질문)을 지켰는지 함께 확인합니다.", style = MaterialTheme.typography.bodyMedium)
        }
        else -> Unit
    }
}

@Composable
private fun ModelButtons(vm: SpeakingViewModel, text: String) {
    Row {
        IconButton(onClick = { vm.speakText(text, 1.0f) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "모범 답변 듣기") }
        TextButton(onClick = { vm.speakText(text, 0.75f) }) { Text("0.75x") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultPanel(r: AnalyzedAttempt, ui: SpeakingUi, vm: SpeakingViewModel) {
    val fb = r.feedback
    val a = r.attempt
    val m = r.metrics
    SectionCard(
        title = "결과",
        trailing = { SourceTag(if (fb.source == "AI") "AI 분석 (Claude)" else "로컬 분석 (AI 아님)", if (fb.source == "AI") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        if (a.transcript.isNotBlank() || fb.score > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${fb.score}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("학습용 참고 점수 · OPIc 공식 등급과 무관", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("발화 시간", m.pauses?.let { formatMs(it.speechMs) } ?: "측정 안 됨")
            MetricTile("2초+ 무음", m.pauses?.longPauseCount?.let { "${it}회" } ?: "-")
            MetricTile("단어 수", "${m.discourse.wordCount}")
            if (a.wpm > 0) MetricTile("속도", "${a.wpm} wpm")
            MetricTile("시제", "과${m.discourse.pastCount}·현${m.discourse.presentCount}·미${m.discourse.futureCount}")
            MetricTile("연결 표현", "${m.discourse.connectorCount}회")
            m.compare?.let { MetricTile("내용 일치", "${(it.coverage * 100).toInt()}%") }
        }
        r.previous?.let { p ->
            Spacer(Modifier.height(10.dp))
            val dScore = a.score - p.score
            val dWords = a.wordCount - p.wordCount
            val dPause = a.longPauseCount - p.longPauseCount
            Banner(
                "이전 시도 대비: 점수 ${signed(dScore)}, 단어 ${signed(dWords)}, 긴 무음 ${signed(dPause)}회" +
                    when { dScore > 0 && dWords >= 0 -> " · 좋아졌습니다."; dScore < 0 -> " · 이번엔 조금 떨어졌습니다."; else -> "" },
                tone = if (dScore >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("잘한 점", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
        fb.strengths.forEach { Bullet(it) }
        if (fb.fixes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("우선 고칠 점", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
            fb.fixes.forEachIndexed { i, f ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${i + 1}. ${f.issue}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    if (f.why.isNotBlank()) Text(f.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (f.better.isNotBlank()) Text("→ ${f.better}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        m.compare?.let { cmp ->
            val missing = cmp.sentences.filter { !it.covered }
            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("빠진 내용", style = MaterialTheme.typography.titleSmall)
                missing.take(4).forEach { Bullet(it.sentence, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        val better = fb.betterAnswer.ifBlank { ui.question?.script?.studyText.orEmpty() }
        if (better.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (fb.betterAnswer.isNotBlank()) "더 자연스러운 답변 예시 (AI)" else "학습본 다시 보기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.speakText(better, 0.9f) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "듣기") }
            }
            Text(better, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(10.dp))
        Banner("다시 말하기 미션: ${fb.retryMission}", tone = MaterialTheme.colorScheme.primary)
        if (a.transcript.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("인식된 내 답변", style = MaterialTheme.typography.titleSmall)
            Text(a.transcript, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.audioPath != null) {
                FilledTonalButton(onClick = { vm.playRecording(1.0f) }) { Icon(Icons.Filled.PlayArrow, null); Text("내 녹음") }
                OutlinedButton(onClick = { vm.playRecording(0.75f) }) { Text("0.75x") }
            }
            OutlinedButton(onClick = { vm.next() }) { Icon(Icons.Filled.Refresh, null); Text("다음 질문") }
        }
        if (fb.limits.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Text(if (open) "측정 한계 접기" else "측정 한계 보기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { open = !open })
            if (open) fb.limits.forEach { Bullet(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
