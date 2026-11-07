package com.opicih.coach.learning

import com.opicih.coach.data.db.ChunkEntity

enum class MorningStep(val label: String) {
    WRONG("어제 틀린 표현"), NEW("오늘의 핵심 표현"), COMPLETE("문장 완성"), PRONOUNCE("발음 훈련")
}

data class MorningItem(val step: MorningStep, val chunk: ChunkEntity, val cloze: String? = null)

/** Builds the 5–10 minute morning routine: wrong 5 + new 5 + sentence completion 3 + pronunciation 2. */
object MorningRoutineBuilder {
    fun build(chunks: List<ChunkEntity>, today: Long, seed: Long = today): List<MorningItem> {
        val rnd = java.util.Random(seed)
        val wrong = chunks.filter { it.lastResult == "FAIL" }.sortedByDescending { it.lastReviewedEpochDay ?: 0 }.take(5)
        val due = chunks.filter { it.lastResult != "NONE" && it.dueEpochDay <= today && it !in wrong }
        val fresh = chunks.filter { it.lastResult == "NONE" }
        val newOnes = (fresh.take(5) + due).take(5 + maxOf(0, 5 - wrong.size)).take(10 - wrong.size).take(5)
        val pool = (wrong + newOnes).ifEmpty { chunks.shuffled(rnd).take(5) }
        val complete = pool.shuffled(rnd).take(3).map { MorningItem(MorningStep.COMPLETE, it, cloze(it)) }
        val pron = pool.shuffled(rnd).take(2).map { MorningItem(MorningStep.PRONOUNCE, it) }
        return wrong.map { MorningItem(MorningStep.WRONG, it) } + newOnes.map { MorningItem(MorningStep.NEW, it) } + complete + pron
    }

    /** Replaces the chunk inside its example sentence with a blank. */
    fun cloze(c: ChunkEntity): String {
        val core = c.phrase.trimEnd('.', '…', ' ').removeSuffix("...").trim()
        val idx = c.example.indexOf(core, ignoreCase = true)
        return if (idx >= 0) c.example.replaceRange(idx, idx + core.length, "_____") else "_____ " + c.example
    }
}
