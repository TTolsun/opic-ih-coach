package com.opicih.coach.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.Voice
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.SegmentRepository
import com.opicih.coach.learning.ListenRole
import com.opicih.coach.playback.ListenService
import com.opicih.coach.playback.ListenStatus
import com.opicih.coach.speech.VoiceGenders
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.theme.EnglishReading
import kotlinx.coroutines.launch

/**
 * 하루 종일 소리로만 듣는 스크립트 플레이어. 재생 자체는 [ListenService]가 화면 밖에서 이어 가므로,
 * 이 화면은 상태를 보여 주고 명령을 보내는 리모컨 역할만 한다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenScreen(onOpenSettings: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by c.listen.state.collectAsState()
    val s by c.settings.flow.collectAsState(initial = Settings())
    val voices by produceState(emptyList<Voice>()) { value = c.listenTts.englishVoices() }
    val playing = st.status == ListenStatus.PLAYING
    val gen by c.segments.state.collectAsState()
    val counts by produceState(0 to 0, gen.running, gen.done) { value = c.segments.counts() }

    // 알림 권한은 거부돼도 재생은 가능하다(재생 컨트롤 알림만 보이지 않는다). 그래서 응답과 무관하게 재생을 시작한다.
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ListenService.command(context, ListenService.ACTION_PLAY)
    }
    fun play() {
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needAsk) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else ListenService.command(context, ListenService.ACTION_PLAY)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "지금 재생") {
            if (st.status == ListenStatus.IDLE) {
                Text(
                    "재생을 누르면 스크립트를 무작위로 골라 질문과 답변을 영어와 한국어로 이어서 들려줍니다. 화면을 꺼도 계속 재생됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (st.topic.isNotBlank()) SourceTag(st.topic)
                    SourceTag("${st.stepIndex}/${st.stepCount} · ${st.stepLabel}", MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                Text(st.scriptTitle, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(st.text, style = EnglishReading)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playing) {
                    Button(onClick = { ListenService.command(context, ListenService.ACTION_PAUSE) }) {
                        Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(6.dp)); Text("일시정지")
                    }
                } else {
                    Button(onClick = { play() }) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("재생") }
                }
                FilledTonalButton(onClick = { ListenService.command(context, ListenService.ACTION_NEXT) }) {
                    Icon(Icons.Filled.SkipNext, null); Spacer(Modifier.width(6.dp)); Text("다음")
                }
                if (st.status != ListenStatus.IDLE) {
                    OutlinedButton(onClick = { ListenService.command(context, ListenService.ACTION_STOP) }) {
                        Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("종료")
                    }
                }
            }
        }

        st.notice?.let { msg ->
            if (st.koreanSkipped) {
                Banner(msg, action = "TTS 설정") {
                    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            } else {
                Banner(msg)
            }
        }

        SectionCard(title = "한국어 번역") {
            val (ready, total) = counts
            val ai = c.realAi(s)
            Text("한국어 번역 완료 $ready / $total 개 스크립트", style = MaterialTheme.typography.labelLarge)
            Text(
                "번역이 있는 스크립트는 질문, 답변 전체, 문단 하나, 문장 하나를 영어 다음에 한국어로 이어서 들려줍니다. " +
                    "영어를 먼저 문장으로 나눈 뒤 문장마다 번역하므로 한국어는 바로 앞 영어의 번역입니다. 스크립트를 고치면 그 스크립트는 다시 만들어야 합니다.",
                style = MaterialTheme.typography.labelSmall,
            )
            if (gen.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { if (gen.total == 0) 0f else gen.done / gen.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${gen.done}/${gen.total} · ${gen.currentTitle}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { c.segments.cancel() }) { Text("중단") }
            } else if (ready < total) {
                Spacer(Modifier.height(8.dp))
                if (ai == null) {
                    Banner(
                        "한국어 번역은 Gemini로 만듭니다. 설정에서 AI 사용, 텍스트 전송 동의, API 키를 먼저 설정해 주세요.",
                        action = "설정 열기", onAction = onOpenSettings,
                    )
                } else {
                    if (ready == 0) {
                        // 처음에는 2개만 만들어 말투를 귀로 확인한 뒤 나머지를 만든다.
                        Button(onClick = { c.segments.start(ai, SegmentRepository.PILOT_COUNT) }) { Text("시범 ${SegmentRepository.PILOT_COUNT}개 먼저 만들기") }
                        Text("재생해서 한국어 말투가 괜찮은지 들어 본 뒤, 나머지 ${total - SegmentRepository.PILOT_COUNT}개를 만들 수 있습니다.", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Button(onClick = { c.segments.start(ai) }) { Text("나머지 ${total - ready}개 만들기") }
                    }
                    Text("질문과 스크립트 텍스트가 Gemini로 전송됩니다.", style = MaterialTheme.typography.labelSmall)
                }
            }
            gen.error?.let { Spacer(Modifier.height(6.dp)); Banner(it) }
        }

        SectionCard(title = "목소리") {
            Text("질문은 여성, 답변은 남성 목소리로 읽습니다(한국어도 같습니다). 자동은 설치된 음성 중에서 이름으로 성별을 추정해 고르므로, 음성을 눌러 미리 들어 보고 맞지 않으면 직접 고르세요.", style = MaterialTheme.typography.labelSmall)
            VoicePicker("질문 목소리", voices, s.listenQuestionVoice, !playing) { name ->
                scope.launch {
                    c.settings.update { it.copy(listenQuestionVoice = name) }
                    if (!playing) c.listenTts.preview(QUESTION_SAMPLE, ListenRole.QUESTION, s.listenRate)
                }
            }
            VoicePicker("답변 목소리", voices, s.listenAnswerVoice, !playing) { name ->
                scope.launch {
                    c.settings.update { it.copy(listenAnswerVoice = name) }
                    if (!playing) c.listenTts.preview(ANSWER_SAMPLE, ListenRole.ANSWER, s.listenRate)
                }
            }
            if (voices.isEmpty()) {
                Text("선택할 수 있는 영어 음성이 없습니다. 기기의 TTS 설정에서 영어 음성 데이터를 설치하세요.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard(title = "속도와 간격") {
            Text("재생 속도 ${"%.2f".format(s.listenRate)}배", style = MaterialTheme.typography.labelLarge)
            Slider(s.listenRate, { v -> scope.launch { c.settings.update { it.copy(listenRate = v) } } }, valueRange = 0.6f..1.6f)
            Text("단계 사이 간격 ${"%.1f".format(s.listenGapMs / 1000f)}초", style = MaterialTheme.typography.labelLarge)
            Slider(s.listenGapMs / 1000f, { v -> scope.launch { c.settings.update { it.copy(listenGapMs = (v * 1000).toInt()) } } }, valueRange = 0f..3f)
            Text("바꾼 값은 다음 단계부터 적용됩니다.", style = MaterialTheme.typography.labelSmall)
        }

        SectionCard(title = "재생 순서", collapsible = true, initiallyExpanded = false) {
            Text(
                "질문 → 답변 전체 → 문단 하나 → 문장 하나 순서로, 조각마다 영어 다음에 한국어를 들려준 뒤 다음 스크립트로 넘어갑니다. 문단과 문장은 매번 무작위로 고릅니다. " +
                    "스크립트는 한 바퀴를 다 돌 때까지 겹치지 않게 섞어서 고릅니다. " +
                    "번역이 없는 스크립트는 질문과 답변 전체의 영어만 들려주고, 한국어 음성이 없는 기기에서는 한국어 단계를 건너뜁니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePicker(title: String, voices: List<Voice>, selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Pill("자동", selected.isBlank()) { if (enabled) onPick("") }
        voices.forEachIndexed { i, v ->
            val gender = VoiceGenders.of(v.name).label.let { if (it.isEmpty()) "" else " · $it(추정)" }
            val label = "${i + 1}. ${v.locale.displayCountry.ifBlank { v.locale.country }} ${if (v.isNetworkConnectionRequired) "온라인" else "오프라인"}$gender"
            Pill(label, selected == v.name) { if (enabled) onPick(v.name) }
        }
    }
}

private const val QUESTION_SAMPLE = "Tell me about a place you like to visit. Why do you enjoy going there?"
private const val ANSWER_SAMPLE = "I usually go there on weekends because it helps me relax and clear my mind."
