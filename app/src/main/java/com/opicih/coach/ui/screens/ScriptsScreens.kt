package com.opicih.coach.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.db.RevisionOrigin
import com.opicih.coach.data.db.RevisionStatus
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.importer.ImportService
import com.opicih.coach.importer.ParsedScript
import com.opicih.coach.importer.QuestionClassifier
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.Pill
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag
import com.opicih.coach.ui.theme.EnglishReading
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ScriptsScreen(onOpen: (Long) -> Unit, onNew: () -> Unit, onImport: () -> Unit) {
    val c = LocalContainer.current
    val scripts by c.dao.scriptsFlow().collectAsState(initial = emptyList())
    val pending by c.dao.pendingRevisionsFlow().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val pendingIds = pending.map { it.scriptId }.toSet()
    val filtered = scripts.filter { query.isBlank() || (it.title + it.topic + it.question + it.original).contains(query, true) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onImport) { Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("가져오기") }
                    OutlinedButton(onClick = onNew) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(6.dp)); Text("직접 작성") }
                }
                if (scripts.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("스크립트가 없습니다.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            filtered.groupBy { it.topic }.forEach { (topic, list) ->
                item(key = "h_$topic") {
                    Text(topic, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(list, key = { it.id }) { s ->
                    SectionCard(Modifier.clickable { onOpen(s.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (s.id in pendingIds) SourceTag("개선안 대기", MaterialTheme.colorScheme.primary)
                        }
                        Text(s.question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SourceTag(s.mastery.label)
                            SourceTag("학습본: ${s.active.label}")
                            if (s.linkedGroupId != null) SourceTag("유사 문항 연결")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScriptDetailScreen(id: Long, onPractice: (Long, PracticeMode) -> Unit, onEdit: (Long) -> Unit, onDeleted: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val script by c.dao.scriptFlow(id).collectAsState(initial = null)
    val revisions by c.dao.revisionsFlow(id).collectAsState(initial = emptyList())
    val settings by c.settings.flow.collectAsState(initial = Settings())
    var tab by remember { mutableStateOf(ScriptVersion.ORIGINAL) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val s = script ?: run { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    LaunchedEffect(s.id) { tab = s.active }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceTag(ContentSource.of(s.source).label)
                SourceTag(s.type.label)
                SourceTag("${s.mastery.label} · ${s.masteryScore}")
            }
            Spacer(Modifier.height(8.dp))
            Text("${s.topic} · ${s.title}", style = MaterialTheme.typography.titleLarge)
            Text(s.question, style = EnglishReading, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val dueIn = s.dueEpochDay - LocalDate.now().toEpochDay()
            Text(if (dueIn <= 0) "오늘 복습" else "${dueIn}일 뒤 복습 · 예상 발화 ${s.estSeconds}초", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Practice entry points
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PracticeMode.READ_LISTEN, PracticeMode.SHADOWING, PracticeMode.FILL_BLANK, PracticeMode.KEYWORD, PracticeMode.BLIND, PracticeMode.RANDOM, PracticeMode.VARIATION).forEach { m ->
                Pill(m.label, false) { onPractice(s.id, m) }
            }
        }

        // Versions
        SectionCard(title = "버전") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScriptVersion.entries.forEach { v -> Pill(v.label + if (s.active == v) " ✓" else "", tab == v) { tab = v } }
            }
            Spacer(Modifier.height(10.dp))
            val text = when (tab) {
                ScriptVersion.ORIGINAL -> s.original
                ScriptVersion.NATURAL -> s.natural
                ScriptVersion.IH_TARGET -> s.ihTarget
                ScriptVersion.SHORT -> s.shortVersion
                ScriptVersion.KEYWORDS -> s.keywords
            }
            if (text.isNullOrBlank()) {
                Text("승인된 ${tab.label} 버전이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(text, style = EnglishReading)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { c.tts.speak(text, 0.95f) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "듣기") }
                    TextButton(onClick = { c.tts.speak(text, 0.75f) }) { Text("0.75x") }
                    Spacer(Modifier.weight(1f))
                    if (tab != ScriptVersion.KEYWORDS && s.active != tab) TextButton(onClick = { scope.launch { c.scripts.setActive(s, tab) } }) { Text("이 버전으로 학습") }
                }
            }
            s.koreanMeaning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        // Pending improvements: original vs proposal, with Korean reasons, approve/reject
        val pending = revisions.filter { it.status == RevisionStatus.PENDING.name }
        if (pending.isNotEmpty()) {
            Text("승인 대기 개선안", style = MaterialTheme.typography.titleMedium)
            pending.forEach { r ->
                SectionCard(title = ScriptVersion.of(r.version).label, trailing = { SourceTag(RevisionOrigin.entries.firstOrNull { it.name == r.origin }?.label ?: r.origin) }) {
                    if (r.version != ScriptVersion.KEYWORDS.name) {
                        Text("원본", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.original, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("개선안", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(r.text, style = MaterialTheme.typography.bodyMedium)
                    if (r.reasonKo.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("변경 이유", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(r.reasonKo, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { c.scripts.approve(r); tab = ScriptVersion.of(r.version) } }) { Text("승인") }
                        OutlinedButton(onClick = { scope.launch { c.scripts.reject(r) } }) { Text("거절") }
                        IconButton(onClick = { c.tts.speak(r.text, 0.95f) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "듣기") }
                    }
                }
            }
        }

        SectionCard(title = "AI 개선") {
            val ai = c.realAi(settings)
            Text(
                if (ai != null) "Claude(${settings.aiModel})가 Natural / IH Target / Short / Keywords와 변경 이유를 만듭니다. 결과는 승인 대기로 저장됩니다. (API 비용 발생)"
                else "AI가 꺼져 있습니다. 지금은 로컬 규칙으로 Short/Keywords만 만들 수 있습니다. 설정 > AI에서 키를 입력하고 동의하면 전체 개선을 쓸 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(enabled = !busy, onClick = {
                busy = true; message = null
                scope.launch {
                    val stories = c.dao.stories().joinToString("; ") { "${it.id} ${it.titleKo}: ${it.summary}" }
                    c.scripts.requestImprovement(s, c.coach(settings), stories)
                        .onSuccess { n -> message = "개선안 ${n}개를 승인 대기로 저장했습니다." }
                        .onFailure { e -> message = e.message }
                    busy = false
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.width(6.dp)); Text(if (ai != null) "AI 개선 요청" else "로컬 Short/Keywords 만들기")
            }
            message?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEdit(s.id) }) { Icon(Icons.Filled.Edit, null); Text("편집") }
            OutlinedButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, null); Text("삭제") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("스크립트 삭제") },
        text = { Text("'${s.title}'와 개선안을 삭제합니다. 연습 기록은 남습니다. 되돌릴 수 없습니다.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { c.scripts.delete(s); onDeleted() } }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditScreen(id: Long, onDone: (Long) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<ScriptEntity?>(null) }
    var topic by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var korean by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(QuestionType.DESCRIPTION) }
    var typeOpen by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id > 0) c.dao.script(id)?.let { s ->
            loaded = s; topic = s.topic; title = s.title; question = s.question; original = s.studyText; korean = s.koreanMeaning.orEmpty(); type = s.type
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loaded != null && loaded!!.active != ScriptVersion.ORIGINAL) {
            Banner("학습본(${loaded!!.active.label}) 편집 · 원본은 보존됩니다")
        }
        OutlinedTextField(topic, { topic = it }, label = { Text("주제 (예: Movies)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(title, { title = it }, label = { Text("제목") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(question, { question = it; if (loaded == null) type = QuestionClassifier.infer(it) }, label = { Text("질문 (영어)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        ExposedDropdownMenuBox(expanded = typeOpen, onExpandedChange = { typeOpen = it }) {
            OutlinedTextField(type.label, {}, readOnly = true, label = { Text("질문 유형") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeOpen) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            androidx.compose.material3.DropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                QuestionType.entries.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { type = t; typeOpen = false }) }
            }
        }
        OutlinedTextField(original, { original = it }, label = { Text("영어 답변") }, modifier = Modifier.fillMaxWidth(), minLines = 8)
        OutlinedTextField(korean, { korean = it }, label = { Text("한국어 의미 (선택)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(enabled = original.isNotBlank() && question.isNotBlank(), onClick = {
            scope.launch {
                val base = loaded
                val saved = if (base == null) {
                    c.scripts.save(ScriptEntity(topic = topic.ifBlank { "My Scripts" }, title = title.ifBlank { question.take(40) }, question = question, questionType = type.name,
                        original = original, koreanMeaning = korean.ifBlank { null }, source = ContentSource.USER_IMPORT.name, dueEpochDay = LocalDate.now().toEpochDay()))
                } else {
                    val edited = when (base.active) {
                        ScriptVersion.ORIGINAL, ScriptVersion.KEYWORDS -> base.copy(original = original)
                        ScriptVersion.NATURAL -> base.copy(natural = original)
                        ScriptVersion.IH_TARGET -> base.copy(ihTarget = original)
                        ScriptVersion.SHORT -> base.copy(shortVersion = original)
                    }
                    c.scripts.save(edited.copy(topic = topic, title = title, question = question, questionType = type.name, koreanMeaning = korean.ifBlank { null }))
                }
                c.scripts.relink()
                onDone(saved)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("저장") }
    }
}

@Composable
fun ImportScreen(onDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("붙여넣기") }
    var mime by remember { mutableStateOf("text/plain") }
    var parsed by remember { mutableStateOf<List<ParsedScript>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            runCatching { c.importer.readUri(uri) }
                .onSuccess { r -> raw = r.rawText; fileName = r.fileName; mime = r.mime; parsed = r.scripts }
                .onFailure { e -> error = e.message ?: "가져오기 실패" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("TXT · MD · PDF · DOCX", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { picker.launch(ImportService.MIME_TYPES) }, enabled = !busy) { Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("파일 선택") }
        OutlinedTextField(raw, { raw = it; fileName = "붙여넣기"; mime = "text/plain" }, label = { Text("또는 여기에 붙여넣기") }, minLines = 8, maxLines = 14, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = raw.isNotBlank() && !busy, onClick = { scope.launch { parsed = c.importer.parseText(raw, fileName) } }) { Text("분석하기") }
        if (busy) CircularProgressIndicator()
        error?.let { Banner(it, tone = MaterialTheme.colorScheme.error) }
        if (parsed.isNotEmpty()) {
            SectionCard(title = "인식된 스크립트 ${parsed.size}개") {
                parsed.take(40).forEach { p ->
                    Text("${p.topic} · ${p.title}", style = MaterialTheme.typography.titleSmall)
                    Text("${p.questionType.label} · 약 ${p.estSeconds}초 · ${p.answer.take(70)}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
                if (parsed.size > 40) Text("외 ${parsed.size - 40}개")
            }
            Button(onClick = {
                scope.launch { c.scripts.importParsed(fileName, mime, raw, parsed); onDone() }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Text("${parsed.size}개 가져오기") }
        }
    }
}
