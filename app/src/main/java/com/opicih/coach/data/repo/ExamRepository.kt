package com.opicih.coach.data.repo

import androidx.room.withTransaction
import com.opicih.coach.data.db.AppDatabase
import com.opicih.coach.data.db.ExamDataUpdateEntity
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamStatusEventEntity
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.SettingsRepository
import com.opicih.coach.learning.ExamBootstrap
import com.opicih.coach.learning.ExamSchedule
import com.opicih.coach.learning.YearEndExamUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.ZonedDateTime

/** Exam registrations: the single source of truth for "which exam is next" since DB v3. */
class ExamRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao = db.dao()
    private val bootstrapLock = Mutex()

    val flow: Flow<List<ExamRegistrationEntity>> = dao.registrationsFlow()

    /** Nearest registered exam that has not ended (KST wall clock). */
    suspend fun focus(now: ZonedDateTime = nowKst()): ExamRegistrationEntity? = ExamSchedule.focus(dao.registrations(), now)

    /** Focus exam day as epochDay, used to clamp SRS intervals before the exam. */
    suspend fun focusEpochDay(): Long? = focus()?.date?.toEpochDay()

    /** Applies each data update once, including when an existing installation opens after an upgrade. */
    suspend fun bootstrapIfNeeded() = bootstrapLock.withLock {
        val s = settings.current()
        if (s.examBootstrapVersion >= YearEndExamUpdate.VERSION) return@withLock
        db.withTransaction {
            if (dao.hasExamDataUpdate(YearEndExamUpdate.VERSION)) return@withTransaction
            if (s.examBootstrapVersion < 1) {
                val plan = ExamBootstrap.plan(dao.registrations(), s.examDate)
                plan.absorbIds.forEach { id -> dao.deleteStatusEventsFor(id); dao.deleteRegistration(id) }
                plan.inserts.forEach { seed ->
                    val id = dao.insertRegistration(seed.registration)
                    dao.insertStatusEvents(seed.history.map {
                        ExamStatusEventEntity(registrationId = id, fromStatus = it.from?.name, toStatus = it.to.name, effectiveDate = it.effectiveDate, note = it.note)
                    })
                }
            }
            applyYearEndUpdate()
            // If DataStore is interrupted after this commit, retry only its settings on next startup.
            dao.insertExamDataUpdate(ExamDataUpdateEntity(YearEndExamUpdate.VERSION))
        }
        settings.update { current ->
            if (current.examBootstrapVersion < YearEndExamUpdate.VERSION) YearEndExamUpdate.updateSettings(current) else current
        }
    }

    private suspend fun applyYearEndUpdate() {
        val existing = dao.registrations()
        val completed = YearEndExamUpdate.completion(existing)
        if (completed != null) {
            writeRegistration(completed, "사용자 확인: SAIL 응시 및 IL 결과", effectiveDate = completed.dateLocal)
        }
        for (plan in YearEndExamUpdate.plans()) {
            val sameDay = existing.firstOrNull { it.type == plan.type && it.dateLocal == plan.dateLocal }
            if (sameDay == null) {
                writeRegistration(plan, "사용자 확인: 연말 시험 계획")
            } else if (sameDay.state == RegistrationStatus.PLANNED && sameDay.waiverPlanned == null) {
                writeRegistration(sameDay.copy(waiverPlanned = plan.waiverPlanned), "")
            }
        }
    }

    /** Insert or update. A status change is always written to the history table. */
    suspend fun save(r: ExamRegistrationEntity, note: String = "") {
        db.withTransaction { writeRegistration(r, note) }
    }

    private suspend fun writeRegistration(r: ExamRegistrationEntity, note: String, effectiveDate: String? = null) {
        val now = System.currentTimeMillis()
        val old = if (r.id == 0L) null else dao.registration(r.id) ?: return
        if (old == r) return
        val id = if (old == null) dao.insertRegistration(r.copy(createdAt = now, updatedAt = now)) else {
            dao.updateRegistration(r.copy(updatedAt = now))
            r.id
        }
        if (old?.status != r.status) {
            dao.insertStatusEvent(ExamStatusEventEntity(registrationId = id, fromStatus = old?.status,
                toStatus = r.status, effectiveDate = effectiveDate, note = note.ifBlank { "등록" }))
        }
    }

    suspend fun setStatus(id: Long, status: RegistrationStatus, note: String = "") {
        db.withTransaction {
            val r = dao.registration(id) ?: return@withTransaction
            writeRegistration(r.copy(status = status.name, needsReview = false), note)
        }
    }

    suspend fun delete(id: Long) {
        db.withTransaction { dao.deleteStatusEventsFor(id); dao.deleteRegistration(id) }
    }

    companion object {
        val KST: ZoneId = ZoneId.of(ExamRegistrationEntity.DEFAULT_ZONE)
        fun nowKst(): ZonedDateTime = ZonedDateTime.now(KST)
    }
}
