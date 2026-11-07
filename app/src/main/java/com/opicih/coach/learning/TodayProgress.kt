package com.opicih.coach.learning

import com.opicih.coach.data.db.DailyLogEntity

data class TaskStatus(val task: PlanTask, val done: Boolean)

/**
 * Decides which of today's plan tasks are finished, from the daily log only (real speaking/review records).
 * Kept out of the UI so the rule is unit-tested and shared by every screen that shows today's plan.
 */
object TodayProgress {

    fun statuses(plan: DayPlan, log: DailyLogEntity?): List<TaskStatus> {
        val reviewGoal = reviewGoal(plan)
        val reviewed = log?.scriptsReviewed ?: 0
        val attempts = log?.attempts ?: 0
        return plan.tasks.map { t ->
            val done = when (t.type) {
                TaskType.MORNING -> log?.morningDone == true
                TaskType.REVIEW -> reviewed >= t.targetCount
                TaskType.MOCK -> log?.mockDone == true
                // Review attempts also count as attempts, so they are subtracted before judging speaking tasks.
                else -> attempts >= t.targetCount + (if (reviewed > 0) reviewGoal else 0)
            }
            TaskStatus(t, done)
        }
    }

    /** The first unfinished task, in plan order. Null when everything is done. */
    fun next(statuses: List<TaskStatus>): PlanTask? = statuses.firstOrNull { !it.done }?.task

    fun reviewGoal(plan: DayPlan): Int = plan.tasks.firstOrNull { it.type == TaskType.REVIEW }?.targetCount ?: 1
}
