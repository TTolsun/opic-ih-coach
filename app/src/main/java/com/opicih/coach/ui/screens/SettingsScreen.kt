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
fun SettingsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState(initial = Settings())
    var showDate by remember { mutableStateOf(false) }
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

        SectionCard(title = "시험과 학습량") {
            Row(Modifier.fillMaxWidth().clickable { showDate = true }.padding(vertical = 6.dp)) {
                Text("첫 시험일", modifier = Modifier.weight(1f)); Text(s.examDate.toString(), color = MaterialTheme.colorScheme.primary)
            }
            Text("매일 최소 훈련 시간", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { StudyLevel.entries.forEach { l -> Pill(l.label, s.studyLevel == l) { save { it.copy(studyLevel = l) } } } }
        }

        SectionCard(title = "재응시 규칙 (다음 시험 추천에 사용)") {
            Text("앱이 직접 조회할 수 없는 값입니다. 사내 공지나 접수 사이트에서 확인한 값으로 고치세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberRow("재응시 간격(일)", s.retakeDays) { v -> save { it.copy(retakeDays = v.coerceIn(0, 120)) } }
            ToggleRow("위 간격을 공지/접수 사이트에서 확인함", s.retakeVerified) { v -> save { it.copy(retakeVerified = v) } }
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

        SectionCard(title = "Background Survey 주제") {
            Text("선택한 주제를 자유 발화와 모의시험에서 우선 출제합니다. 주제를 겹치게 고르면 준비 범위가 줄어듭니다 (응시 후기 기반 비공식 전략).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SURVEY_CHOICES.forEach { t ->
                    Pill(t, t in s.surveyTopics) { save { st -> st.copy(surveyTopics = if (t in st.surveyTopics) st.surveyTopics - t else st.surveyTopics + t) } }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Self Assessment 난이도: ${s.selfLevel}", style = MaterialTheme.typography.labelLarge)
            Slider(s.selfLevel.toFloat(), { v -> save { it.copy(selfLevel = v.toInt()) } }, valueRange = 1f..6f, steps = 4)
            Text("IH 목표라면 4~5단계를 많이 고릅니다 (비공식 경향). 모의시험 문항 난도에 반영됩니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(title = "아침 알람") {
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
                Banner("정확한 알람 권한이 꺼져 있습니다. 켜지 않으면 알람이 몇 분 늦을 수 있습니다.", "권한 열기") {
                    if (Build.VERSION.SDK_INT >= 31) runCatching { context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))) }
                    refresh++
                }
            }
            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                Banner("삼성 절전 기능이 알람을 늦출 수 있습니다. 배터리 > 백그라운드 사용 제한에서 이 앱을 '제한 없음'으로 두세요.", "설정 열기") {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
            OutlinedButton(onClick = { scope.launch { AlarmScheduler.snooze(context, 1); message = "1분 뒤 테스트 알람을 예약했습니다." } }) { Text("1분 뒤 테스트 알람") }
        }

        SectionCard(title = "음성") {
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
            val cap = remember { SpeechCapabilities.detect(context) }
            val tts by c.tts.state.collectAsState()
            Text("음성 진단", style = MaterialTheme.typography.labelLarge)
            Text("음성 인식: ${if (cap.recognitionAvailable) "사용 가능" else "없음"} · 온디바이스: ${when (cap.onDeviceAvailable) { true -> "가능"; false -> "불가"; null -> "확인 불가(Android 12 미만)" }}", style = MaterialTheme.typography.bodySmall)
            Text("인식 서비스: ${cap.recognizerPackages.joinToString().ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall)
            Text("TTS: ${c.tts.stateMessage()} (${tts.name})", style = MaterialTheme.typography.bodySmall)
            Text("발음 정확도·억양은 측정하지 않습니다. 전용 발음 평가 엔진이 없기 때문입니다. 녹음과 TTS를 직접 비교해 들으세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(title = "AI (선택, 비용 발생)") {
            Text("키는 APK에 포함되지 않고, 이 기기의 Android Keystore로 암호화해 저장합니다. 서버 중계 없이 기기에서 직접 호출합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeyField(c.keys, ApiKeyStore.Provider.ANTHROPIC) { message = it }
            ToggleRow("AI 사용 (스크립트 개선, 답변 평가, 코치)", s.aiEnabled) { v -> save { it.copy(aiEnabled = v) } }
            ToggleRow("답변 텍스트를 Anthropic에 전송하는 것에 동의", s.aiTextConsent) { v -> save { it.copy(aiTextConsent = v) } }
            Text("모델", style = MaterialTheme.typography.labelLarge)
            Settings.MODEL_CHOICES.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().clickable { save { it.copy(aiModel = id) } }.padding(vertical = 4.dp)) {
                    Pill(label, s.aiModel == id) { save { it.copy(aiModel = id) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("녹음 클라우드 전사 (녹음 우선 모드에서만)", style = MaterialTheme.typography.labelLarge)
            KeyField(c.keys, ApiKeyStore.Provider.OPENAI_STT) { message = it }
            ToggleRow("녹음 파일을 OpenAI로 보내 텍스트로 전사", s.cloudSttEnabled) { v -> save { it.copy(cloudSttEnabled = v) } }
            ToggleRow("원본 음성 외부 전송에 동의", s.audioUploadConsent) { v -> save { it.copy(audioUploadConsent = v) } }
        }

        SectionCard(title = "데이터") {
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
            Text("백업에는 스크립트, 개선안, 연습 기록, 오류 노트가 들어갑니다. 녹음 파일과 API 키는 포함되지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { confirmWipe = true }) { Text("모든 학습 데이터 삭제", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = s.examDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = { TextButton(onClick = {
                st.selectedDateMillis?.let { ms -> val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate(); save { it.copy(examDate = d) } }
                showDate = false
            }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("취소") } },
        ) { DatePicker(st) }
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
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(p.label + if (has) " · 저장됨" else " · 없음", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text(if (has) "변경하려면 새 키 입력" else "키 붙여넣기") }, modifier = Modifier.weight(1f))
            TextButton(enabled = input.isNotBlank(), onClick = { keys.set(p, input); input = ""; has = true; onMessage("키를 암호화해 저장했습니다.") }) { Text("저장") }
            if (has) TextButton(onClick = { keys.set(p, null); has = false; onMessage("키를 삭제했습니다.") }) { Text("삭제") }
        }
    }
}

val SURVEY_CHOICES = listOf(
    "Overseas Travel", "Domestic Travel", "Movies", "Vacation", "Home & Furniture", "Park & Music", "Geography",
    "Music", "Concerts", "Jogging", "Walking", "Cafe", "Games", "TV", "Shopping", "Cooking", "Work",
)
