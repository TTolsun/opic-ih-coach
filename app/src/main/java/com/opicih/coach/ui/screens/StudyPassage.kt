package com.opicih.coach.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.learning.ListenCycleBuilder
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.components.GlossaryText

@Composable
internal fun StudyPassage(script: ScriptEntity) {
    val segments = LocalContainer.current.segments
    GlossaryText(script.studyText, script.id)
    key(script.id, script.question, script.studyText, script.studyMeaning, script.active) {
        val meaning = script.studyMeaning?.takeIf { it.isNotBlank() && ListenCycleBuilder.koreanMatchesStudyText(script) }
        val translation by produceState(meaning) {
            if (meaning == null) value = segments.forPlayback(script)?.fullKo
        }
        translation?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
