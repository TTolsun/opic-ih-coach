package com.opicih.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.learning.DiagnosticCriterion
import com.opicih.coach.learning.DiagnosticSession
import com.opicih.coach.learning.DiagnosticStatus
import com.opicih.coach.learning.SpeakingDiagnostic
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.InfoButton
import com.opicih.coach.ui.components.InfoTopics
import com.opicih.coach.ui.components.MicPermissionBanner
import com.opicih.coach.ui.components.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SpeakingDiagnosticScreen() {
    val vm: SpeakingDiagnosticViewModel = viewModel()
    val data by vm.data.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val operation by vm.operation.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val snapshot = data
    val session = snapshot?.sessions?.firstOrNull { it.id == selected }
    val question = snapshot?.nextQuestions(session)?.firstOrNull()
    var micDenied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) question?.let(vm::startRecording)
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.onBackground()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = selected != null, onBack = vm::close)
    val scroll = rememberScrollState()
    LaunchedEffect(selected, question) { scroll.scrollTo(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        operation.error?.let { Banner(it, tone = MaterialTheme.colorScheme.error) }
        when {
            snapshot == null -> CircularProgressIndicator()
            selected == null -> {
                SectionCard(title = "진단 시작", trailing = { InfoButton(InfoTopics.DIAGNOSTIC) }) {
                    Text(PracticeModes.of(PracticeMode.DIAGNOSTIC).hint)
                    val inProgress = snapshot.sessions.firstOrNull { !it.complete }
                    val hasNew = snapshot.nextQuestions() != null
                    Button(
                        onClick = { if (inProgress == null) vm.start() else vm.open(inProgress.id) },
                        enabled = inProgress != null || hasNew,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (inProgress == null) "새 진단 시작" else "진단 이어서 하기") }
                    if (!hasNew && inProgress == null) Text("남은 진단 문항이 없습니다.")
                }
                snapshot.sessions.forEach { saved ->
                    SectionCard(title = diagnosticDate(saved.startedAt)) {
                        Text("${saved.answers.size} / ${SpeakingDiagnostic.types.size}문항 저장")
                        TextButton(onClick = { vm.open(saved.id) }) { Text(if (saved.complete) "결과 보기" else "이어서 답하기") }
                    }
                }
            }
            session?.complete == true -> {
                val previous = snapshot.sessions.dropWhile { it.id != session.id }.drop(1).firstOrNull { it.complete }
                SectionCard(title = "진단 결과", trailing = { InfoButton(InfoTopics.DIAGNOSTIC) }) {
                    Text("${diagnosticDate(session.startedAt)} · ${session.answers.size}문항")
                    previous?.let { Text("이전 진단 ${diagnosticDate(it.startedAt)}") }
                }
                SpeakingDiagnostic.types.forEach { type ->
                    DiagnosticAnswerCard(
                        session.answers.getValue(type), previous?.answers?.get(type), type, operation,
                        onPlay = vm::playAnswer, onReview = vm::review,
                    )
                }
                OutlinedButton(onClick = vm::close, enabled = !operation.busy, modifier = Modifier.fillMaxWidth()) { Text("진단 목록") }
            }
            question != null -> {
                val number = SpeakingDiagnostic.types.indexOf(question.type) + 1
                SectionCard(title = "$number / ${SpeakingDiagnostic.types.size} · ${question.type.label}") {
                    Text(question.text, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { vm.playQuestion(question) }, enabled = !operation.busy) { Text("질문 듣기") }
                }
                if (micDenied) MicPermissionBanner()
                Button(
                    onClick = {
                        if (operation.recording) vm.saveRecording(question)
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            micDenied = false
                            vm.startRecording(question)
                        } else permission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = !operation.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(when {
                        operation.saving -> "녹음 저장 중"
                        operation.recording -> "답변 저장"
                        else -> "녹음 시작"
                    })
                }
                OutlinedButton(onClick = vm::close, enabled = !operation.saving, modifier = Modifier.fillMaxWidth()) { Text("진단 목록") }
            }
            else -> {
                Text("남은 문항을 불러오지 못했습니다.")
                OutlinedButton(onClick = vm::close) { Text("진단 목록") }
            }
        }
    }
}

@Composable
private fun DiagnosticAnswerCard(
    answer: PracticeAttemptEntity,
    previous: PracticeAttemptEntity?,
    type: QuestionType,
    operation: DiagnosticOperation,
    onPlay: (String) -> Unit,
    onReview: (PracticeAttemptEntity) -> Unit,
) {
    val report = AttemptMetrics.from(answer)?.diagnostic
    val prior = previous?.takeIf { it.questionText != answer.questionText }?.let { AttemptMetrics.from(it)?.diagnostic }
    val canReview = answer.audioPath != null || SpeakingDiagnostic.hasUsableTranscript(answer.transcript)
    SectionCard(title = type.label) {
        Text(answer.questionText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            answer.audioPath?.let { path -> TextButton(onClick = { onPlay(path) }) { Text("녹음 듣기") } }
            val needsReview = report == null || report.observations.values.all { it.status == DiagnosticStatus.UNASSESSED }
            if (canReview && needsReview) {
                TextButton(onClick = { onReview(answer) }, enabled = !operation.busy) {
                    Text(if (operation.reviewing == answer.id) "관찰 중" else if (report == null) "관찰 결과 만들기" else "다시 관찰하기")
                }
            }
        }
        if (report == null) {
            Text(if (canReview) "관찰 결과 대기 중" else "관찰할 녹음이 없습니다.", style = MaterialTheme.typography.bodySmall)
        } else {
            DiagnosticCriterion.entries.filter { it != DiagnosticCriterion.TIME || type in SpeakingDiagnostic.timeTasks }.forEach { criterion ->
                val observation = report.observations[criterion]
                val status = observation?.status ?: DiagnosticStatus.UNASSESSED
                Text("${criterion.label} · ${status.label}", style = MaterialTheme.typography.titleSmall)
                observation?.quote?.let { Text("“$it”") }
                SpeakingDiagnostic.compare(report, prior, criterion)?.let { previousStatus ->
                    val quote = prior?.observations?.get(criterion)?.quote.orEmpty()
                    Text("이전 · ${previousStatus.label} · “$quote”", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val diagnosticDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

private fun diagnosticDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(diagnosticDateFormat)
