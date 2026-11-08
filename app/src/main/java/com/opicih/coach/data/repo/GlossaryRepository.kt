package com.opicih.coach.data.repo

import android.content.Context
import android.util.Log
import com.opicih.coach.data.db.AppDao
import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.WordLookupEntity
import com.opicih.coach.learning.Glossary
import com.opicih.coach.learning.GlossaryEntry
import com.opicih.coach.learning.LookupCounter
import java.time.LocalDate

/**
 * Script glossary: meanings come from the bundled asset (seed/glossary.json), lookups are recorded per script, and a
 * phrase looked up on [LookupCounter.AUTO_REVIEW_DAYS] different days becomes a morning-routine review card.
 */
class GlossaryRepository(context: Context, private val dao: AppDao) {
    private val ctx = context.applicationContext

    val glossary: Glossary by lazy {
        runCatching { ctx.assets.open(ASSET).use { Glossary.parse(it.readBytes().toString(Charsets.UTF_8)) } }
            .getOrElse { Log.e(TAG, "glossary asset could not be loaded; word lookup is off", it); Glossary(emptyList()) }
    }

    fun lookups(scriptId: Long) = dao.lookupsFlow(scriptId)

    /**
     * Records one lookup and returns whether the phrase is now a review card. A row's count grows only on the first
     * lookup of the phrase that day in any script, so SUM(count) over the phrase is the number of different days.
     */
    suspend fun record(scriptId: Long, entry: GlossaryEntry, surface: String, sentence: String, now: Long = System.currentTimeMillis()): Boolean {
        val add = if (LookupCounter.isNewDay(dao.lastLookupAt(entry.phrase), now)) 1 else 0
        val existing = dao.lookup(entry.phrase, scriptId)
        if (existing == null) {
            dao.insertLookup(WordLookupEntity(phrase = entry.phrase, scriptId = scriptId, surface = surface, sentence = sentence,
                count = add, firstAt = now, lastAt = now))
        } else {
            dao.updateLookup(existing.copy(count = existing.count + add, surface = surface, sentence = sentence, lastAt = now))
        }
        val inReview = isInReview(entry, surface)
        if (LookupCounter.shouldAutoAdd(dao.lookupDays(entry.phrase), inReview)) { addToReview(entry, surface, sentence); return true }
        return inReview
    }

    suspend fun isInReview(entry: GlossaryEntry, surface: String): Boolean = dao.chunkCount(reviewKeys(entry, surface)) > 0

    /** The card keeps the surface form so the morning cloze can blank it out of the script sentence. */
    suspend fun addToReview(entry: GlossaryEntry, surface: String, sentence: String) {
        if (isInReview(entry, surface)) return
        dao.insertChunks(listOf(ChunkEntity(phrase = LookupCounter.normalize(surface), meaningKo = entry.meaningKo,
            example = LookupCounter.normalize(sentence), category = CATEGORY, dueEpochDay = LocalDate.now().toEpochDay(),
            source = ContentSource.SCRIPT_LOOKUP.name)))
    }

    companion object {
        const val ASSET = "seed/glossary.json"
        const val CATEGORY = "스크립트 표현"

        private const val TAG = "Glossary"

        /** Every text a review card for [entry] may have been saved under, normalized and lower-cased. */
        fun reviewKeys(entry: GlossaryEntry, surface: String = ""): List<String> =
            (entry.allForms + entry.phrase + surface).filter { it.isNotBlank() }.map { LookupCounter.normalize(it).lowercase() }.distinct()
    }
}
