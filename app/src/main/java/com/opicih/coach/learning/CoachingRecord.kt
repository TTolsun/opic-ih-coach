package com.opicih.coach.learning

import kotlinx.serialization.Serializable

@Serializable
enum class CoachingStage { FIRST, RETRY, DELAYED }

@Serializable
data class CoachingTrack(val id: String, val promptId: String, val stage: CoachingStage)

/** Read-only metadata from 0.4.3. Past attempts retain their original assessment status. */
@Serializable
data class CoachingRecord(
    val track: CoachingTrack,
    val evidence: CoachingEvidence?,
    val receivedCorrection: Boolean,
    val questionListens: Int,
    val day: Long,
    val dueDay: Long? = null,
    val assessmentId: String? = null,
    val opened: Int = 0,
    val assessmentSubmitted: Boolean = false,
) {
    val seenBefore: Boolean get() = track.stage == CoachingStage.DELAYED && opened > 1

    val usableForIndependentAssessment: Boolean
        get() = evidence != null && !receivedCorrection && !seenBefore
}

@Serializable
data class CoachingEvidence(val parts: List<String?>, val sample: String)
