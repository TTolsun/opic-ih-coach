package com.opicih.coach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.ui.components.InfoButton
import com.opicih.coach.ui.components.InfoTopics
import com.opicih.coach.ui.components.Pill

/** Learning stage: memorise the script, recall it without looking, then apply it to new questions. */
enum class PracticeStage(val label: String) { MEMORIZE("암기"), RECALL("인출"), APPLY("응용") }

/**
 * How each practice mode is presented. One table, so a new mode is added here only
 * (the screen reads it instead of branching per mode for labels and instructions).
 * [hint] is the only instruction shown for the mode: one short line.
 */
data class ModeUi(val mode: PracticeMode, val stage: PracticeStage, val label: String, val hint: String)

object PracticeModes {
    val all = listOf(
        ModeUi(PracticeMode.READ_LISTEN, PracticeStage.MEMORIZE, PracticeMode.READ_LISTEN.label, "학습본을 듣고 소리 내어 읽기"),
        ModeUi(PracticeMode.SHADOWING, PracticeStage.MEMORIZE, PracticeMode.SHADOWING.label, "한 문장씩 듣고 같은 속도로 따라 말하기"),
        ModeUi(PracticeMode.FILL_BLANK, PracticeStage.MEMORIZE, PracticeMode.FILL_BLANK.label, "빈칸을 채워 전체를 말하기 · 막히면 빈칸 탭"),
        ModeUi(PracticeMode.KEYWORD, PracticeStage.MEMORIZE, PracticeMode.KEYWORD.label, "키워드만 보고 말하기"),
        ModeUi(PracticeMode.BLIND, PracticeStage.RECALL, PracticeMode.BLIND.label, "스크립트 없이 말하기 · 표현이 달라도 내용이 같으면 인정"),
        ModeUi(PracticeMode.RANDOM, PracticeStage.RECALL, PracticeMode.RANDOM.label, "다르게 물어본 질문에 같은 내용으로 답하기"),
        ModeUi(PracticeMode.VARIATION, PracticeStage.APPLY, PracticeMode.VARIATION.label, "미션 조건(시제·질문)을 지켜 바꿔 말하기"),
        ModeUi(PracticeMode.FREE, PracticeStage.APPLY, PracticeMode.FREE.label, "처음 보는 질문에 바로 답하기"),
        ModeUi(PracticeMode.FRAME_ATTACH, PracticeStage.APPLY, PracticeMode.FRAME_ATTACH.label, "처음 보는 질문에 문장 틀을 써서 답하기"),
        ModeUi(PracticeMode.PARAGRAPH_LADDER, PracticeStage.APPLY, PracticeMode.PARAGRAPH_LADDER.label, "같은 질문에 1문장, 3문장, 5문장으로 늘려 답하기"),
    )

    fun of(mode: PracticeMode): ModeUi = all.firstOrNull { it.mode == mode } ?: all.first { it.mode == PracticeMode.FREE }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModeSelector(current: PracticeMode, onSelect: (PracticeMode) -> Unit) {
    val cur = PracticeModes.of(current)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                PracticeStage.entries.forEachIndexed { i, st ->
                    SegmentedButton(
                        selected = cur.stage == st,
                        onClick = { if (cur.stage != st) onSelect(PracticeModes.all.first { it.stage == st }.mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, PracticeStage.entries.size),
                        label = { Text(st.label) },
                    )
                }
            }
            InfoButton(InfoTopics.PRACTICE_STAGES)
        }
        // FlowRow, not a LazyRow: with the full mode names the 4th chip ran off screen with no hint to scroll.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PracticeModes.all.filter { it.stage == cur.stage }.forEach { m ->
                Pill(m.label, m.mode == current) { onSelect(m.mode) }
            }
        }
        Text(cur.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
