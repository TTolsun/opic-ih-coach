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
import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.alarm.ReminderWorker
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.prefs.StudyLevel
import com.opicih.coach.ai.GeminiAiCoach
import com.opicih.coach.data.secure.ApiKeyStore
import com.opicih.coach.speech.SpeechCapabilities
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onExams: () -> Unit = {}) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState(initial = Settings())
    var showTime by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    fun save(block: (Settings) -> Settings) = scope.launch {
        c.settings.update(block)
        val n = c.settings.current()
        if (n.alarmEnabled) {
            val mode = AlarmScheduler.schedule(context, n.alarmHour, n.alarmMinute, n.useExactAlarm)
            if (n.useExactAlarm && mode == AlarmScheduler.Mode.INEXACT) message = "정확한 알람 권한이 없어 일반 알림 방식으로 예약했습니다. 몇 분 늦을 수 있습니다."
        } else AlarmScheduler.cancel(context)
        ReminderWorker.ensure(context, n.eveningReminder)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { "백업 완료: 스크립트 ${c.backup.export(uri)}개" }.getOrElse { "백업 실패: ${it.message}" } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { "복원 완료: 스크립트 ${c.backup.restore(uri)}개" }.getOrElse { "복원 실패: ${it.message}" } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        message?.let { Banner(it, "닫기") { message = null } }

        SectionCard(title = "시험과 학습량", collapsible = true) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onExams).padding(vertical = 6.dp)) {
                Text("시험 일정 관리 (OPIc·SAIL)", modifier = Modifier.weight(1f)); Text("열기", color = MaterialTheme.colorScheme.primary)
            }
            Text("매일 최소 훈련 시간", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { StudyLevel.entries.forEach { l -> Pill(l.label, s.studyLevel == l) { save { it.copy(studyLevel = l) } } } }
        }

        SectionCard(title = "재응시 규칙 (다음 시험 추천에 사용)", collapsible = true, initiallyExpanded = false) {
            Text("OPIc은 공식 시험관리규정 제5조를 씁니다: 응시일 다음 날부터 25일 제한, D+26부터 응시 가능, Waiver 150일마다 1회.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberRow("OPIc Waiver 보유 수 (${s.opicWaiverDeclaredOn} 기준)", s.opicWaivers.coerceAtLeast(0)) { v ->
                save { it.copy(opicWaivers = v.coerceIn(0, 5), opicWaiverDeclaredOn = LocalDate.now()) }
            }
            NumberRow("SAIL 재응시 제한(일, 응시일 제외)", s.retakeDays) { v -> save { it.copy(retakeDays = v.coerceIn(0, 120)) } }
            ToggleRow("SAIL 제한을 사내 공지에서 확인함", s.retakeVerified) { v -> save { it.copy(retakeVerified = v) } }
            ToggleRow("SAIL과 OPIc이 재응시 제한을 공유함 (기본: 독립)", s.sailSharesOpicLimit) { v -> save { it.copy(sailSharesOpicLimit = v) } }
            NumberRow("결과 발표까지(일)", s.resultLagDays) { v -> save { it.copy(resultLagDays = v.coerceIn(0, 60)) } }
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
            ToggleRow("저녁 학습 리마인더 (목표 미달 시, 일반 알림)", s.eveningReminder) { v -> save { it.copy(eveningReminder = v) } }
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
            Text("녹음 전사 (녹음 우선 모드에서만)", style = MaterialTheme.typography.labelLarge)
            Text("필러(um, uh)와 문법 실수까지 그대로 받아 적습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("녹음 파일을 Gemini로 전사", s.cloudSttEnabled) { v -> save { it.copy(cloudSttEnabled = v) } }
            ToggleRow("원본 음성 외부 전송에 동의", s.audioUploadConsent) { v -> save { it.copy(audioUploadConsent = v) } }
            if (s.cloudSttEnabled && s.audioUploadConsent && s.captureMode != CaptureMode.RECORD_ONLY) {
                Banner("녹음 전사는 '녹음 우선' 캡처 방식에서만 동작합니다.", "녹음 우선으로") { save { it.copy(captureMode = CaptureMode.RECORD_ONLY) } }
            }
        }

        SectionCard(title = "데이터", collapsible = true, initiallyExpanded = false) {
            val usage = remember(refresh) { c.backup.audioUsageBytes() }
            Text("녹음 저장 용량: ${usage / 1024 / 1024}MB · 보관 ${s.audioRetentionDays}일", style = MaterialTheme.typography.bodyMedium)
            Slider(s.audioRetentionDays.toFloat(), { v -> save { it.copy(audioRetentionDays = v.toInt()) } }, valueRange = 1f..60f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { val n = c.backup.purgeAudio(s.audioRetentionDays); message = "오래된 녹음 ${n}개 삭제"; refresh++ } }) { Text("정리") }
                OutlinedButton(onClick = { scope.launch { c.backup.deleteAllAudio(); message = "녹음 파일을 모두 삭제했습니다."; refresh++ } }) { Text("녹음 전체 삭제") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("opic-coach-backup-${LocalDate.now()}.json") }) { Text("백업") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("복원") }
            }
            Text("시험 일정도 백업됩니다. 녹음 파일과 API 키는 백업되지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { confirmWipe = true }) { Text("모든 학습 데이터 삭제", color = MaterialTheme.colorScheme.error) }
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
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("모든 학습 데이터 삭제") },
        text = { Text("스크립트, 개선안, 연습 기록, 오류 노트, 녹음 파일, 저장된 API 키를 모두 영구 삭제합니다. 되돌릴 수 없습니다. 먼저 백업을 권장합니다.") },
        confirmButton = { TextButton(onClick = {
            confirmWipe = false
            scope.launch {
                c.dao.wipeAll(); c.backup.deleteAllAudio(); c.keys.clearAll()
                c.settings.update { it.copy(seedVersion = 0) }
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
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(value, onChange)
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

val SURVEY_CHOICES = listOf(
    "Overseas Travel", "Domestic Travel", "Movies", "Vacation", "Home & Furniture", "Park & Music", "Geography",
    "Music", "Concerts", "Jogging", "Walking", "Cafe", "Games", "TV", "Shopping", "Cooking", "Work",
)
