package com.opicih.coach.learning

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class BundledSentence(val en: String, val ko: String)

/** 앱에 내장한 번역 한 건. [hash]는 질문과 학습본 텍스트의 해시([ScriptSegmenter.sourceHash])다. */
@Serializable
data class BundledItem(
    val hash: String,
    val title: String = "",
    val question: String = "",
    val questionKo: String,
    val sentences: List<BundledSentence>,
)

@Serializable data class BundledFile(val version: Int = 1, val items: List<BundledItem> = emptyList())

/**
 * assets/seed/segments_ko.json: 미리 만들어 둔 영한 번역. API 키나 네트워크 없이 바로 쓸 수 있다.
 * 스크립트의 질문이나 학습본이 번역을 만든 때와 달라졌으면 해시가 맞지 않아 가져오지 않는다.
 */
object BundledSegments {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): BundledFile = json.decodeFromString(BundledFile.serializer(), text)

    /**
     * 이 스크립트의 현재 질문·학습본에 맞는 내장 번역이 있으면 저장할 행을, 없으면 null을 돌려준다.
     * 해시가 같아도 영어 문장 목록이 지금 분할 결과와 다르면(분할 규칙이 바뀐 경우) 어긋나지 않도록 쓰지 않는다.
     */
    fun rowsFor(question: String, studyText: String, items: List<BundledItem>): List<SegmentRow>? {
        val hash = ScriptSegmenter.sourceHash(question, studyText)
        val item = items.firstOrNull { it.hash == hash } ?: return null
        val plan = ScriptSegmenter.plan(studyText)
        if (plan.sentences != item.sentences.map { it.en }) return null
        return ScriptSegmenter.assemble(question, plan, item.questionKo, item.sentences.map { it.ko })
    }
}
