package com.opicih.coach.data.seed

import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptSegmentEntity
import com.opicih.coach.learning.ScriptSegmenter
import kotlinx.serialization.Serializable

@Serializable
internal data class AppointmentSeed(
    val title: String,
    val type: String,
    val question: String,
    val original: String,
    val questionKo: String,
    val sentenceKo: List<String>,
) {
    fun newScript(today: Long) = ScriptEntity(
        topic = "Appointments", title = title, questionType = type, question = question,
        original = original, source = ContentSource.SEED_USER.name, dueEpochDay = today,
        estSeconds = (TextNormalizer.words(original).size / 2.2).toInt().coerceAtLeast(10),
    )

    fun matches(script: ScriptEntity) =
        script.topic == "Appointments" && (script.title == title || script.question == question)

    fun translationsFor(script: ScriptEntity, existing: List<ScriptSegmentEntity>): List<ScriptSegmentEntity> {
        if (script.question != question || script.studyText != original) return emptyList()
        val hash = ScriptSegmenter.sourceHash(question, original)
        val aligned = ScriptSegmenter.assemble(question, ScriptSegmenter.plan(original), questionKo, sentenceKo)
        val rows = aligned.map {
            ScriptSegmentEntity(scriptId = script.id, version = script.activeVersion, sourceHash = hash,
                kind = it.kind.name, idx = it.idx, en = it.en, ko = it.ko)
        }
        if (existing.isEmpty() || existing.none { it.sourceHash == hash }) return rows

        // Upgrade only the known checkup typos. Keep translations the learner has changed.
        val byPosition = rows.associateBy { it.kind to it.idx }
        val repaired = existing.map { old ->
            val expected = byPosition[old.kind to old.idx]
            if (old.sourceHash != hash || expected == null) return@map old
            val en = if (old.en == expected.en.replace("did I get taller again?\"", "did I get taller again?")) expected.en else old.en
            val ko = if (old.ko == expected.ko.replace("사내 의원", "회사 내 사제의원")) expected.ko else old.ko
            old.copy(en = en, ko = ko)
        }
        return if (repaired == existing) emptyList() else repaired
    }
}
