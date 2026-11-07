package com.opicih.coach.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.learning.MorningItem
import com.opicih.coach.learning.MorningRoutineBuilder
import com.opicih.coach.learning.MorningStep
import com.opicih.coach.speech.LiveRecognizer
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.theme.EnglishReading
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 5–10 minute chunk routine linked to the morning alarm. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MorningScreen(onReviewScript: (Long) -> Unit, onDone: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MorningItem>>(emptyList()) }
    var dueScripts by remember { mutableStateOf<List<ScriptEntity>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    val recognizer = remember { LiveRecognizer(context) }
    val live by recognizer.state.collectAsState()
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recognizer.release(); c.tts.stop() } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) { recognizer.start(); listening = true } }

    LaunchedEffect(Unit) {
        val today = LocalDate.now().toEpochDay()
        items = MorningRoutineBuilder.build(c.dao.chunks(), today)
        dueScripts = c.dao.scripts().filter { it.dueEpochDay <= today }.sortedBy { it.masteryScore }.take(3)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (items.isEmpty()) { Text("표현을 불러오는 중입니다."); return@Column }
        LinearProgressIndicator(progress = { index.toFloat() / items.size }, modifier = Modifier.fillMaxWidth())
        if (index >= items.size) {
            SectionCard(title = "아침 루틴 완료") {
                Text("오늘 복습할 스크립트", style = MaterialTheme.typography.titleSmall)
                if (dueScripts.isEmpty()) Text("오늘 복습할 스크립트가 없습니다.", style = MaterialTheme.typography.bodyMedium)
                dueScripts.forEach { s ->
                    Text("• ${s.topic} · ${s.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onReviewScript(s.id) }.padding(vertical = 4.dp))
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { dueScripts.firstOrNull()?.let { onReviewScript(it.id) } ?: onDone() }) { Text("바로 복습 말하기") }
                TextButton(onClick = onDone) { Text("홈으로") }
            }
            return@Column
        }
        val item = items[index]
        val ch = item.chunk
        Row { SourceTag("${index + 1}/${items.size} · ${item.step.label}"); Spacer(Modifier.width(6.dp)); SourceTag(ch.category) }

        SectionCard {
            when (item.step) {
                MorningStep.COMPLETE -> {
                    Text(item.cloze ?: ch.example, style = EnglishReading)
                    Text("힌트: ${ch.meaningKo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (revealed) { Spacer(Modifier.height(6.dp)); Text(ch.phrase, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                }
                MorningStep.PRONOUNCE -> {
                    Text(ch.example, style = EnglishReading)
                }
                else -> {
                    Text(ch.phrase, style = MaterialTheme.typography.headlineSmall)
                    Text(ch.meaningKo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (revealed || item.step == MorningStep.NEW) Text(ch.example, style = EnglishReading)
                    else OutlinedButton(onClick = { revealed = true }) { Icon(Icons.Filled.Visibility, null); Spacer(Modifier.width(6.dp)); Text("예문 보기") }
                }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { c.tts.speak(if (item.step == MorningStep.COMPLETE && !revealed) ch.phrase else ch.example, 0.9f) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, null); Text("듣기") }
                OutlinedButton(onClick = { c.tts.speak(ch.example, 0.7f) }) { Text("느리게") }
                OutlinedButton(onClick = {
                    if (listening) { scope.launch { recognizer.finish() }; listening = false; revealed = true }
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { recognizer.start(); listening = true }
                    else perm.launch(Manifest.permission.RECORD_AUDIO)
                }) { Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, null); Text(if (listening) "멈춤" else "말하기") }
            }
            val said = live.fullText
            if (said.isNotBlank()) {
                val target = TextNormalizer.contentLemmas(ch.phrase).toSet()
                val got = TextNormalizer.contentLemmas(said).toSet()
                val ok = target.isEmpty() || target.count { it in got } * 2 >= target.size
                Spacer(Modifier.height(6.dp))
                Text("인식: $said", style = MaterialTheme.typography.bodySmall)
                Text(if (ok) "핵심 표현 인식됨" else "핵심 표현이 안 들렸습니다. 다시 말해 보세요.", style = MaterialTheme.typography.labelMedium,
                    color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
            }
            live.error?.let { if (!live.active) Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                scope.launch { c.practice.reviewChunk(ch, true, c.settings.current().examDate.toEpochDay()) }
                next(items.size, index) { index = it; revealed = false; if (it >= items.size) scope.launch { c.practice.markMorningDone() } }
                if (listening) { recognizer.stop(cancelOnly = true); listening = false }
            }, modifier = Modifier.weight(1f)) { Text("말할 수 있어요") }
            OutlinedButton(onClick = {
                scope.launch { c.practice.reviewChunk(ch, false, c.settings.current().examDate.toEpochDay()) }
                next(items.size, index) { index = it; revealed = false; if (it >= items.size) scope.launch { c.practice.markMorningDone() } }
                if (listening) { recognizer.stop(cancelOnly = true); listening = false }
            }, modifier = Modifier.weight(1f)) { Text("헷갈려요") }
        }
    }
}

private inline fun next(size: Int, index: Int, set: (Int) -> Unit) = set((index + 1).coerceAtMost(size))
