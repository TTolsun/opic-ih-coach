package com.opicih.coach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.ui.LocalContainer

@Composable
internal fun StoryReuseQuestions(script: ScriptEntity, onQuestion: (String) -> Unit) {
    val c = LocalContainer.current
    val stored by remember(script.id) { c.dao.scriptFlow(script.id) }.collectAsState(initial = script)
    val current = stored?.copy(activeVersion = script.activeVersion) ?: return
    key(current.id, current.question, current.studyText, current.storyIds) {
        val questions by produceState(emptyList<String>()) {
            value = c.seed.reuseQuestions(current)
        }

        if (questions.isNotEmpty()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "같은 이야기 질문 접기" else "같은 이야기 질문 ${questions.size}개")
                }
                if (expanded) questions.forEach { question ->
                    TextButton(onClick = { onQuestion(question) }) { Text(question) }
                }
            }
        }
    }
}
