package com.opicih.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.Visibility
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
import com.opicih.coach.ui.components.MicPermissionBanner
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.components.formatMs
import com.opicih.coach.ui.theme.EnglishReading
import com.opicih.coach.ui.components.GlossaryText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeakingScreen(scriptId: Long?, mode: PracticeMode?, reviewId: Long? = null, onOpenFrames: () -> Unit = {}) {
    val vm: SpeakingViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(scriptId, mode, reviewId) { vm.configure(scriptId, mode, reviewId) }

    // Stop the mic when the app goes to background (call, home button, screen off).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) { vm.onBackground(); vm.stopAudio() }
            if (e == Lifecycle.Event.ON_RESUME) vm.refreshFrames()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var micDenied by rememberSaveable { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) vm.toggleMic()
    }
    fun onMic() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micDenied = false
            vm.toggleMic()
        } else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    var showPicker by remember { mutableStateOf(false) }
    var showTyped by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ModeSelector(ui.mode) { vm.setMode(it) }

        if (micDenied) MicPermissionBanner()

        val q = ui.question
        if (q == null) {
            SectionCard { Text(ui.error ?: "질문을 불러오는 중입니다.") }
            return@Column
        }

        val examLike = PracticeModes.of(ui.mode).stage != PracticeStage.MEMORIZE

        // Question card
        SectionCard {
            // FlowRow so a tag that does not fit moves to the next line instead of being squeezed.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(q.topic, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SourceTag(QuestionType.of(q.type).label)
                SourceTag(q.sourceLabel)
            }
            Spacer(Modifier.height(10.dp))
            // Recall/apply stages copy exam conditions: the exam asks by audio only, so the text stays hidden
            // until the learner asks for it or the result is shown.
            if (examLike && ui.phase != Phase.RESULT) {
                var reveal by remember(q.text) { mutableStateOf(false) }
                if (reveal) Text(q.text, style = EnglishReading)
                else OutlinedButton(onClick = { reveal = true }) { Icon(Icons.Filled.Visibility, null); Spacer(Modifier.width(6.dp)); Text("질문 보기") }
            } else {
                Text(q.text, style = EnglishReading)
            }
            // 틀 붙이기는 아래 틀 카드에 같은 내용을 보여 주므로 미션 배너를 따로 띄우지 않습니다.
            q.mission?.takeIf { ui.mode != PracticeMode.FRAME_ATTACH }?.let {
                Spacer(Modifier.height(8.dp))
                Banner("미션: $it", tone = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (examLike) {
                    // Exam rule: one listen plus one replay. 0.75x is a memorize-stage aid only.
                    val left = EXAM_PLAYS - ui.replays
                    FilledTonalButton(enabled = left > 0, onClick = { vm.playQuestion(1.0f) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null); Spacer(Modifier.width(6.dp))
                        Text(if (ui.replays == 0) "질문 듣기" else "다시 듣기 (${left.coerceAtLeast(0)})")
                    }
                } else {
                    FilledTonalButton(onClick = { vm.playQuestion(1.0f) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, null); Spacer(Modifier.width(6.dp)); Text("질문 듣기") }
                    TextButton(onClick = { vm.playQuestion(0.75f) }) { Text("0.75x") }
                }
                Spacer(Modifier.weight(1f))
                if (ui.mode.usesScript) IconButton(onClick = { showPicker = true }) { Icon(Icons.Filled.List, "스크립트 선택") }
                IconButton(onClick = { vm.next() }) { Icon(Icons.Filled.SkipNext, "다음 질문") }
            }
        }

        // Mode-specific support panel
        ModePanel(ui, vm)
        PracticePanel(ui, onOpenFrames)

        // Mic area
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (ui.phase) {
                Phase.PROCESSING -> { CircularProgressIndicator(); Text("분석 중", style = MaterialTheme.typography.bodyMedium) }
                else -> {
                    MicButton(recording = ui.phase == Phase.RECORDING) { onMic() }
                    Text(
                        when (ui.phase) {
                            Phase.RECORDING -> "${formatMs(ui.elapsedMs)} · 눌러서 끝내기"
                            Phase.RESULT -> "다시 말하기"
                            else -> "눌러서 말하기"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (ui.phase == Phase.RECORDING) {
                LevelBar(ui.level, Modifier.fillMaxWidth(0.6f))
                if (examLike) {
                    // No live captions here: reading your own words while speaking is not possible in the exam.
                    when {
                        ui.elapsedMs >= 120_000 -> Text("2분 넘음 · 마무리하기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                        ui.elapsedMs >= 60_000 -> Text("목표 구간 (1~2분)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    }
                } else if (ui.liveText.isNotBlank()) {
                    Text(ui.liveText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.phase == Phase.IDLE || ui.showTypedFallback) {
                TextButton(onClick = { showTyped = true }) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(4.dp)); Text("직접 입력") }
            }
        }

        ui.error?.let { Banner(it, "닫기", tone = MaterialTheme.colorScheme.error) { vm.dismissError() } }

        ui.check?.let { if (ui.result != null) CheckCard(it, vm) }

        ui.result?.let { ResultPanel(it, ui, vm, onRetry = { onMic() }) }

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
                    OutlinedTextField(text, { text = it }, minLines = 5, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { showTyped = false; vm.submitTyped(text) }) { Text("분석") } },
            dismissButton = { TextButton(onClick = { showTyped = false }) { Text("취소") } },
        )
    }
}

