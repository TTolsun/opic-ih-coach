package com.opicih.coach.ui.screens

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opicih.coach.alarm.AlarmCoordinator
import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.alarm.StudyBlock
import com.opicih.coach.alarm.StudyBlocks
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.prefs.LearnerGrade
import com.opicih.coach.learning.latestResultGrade
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.ExamRepository
import com.opicih.coach.data.prefs.StudyLevel
import com.opicih.coach.ai.GeminiAiCoach
import com.opicih.coach.data.secure.ApiKeyStore
import com.opicih.coach.speech.SpeechCapabilities
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState(initial = Settings())
    var showTime by remember { mutableStateOf(false) }
    var editBlock by remember { mutableStateOf<Int?>(null) }
    val studiedMs by c.studyTime.flow.collectAsState(initial = 0L)
    var message by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmDeleteAudio by remember { mutableStateOf(false) }
    var deletingAudio by remember { mutableStateOf(false) }
    var audioDeleteMessage by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    fun save(block: (Settings) -> Settings) = scope.launch {
        c.settings.update(block)
        val n = c.settings.current()
        val mode = AlarmCoordinator.applyAll(context, n)
        if (n.alarmEnabled && n.useExactAlarm && mode == AlarmScheduler.Mode.INEXACT) message = "정확한 알람 권한이 없어 일반 알림 방식으로 예약했습니다. 몇 분 늦을 수 있습니다."
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { "백업 완료: 스크립트 ${c.backup.export(uri)}개" }.getOrElse { "백업 실패: ${it.message}" } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching { c.backup.restore(uri) }
                .onSuccess { runCatching { c.seed.seedIfNeeded() } }
                .fold(
                    onSuccess = { "복원 완료: 스크립트 ${it}개" },
                    onFailure = { "복원 실패: ${it.message}" },
                )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        message?.let { Banner(it, "닫기") { message = null } }

        // The exam schedule is reached from Home (header, 시험 현황), 기록 and 다음 OPIc 추천; a fifth link here
        // only added another path to the same screen.
        SectionCard(title = "학습량", collapsible = true) {
            Text("하루 훈련 분량 (홈의 '오늘 권장' 계획)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { StudyLevel.entries.forEach { l -> Pill(l.label, s.studyLevel == l) { save { it.copy(studyLevel = l) } } } }
        }

        SectionCard(title = "재응시 규칙 (다음 시험 추천에 사용)", collapsible = true, initiallyExpanded = false) {
            Text("OPIc과 SAIL은 재응시 제한과 웨이버를 각각 계산합니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberRow("OPIc 웨이버 보유 수 (${s.opicWaiverDeclaredOn.monthDay()} 기준)", s.opicWaivers.coerceAtLeast(0)) { v ->
                save { it.copy(opicWaivers = v.coerceIn(0, 5), opicWaiverDeclaredOn = ExamRepository.nowKst().toLocalDate()) }
            }
            NumberRow("SAIL 웨이버 보유 수 (${s.sailWaiverDeclaredOn.monthDay()} 기준)", s.sailWaivers.coerceAtLeast(0)) { v ->
                save { it.copy(sailWaivers = v.coerceIn(0, 5), sailWaiverDeclaredOn = ExamRepository.nowKst().toLocalDate()) }
            }
            NumberRow("SAIL 재응시 제한(일, 응시일 제외)", s.retakeDays) { v -> save { it.copy(retakeDays = v.coerceIn(0, 120)) } }
            ToggleRow("SAIL 재응시 규칙을 확인함", s.retakeVerified) { v -> save { it.copy(retakeVerified = v) } }
            NumberRow("접수 마감: 응시 며칠 전", s.registerLeadDays) { v -> save { it.copy(registerLeadDays = v.coerceIn(0, 60)) } }
            var deadline by remember(s.gradeDeadline) { mutableStateOf(s.gradeDeadline?.toString().orEmpty()) }
            OutlinedTextField(
                deadline,
                { v ->
                    deadline = v
                    if (v.isBlank()) save { st -> st.copy(gradeDeadline = null) }
                    else runCatching { LocalDate.parse(v.trim()) }.getOrNull()?.let { d -> save { st -> st.copy(gradeDeadline = d) } }
                },
                label = { Text("등급 필요 마감일 (YYYY-MM-DD, 비우면 없음)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            var slots by remember(s.examSlots) { mutableStateOf(s.slotDates().joinToString(", ")) }
            OutlinedTextField(slots, { slots = it }, label = { Text("사내 시험 회차 (쉼표로 구분, 비우면 제한 없음)") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                val parsed = slots.split(',', ' ', '\n').mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
                save { it.copy(examSlots = parsed.map { d -> d.toString() }.toSet()) }
                message = "회차 ${parsed.size}개를 저장했습니다."
            }) { Text("회차 저장") }
        }

        SectionCard(title = "학습 등급") {
            val regs by c.exams.flow.collectAsState(initial = emptyList())
            Text("현재 등급 (최근 공식 결과)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CURRENT_GRADE_CHOICES.forEach { g -> Pill(g.code, g == s.currentGrade) { save { it.copy(currentGrade = g) } } }
            }
            latestResultGrade(regs)?.takeIf { it != s.currentGrade }?.let { g ->
                Spacer(Modifier.height(6.dp))
                Banner("최근 OPIc 결과는 ${g.code}입니다.", "현재 등급으로 설정") { save { it.copy(currentGrade = g) } }
            }
            Spacer(Modifier.height(10.dp))
            Text("다음 시험 목표 등급", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TARGET_GRADE_CHOICES.forEach { g -> Pill(g.code, g == s.targetGrade) { save { it.copy(targetGrade = g) } } }
            }
            Spacer(Modifier.height(6.dp))
            // Home shows "IH 준비도" and "최종 목표 IH"; say here how those relate to the grades picked above.
            Text(
                "최종 목표는 ${s.profile.finalGoal.code}" + (s.gradeDeadline?.let { " (${it.monthDay()}까지)" } ?: "") +
                    "입니다. 다음 시험 추천의 준비도 조건은 위 목표 등급(${s.targetGrade.code}) 기준이고, 홈의 ${s.profile.finalGoal.code} 준비도는 최종 목표 기준입니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Background Survey 주제", collapsible = true, initiallyExpanded = false) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SURVEY_CHOICES.forEach { t ->
                    Pill(t, t in s.surveyTopics) { save { st -> st.copy(surveyTopics = if (t in st.surveyTopics) st.surveyTopics - t else st.surveyTopics + t) } }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Self Assessment 난이도: ${s.selfLevel}", style = MaterialTheme.typography.labelLarge)
            Slider(s.selfLevel.toFloat(), { v -> save { it.copy(selfLevel = v.toInt()) } }, valueRange = 1f..6f, steps = 4)
        }

        SectionCard(title = "아침 알람", collapsible = true) {
            refresh.let { }
            ToggleRow("매일 아침 알람", s.alarmEnabled) { v -> save { it.copy(alarmEnabled = v) } }
            Row(Modifier.fillMaxWidth().clickable { showTime = true }.padding(vertical = 6.dp)) {
                Text("시간", modifier = Modifier.weight(1f)); Text("%02d:%02d".format(s.alarmHour, s.alarmMinute), color = MaterialTheme.colorScheme.primary)
            }
            Text("다음 알람: ${AlarmScheduler.describe(LocalDateTime.now(), s.alarmHour, s.alarmMinute)}", style = MaterialTheme.typography.labelSmall)
            ToggleRow("정확한 시간 알람 (알람 아이콘 표시)", s.useExactAlarm) { v -> save { it.copy(useExactAlarm = v) } }
            ToggleRow("소리", s.alarmSound) { v -> save { it.copy(alarmSound = v) } }
            ToggleRow("진동", s.alarmVibrate) { v -> save { it.copy(alarmVibrate = v) } }
            // ReminderWorker skips the evening reminder while block alarms are on, so the switch says so instead of
            // looking active.
            ToggleRow("저녁 학습 리마인더 (목표 미달 시, 일반 알림)", s.eveningReminder, enabled = !s.studyBlocksEnabled) { v -> save { it.copy(eveningReminder = v) } }
            if (s.studyBlocksEnabled) {
                Text("학습 블록 알람이 켜져 있어 저녁 리마인더는 보내지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!AlarmScheduler.canExact(context)) {
                Banner("정확한 알람 권한이 꺼져 있습니다.", "권한 열기") {
                    if (Build.VERSION.SDK_INT >= 31) runCatching { context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))) }
                    refresh++
                }
            }
            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                Banner("절전 설정이 알람을 늦출 수 있습니다.", "설정 열기") {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
            OutlinedButton(onClick = { scope.launch { AlarmScheduler.snooze(context, 1); message = "1분 뒤 테스트 알람을 예약했습니다." } }) { Text("1분 뒤 테스트 알람") }
        }

        SectionCard(title = "학습 블록 알람 (하루 강제 학습)", collapsible = true) {
            val blocks = StudyBlocks.parse(s.studyBlocks)
            fun saveBlocks(list: List<StudyBlock>) = save { it.copy(studyBlocks = StudyBlocks.encode(list.sortedBy { b -> b.startMinute })) }
            ToggleRow("블록 알람 사용 (켜면 저녁 리마인더는 쉽니다)", s.studyBlocksEnabled) { v -> save { it.copy(studyBlocksEnabled = v) } }
            Text("오늘 앱 학습 시간 ${(studiedMs / 60_000L).toInt()}/${StudyBlocks.dailyGoalMinutes(blocks)}분", style = MaterialTheme.typography.titleMedium)
            blocks.forEachIndexed { i, b ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // "24:00 (익일 00:00)" wrapped to two lines next to the −/+/삭제 buttons; one form is enough.
                        "${i + 1}. " + if (b.startMinute >= 1440) "익일 ${StudyBlocks.label(b.startMinute - 1440)}" else StudyBlocks.label(b.startMinute),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clickable { editBlock = i }.padding(vertical = 8.dp),
                    )
                    TextButton(onClick = { saveBlocks(blocks.toMutableList().also { it[i] = b.copy(minutes = (b.minutes - 5).coerceAtLeast(StudyBlocks.MIN_MINUTES)) }) }) { Text("−") }
                    Text("${b.minutes}분", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { saveBlocks(blocks.toMutableList().also { it[i] = b.copy(minutes = (b.minutes + 5).coerceAtMost(StudyBlocks.MAX_MINUTES)) }) }) { Text("+") }
                    TextButton(onClick = { saveBlocks(blocks.filterIndexed { j, _ -> j != i }) }) { Text("삭제") }
                }
            }
            if (blocks.size < StudyBlocks.MAX_BLOCKS) OutlinedButton(onClick = {
                val last = blocks.lastOrNull()
                val start = ((last?.let { it.startMinute + it.minutes + 60 } ?: 600)).coerceAtMost(StudyBlocks.MAX_START_MINUTE)
                saveBlocks(blocks + StudyBlock(start, 30))
            }) { Text("블록 추가") }
            Text("시간을 누르면 바꿉니다. 04:00 이전 시각은 전날 학습의 연장으로 보고 '익일'로 표시합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("앱의 학습 화면(스크립트·말하기·모의시험·SAIL·코치·아침 표현)을 열어 둔 시간만 셉니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(title = "음성", collapsible = true, initiallyExpanded = false) {
            Text("캡처 방식", style = MaterialTheme.typography.labelLarge)
            CaptureMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { save { it.copy(captureMode = m) } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(m.label, s.captureMode == m) { save { it.copy(captureMode = m) } }
                    Spacer(Modifier.padding(4.dp))
                    Text(m.description, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                }
            }
            Text("TTS 속도 ${"%.2f".format(s.ttsRate)}", style = MaterialTheme.typography.labelLarge)
            Slider(s.ttsRate, { v -> save { it.copy(ttsRate = v) } }, valueRange = 0.6f..1.2f)
            Text("TTS 음높이 ${"%.2f".format(s.ttsPitch)} (낮을수록 차분합니다)", style = MaterialTheme.typography.labelLarge)
            Slider(s.ttsPitch, { v -> save { it.copy(ttsPitch = v) } }, valueRange = 0.6f..1.2f)
            val cap = remember { SpeechCapabilities.detect(context) }
            val tts by c.tts.state.collectAsState()
            val voices = remember(tts) { c.tts.englishVoices() }
            Text("영어 음성 선택", style = MaterialTheme.typography.labelLarge)
            if (voices.isEmpty()) {
                Text("선택할 수 있는 영어 음성이 없습니다. 기기의 TTS 설정에서 음성 데이터를 설치하세요.", style = MaterialTheme.typography.bodySmall)
            } else {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill("자동", s.ttsVoice.isBlank()) { save { it.copy(ttsVoice = "") } }
                    voices.forEachIndexed { i, v ->
                        val label = "${i + 1}. ${v.locale.displayCountry.ifBlank { v.locale.country }} ${if (v.isNetworkConnectionRequired) "온라인" else "오프라인"}"
                        Pill(label, s.ttsVoice == v.name) {
                            save { it.copy(ttsVoice = v.name) }
                            c.tts.configure(v.name, s.ttsPitch)
                            c.tts.speak("Hello. Let's begin the interview. Please answer calmly and clearly.", s.ttsRate)
                        }
                    }
                }
                Text("음성을 누르면 바로 들어볼 수 있습니다.", style = MaterialTheme.typography.labelSmall)
            }
            Text("음성 진단", style = MaterialTheme.typography.labelLarge)
            Text("음성 인식: ${if (cap.recognitionAvailable) "사용 가능" else "없음"} · 온디바이스: ${when (cap.onDeviceAvailable) { true -> "가능"; false -> "불가"; null -> "확인 불가(Android 12 미만)" }}", style = MaterialTheme.typography.bodySmall)
            Text("인식 서비스: ${cap.recognizerPackages.joinToString().ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall)
            Text("TTS: ${c.tts.stateMessage()} (${tts.name})", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(title = "AI · Gemini (선택, 비용 발생)", collapsible = true, initiallyExpanded = false) {
            Text("키는 이 기기에만 암호화해 저장합니다. 무료 등급은 입력 내용이 Google 제품 개선에 쓰일 수 있고, 유료 등급은 쓰이지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeyField(c.keys, ApiKeyStore.Provider.GEMINI) { message = it }
            var model by remember(s.geminiModel) { mutableStateOf(s.geminiModel) }
            var models by remember { mutableStateOf<List<String>>(emptyList()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(model, { model = it }, label = { Text("Gemini 모델 ID") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = model.isNotBlank() && model != s.geminiModel, onClick = { save { it.copy(geminiModel = model.trim()) } }) { Text("저장") }
            }
            OutlinedButton(onClick = {
                val key = c.keys.get(ApiKeyStore.Provider.GEMINI)
                if (key == null) { message = "먼저 Gemini 키를 저장하세요." } else scope.launch {
                    GeminiAiCoach.listModels(key)
                        .onSuccess { models = it; message = "사용 가능한 모델 ${it.size}개를 불러왔습니다." }
                        .onFailure { message = it.message }
                }
            }) { Text("모델 목록 불러오기") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                models.forEach { m -> Pill(m, m == s.geminiModel) { save { it.copy(geminiModel = m) } } }
            }
            Text("flash: 빠르고 저렴 · pro: 평가 품질 높음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberRow("SAIL 대화 AI 호출 하루 상한", s.aiDailyCap) { v -> save { it.copy(aiDailyCap = v.coerceIn(0, 200)) } }
            Text("오늘 ${s.aiCallsToday(java.time.LocalDate.now().toEpochDay())}회 사용. 상한을 넘으면 추가 질문은 오프라인 규칙으로 만듭니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text("텍스트 분석", style = MaterialTheme.typography.labelLarge)
            ToggleRow("AI 사용 (스크립트 개선, 답변 평가, 코치)", s.aiEnabled) { v -> save { it.copy(aiEnabled = v) } }
            ToggleRow("답변 텍스트를 Gemini에 전송하는 것에 동의", s.aiTextConsent) { v -> save { it.copy(aiTextConsent = v) } }
            Spacer(Modifier.height(10.dp))
            Text("녹음 전사 (자유 발화·모의시험에 자동 적용)", style = MaterialTheme.typography.labelLarge)
            Text("필러(um, uh)와 문법 실수까지 그대로 받아 적습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("녹음 파일을 Gemini로 전사", s.cloudSttEnabled) { v -> save { it.copy(cloudSttEnabled = v) } }
            ToggleRow("원본 음성 외부 전송에 동의", s.audioUploadConsent) { v -> save { it.copy(audioUploadConsent = v) } }
        }

        SectionCard(title = "데이터", collapsible = true, initiallyExpanded = false) {
            val usage = remember(refresh) { c.backup.audioUsageBytes() }
            Text("녹음 저장 용량: ${usage / 1024 / 1024}MB · 보관 ${s.audioRetentionDays}일", style = MaterialTheme.typography.bodyMedium)
            Slider(s.audioRetentionDays.toFloat(), { v -> save { it.copy(audioRetentionDays = v.toInt()) } }, valueRange = 1f..60f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !deletingAudio, onClick = { scope.launch { val n = c.backup.purgeAudio(s.audioRetentionDays); message = "오래된 녹음 ${n}개 삭제"; refresh++ } }) { Text("정리") }
                OutlinedButton(enabled = !deletingAudio, onClick = { confirmDeleteAudio = true }) { Text("녹음 전체 삭제") }
            }
            audioDeleteMessage?.let { Banner(it, "닫기") { audioDeleteMessage = null } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("opic-coach-backup-${LocalDate.now()}.json") }) { Text("백업") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("복원") }
            }
            Text("시험 일정도 백업됩니다. 녹음 파일과 API 키는 백업되지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !deletingAudio, onClick = { confirmWipe = true }) { Text("모든 학습 데이터 삭제", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showTime) {
        val st = rememberTimePickerState(s.alarmHour, s.alarmMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { save { it.copy(alarmHour = st.hour, alarmMinute = st.minute) }; showTime = false }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("취소") } },
            text = { TimePicker(st) },
        )
    }
    editBlock?.let { idx ->
        val blocks = StudyBlocks.parse(s.studyBlocks)
        val cur = blocks.getOrNull(idx)
        if (cur == null) editBlock = null else {
            val st = androidx.compose.runtime.key(idx) { rememberTimePickerState((cur.startMinute / 60) % 24, cur.startMinute % 60, is24Hour = true) }
            AlertDialog(
                onDismissRequest = { editBlock = null },
                confirmButton = { TextButton(onClick = {
                    var m = st.hour * 60 + st.minute
                    if (m < StudyBlocks.DAY_BOUNDARY_HOUR * 60) m += 1440
                    val list = blocks.toMutableList().also { it[idx] = cur.copy(startMinute = m) }
                    save { it.copy(studyBlocks = StudyBlocks.encode(list)) }
                    editBlock = null
                }) { Text("확인") } },
                dismissButton = { TextButton(onClick = { editBlock = null }) { Text("취소") } },
                text = { TimePicker(st) },
            )
        }
    }
    if (confirmDeleteAudio) AlertDialog(
        onDismissRequest = { confirmDeleteAudio = false },
        title = { Text("녹음을 모두 삭제할까요?") },
        text = { Text("기기에 저장된 연습·모의시험 녹음 파일을 모두 삭제합니다. 삭제한 녹음은 복구할 수 없습니다.") },
        confirmButton = {
            TextButton(onClick = {
                if (deletingAudio) return@TextButton
                confirmDeleteAudio = false
                deletingAudio = true
                audioDeleteMessage = null
                scope.launch {
                    try {
                        c.backup.deleteAllAudio()
                        audioDeleteMessage = "녹음 파일을 모두 삭제했습니다."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        audioDeleteMessage = "녹음 파일을 모두 삭제하지 못했습니다."
                    } finally {
                        refresh++
                        deletingAudio = false
                    }
                }
            }) { Text("영구 삭제", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDeleteAudio = false }) { Text("취소") } },
    )

    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("모든 학습 데이터 삭제") },
        text = { Text("스크립트, 개선안, 연습 기록, 오류 노트, 녹음 파일, 저장된 API 키를 모두 영구 삭제합니다. 되돌릴 수 없습니다. 먼저 백업을 권장합니다.") },
        confirmButton = { TextButton(onClick = {
            confirmWipe = false
            scope.launch {
                c.dao.wipeAll(); c.backup.deleteAllAudio(); c.keys.clearAll()
                c.settings.update { it.copy(seedVersion = 0, templateSeedVersion = 0) }
                c.seed.seedIfNeeded()
                message = "삭제했습니다. 기본 콘텐츠를 다시 불러왔습니다."
            }
        }) { Text("영구 삭제", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("취소") } },
    )
}

@Composable
private fun NumberRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange(value - 1) }) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange(value + 1) }) { Text("+") }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(value, onChange, enabled = enabled)
    }
}

@Composable
private fun KeyField(keys: ApiKeyStore, p: ApiKeyStore.Provider, onMessage: (String) -> Unit) {
    var has by remember { mutableStateOf(keys.has(p)) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun saveKey() {
        if (input.isBlank()) return
        keys.set(p, input)
        input = ""; has = true
        status = "저장했습니다. 이 기기에 암호화되어 보관됩니다."
        focus.clearFocus()
        onMessage("키를 암호화해 저장했습니다.")
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(p.label + if (has) " · 저장됨" else " · 없음", style = MaterialTheme.typography.labelLarge,
            color = if (has) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it; status = null }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(if (has) "변경하려면 새 키 입력" else "키 붙여넣기") }, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { saveKey() }),
            )
            Spacer(Modifier.padding(4.dp))
            Button(enabled = input.isNotBlank(), onClick = { saveKey() }) { Text("저장") }
            if (has && input.isBlank()) TextButton(onClick = { keys.set(p, null); has = false; status = "키를 삭제했습니다."; onMessage("키를 삭제했습니다.") }) { Text("삭제") }
        }
        when {
            input.isNotBlank() -> Text("아직 저장되지 않았습니다. '저장' 또는 키보드의 완료를 누르세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            status != null -> Text(status!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private val CURRENT_GRADE_CHOICES = listOf(LearnerGrade.IL, LearnerGrade.IM1, LearnerGrade.IM2, LearnerGrade.IM3, LearnerGrade.IH)
private val TARGET_GRADE_CHOICES = listOf(LearnerGrade.IM2, LearnerGrade.IM3, LearnerGrade.IH, LearnerGrade.AL)

val SURVEY_CHOICES = listOf(
    "Overseas Travel", "Domestic Travel", "Movies", "Vacation", "Home & Furniture", "Park & Music", "Geography",
    "Music", "Concerts", "Jogging", "Walking", "Cafe", "Games", "TV", "Shopping", "Cooking", "Work",
)
