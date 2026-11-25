package com.opicih.coach.learning

import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ScriptEntity

enum class MorningStep(val label: String) {
    WRONG("다시 볼 표현"), NEW("오늘의 핵심 표현"), COMPLETE("문장 완성"), PRONOUNCE("발음 훈련")
}

data class MorningItem(val step: MorningStep, val chunk: ChunkEntity, val cloze: String? = null)

/** Builds the 5–10 minute morning routine: wrong 5 + new 5 + sentence completion 3 + pronunciation 2. */
object MorningRoutineBuilder {
    private fun key(phrase: String) = LookupCounter.normalize(phrase).lowercase()

    /** Bind each expression to a sentence in the currently selected version, retaining its review ID and schedule. */
    fun pool(scripts: List<ScriptEntity>, glossary: Glossary, stored: List<ChunkEntity>): List<ChunkEntity> {
        val existing = stored.sortedBy { it.source != ContentSource.ACTIVE_SCRIPT.name }.associateBy { key(it.phrase) }
        val active = linkedMapOf<String, ChunkEntity>()
        for (script in scripts) {
            for (sentence in ScriptSegmenter.plan(script.studyText).sentences) {
                for (span in glossary.spans(sentence)) {
                    if (!span.entry.isIdiom && ' ' !in span.entry.phrase) continue
                    val phrase = sentence.substring(span.start, span.end)
                    val phraseKey = key(phrase)
                    if (phraseKey in active) continue
                    val card = existing[phraseKey] ?: ChunkEntity(phrase = phrase, meaningKo = span.entry.meaningKo,
                        example = sentence, category = script.title)
                    active[phraseKey] = card.copy(phrase = phrase, meaningKo = span.entry.meaningKo, example = sentence,
                        category = "${script.topic} · ${script.title}", source = ContentSource.ACTIVE_SCRIPT.name)
                }
            }
        }
        return active.values + stored.filter { it.source != ContentSource.ACTIVE_SCRIPT.name && key(it.phrase) !in active }
    }

    fun build(chunks: List<ChunkEntity>, today: Long, seed: Long = today): List<MorningItem> {
        val rnd = java.util.Random(seed)
        val ready = chunks.filter { it.dueEpochDay <= today }.shuffled(rnd)
        val wrong = ready.filter { it.lastResult == "FAIL" }.sortedBy { it.dueEpochDay }.take(5)
        val due = ready.filter { it.lastResult == "OK" }.sortedBy { it.dueEpochDay }
        val fresh = chunks.filter { it.lastResult == "NONE" }
        val active = rotate(fresh.filter { it.source == ContentSource.ACTIVE_SCRIPT.name }, seed)
        val saved = rotate(fresh.filter { it.source == ContentSource.SCRIPT_LOOKUP.name || it.source == ContentSource.USER_IMPORT.name }, seed)
        val other = rotate(fresh.filter { it.source !in setOf(ContentSource.ACTIVE_SCRIPT.name,
            ContentSource.SCRIPT_LOOKUP.name, ContentSource.USER_IMPORT.name) }, seed)
        val newOnes = (due.take(2) + saved.take(1) + active + saved.drop(1) + due.drop(2) + other).take(5)
        val pool = wrong + newOnes
        val drills = pool.shuffled(rnd)
        val complete = drills.take(3).map { MorningItem(MorningStep.COMPLETE, it, cloze(it)) }
        val pron = (drills.drop(3) + drills.take(3)).take(2).map { MorningItem(MorningStep.PRONOUNCE, it) }
        return wrong.map { MorningItem(MorningStep.WRONG, it) } + newOnes.map { MorningItem(MorningStep.NEW, it) } + complete + pron
    }

    private fun rotate(cards: List<ChunkEntity>, day: Long): List<ChunkEntity> {
        if (cards.isEmpty()) return cards
        val ordered = cards.sortedBy { key(it.phrase) }.shuffled(java.util.Random(0))
        val offset = Math.floorMod(day * 5, cards.size.toLong()).toInt()
        return ordered.drop(offset) + ordered.take(offset)
    }

    /** Replaces the chunk inside its example sentence with a blank. */
    fun cloze(c: ChunkEntity): String {
        val core = c.phrase.trimEnd('.', '…', ' ').removeSuffix("...").trim()
        val idx = c.example.indexOf(core, ignoreCase = true)
        return if (idx >= 0) c.example.replaceRange(idx, idx + core.length, "_____") else "_____ " + c.example
    }
}
