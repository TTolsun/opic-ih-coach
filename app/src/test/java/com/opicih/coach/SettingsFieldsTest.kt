package com.opicih.coach

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.prefs.SETTINGS_FIELDS
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.prefs.StudyLevel
import com.opicih.coach.data.prefs.toSettings
import com.opicih.coach.data.prefs.writeChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.LocalDate

class SettingsFieldsTest {
    @Test
    fun everySettingsPropertyHasExactlyOneTableEntry() {
        val props = Settings::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
        val tableProps = SETTINGS_FIELDS.map { it.property }
        assertEquals(props.sorted(), tableProps.sorted())
        assertEquals(tableProps.size, tableProps.toSet().size)
    }

    @Test
    fun keyNamesAreUnique() {
        val names = SETTINGS_FIELDS.map { it.key.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun emptyPreferencesYieldDefaults() {
        assertEquals(Settings(), emptyPreferences().toSettings())
    }

    @Test
    fun roundTripNonDefaultValues() {
        val s = Settings(
            examDate = LocalDate.of(2027, 1, 2),
            studyLevel = StudyLevel.INTENSIVE,
            alarmEnabled = false, alarmHour = 5, alarmMinute = 45,
            alarmSound = false, alarmVibrate = false, useExactAlarm = false, eveningReminder = false,
            surveyTopics = setOf("A", "B"), selfLevel = 6,
            captureMode = CaptureMode.RECORD_ONLY,
            ttsRate = 1.2f, ttsVoice = "voice-x", ttsPitch = 1.1f,
            aiEnabled = true, geminiModel = "m", aiTextConsent = true, cloudSttEnabled = true,
            audioUploadConsent = true, audioRetentionDays = 3, seedVersion = 9,
            focusTopics = setOf("F"), retakeDays = 30, retakeVerified = true, resultLagDays = 5,
            gradeDeadline = LocalDate.of(2027, 3, 4), examSlots = setOf("2027-01-05"),
            registerLeadDays = 3, examBootstrapVersion = 1, opicWaivers = 2,
            opicWaiverDeclaredOn = LocalDate.of(2027, 2, 2), planWithWaiver = true,
            sailSharesOpicLimit = true, aiDailyCap = 5, aiCallDay = 20000L, aiCallCount = 4,
            studyBlocksEnabled = false, studyBlocks = "60:10",
            listenQuestionVoice = "q-voice", listenAnswerVoice = "a-voice", listenGapMs = 1200, listenRate = 1.25f,
        )
        val prefs = mutablePreferencesOf()
        SETTINGS_FIELDS.forEach { it.write(prefs, s) }
        assertEquals(s, prefs.toSettings())
    }

    @Test
    fun writesOnlyChangedFields() {
        val prefs = mutablePreferencesOf()
        val old = prefs.toSettings()
        prefs.writeChanges(old, old.copy(alarmHour = 9))
        assertEquals(1, prefs.asMap().size)
        assertEquals(9, prefs.toSettings().alarmHour)
    }

    @Test fun clearedDeadlineStaysCleared() {
        val prefs = mutablePreferencesOf()
        SETTINGS_FIELDS.forEach { it.write(prefs, Settings(gradeDeadline = null)) }
        assertNull(prefs.toSettings().gradeDeadline)
    }
}
