package com.opicih.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.learning.MorningItem
import com.opicih.coach.learning.MorningRating
import com.opicih.coach.learning.MorningStep
import com.opicih.coach.speech.LiveRecognizer
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.theme.EnglishReading
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningScreen(
    onReviewScript: (Long) -> Unit,
    onExit: () -> Unit,
    vm: MorningViewModel = viewModel(),
) {
    val state = vm.state
    val index = state?.index ?: 0
    val saving = state?.pending != null
    val previous = { vm.previous(index) }
    BackHandler { previous() }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("아침 표현 루틴", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                TextButton(onClick = previous, enabled = index > 0 && !saving) { Text("이전") }
            },
            actions = { TextButton(onClick = onExit, enabled = !saving) { Text("나가기") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        if (state == null || state.itemCount == 0) {
            Column(Modifier.padding(16.dp)) {
                when {
                    vm.loadFailed -> {
                        Text("표현을 불러오지 못했어요.")
                        TextButton(onClick = vm::load) { Text("다시 불러오기") }
                    }
                    state == null -> Text("표현을 불러오는 중입니다.")
                    else -> {
                        Text("연습할 표현이 없어요.")
                        TextButton(onClick = vm::load) { Text("다시 불러오기") }
                    }
                }
            }
            return@Column
        }

        key(index) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.isSummary) {
                    MorningSummary(vm.dueScripts, previous, onReviewScript, onExit)
                } else {
                    val item = vm.items[index]
                    Text(
                        "${index + 1}/${state.itemCount} · ${item.step.label}",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(
                        progress = { (index + 1).toFloat() / state.itemCount },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MorningPrompt(item, enabled = !saving)
                    MorningRatingOptions(state.selection, enabled = !saving) { vm.select(index, it) }
                }
            }
        }
        if (!state.isSummary) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                if (state.saveFailed) {
                    Text(
                        "평가를 저장하지 못했어요.",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = { vm.next(index) },
                    enabled = state.selection != null && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        when {
                            saving -> "저장 중"
                            state.saveFailed -> "다시 저장"
                            state.isLastItem -> "완료"
                            else -> "다음"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MorningRatingOptions(
    selection: MorningRating?,
    enabled: Boolean,
    onSelect: (MorningRating) -> Unit,
) {
    SectionCard(title = "자기평가") {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (rating in MorningRating.entries) {
                val selected = selection == rating
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(rating) })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, enabled = enabled, onClick = null)
                        Text(rating.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MorningPrompt(item: MorningItem, enabled: Boolean) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { LiveRecognizer(context) }
    val live by recognizer.state.collectAsState()
    var finishing by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val ch = item.chunk
    val canSpeak = item.step == MorningStep.COMPLETE || item.step == MorningStep.PRONOUNCE
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && enabled) recognizer.start()
    }
    DisposableEffect(recognizer) {
        onDispose {
            recognizer.release()
            c.tts.stop()
        }
    }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.step) {
                MorningStep.COMPLETE -> {
                    Text(item.cloze ?: ch.example, style = EnglishReading)
                    Text(ch.meaningKo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (revealed) Text(ch.phrase, style = MaterialTheme.typography.titleMedium)
                }
                MorningStep.PRONOUNCE -> Text(ch.example, style = EnglishReading)
                MorningStep.WRONG, MorningStep.NEW -> {
                    Text(ch.phrase, style = MaterialTheme.typography.headlineSmall)
                    Text(ch.meaningKo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (revealed || item.step == MorningStep.NEW) Text(ch.example, style = EnglishReading)
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canSpeak) {
                    FilledTonalButton(enabled = enabled && !finishing, onClick = {
                        if (live.active) {
                            finishing = true
                            scope.launch {
                                recognizer.finish()
                                finishing = false
                            }
                        } else {
                            c.tts.stop()
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                recognizer.start()
                            } else {
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }) {
                        Icon(if (live.active) Icons.Filled.Stop else Icons.Filled.Mic, null, Modifier.size(18.dp))
                        Text(if (live.active) "말하기 멈춤" else "말하기", Modifier.padding(start = 6.dp))
                    }
                }
                if (!revealed && (item.step == MorningStep.WRONG || item.step == MorningStep.COMPLETE)) {
                    TextButton(onClick = { revealed = true }, enabled = enabled) {
                        Text(if (item.step == MorningStep.COMPLETE) "정답 보기" else "예문 보기")
                    }
                }
                if (item.step != MorningStep.COMPLETE || revealed) {
                    TextButton(
                        onClick = { c.tts.speak(if (item.step == MorningStep.WRONG && !revealed) ch.phrase else ch.example, 0.9f) },
                        enabled = enabled && !live.active && !finishing,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(18.dp))
                        Text("듣기", Modifier.padding(start = 6.dp))
                    }
                }
                if (item.step == MorningStep.PRONOUNCE) {
                    TextButton(
                        onClick = { c.tts.speak(ch.example, 0.7f) },
                        enabled = enabled && !live.active && !finishing,
                    ) { Text("느리게 듣기") }
                }
            }

            val said = live.fullText
            if (said.isNotBlank()) {
                Text("음성 인식", style = MaterialTheme.typography.labelLarge)
                Text(said, style = MaterialTheme.typography.bodyMedium)
                val target = TextNormalizer.contentLemmas(ch.phrase).toSet()
                val got = TextNormalizer.contentLemmas(said).toSet()
                val matched = target.isEmpty() || target.count { it in got } * 2 >= target.size
                Text(
                    if (matched) "핵심 표현을 인식했어요." else "핵심 표현이 들리지 않았어요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (live.error != null && !live.active) {
                Text("음성을 인식하지 못했어요.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MorningSummary(
    dueScripts: List<ScriptEntity>,
    onEdit: () -> Unit,
    onReviewScript: (Long) -> Unit,
    onExit: () -> Unit,
) {
    Text(
        "아침 루틴 완료",
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
    )
    OutlinedButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("평가 수정") }
    SectionCard(title = "오늘 복습할 스크립트") {
        if (dueScripts.isEmpty()) {
            Text("오늘 복습할 스크립트가 없어요.", style = MaterialTheme.typography.bodyMedium)
        }
        for (script in dueScripts) {
            TextButton(onClick = { onReviewScript(script.id) }) { Text("${script.topic} · ${script.title}") }
        }
    }
    dueScripts.firstOrNull()?.let { script ->
        Button(onClick = { onReviewScript(script.id) }) { Text("바로 복습 말하기") }
    }
    TextButton(onClick = onExit) { Text("홈으로") }
}
