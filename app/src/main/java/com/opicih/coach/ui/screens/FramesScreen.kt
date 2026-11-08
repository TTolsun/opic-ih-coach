package com.opicih.coach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.SentenceFrameEntity
import com.opicih.coach.learning.FrameOrigin
import com.opicih.coach.learning.FrameStatus
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.theme.EnglishReading
import kotlinx.coroutines.launch

/** 문장 틀 목록. 승인한 틀만 '틀 붙이기' 모드에서 쓰입니다. */
@Composable
fun FramesScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val frames by c.frames.flow.collectAsState(initial = emptyList())
    var message by remember { mutableStateOf<String?>(null) }
    val pending = frames.filter { it.status == FrameStatus.PENDING.name }
    val approved = frames.filter { it.status == FrameStatus.APPROVED.name }
    val rejected = frames.filter { it.status == FrameStatus.REJECTED.name }
    var showRejected by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("문장 틀", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = {
                scope.launch {
                    val n = c.frames.findNew()
                    message = if (n == 0) "새로 찾은 틀이 없습니다." else "틀 ${n}개를 찾았습니다."
                }
            }) { Text("틀 찾기") }
            message?.let { Banner(it, "닫기") { message = null } }
        }
        if (pending.isNotEmpty()) {
            item { Text("승인 대기 ${pending.size}개", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(pending, key = { it.id }) { f ->
                FrameCard(f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { scope.launch { c.frames.setStatus(f.id, FrameStatus.APPROVED) } }) { Text("승인") }
                        OutlinedButton(onClick = { scope.launch { c.frames.setStatus(f.id, FrameStatus.REJECTED) } }) { Text("삭제") }
                    }
                }
            }
        }
        item { Text("사용 중 ${approved.size}개", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        if (approved.isEmpty()) item { Text("승인한 틀이 없습니다.", style = MaterialTheme.typography.bodyMedium) }
        items(approved, key = { it.id }) { f ->
            FrameCard(f) { TextButton(onClick = { scope.launch { c.frames.setStatus(f.id, FrameStatus.REJECTED) } }) { Text("삭제") } }
        }
        if (rejected.isNotEmpty()) {
            item {
                TextButton(onClick = { showRejected = !showRejected }) { Text("삭제한 틀 ${rejected.size}개 ${if (showRejected) "접기" else "보기"}") }
            }
            if (showRejected) items(rejected, key = { it.id }) { f ->
                FrameCard(f) { TextButton(onClick = { scope.launch { c.frames.setStatus(f.id, FrameStatus.PENDING) } }) { Text("되살리기") } }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FrameCard(f: SentenceFrameEntity, actions: @Composable () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.pattern, style = EnglishReading)
            Text(f.hintKo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            f.example?.let { Text("내 문장: $it", style = MaterialTheme.typography.bodySmall) }
            SourceTag(if (f.origin == FrameOrigin.SCRIPT.name) "내 스크립트에서 찾음" else "기본 제공")
            actions()
        }
    }
}
