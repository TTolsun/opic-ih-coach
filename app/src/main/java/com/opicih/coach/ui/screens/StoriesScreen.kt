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
    val scripts by c.dao.scriptsFlow().collectAsState(initial = null)
    // Stories are seeded reference data and do not change while the screen is open.
    val stories by produceState<List<StoryEntity>?>(null) { value = c.dao.stories() }
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
        items(map.uses, key = { it.story.id }) { use -> StoryCard(use, onOpenScript) }
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
}

@Composable
private fun StoryCard(use: StoryMap.StoryUse, onOpenScript: (Long) -> Unit) {
    SectionCard(title = use.story.titleKo, trailing = { SourceTag("답변 ${use.scripts.size}개") }) {
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
