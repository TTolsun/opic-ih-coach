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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.StoryEntity
import com.opicih.coach.learning.StoryMap
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.Banner
import com.opicih.coach.ui.components.SectionCard
import com.opicih.coach.ui.components.SourceTag

/**
 * 이야기 묶음: which real story each script tells. IH answers come from a few stories reused across questions,
 * so this screen shows the stories first and the scripts under them.
 */
@Composable
fun StoriesScreen(onOpenScript: (Long) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val scripts by c.dao.scriptsFlow().collectAsState(initial = null)
    // Re-read after an edit (stories have no Flow query; they only change here and in the script picker).
    var reload by remember { mutableIntStateOf(0) }
    val stories by produceState<List<StoryEntity>?>(null, reload) { value = c.dao.stories() }
    var editing by remember { mutableStateOf<StoryEntity?>(null) }
    val list = scripts ?: return
    val all = stories ?: return
    val map = StoryMap.build(all, list)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                "실제 이야기 ${map.uses.size}개가 답변 ${map.uses.flatMap { it.scripts }.distinctBy { it.id }.size}개에 쓰입니다. " +
                    "새 질문을 받으면 질문에 맞는 이야기를 골라 그 장면으로 답하세요. 문장을 외우기보다 장면과 순서를 기억하는 편이 낫습니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(map.uses, key = { it.story.id }) { use -> StoryCard(use, onOpenScript) { editing = use.story } }
        if (map.withoutStory.isNotEmpty()) {
            item {
                SectionCard(title = "이야기가 없는 답변 ${map.withoutStory.size}개") {
                    Text(
                        "묘사나 습관만 있는 답변입니다. 시험에서 이 주제가 나오면 위 이야기 중 하나를 짧게 붙이면 문단이 길어집니다.",
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
            scope.launch { c.dao.insertStories(listOf(updated)); reload++ }
        }
    }
}

/** Rename a story or rewrite its summary and topics. The id stays, so every linked answer keeps the link. */
@Composable
private fun EditStoryDialog(story: StoryEntity, onDismiss: () -> Unit, onSave: (StoryEntity) -> Unit) {
    var title by remember { mutableStateOf(story.titleKo) }
    var summary by remember { mutableStateOf(story.summary) }
    var topics by remember { mutableStateOf(story.reusableFor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이야기 편집") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("이야기 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(summary, { summary = it }, label = { Text("장면 요약") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(topics, { topics = it }, label = { Text("쓸 수 있는 주제") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(story.copy(titleKo = title.trim(), summary = summary.trim(), reusableFor = topics.trim()))
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun StoryCard(use: StoryMap.StoryUse, onOpenScript: (Long) -> Unit, onEdit: () -> Unit) {
    val own = use.story.tags == "user"
    SectionCard(title = use.story.titleKo, trailing = { SourceTag((if (own) "직접 만듦 · " else "") + "답변 ${use.scripts.size}개") }) {
        if (use.story.summary.isNotBlank()) Text(use.story.summary, style = MaterialTheme.typography.bodyMedium)
        if (use.story.reusableFor.isNotBlank()) {
            Text("이런 질문에 쓸 수 있습니다: ${use.story.reusableFor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (use.sharedAcrossTopics) {
            Spacer(Modifier.height(6.dp))
            Banner(
                "${use.topics.joinToString(" · ")} 주제가 같은 이야기를 씁니다. 한 시험에 함께 나오면 같은 이야기를 두 번 하게 되니, 한쪽은 다른 장면을 준비해 두세요.",
                tone = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.height(6.dp))
        use.scripts.forEach { ScriptRow(it, onOpenScript) }
        TextButton(onClick = onEdit) { Text("편집") }
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
