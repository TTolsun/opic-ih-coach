package com.opicih.coach.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class StudyLevel(val label: String, val minutes: Int) {
    QUICK("Quick 10분", 10), STANDARD("Standard 30분", 30), INTENSIVE("Intensive 60분", 60);

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/** How the Speaking screen captures audio. See docs/HANDOFF.md section 4. */
enum class CaptureMode(val label: String, val description: String) {
    LIVE_STT("실시간 인식", "기기 음성 인식으로 바로 텍스트를 만듭니다. 녹음 파일은 남지 않습니다."),
    RECORD_ONLY("녹음 우선", "녹음해서 다시 들을 수 있습니다. 텍스트는 클라우드 전사(선택) 또는 없음입니다.");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: LIVE_STT
    }
}

data class Settings(
    val examDate: LocalDate = DEFAULT_EXAM,
    val studyLevel: StudyLevel = StudyLevel.STANDARD,
    val alarmEnabled: Boolean = true,
    val alarmHour: Int = 7,
    val alarmMinute: Int = 0,
    val alarmSound: Boolean = true,
    val alarmVibrate: Boolean = true,
    val useExactAlarm: Boolean = true,
    val eveningReminder: Boolean = true,
    val surveyTopics: Set<String> = DEFAULT_SURVEY,
    val selfLevel: Int = 4,
    val captureMode: CaptureMode = CaptureMode.LIVE_STT,
    val ttsRate: Float = 0.9f,
    val aiEnabled: Boolean = false,
    val aiModel: String = DEFAULT_MODEL,
    val aiTextConsent: Boolean = false,
    val cloudSttEnabled: Boolean = false,
    val audioUploadConsent: Boolean = false,
    val audioRetentionDays: Int = 14,
    val seedVersion: Int = 0,
    val focusTopics: Set<String> = emptySet(),
) {
    companion object {
        val DEFAULT_EXAM: LocalDate = LocalDate.of(2026, 11, 14)
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        val DEFAULT_SURVEY = setOf("Overseas Travel", "Domestic Travel", "Movies", "Vacation", "Home & Furniture", "Park & Music")
        val MODEL_CHOICES = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (품질 우선)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (기본, 균형)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (저비용)",
        )
    }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val exam = longPreferencesKey("exam_epoch_day")
        val level = stringPreferencesKey("study_level")
        val alarmOn = booleanPreferencesKey("alarm_on")
        val alarmH = intPreferencesKey("alarm_h")
        val alarmM = intPreferencesKey("alarm_m")
        val sound = booleanPreferencesKey("alarm_sound")
        val vibrate = booleanPreferencesKey("alarm_vibrate")
        val exact = booleanPreferencesKey("alarm_exact")
        val evening = booleanPreferencesKey("evening_reminder")
        val survey = stringSetPreferencesKey("survey")
        val selfLevel = intPreferencesKey("self_level")
        val capture = stringPreferencesKey("capture_mode")
        val ttsRate = floatPreferencesKey("tts_rate")
        val aiOn = booleanPreferencesKey("ai_on")
        val aiModel = stringPreferencesKey("ai_model")
        val aiText = booleanPreferencesKey("ai_text_consent")
        val cloudStt = booleanPreferencesKey("cloud_stt")
        val audioConsent = booleanPreferencesKey("audio_consent")
        val retention = intPreferencesKey("audio_retention")
        val seed = intPreferencesKey("seed_version")
        val focus = stringSetPreferencesKey("focus_topics")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = flow.first()

    suspend fun update(block: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = block(p.toSettings())
            p[K.exam] = s.examDate.toEpochDay()
            p[K.level] = s.studyLevel.name
            p[K.alarmOn] = s.alarmEnabled
            p[K.alarmH] = s.alarmHour
            p[K.alarmM] = s.alarmMinute
            p[K.sound] = s.alarmSound
            p[K.vibrate] = s.alarmVibrate
            p[K.exact] = s.useExactAlarm
            p[K.evening] = s.eveningReminder
            p[K.survey] = s.surveyTopics
            p[K.selfLevel] = s.selfLevel
            p[K.capture] = s.captureMode.name
            p[K.ttsRate] = s.ttsRate
            p[K.aiOn] = s.aiEnabled
            p[K.aiModel] = s.aiModel
            p[K.aiText] = s.aiTextConsent
            p[K.cloudStt] = s.cloudSttEnabled
            p[K.audioConsent] = s.audioUploadConsent
            p[K.retention] = s.audioRetentionDays
            p[K.seed] = s.seedVersion
            p[K.focus] = s.focusTopics
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            examDate = this[K.exam]?.let { LocalDate.ofEpochDay(it) } ?: d.examDate,
            studyLevel = StudyLevel.of(this[K.level]),
            alarmEnabled = this[K.alarmOn] ?: d.alarmEnabled,
            alarmHour = this[K.alarmH] ?: d.alarmHour,
            alarmMinute = this[K.alarmM] ?: d.alarmMinute,
            alarmSound = this[K.sound] ?: d.alarmSound,
            alarmVibrate = this[K.vibrate] ?: d.alarmVibrate,
            useExactAlarm = this[K.exact] ?: d.useExactAlarm,
            eveningReminder = this[K.evening] ?: d.eveningReminder,
            surveyTopics = this[K.survey] ?: d.surveyTopics,
            selfLevel = this[K.selfLevel] ?: d.selfLevel,
            captureMode = CaptureMode.of(this[K.capture]),
            ttsRate = this[K.ttsRate] ?: d.ttsRate,
            aiEnabled = this[K.aiOn] ?: d.aiEnabled,
            aiModel = this[K.aiModel] ?: d.aiModel,
            aiTextConsent = this[K.aiText] ?: d.aiTextConsent,
            cloudSttEnabled = this[K.cloudStt] ?: d.cloudSttEnabled,
            audioUploadConsent = this[K.audioConsent] ?: d.audioUploadConsent,
            audioRetentionDays = this[K.retention] ?: d.audioRetentionDays,
            seedVersion = this[K.seed] ?: d.seedVersion,
            focusTopics = this[K.focus] ?: d.focusTopics,
        )
    }
}
