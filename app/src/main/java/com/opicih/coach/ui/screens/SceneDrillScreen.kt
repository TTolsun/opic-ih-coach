package com.opicih.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opicih.coach.analysis.VerbatimMarkup
import com.opicih.coach.learning.QuestionItem
import com.opicih.coach.learning.SceneCoaching
import com.opicih.coach.learning.SpeakingDiagnostic
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.MicPermissionBanner
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.formatMs
import com.opicih.coach.ui.theme.EnglishReading

internal enum class SceneStep(val title: String) {
    FIRST("첫 질문"), SECOND("바뀐 질문"), FEEDBACK("내 답변 피드백"), RETRY("같은 질문 다시 말하기"), DONE("다시 말한 답변")
}

@Composable
internal fun SceneDrillScreen(scene: String, onDone: () -> Unit) {
    val container = LocalContainer.current
    val drill = remember(scene) { container.seed.sceneDrill(scene) }
    if (drill == null) {
        Column(Modifier.padding(16.dp)) {
            Text("이 장면의 연습 질문을 찾을 수 없어요.")
            TextButton(onClick = onDone) { Text("장면 목록으로") }
        }
        return
    }
    val vm: SpeakingViewModel = viewModel { SpeakingViewModel(container, container.newSpeakingCapture()) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var step by rememberSaveable(scene) { mutableStateOf(SceneStep.FIRST) }
    var first by rememberSaveable(scene) { mutableStateOf("") }
    var second by rememberSaveable(scene) { mutableStateOf("") }
    var revised by rememberSaveable(scene) { mutableStateOf("") }
    var correction by rememberSaveable(scene) { mutableStateOf("") }
    val prompt = if (step == SceneStep.FIRST) drill.first else drill.second
    val answering = step == SceneStep.FIRST || step == SceneStep.SECOND || step == SceneStep.RETRY
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    LaunchedEffect(step, ui.result?.attempt?.id) {
        if (!answering) return@LaunchedEffect
        vm.openSceneTurn(step, QuestionItem(scene, prompt.text, prompt.type.name, "질문 바꿔 말하기"))
        val result = vm.ui.value.result ?: return@LaunchedEffect
        val text = VerbatimMarkup.parse(result.attempt.transcript).clean
        when (step) {
            SceneStep.FIRST -> { first = text; step = SceneStep.SECOND }
            SceneStep.SECOND -> {
                second = text
                correction = SceneCoaching.correction(drill, first, text, result.feedback.errors)
                step = SceneStep.FEEDBACK
            }
            SceneStep.RETRY -> { revised = text; step = SceneStep.DONE }
            else -> Unit
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(vm, owner) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { vm.onBackground(); vm.stopAudio() }
        }
        owner.lifecycle.addObserver(listener)
        onDispose { owner.lifecycle.removeObserver(listener); vm.stopAudio() }
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(scene, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (answering) {
            SectionCard(title = step.title) {
                Text(prompt.text, style = EnglishReading)
                TextButton(onClick = { vm.playQuestion() }, enabled = ui.phase == Phase.IDLE && ui.question?.text == prompt.text) { Text("질문 듣기") }
            }
            if (step == SceneStep.RETRY) SectionCard(title = "이번에 고칠 한 가지") { Text(correction) }
            if (ui.question?.text == prompt.text) SceneAnswerControls(vm, ui, step)
        } else if (step == SceneStep.FEEDBACK) {
            SectionCard(title = "잘 쓴 표현 하나") {
                val kept = remember(drill, first, second) { SceneCoaching.expression(drill, first, second, container.glossary.seed) }
                if (kept == null) Text("두 답변에서 짚어 낼 만한 영어 표현을 찾기 어려웠어요.")
                else {
                    Text(if (kept.fromSecond) "바뀐 질문에서 한 말" else "첫 질문에서 한 말", style = MaterialTheme.typography.labelMedium)
                    Text("“${kept.words}”", style = EnglishReading)
                    Text(kept.why)
                }
            }
            SectionCard(title = "고칠 부분 하나") { Text(correction) }
            Button(onClick = { step = SceneStep.RETRY }, modifier = Modifier.fillMaxWidth()) { Text("바뀐 질문 다시 말하기") }
            SectionCard(title = "내가 한 두 답변", collapsible = true, initiallyExpanded = false) {
                Text(drill.first.text, style = MaterialTheme.typography.labelLarge)
                Text(first)
                Spacer(Modifier.height(8.dp))
                Text(drill.second.text, style = MaterialTheme.typography.labelLarge)
                Text(second)
            }
        } else {
            Text(drill.second.text, style = EnglishReading)
            SectionCard(title = "다시 말하기 전") { Text(second) }
            SectionCard(title = SceneStep.DONE.title) { Text(revised) }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("연습 마치기") }
        }
    }
}

@Composable
private fun SceneAnswerControls(vm: SpeakingViewModel, ui: SpeakingUi, step: SceneStep) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted && vm.ui.value.phase == Phase.IDLE) vm.toggleMic()
    }
    var typing by rememberSaveable(step) { mutableStateOf(false) }
    var draft by rememberSaveable(step) { mutableStateOf("") }
    if (denied) MicPermissionBanner()
    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    when (ui.phase) {
        Phase.PROCESSING, Phase.RESULT -> CircularProgressIndicator()
        Phase.RECORDING -> {
            Text("녹음 중 · ${formatMs(ui.elapsedMs)}")
            Button(onClick = { vm.stop() }, modifier = Modifier.fillMaxWidth()) { Text("답변 마치기") }
        }
        Phase.IDLE -> {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    denied = false
                    vm.toggleMic()
                } else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.fillMaxWidth()) { Text("말하기 시작") }
            if (vm.hasPendingRecording) TextButton(onClick = vm::playPendingRecording) { Text("내 녹음 듣기") }
            if (typing || ui.showTypedFallback) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("내가 말한 영어") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedButton(onClick = { vm.submitTyped(draft) }, enabled = SpeakingDiagnostic.hasUsableTranscript(draft)) {
                    Text("답변 제출")
                }
            } else TextButton(onClick = { typing = true }) { Text("직접 입력") }
        }
    }
}