/** First listen plus one replay, as in the exam. */
private const val EXAM_PLAYS = 2

/** 틀 붙이기에서 오늘 쓸 틀을 보여 줍니다. 틀 자체를 보여 주는 카드이고, 다른 모드에서는 아무것도 그리지 않습니다. */
@Composable
private fun PracticePanel(ui: SpeakingUi, onOpenFrames: () -> Unit) {
    val q = ui.question ?: return
    if (ui.mode != PracticeMode.FRAME_ATTACH) return
    SectionCard(title = "이 틀로 답하기") {
        if (q.frames.isEmpty()) Banner("승인한 문장 틀이 없습니다.", "틀 고르기") { onOpenFrames() }
        q.frames.forEach { f ->
            Text(f.pattern, style = EnglishReading)
            Text(f.hintKo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 이 모드의 목표를 달성했는지(틀 사용, 사다리 단계)를 점수와 별개로 보여 줍니다. */
@Composable
private fun CheckCard(check: PracticeCheck, vm: SpeakingViewModel) {
    if (check.frameUsage.isNotEmpty()) SectionCard(title = "틀 사용") {
        check.frameUsage.forEach { (f, used) -> Bullet((if (used) "사용함 · " else "안 씀 · ") + f.pattern) }
    }
    check.ladder?.let { r ->
        SectionCard(title = if (r.passed) "${r.level.label} 통과" else "${r.level.label} 아직 부족") {
            r.missing.forEach { Bullet(it) }
            if (r.passed) {
                val next = r.level.next()
                if (next != null) FilledTonalButton(onClick = { vm.ladderAdvance() }) { Text("${next.label}로 올리기") }
                else FilledTonalButton(onClick = { vm.next() }) { Text("다른 질문") }
            }
        }
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
            GlossaryText(text, s.id)
            s.koreanMeaning?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        PracticeMode.SHADOWING -> {
            val sentences = vm.shadowSentences()
            val cur = sentences.getOrNull(ui.shadowIndex).orEmpty()
            SectionCard(title = "${ui.shadowIndex + 1} / ${sentences.size}") {
                GlossaryText(cur, s.id)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shadowMove(-1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "이전 문장") }
                    FilledTonalButton(onClick = { vm.speakText(cur, 1.0f) }) { Icon(Icons.Filled.PlayArrow, null); Text("1.0x") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { vm.speakText(cur, 0.75f) }) { Text("0.75x") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.shadowMove(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "다음 문장") }
                }
            }
        }
        PracticeMode.FILL_BLANK -> {
            val words = remember(text) { text.split(Regex("(?<=\\s)|(?=\\s)")) }
            val revealed = remember(text) { mutableStateListOf<Int>() }
            SectionCard(trailing = { ModelButtons(vm, text) }) {
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
            }
        }
        PracticeMode.KEYWORD -> SectionCard(trailing = { if (s.keywords == null) SourceTag("자동 추출") }) {
            val kws = s.keywordList().ifEmpty { com.opicih.coach.analysis.ScriptComparator.pickKeywords(text, 5) }
            kws.forEachIndexed { i, k -> Bullet(k, mark = "${i + 1}") }
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
