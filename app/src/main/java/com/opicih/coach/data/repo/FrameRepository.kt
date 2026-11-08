package com.opicih.coach.data.repo

import com.opicih.coach.data.db.AppDao
import com.opicih.coach.data.db.SentenceFrameEntity
import com.opicih.coach.learning.FrameExtractor
import com.opicih.coach.learning.FrameStatus
import com.opicih.coach.learning.ScriptText

/**
 * Sentence frames the learner reuses across questions. Frames are only suggested here (PENDING); practice uses the
 * ones the learner approved.
 */
class FrameRepository(private val dao: AppDao) {
    val flow = dao.framesFlow()

    suspend fun approved(): List<SentenceFrameEntity> = dao.approvedFrames()

    /**
     * Looks through the learner's scripts (every version text they have) for frames they already use, tops up with
     * built-in frames, and stores the new ones as PENDING. Returns how many new frames were added.
     */
    suspend fun findNew(): Int {
        val existing = dao.frames().map { it.pattern }.toSet()
        val texts = dao.scripts().flatMap { s ->
            listOfNotNull(s.original, s.natural, s.ihTarget).filter { it.isNotBlank() }.map { ScriptText(s.id, it) }
        }
        val fresh = FrameExtractor.extract(texts, existing).map {
            SentenceFrameEntity(pattern = it.pattern, hintKo = it.hintKo, example = it.example, sourceScriptId = it.sourceScriptId,
                origin = it.origin.name, status = FrameStatus.PENDING.name)
        }
        dao.insertFrames(fresh)
        return fresh.size
    }

    suspend fun setStatus(id: Long, status: FrameStatus) = dao.setFrameStatus(id, status.name)
}
