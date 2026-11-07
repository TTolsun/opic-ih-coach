package com.opicih.coach.data.repo

import androidx.room.withTransaction
import com.opicih.coach.data.db.AppDatabase
import com.opicih.coach.data.db.ExamRegistrationEntity
import com.opicih.coach.data.db.ExamStatusEventEntity
import com.opicih.coach.data.db.RegistrationStatus
import com.opicih.coach.data.prefs.SettingsRepository
import com.opicih.coach.learning.ExamBootstrap
import com.opicih.coach.learning.ExamSchedule
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import java.time.ZonedDateTime

/** Exam registrations: the single source of truth for "which exam is next" since DB v3. */
class ExamRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao = db.dao()

    val flow: Flow<List<ExamRegistrationEntity>> = dao.registrationsFlow()

    suspend fun all(): List<ExamRegistrationEntity> = dao.registrations()

    /** Nearest registered exam that has not ended (KST wall clock). */
    suspend fun focus(now: ZonedDateTime = nowKst()): ExamRegistrationEntity? = ExamSchedule.focus(dao.registrations(), now)

    /** Focus exam day as epochDay, used to clamp SRS intervals before the exam. */
    suspend fun focusEpochDay(): Long? = focus()?.date?.toEpochDay()

    /** Runs once: converts v2 exam data and adds the user-confirmed exam facts without overwriting anything. */
    suspend fun bootstrapIfNeeded() {
        val s = settings.current()
        if (s.examBootstrapVersion >= BOOTSTRAP_VERSION) return
        db.withTransaction {
            val plan = ExamBootstrap.plan(dao.registrations(), s.examDate)
            plan.absorbIds.forEach { id -> dao.deleteStatusEventsFor(id); dao.deleteRegistration(id) }
            plan.inserts.forEach { seed ->
                val id = dao.insertRegistration(seed.registration)
                dao.insertStatusEvents(seed.history.map {
                    ExamStatusEventEntity(registrationId = id, fromStatus = it.from?.name, toStatus = it.to.name, effectiveDate = it.effectiveDate, note = it.note)
                })
            }
        }
        // User-confirmed on 2026-11-07: one OPIc waiver available. Only set when nothing was entered before.
        settings.update { it.copy(examBootstrapVersion = BOOTSTRAP_VERSION, opicWaivers = if (it.opicWaivers < 0) 1 else it.opicWaivers) }
    }

    /** Insert or update. A status change is always written to the history table. */
    suspend fun save(r: ExamRegistrationEntity, note: String = "") {
        db.withTransaction {
            val now = System.currentTimeMillis()
            if (r.id == 0L) {
                val id = dao.insertRegistration(r.copy(createdAt = now, updatedAt = now))
                dao.insertStatusEvent(ExamStatusEventEntity(registrationId = id, fromStatus = null, toStatus = r.status, note = note.ifBlank { "등록" }))
            } else {
                val old = dao.registration(r.id)
                dao.updateRegistration(r.copy(updatedAt = now))
                if (old != null && old.status != r.status) {
                    dao.insertStatusEvent(ExamStatusEventEntity(registrationId = r.id, fromStatus = old.status, toStatus = r.status, note = note))
                }
            }
        }
    }

    suspend fun setStatus(id: Long, status: RegistrationStatus, note: String = "") {
        val r = dao.registration(id) ?: return
        save(r.copy(status = status.name, needsReview = false), note)
    }

    suspend fun delete(id: Long) {
        db.withTransaction { dao.deleteStatusEventsFor(id); dao.deleteRegistration(id) }
    }

    companion object {
        const val BOOTSTRAP_VERSION = 1
        val KST: ZoneId = ZoneId.of(ExamRegistrationEntity.DEFAULT_ZONE)
        fun nowKst(): ZonedDateTime = ZonedDateTime.now(KST)
    }
}
