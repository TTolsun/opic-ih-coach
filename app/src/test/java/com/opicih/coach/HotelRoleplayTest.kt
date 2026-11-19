package com.opicih.coach

import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.backup.BackupManager.Companion.toBackup
import com.opicih.coach.data.backup.BackupManager.Companion.toEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.learning.HotelRoleplay
import com.opicih.coach.learning.HotelStage
import com.opicih.coach.learning.MockTestBuilder
import com.opicih.coach.learning.QuestionSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotelRoleplayTest {
    private val bank = listOf(
        QuestionSource(HotelRoleplay.TOPIC, "Ask about the hotel.", "ROLEPLAY", "test bank"),
        QuestionSource(HotelRoleplay.TOPIC, "Work out the booking problem.", "ROLEPLAY", "test bank"),
        QuestionSource(HotelRoleplay.TOPIC, "Describe a booking problem from your past.", "PAST_EXPERIENCE", "test bank"),
    )

    @Test
    fun standaloneAndMockUseTheSameOrderedBankQuestions() {
        val questions = HotelRoleplay.questions(bank)
        assertEquals(HotelStage.entries, questions.keys.toList())
        assertEquals(bank, questions.values.toList())
        assertTrue(HotelRoleplay.questions(listOf(bank[0], bank[0], bank[2])).isEmpty())

        val mock = MockTestBuilder.build(bank, emptySet(), count = 15, selfLevel = 4, seed = 21L)
        val roleplay = mock.filter { it.stage == "롤플레이" }
        assertEquals(bank.map { it.text }, roleplay.map { it.text })
        assertEquals(bank.map { it.source }, roleplay.map { it.source })
    }

    @Test
    fun continuesAtTheFirstUnsavedStage() {
        assertEquals(HotelStage.QUESTIONS, HotelRoleplay.nextStage(emptySet()))
        assertEquals(HotelStage.ALTERNATIVES, HotelRoleplay.nextStage(setOf(HotelStage.QUESTIONS)))
        assertEquals(HotelStage.EXPERIENCE, HotelRoleplay.nextStage(setOf(HotelStage.ALTERNATIVES, HotelStage.QUESTIONS)))
        assertNull(HotelRoleplay.nextStage(HotelStage.entries.toSet()))
    }

    @Test
    fun extractsVerbatimExcerptsForQuestionsStatementsAndShortSuggestions() {
        val suggestions = listOf(
            "Could you check another branch for a room?",
            "I can move my reservation to Friday.",
            "How about another room?",
        )
        val answer = "My reservation is missing. " + suggestions.joinToString(" ")
        val excerpts = HotelRoleplay.alternativeExcerpts(answer)
        assertEquals(suggestions, excerpts)
        assertTrue(excerpts.all { it in answer })
        assertEquals(
            listOf("We could wait in the lobby or book another room."),
            HotelRoleplay.alternativeExcerpts("We could wait in the lobby or book another room."),
        )
    }

    @Test
    fun genericRequestsAndReservationProblemDoNotCountAsAlternativeEvidence() {
        assertTrue(HotelRoleplay.alternativeExcerpts("").isEmpty())
        assertTrue(HotelRoleplay.alternativeExcerpts("My booking is missing. Could you help me? I can’t move my booking.").isEmpty())
        assertTrue(HotelRoleplay.alternativeExcerpts("Could you check my reservation? Please show me my confirmation.").isEmpty())
        assertTrue(HotelRoleplay.alternativeExcerpts(bank[1].text).isEmpty())
    }

    @Test
    fun backupPreservesRoleplaySetsStagesAndAnswerProvenance() = runBlocking {
        val transcript = "How about another room?"
        val records = listOf(17L, 29L).flatMap { setId ->
            HotelRoleplay.questions(bank).map { (stage, question) ->
                val request = PracticeRepository.Request(
                    script = null, topic = question.topic, question = question.text, questionType = question.type,
                    mode = PracticeMode.HOTEL_ROLEPLAY, transcript = transcript, captureMode = "TYPED",
                    audioPath = null, durationMs = 0, pauses = null, transcriptFromDeviceStt = false,
                    trainingSessionId = setId, hotelStage = stage,
                )
                PracticeRepository.analyze(request, ai = null).attempt
            }
        }
        val snapshot = BackupManager.Snapshot(attempts = records.map { it.toBackup() })
        val restored = BackupManager.decode(BackupManager.encode(snapshot)).attempts.map { it.toEntity() }
        val sets = restored.groupBy { it.trainingSessionId }
        assertEquals(setOf(17L, 29L), sets.keys)
        sets.values.forEach { set ->
            assertEquals(HotelStage.entries, set.map { AttemptMetrics.from(it)?.hotelStage })
            assertEquals(bank.map { it.text }, set.map { it.questionText })
            assertTrue(set.all { it.mode == PracticeMode.HOTEL_ROLEPLAY.name && it.captureMode == "TYPED" && it.transcript == transcript })
        }
    }
}
