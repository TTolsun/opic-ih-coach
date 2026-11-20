package com.opicih.coach.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.FilledTonalButton
import com.opicih.coach.learning.SharedPassage
import com.opicih.coach.learning.sharedPassages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.StoryEntity
import com.opicih.coach.learning.StoryMap
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag

/**
 * 이야기 묶음: which real story each script tells. IH answers come from a few stories reused across questions,
 * so this screen shows the stories first and the scripts under them.
 */
@Composable
fun StoriesScreen(onOpenScript: (Long) -> Unit, onQuestion: (Long, String) -> Unit, onScripts: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val scripts by c.dao.scriptsFlow().collectAsState(initial = null)
    val stories by remember { c.dao.storiesFlow() }.collectAsState(initial = null)
    var editing by remember { mutableStateOf<StoryEntity?>(null) }
    val list = scripts ?: return
    val all = stories ?: return
    val map = remember(all, list) { StoryMap.build(all, list.filter { it.source != ContentSource.AI_SAMPLE.name }) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            if (map.uses.isEmpty() && map.withoutStory.isEmpty()) {
                Text("이야기에 연결할 대본이 없습니다.")
                TextButton(onClick = onScripts) { Text("스크립트 추가하기") }
            }
        }
        items(map.uses, key = { it.story.id }) { use ->
            StoryCard(use, onOpenScript, onQuestion, onEdit = { editing = it })
        }
        if (map.withoutStory.isNotEmpty()) {
            item {
                SectionCard(title = "아직 연결하지 않은 답변 ${map.withoutStory.size}개") {
                    Text(
                        "대본을 열어 공통 대목을 찾거나 이야기를 연결하세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    map.withoutStory.forEach { ScriptRow(it, onOpenScript) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    editing?.let { story ->
        EditStoryDialog(story, onDismiss = { editing = null }) { updated ->
            editing = null
            scope.launch { c.dao.insertStories(listOf(updated)) }
        }
    }
}

/** Rename a story or rewrite its summary and topics. The id stays, so every linked answer keeps the link. */
@Composable
internal fun EditStoryDialog(story: StoryEntity, onDismiss: () -> Unit, onSave: (StoryEntity) -> Unit) {
    var title by remember { mutableStateOf(story.titleKo) }
    var summary by remember { mutableStateOf(story.summary) }
    var answer by rememberSaveable(story.id) { mutableStateOf(story.commonAnswer.orEmpty()) }
    var topics by remember { mutableStateOf(story.reusableFor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이야기 편집") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("이야기 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(answer, { answer = it }, label = { Text("함께 외울 영어 답변") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(summary, { summary = it }, label = { Text("장면 요약") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(topics, { topics = it }, label = { Text("쓸 수 있는 주제") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(story.copy(titleKo = title.trim(), summary = summary.trim(), reusableFor = topics.trim(), commonAnswer = answer.trim().ifBlank { null }))
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun StoryCard(
    use: StoryMap.StoryUse,
    onOpenScript: (Long) -> Unit,
    onQuestion: (Long, String) -> Unit,
    onEdit: (StoryEntity) -> Unit,
) {
    SectionCard(title = use.story.titleKo, collapsible = true, initiallyExpanded = false,
        trailing = { SourceTag("답변 ${use.scripts.size}개") }) {
        if (use.story.summary.isNotBlank()) Text(use.story.summary)
        use.story.commonAnswer?.let { answer ->
            val c = LocalContainer.current
            var visible by rememberSaveable(answer) { mutableStateOf(true) }
            if (visible) Text(answer, style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = { c.tts.speak(answer) }) { Text("공통 답변 듣기") }
                TextButton(onClick = { visible = !visible }) { Text(if (visible) "가리고 떠올리기" else "답변 확인") }
            }
        }
        TextButton(onClick = { onEdit(use.story) }) {
            Text(if (use.story.commonAnswer == null) "공통 답변 작성" else "공통 답변 편집")
        }
        var selectedId by rememberSaveable { mutableStateOf(use.scripts.first().id) }
        val selected = use.scripts.firstOrNull { it.id == selectedId } ?: use.scripts.first()
        use.scripts.forEach { script ->
            TextButton(onClick = { selectedId = script.id }) {
                Text((if (script.id == selected.id) "✓ " else "") + script.question)
            }
        }
        FilledTonalButton(onClick = { onQuestion(selected.id, selected.question) }) { Text("이 질문에 답하기") }
        TextButton(onClick = { onOpenScript(selected.id) }) { Text("대본 열기 · ${selected.title}") }
        PassageComparison(selected, use.scripts, onUse = { onEdit(use.story.copy(commonAnswer = it.here)) })
    }
}

@Composable
internal fun PassageComparison(source: ScriptEntity, library: List<ScriptEntity>, onUse: (SharedPassage) -> Unit) {
    if (library.none { it.id != source.id }) return
    key(source.id, source.original, library) {
        val matches by produceState<List<SharedPassage>?>(null) {
            value = withContext(Dispatchers.Default) { sharedPassages(source, library) }
        }
        val examples = matches
        var expandAll by rememberSaveable { mutableStateOf(false) }
        if (examples == null) {
            Text("원문 비교 중…")
        } else if (examples.isEmpty()) {
            Text("공통 표현을 찾지 못했습니다.")
        } else {
            (if (expandAll) examples else examples.take(1)).forEach { match ->
                Text("함께 쓸 수 있는 원문", style = MaterialTheme.typography.labelLarge)
                Text(match.here)
                Text(match.other.question, style = MaterialTheme.typography.labelLarge)
                Text(match.there)
                match.hereDetail?.let {
                    Text("${source.title}에서 덧붙인 내용", style = MaterialTheme.typography.labelLarge)
                    Text(it)
                }
                match.thereDetail?.let {
                    Text("${match.other.title}에서 덧붙인 내용", style = MaterialTheme.typography.labelLarge)
                    Text(it)
                }
                TextButton(onClick = { onUse(match) }) { Text("이 문장으로 공통 답변 쓰기") }
                Spacer(Modifier.height(8.dp))
            }
            if (examples.size > 1) TextButton(onClick = { expandAll = !expandAll }) {
                Text(if (expandAll) "비교 접기" else "다른 비교 ${examples.size - 1}개 보기")
            }
        }
    }
}

@Composable
private fun ScriptRow(s: ScriptEntity, onOpenScript: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onOpenScript(s.id) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${s.topic} · ${s.title}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(s.mastery.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
