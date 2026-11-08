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
    RECORD_ONLY("녹음 우선", "녹음해서 다시 들을 수 있습니다. 텍스트는 Gemini 전사(설정에서 켜기) 또는 없음입니다.");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: LIVE_STT
    }
}

data class Settings(
    /**
     * Legacy single exam date (v2). Since v3 the schedule lives in `exam_registrations`; this value is only read once
     * by ExamBootstrap and is no longer used for planning.
     */
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
    /** Installed TTS voice name; empty = engine default. */
    val ttsVoice: String = "",
    val ttsPitch: Float = 0.9f,
    val aiEnabled: Boolean = false,
    val geminiModel: String = "gemini-3.5-flash",
    val aiTextConsent: Boolean = false,
    val cloudSttEnabled: Boolean = false,
    val audioUploadConsent: Boolean = false,
    val audioRetentionDays: Int = 14,
    val seedVersion: Int = 0,
    val focusTopics: Set<String> = emptySet(),
    // Retake planning (values the app cannot look up; the user confirms them).
    val retakeDays: Int = 25,
    val retakeVerified: Boolean = false,
    val resultLagDays: Int = 10,
    val gradeDeadline: LocalDate? = LocalDate.of(2026, 12, 31),
    val examSlots: Set<String> = emptySet(),
    val registerLeadDays: Int = 7,
    /** 1 = v2 exam data has been converted into registrations (runs once). */
    val examBootstrapVersion: Int = 0,
    /** OPIc waivers the user reports holding; -1 = not entered. Waiver rules themselves are unverified (B phase). */
    val opicWaivers: Int = -1,
    /** Day the waiver balance above was entered; later waiver uses are subtracted from it. */
    val opicWaiverDeclaredOn: LocalDate = LocalDate.of(2026, 11, 7),
    /** Plan the next OPIc with a waiver (recommendations are recomputed when this changes). */
    val planWithWaiver: Boolean = false,
    /** User setting: SAIL and OPIc share one retake limit (default: independent). */
    val sailSharesOpicLimit: Boolean = false,
    /** Paid AI calls allowed per day for automatic chains (SAIL conversation follow-ups + summaries). */
    val aiDailyCap: Int = 30,
    val aiCallDay: Long = 0,
    val aiCallCount: Int = 0,
    /** Study block contract (see alarm/StudyBlocks.kt). Encoded as "startMinute:minutes,...". */
    val studyBlocksEnabled: Boolean = true,
    val studyBlocks: String = "450:40,1140:20,1440:60",
) {
    fun aiCallsToday(todayEpochDay: Long): Int = if (aiCallDay == todayEpochDay) aiCallCount else 0

    fun slotDates(): List<LocalDate> = examSlots.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()

    companion object {
        val DEFAULT_EXAM: LocalDate = LocalDate.of(2026, 11, 11)
        val DEFAULT_SURVEY = setOf("Overseas Travel", "Domestic Travel", "Movies", "Vacation", "Home & Furniture", "Park & Music")
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
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val aiOn = booleanPreferencesKey("ai_on")
        val geminiModel = stringPreferencesKey("gemini_model")
        val aiText = booleanPreferencesKey("ai_text_consent")
        val cloudStt = booleanPreferencesKey("cloud_stt")
        val audioConsent = booleanPreferencesKey("audio_consent")
        val retention = intPreferencesKey("audio_retention")
        val seed = intPreferencesKey("seed_version")
        val focus = stringSetPreferencesKey("focus_topics")
        val retakeDays = intPreferencesKey("retake_days")
        val retakeVerified = booleanPreferencesKey("retake_verified")
        val resultLag = intPreferencesKey("result_lag_days")
        val deadline = longPreferencesKey("grade_deadline")
        val slots = stringSetPreferencesKey("exam_slots")
        val lead = intPreferencesKey("register_lead_days")
        val examBootstrap = intPreferencesKey("exam_bootstrap_version")
        val opicWaivers = intPreferencesKey("opic_waivers")
        val waiverDeclaredOn = longPreferencesKey("opic_waiver_declared_on")
        val planWaiver = booleanPreferencesKey("plan_with_waiver")
        val sailShares = booleanPreferencesKey("sail_shares_opic_limit")
        val aiCap = intPreferencesKey("ai_daily_cap")
        val aiDay = longPreferencesKey("ai_call_day")
        val aiCount = intPreferencesKey("ai_call_count")
        val blocksOn = booleanPreferencesKey("study_blocks_on")
        val blocks = stringPreferencesKey("study_blocks")
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
            p[K.ttsVoice] = s.ttsVoice
            p[K.ttsPitch] = s.ttsPitch
            p[K.aiOn] = s.aiEnabled
            p[K.geminiModel] = s.geminiModel
            p[K.aiText] = s.aiTextConsent
            p[K.cloudStt] = s.cloudSttEnabled
            p[K.audioConsent] = s.audioUploadConsent
            p[K.retention] = s.audioRetentionDays
            p[K.seed] = s.seedVersion
            p[K.focus] = s.focusTopics
            p[K.retakeDays] = s.retakeDays
            p[K.retakeVerified] = s.retakeVerified
            p[K.resultLag] = s.resultLagDays
            p[K.deadline] = s.gradeDeadline?.toEpochDay() ?: -1L
            p[K.slots] = s.examSlots
            p[K.lead] = s.registerLeadDays
            p[K.examBootstrap] = s.examBootstrapVersion
            p[K.opicWaivers] = s.opicWaivers
            p[K.waiverDeclaredOn] = s.opicWaiverDeclaredOn.toEpochDay()
            p[K.planWaiver] = s.planWithWaiver
            p[K.sailShares] = s.sailSharesOpicLimit
            p[K.aiCap] = s.aiDailyCap
            p[K.aiDay] = s.aiCallDay
            p[K.aiCount] = s.aiCallCount
            p[K.blocksOn] = s.studyBlocksEnabled
            p[K.blocks] = s.studyBlocks
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
            ttsVoice = this[K.ttsVoice] ?: d.ttsVoice,
            ttsPitch = this[K.ttsPitch] ?: d.ttsPitch,
            aiEnabled = this[K.aiOn] ?: d.aiEnabled,
            geminiModel = this[K.geminiModel] ?: d.geminiModel,
            aiTextConsent = this[K.aiText] ?: d.aiTextConsent,
            cloudSttEnabled = this[K.cloudStt] ?: d.cloudSttEnabled,
            audioUploadConsent = this[K.audioConsent] ?: d.audioUploadConsent,
            audioRetentionDays = this[K.retention] ?: d.audioRetentionDays,
            seedVersion = this[K.seed] ?: d.seedVersion,
            focusTopics = this[K.focus] ?: d.focusTopics,
            retakeDays = this[K.retakeDays] ?: d.retakeDays,
            retakeVerified = this[K.retakeVerified] ?: d.retakeVerified,
            resultLagDays = this[K.resultLag] ?: d.resultLagDays,
            gradeDeadline = this[K.deadline]?.let { if (it < 0) null else LocalDate.ofEpochDay(it) } ?: d.gradeDeadline,
            examSlots = this[K.slots] ?: d.examSlots,
            registerLeadDays = this[K.lead] ?: d.registerLeadDays,
            examBootstrapVersion = this[K.examBootstrap] ?: d.examBootstrapVersion,
            opicWaivers = this[K.opicWaivers] ?: d.opicWaivers,
            opicWaiverDeclaredOn = this[K.waiverDeclaredOn]?.let { LocalDate.ofEpochDay(it) } ?: d.opicWaiverDeclaredOn,
            planWithWaiver = this[K.planWaiver] ?: d.planWithWaiver,
            sailSharesOpicLimit = this[K.sailShares] ?: d.sailSharesOpicLimit,
            aiDailyCap = this[K.aiCap] ?: d.aiDailyCap,
            aiCallDay = this[K.aiDay] ?: d.aiCallDay,
            aiCallCount = this[K.aiCount] ?: d.aiCallCount,
            studyBlocksEnabled = this[K.blocksOn] ?: d.studyBlocksEnabled,
            studyBlocks = this[K.blocks] ?: d.studyBlocks,
        )
    }
}
