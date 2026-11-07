package com.opicih.coach.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.opicih.coach.ai.ChatTurn
import com.opicih.coach.ai.CoachContext
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.learning.PlanGenerator
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SourceTag
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun CoachScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState(initial = Settings())
    val turns = remember { mutableStateListOf<ChatTurn>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val isAi = c.realAi(settings) != null
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        turns += ChatTurn(true, text.trim())
        input = ""
        busy = true
        scope.launch {
            val attempts = c.dao.attempts()
            val ctx = CoachContext(
                examDday = PlanGenerator.dLabel(LocalDate.now(), settings.examDate) + " (${settings.examDate})",
                surveyTopics = settings.surveyTopics,
                weakTopics = ProgressCalculator.weakTopics(attempts, settings.surveyTopics),
                frequentErrors = c.dao.errors().take(8).map { "${it.example} → ${it.correction} (${it.count}회)" },
                scriptTitles = c.dao.scripts().map { "${it.topic}/${it.title}" },
                recentScores = attempts.take(8).map { it.score },
            )
            val last = attempts.firstOrNull()?.let { "\n\n[최근 답변] Q: ${it.questionText}\nA: ${it.transcript.take(1200)}" }.orEmpty()
            val history = turns.toList().let { h -> if (h.size == 1 && last.isNotEmpty()) listOf(ChatTurn(true, h[0].text + last)) else h }
            val reply = c.coach(settings).chat(history, ctx).getOrElse { "오류: ${it.message}" }
            turns += ChatTurn(false, reply)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 코치", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            SourceTag(if (isAi) (if (settings.aiProvider == com.opicih.coach.data.prefs.AiProvider.GEMINI) "Gemini ${settings.geminiModel}" else "Claude ${settings.aiModel}") else "오프라인 (AI 꺼짐)", if (isAi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (turns.isEmpty()) item {
                Text("내 스크립트, 연습 기록, 반복 오류, 시험 일정을 바탕으로 답합니다. 첫 질문에는 가장 최근 답변이 함께 전달됩니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(turns) { t ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (t.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        t.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (t.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(12.dp),
                    )
                }
            }
            if (busy) item { CircularProgressIndicator(Modifier.padding(8.dp)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("이 답변 IH 목표에 충분해?", "좀 더 쉽게 말할 수 있게 바꿔줘.", "내가 반복해서 틀리는 문법이 뭐야?", "이 내용을 다른 질문에도 쓸 수 있어?", "돌발 질문 하나 내줘.", "내 답변을 자연스럽게 고쳐줘.")
                .forEach { Pill(it, false) { send(it) } }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("코치에게 질문") }, maxLines = 4)
            IconButton(onClick = { send(input) }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.Send, "보내기") }
        }
        Spacer(Modifier.height(4.dp))
    }
}
