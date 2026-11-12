package com.opicih.coach.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A titled list of facts shown on demand, instead of caveat text printed on every screen. */
data class InfoTopic(val title: String, val facts: List<String>)

/**
 * The single home of the "honest measurement" caveats (README 4.1).
 * Screens show an ⓘ button that opens this; they do not repeat these sentences inline.
 */
object InfoTopics {
    val MEASUREMENT = InfoTopic(
        "측정 기준",
        listOf(
            "점수와 IH 준비도는 학습용 참고값이며 OPIc 공식 등급 예측이 아닙니다.",
            "암기 점수는 스크립트를 얼마나 떠올렸는지 보여 줍니다. 발화 흐름 점수는 말하는 동안 감지한 긴 무음으로 계산합니다. 질문에 맞는 답변인지 판단하는 피드백은 점수와 따로 보여 줍니다.",
            "발화 시간과 2초 이상 무음은 녹음된 소리로 측정합니다.",
            "단어 수, 시제, 연결 표현, 내용 일치는 음성 인식 텍스트로 계산합니다. 기기 음성 인식은 문법을 고치거나 필러를 지울 수 있습니다.",
            "준비도는 음성으로 답한 기록을 사용합니다. 말하기 점수는 같은 방식으로 전사한 답변의 로컬 점수를 비교하고, 긴 무음은 측정 기록이 있을 때 집계합니다.",
            "중단과 자기 수정은 원문 전사에 표시된 횟수입니다. 시제 오류에는 AI 또는 로컬 분석 출처를 표시합니다.",
            "발음과 억양은 측정하지 않습니다. 내 녹음과 TTS를 비교해 들으세요.",
            "실시간 인식은 녹음 파일을 남기지 않습니다. 녹음을 다시 들으려면 설정에서 '녹음 우선'을 고르세요.",
        ),
    )

    val MOCK = InfoTopic(
        "모의시험 안내",
        listOf(
            "공개된 시험 구조(자기소개 → 주제 콤보 → 돌발 → 롤플레이 → 비교/이슈)를 참고한 연습 문제입니다. 기출 문제를 복제하지 않았습니다.",
            "학습용 시간은 미니 모의시험 20분, 12·15문항 40분입니다. 문항당 약 2분을 권합니다.",
            "질문은 한 번 다시 들을 수 있습니다. 12·15문항에서는 7번 답변을 저장한 뒤 남은 문항의 난이도를 고릅니다.",
            "발음은 측정하지 않습니다. 리포트는 공식 등급 예측이 아닙니다.",
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoButton(topic: InfoTopic, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Outlined.Info, topic.title, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text(topic.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            topic.facts.forEach { Bullet(it) }
        }
    }
}
