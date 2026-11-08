package com.opicih.coach.learning

import com.opicih.coach.analysis.ErrorCounts
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.appJson

/** 진행 화면에 그릴 한 답변의 오류 횟수. */
data class ErrorTrendPoint(val createdAt: Long, val tense: Int, val abandoned: Int, val selfCorrection: Int)

/**
 * 시제 오류, 중단 문장, 자기 수정의 추세.
 * 원문 그대로 전사한 답변(`ErrorCounts.verbatim`)만 씁니다. 기기 음성 인식 결과는 문법과 필러가 보정되어 있어서
 * 같은 추세에 섞으면 오류가 줄어든 것처럼 보입니다.
 */
object ErrorTrend {
    fun countsOf(a: PracticeAttemptEntity): ErrorCounts? =
        runCatching { appJson.decodeFromString(AttemptMetrics.serializer(), a.metricsJson).errorCounts }.getOrNull()

    /** 원문 전사 답변이 없을 때 모든 기록의 JSON을 읽지 않도록, 최근 이만큼만 훑습니다. */
    const val MAX_SCAN = 200

    /** [attempts]는 최신순입니다. 결과는 오래된 것부터 최대 [max]개입니다. */
    fun points(attempts: List<PracticeAttemptEntity>, max: Int = 12): List<ErrorTrendPoint> =
        attempts.asSequence().take(MAX_SCAN)
            .mapNotNull { a -> countsOf(a)?.takeIf { it.verbatim }?.let { a to it } }
            .take(max)
            .map { (a, c) -> ErrorTrendPoint(a.createdAt, c.tense ?: 0, c.abandoned ?: 0, c.selfCorrection ?: 0) }
            .toList()
            .reversed()
}
