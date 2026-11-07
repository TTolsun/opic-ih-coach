package com.opicih.coach.ui.screens

import android.Manifest
import android.app.Application
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opicih.coach.App
import com.opicih.coach.data.db.MockTestEntity
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.data.repo.appJson
import com.opicih.coach.learning.Focus
import com.opicih.coach.learning.MockQuestion
import com.opicih.coach.learning.MockTestBuilder
import com.opicih.coach.learning.QuestionSource
import com.opicih.coach.speech.AnswerCapture
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Bullet
import com.opicih.coach.ui.components.MetricTile
import com.opicih.coach.ui.components.InfoButton
import com.opicih.coach.ui.components.InfoTopics
import com.opicih.coach.ui.components.MicButton
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.components.formatMs
import com.opicih.coach.ui.theme.EnglishReading
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class MockReport(
    val totalSpeechMs: Long,
    val totalDurationMs: Long,
    val longPauses: Int,
    val answered: Int,
    val byType: Map<String, Int>,
    val avgWords: Int,
    val timeFrames: Map<String, Int>,
    val avgConnectors: Float,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val focusTopics: List<String>,
    val next3Days: List<String>,
)

enum class MockPhase { SETUP, QUESTION, RECORDING, SAVING, ADJUST, REPORT }

data class MockUi(
    val phase: MockPhase = MockPhase.SETUP,
    val count: Int = 15,
    val questions: List<MockQuestion> = emptyList(),
    val index: Int = 0,
    val replaysLeft: Int = 1,
    val testStartedAt: Long = 0,
    val testElapsedMs: Long = 0,
    val answerElapsedMs: Long = 0,
    val mockId: Long = 0,
    val report: MockReport? = null,
    val error: String? = null,
    val adjusted: Boolean = false,
)

class MockViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container
    private val capture = AnswerCapture(app, c.backup.audioDir())
    val ui = MutableStateFlow(MockUi())
    private var pool: List<QuestionSource> = emptyList()
    private var settings = Settings()
    private var timer: Job? = null

    fun setCount(n: Int) = ui.update { it.copy(count = n) }

    fun start() = viewModelScope.launch {
        settings = c.settings.current()
        pool = c.dao.questions().map { QuestionSource(it.topic, it.text, it.type, if (it.source == "USER_LIST") "내 기출 정리(비공식)" else "AI 생성 연습 문제") } +
            c.dao.scripts().map { QuestionSource(it.topic, it.question, it.questionType, "내 스크립트 질문") }
        val qs = MockTestBuilder.build(pool, settings.surveyTopics, ui.value.count, settings.selfLevel)
        val id = c.dao.insertMock(MockTestEntity(questionCount = qs.size, selfLevel = settings.selfLevel))
        ui.value = MockUi(phase = MockPhase.QUESTION, count = qs.size, questions = qs, testStartedAt = System.currentTimeMillis(), mockId = id)
        timer?.cancel()
        timer = viewModelScope.launch {
            while (isActive) {
                ui.update { it.copy(testElapsedMs = System.currentTimeMillis() - it.testStartedAt, answerElapsedMs = if (it.phase == MockPhase.RECORDING) capture.elapsedMs else it.answerElapsedMs) }
                delay(250)
            }
        }
        playQuestion(first = true)
    }

    fun playQuestion(first: Boolean = false) {
        val u = ui.value
        val q = u.questions.getOrNull(u.index) ?: return
        if (!first && u.replaysLeft <= 0) return
        c.tts.speak(q.text, settings.ttsRate)
        if (!first) ui.update { it.copy(replaysLeft = it.replaysLeft - 1) }
    }

    fun toggleMic() {
        when (ui.value.phase) {
            MockPhase.QUESTION -> {
                c.tts.stop()
                capture.start(viewModelScope, settings.captureMode)?.let { e -> ui.update { it.copy(error = e) }; return }
                ui.update { it.copy(phase = MockPhase.RECORDING, error = null, answerElapsedMs = 0) }
            }
            MockPhase.RECORDING -> finishAnswer()
            else -> Unit
        }
    }

    private fun finishAnswer() {
        ui.update { it.copy(phase = MockPhase.SAVING) }
        viewModelScope.launch {
            val u = ui.value
            val q = u.questions[u.index]
            val r = capture.stop(c.transcriber(settings))
            runCatching {
                c.practice.analyzeAndSave(
                    PracticeRepository.Request(null, q.topic, q.text, q.type, PracticeMode.MOCK, r.transcript, r.captureMode, r.audioPath, r.durationMs, r.pauses, r.fromDeviceStt, mockId = u.mockId),
                    ai = null, examEpochDay = settings.examDate.toEpochDay(), // per-answer AI would be slow and costly during a timed test
                )
            }
            val nextIndex = u.index + 1
            when {
                nextIndex >= u.questions.size -> finish()
                nextIndex == 7 && !u.adjusted -> ui.update { it.copy(phase = MockPhase.ADJUST, index = nextIndex, replaysLeft = 1, error = r.warning) }
                else -> { ui.update { it.copy(phase = MockPhase.QUESTION, index = nextIndex, replaysLeft = 1, error = r.warning) }; playQuestion(first = true) }
            }
        }
    }

    fun adjust(a: MockTestBuilder.Adjust) {
        val u = ui.value
        val qs = MockTestBuilder.readjust(u.questions, u.index, pool, settings.surveyTopics, settings.selfLevel, a)
        ui.update { it.copy(questions = qs, phase = MockPhase.QUESTION, adjusted = true) }
        playQuestion(first = true)
    }

    fun skip() {
        val u = ui.value
        if (u.phase != MockPhase.QUESTION) return
        if (u.index + 1 >= u.questions.size) finish() else { ui.update { it.copy(index = it.index + 1, replaysLeft = 1) }; playQuestion(first = true) }
    }

    fun finish() = viewModelScope.launch {
        timer?.cancel()
        capture.cancel(); c.tts.stop()
        val u = ui.value
        val attempts = c.dao.attemptsForMock(u.mockId)
        val report = buildReport(attempts, u.testElapsedMs)
        c.dao.mock(u.mockId)?.let { c.dao.updateMock(it.copy(finishedAt = System.currentTimeMillis(), reportJson = appJson.encodeToString(MockReport.serializer(), report))) }
        c.settings.update { it.copy(focusTopics = report.focusTopics.toSet()) }
        ui.update { it.copy(phase = MockPhase.REPORT, report = report) }
    }

    fun reset() { capture.cancel(); ui.value = MockUi(count = ui.value.count) }

    fun onBackground() { if (ui.value.phase == MockPhase.RECORDING) finishAnswer() }

    private fun buildReport(a: List<PracticeAttemptEntity>, elapsed: Long): MockReport {
        val metrics = a.mapNotNull { c.practice.decodeMetrics(it) }
        val byType = a.groupBy { QuestionType.of(it.questionType).label }.mapValues { (_, l) -> l.map { it.score }.average().toInt() }
        val past = metrics.sumOf { it.discourse.pastCount }; val present = metrics.sumOf { it.discourse.presentCount }; val future = metrics.sumOf { it.discourse.futureCount }
        val avgConn = if (metrics.isEmpty()) 0f else metrics.map { it.discourse.connectorCount }.average().toFloat()
        val strengths = mutableListOf<String>(); val weak = mutableListOf<String>()
        byType.entries.sortedByDescending { it.value }.take(2).forEach { strengths += "${it.key} 유형 평균 ${it.value}점" }
        byType.entries.sortedBy { it.value }.take(2).forEach { weak += "${it.key} 유형 평균 ${it.value}점" }
        if (future == 0) weak += "미래 시제 사용 0회"
        if (avgConn < 3) weak += "답변당 연결 표현 평균 ${"%.1f".format(avgConn)}회 (문장 나열 경향)"
        val short = a.count { it.wordCount in 1..59 }
        if (short > 0) weak += "60단어 미만 짧은 답변 ${short}개"
        val focus = a.groupBy { it.topic }.mapValues { (_, l) -> l.map { it.score }.average() }.entries.sortedBy { it.value }.take(3).map { it.key }
        val weakTypes = byType.entries.sortedBy { it.value }.map { it.key }
        val next = listOf(
            "Day 1: ${focus.getOrNull(0) ?: "약한 주제"} 스크립트 Keyword → Blind 3회",
            "Day 2: ${weakTypes.firstOrNull() ?: "과거 경험"} 유형 자유 발화 3문항 + 롤플레이 1세트",
            "Day 3: ${focus.getOrNull(1) ?: "돌발 주제"} 응용 챌린지 + 미니 모의시험 7문항",
        )
        return MockReport(
            totalSpeechMs = a.sumOf { if (it.speechMs > 0) it.speechMs else it.durationMs }, totalDurationMs = elapsed,
            longPauses = a.sumOf { it.longPauseCount }, answered = a.size, byType = byType,
            avgWords = if (a.isEmpty()) 0 else a.map { it.wordCount }.average().toInt(),
            timeFrames = mapOf("과거" to past, "현재" to present, "미래" to future), avgConnectors = avgConn,
            strengths = strengths, weaknesses = weak.take(4), focusTopics = focus, next3Days = next,
        )
    }

    override fun onCleared() { capture.release(); c.tts.stop(); timer?.cancel() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MockScreen() {
    val vm: MockViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = LocalContainer.current
    val context = LocalContext.current
    val settings by c.settings.flow.collectAsState(initial = Settings())
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleMic() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onBackground() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (ui.phase) {
            MockPhase.SETUP -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("실전 모의시험", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    InfoButton(InfoTopics.MOCK)
                }
                SectionCard {
                    Text("Survey: ${settings.surveyTopics.joinToString()} · Self Assessment ${settings.selfLevel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(12, 15).forEach { n -> Pill("${n}문항", ui.count == n) { vm.setCount(n) } } }
                }
                Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("시작") }
                val mocks by c.dao.mocksFlow().collectAsState(initial = emptyList())
                mocks.firstOrNull { it.reportJson != null }?.let { m ->
                    runCatching { appJson.decodeFromString(MockReport.serializer(), m.reportJson!!) }.getOrNull()?.let { ReportView(it, past = true) }
                }
            }
            MockPhase.QUESTION, MockPhase.RECORDING, MockPhase.SAVING -> {
                val q = ui.questions.getOrNull(ui.index)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Q${ui.index + 1} / ${ui.questions.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    val remain = (40 * 60_000L - ui.testElapsedMs)
                    Text(if (remain >= 0) "남은 시간 ${formatMs(remain)}" else "시간 초과 ${formatMs(-remain)}", color = if (remain < 5 * 60_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { ui.index.toFloat() / ui.questions.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                if (q != null) SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SourceTag(q.stage); SourceTag(q.source) }
                    Spacer(Modifier.height(8.dp))
                    var show by androidx.compose.runtime.remember(q.no) { androidx.compose.runtime.mutableStateOf(false) }
                    TextButton(onClick = { show = !show }) { Text(if (show) "질문 숨기기" else "질문 보기") }
                    if (show) Text(q.text, style = EnglishReading)
                    Row {
                        OutlinedButton(enabled = ui.replaysLeft > 0 && ui.phase == MockPhase.QUESTION, onClick = { vm.playQuestion() }) { Text("다시 듣기 (${ui.replaysLeft})") }
                        Spacer(Modifier.weight(1f))
                        TextButton(enabled = ui.phase == MockPhase.QUESTION, onClick = { vm.skip() }) { Text("건너뛰기") }
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (ui.phase == MockPhase.SAVING) CircularProgressIndicator()
                    else MicButton(ui.phase == MockPhase.RECORDING) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleMic() else perm.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (ui.phase == MockPhase.RECORDING) "${formatMs(ui.answerElapsedMs)} · 누르면 다음 문항" else "답변 시작", style = MaterialTheme.typography.labelLarge)
                    if (ui.phase == MockPhase.RECORDING && ui.answerElapsedMs > 150_000) Text("2분 넘음", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                }
                ui.error?.let { Banner(it, tone = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { vm.finish() }) { Text("종료하고 리포트 보기") }
            }
            MockPhase.ADJUST -> {
                Text("난이도 재조정", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { vm.adjust(MockTestBuilder.Adjust.EASIER) }, modifier = Modifier.fillMaxWidth()) { Text("쉬운 질문") }
                Button(onClick = { vm.adjust(MockTestBuilder.Adjust.SAME) }, modifier = Modifier.fillMaxWidth()) { Text("비슷한 질문") }
                Button(onClick = { vm.adjust(MockTestBuilder.Adjust.HARDER) }, modifier = Modifier.fillMaxWidth()) { Text("어려운 질문") }
            }
            MockPhase.REPORT -> {
                ui.report?.let { ReportView(it, past = false) }
                Button(onClick = { vm.reset() }, modifier = Modifier.fillMaxWidth()) { Text("새 모의시험") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportView(r: MockReport, past: Boolean) {
    SectionCard(title = if (past) "지난 모의시험 리포트" else "모의시험 리포트", trailing = { InfoButton(InfoTopics.MOCK) }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("답변", "${r.answered}문항")
            MetricTile("전체 발화", formatMs(r.totalSpeechMs))
            MetricTile("소요 시간", formatMs(r.totalDurationMs))
            MetricTile("2초+ 무음", "${r.longPauses}회")
            MetricTile("평균 길이", "${r.avgWords}단어")
            MetricTile("연결 표현", "%.1f회/답".format(r.avgConnectors))
        }
        Spacer(Modifier.height(8.dp))
        Text("시제 사용: " + r.timeFrames.entries.joinToString { "${it.key} ${it.value}" }, style = MaterialTheme.typography.bodyMedium)
        Text("유형별 점수: " + r.byType.entries.joinToString { "${it.key} ${it.value}" }, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("강점", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
        r.strengths.forEach { Bullet(it) }
        Text("약점", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
        r.weaknesses.forEach { Bullet(it) }
        Text("우선 학습할 주제", style = MaterialTheme.typography.titleSmall)
        r.focusTopics.forEach { Bullet(it) }
        Text("다음 3일 계획", style = MaterialTheme.typography.titleSmall)
        r.next3Days.forEach { Bullet(it) }
        Spacer(Modifier.height(6.dp))
    }
}
