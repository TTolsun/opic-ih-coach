package com.opicih.coach.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.learning.HotelRoleplay
import com.opicih.coach.learning.HotelStage
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.MicPermissionBanner
import com.opicih.coach.ui.components.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HotelRoleplayScreen() {
    val vm: HotelRoleplayViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    val questions by vm.questions.collectAsStateWithLifecycle()
    val recognition by vm.recognition.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var micDenied by rememberSaveable { mutableStateOf(false) }
    var requestedMode by rememberSaveable { mutableStateOf(CaptureMode.RECORD_ONLY.name) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) vm.startCapture(CaptureMode.valueOf(requestedMode))
    }
    fun capture(mode: CaptureMode) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micDenied = false
            vm.startCapture(mode)
        } else {
            requestedMode = mode.name
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(owner) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                vm.stopCapture()
                vm.stopAudio()
            }
        }
        owner.lifecycle.addObserver(listener)
        onDispose {
            owner.lifecycle.removeObserver(listener)
            vm.stopAudio()
            if ((context as? Activity)?.isChangingConfigurations != true) vm.close()
        }
    }

    val set = ui.set
    val stage = set?.nextStage
    val question = stage?.let { questions?.get(it) }
    BackHandler(enabled = set != null) { vm.close() }
    val scroll = rememberScrollState()
    LaunchedEffect(set?.id, stage) { scroll.scrollTo(0) }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(PracticeMode.HOTEL_ROLEPLAY.label, style = MaterialTheme.typography.headlineMedium)
        ui.error?.let { Banner(it, tone = MaterialTheme.colorScheme.error) }
        if (set == null) {
            Text(PracticeModes.of(PracticeMode.HOTEL_ROLEPLAY).hint)
            Button(onClick = vm::start, enabled = sets != null && !questions.isNullOrEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("새 세트 시작")
            }
            if (sets == null || questions == null) CircularProgressIndicator()
            if (questions?.isEmpty() == true) Text("호텔 질문을 불러오지 못했습니다.")
            sets.orEmpty().forEach { saved ->
                val date = Instant.ofEpochMilli(saved.startedAt).atZone(ZoneId.systemDefault()).format(hotelDateFormat)
                SectionCard(title = date) {
                    Text("${saved.answers.size} / ${HotelStage.entries.size}단계 저장")
                    TextButton(onClick = { vm.open(saved) }, enabled = saved.nextStage == null || !questions.isNullOrEmpty()) {
                        Text(if (saved.nextStage == null) "결과 보기" else "이어서 답하기")
                    }
                }
            }
        } else if (stage == null) {
            HotelResult(set, onPlay = vm::playAnswer)
            Button(onClick = vm::close, modifier = Modifier.fillMaxWidth()) { Text("세트 목록") }
        } else if (question != null) {
            SectionCard(title = "${stage.number} · ${stage.label}") {
                Text(question.text, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = vm::playQuestion, enabled = !ui.recording && !ui.busy) { Text("질문 듣기") }
            }
            if (micDenied) MicPermissionBanner()
            if (ui.recording) {
                OutlinedButton(onClick = vm::stopCapture, modifier = Modifier.fillMaxWidth()) { Text("음성 답변 마치기") }
                if (ui.recordingMode == CaptureMode.LIVE_STT) {
                    recognition.error?.let { Banner(it, tone = MaterialTheme.colorScheme.error) }
                    if (recognition.fullText.isNotBlank()) Text(recognition.fullText)
                }
            } else if (ui.captured == null) {
                if (vm.onDeviceAvailable) {
                    OutlinedButton(onClick = { capture(CaptureMode.LIVE_STT) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("기기 내 음성 인식")
                    }
                }
                OutlinedButton(onClick = { capture(CaptureMode.RECORD_ONLY) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("녹음 시작")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.captured?.audioPath?.let { path ->
                        OutlinedButton(onClick = { vm.playAnswer(path) }, enabled = !ui.busy) { Text("내 녹음 듣기") }
                    }
                    TextButton(onClick = vm::discardCapture, enabled = !ui.busy) { Text("음성 다시 받기") }
                }
            }
            OutlinedTextField(
                value = ui.draft,
                onValueChange = vm::edit,
                enabled = !ui.recording && !ui.busy,
                label = { Text(if (ui.captured?.fromDeviceStt == true) "인식한 답변" else "답변 직접 입력") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = vm::save,
                enabled = !ui.busy && !ui.recording && (ui.draft.isNotBlank() || ui.captured?.audioPath != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (stage == HotelStage.EXPERIENCE) "저장하고 결과 보기" else "저장하고 다음")
            }
            if (ui.busy) CircularProgressIndicator()
        }
    }
}

private val hotelDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")

@Composable
private fun HotelResult(set: HotelSet, onPlay: (String) -> Unit) {
    val transcript = set.answers.getValue(HotelStage.ALTERNATIVES).transcript
    val excerpts = HotelRoleplay.alternativeExcerpts(transcript)
    SectionCard(title = "내가 제시한 대안") {
        when {
            transcript.isBlank() -> Text("대안을 확인할 답변 텍스트가 없습니다.")
            excerpts.isEmpty() -> Text("답변에서 대안 표현을 확인하지 못했습니다.")
            else -> excerpts.forEach { Bullet(it) }
        }
    }
    HotelStage.entries.forEach { stage ->
        val answer = set.answers.getValue(stage)
        SectionCard(title = "${stage.number} · ${stage.label}") {
            val source = when (answer.captureMode) {
                "TYPED" -> "직접 입력"
                CaptureMode.LIVE_STT.name -> "기기 내 음성 인식"
                else -> "녹음"
            }
            Text(source, style = MaterialTheme.typography.labelMedium)
            Text(answer.transcript.ifBlank { "저장된 답변 텍스트가 없습니다." })
            answer.audioPath?.let { path ->
                TextButton(onClick = { onPlay(path) }) { Text("내 녹음 듣기") }
            }
        }
    }
}
