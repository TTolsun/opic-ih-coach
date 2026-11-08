package com.opicih.coach.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
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
    /** 최근 공식 결과 등급. AI 프롬프트와 개선안 난이도의 출발점입니다. */
    val currentGrade: LearnerGrade = LearnerGrade.IM1,
    /** 다음 시험의 목표 등급. 최종 목표(IH)와 별개로 개선안 길이와 준비도 기준을 정합니다. */
    val targetGrade: LearnerGrade = LearnerGrade.IM2,
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
    /** 스크립트 듣기 플레이어(v5): 질문과 답변에 쓰는 영어 음성 이름. 빈 값 = 자동으로 서로 다른 음성을 고름. */
    val listenQuestionVoice: String = "",
    val listenAnswerVoice: String = "",
    /** 단계 사이 간격(ms)과 재생 속도. */
    val listenGapMs: Int = 800,
    val listenRate: Float = 1.0f,
) {
    fun aiCallsToday(todayEpochDay: Long): Int = if (aiCallDay == todayEpochDay) aiCallCount else 0

    val profile: LearnerProfile get() = LearnerProfile(current = currentGrade, target = targetGrade)

    fun slotDates(): List<LocalDate> = examSlots.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()

    companion object {
        val DEFAULT_EXAM: LocalDate = LocalDate.of(2026, 11, 11)
        val DEFAULT_SURVEY = setOf("Overseas Travel", "Domestic Travel", "Movies", "Vacation", "Home & Furniture", "Park & Music")
    }
}

/**
 * One persisted setting: the DataStore [key], how to read the value from / apply it to a [Settings], and how it is
 * encoded as the stored type [S]. Adding a setting means adding a `Settings` field plus ONE entry in [SETTINGS_FIELDS]
 * (SettingsFieldsTest fails if a field has no entry).
 */
internal class PrefField<T, S : Any>(
    val property: String,
    val key: Preferences.Key<S>,
    private val get: (Settings) -> T,
    private val set: (Settings, T) -> Settings,
    private val encode: (T) -> S,
    private val decode: (S) -> T,
) {
    /** Applies the stored value to [base]; an absent key leaves the data-class default untouched. */
    fun readInto(prefs: Preferences, base: Settings): Settings =
        prefs[key]?.let { set(base, decode(it)) } ?: base

    fun write(prefs: MutablePreferences, s: Settings) {
        prefs[key] = encode(get(s))
    }

    fun changed(old: Settings, new: Settings): Boolean = get(old) != get(new)
}

private fun <T : Any> plain(
    property: String,
    key: Preferences.Key<T>,
    get: (Settings) -> T,
    set: (Settings, T) -> Settings,
) = PrefField(property, key, get, set, { it }, { it })

private fun date(
    property: String,
    key: Preferences.Key<Long>,
    get: (Settings) -> LocalDate,
    set: (Settings, LocalDate) -> Settings,
) = PrefField(property, key, get, set, { it.toEpochDay() }, { LocalDate.ofEpochDay(it) })

/** The single source of truth for persistence: key names and stored types must never change (existing installs). */
internal val SETTINGS_FIELDS: List<PrefField<*, *>> = listOf(
    date("examDate", longPreferencesKey("exam_epoch_day"), { it.examDate }) { s, v -> s.copy(examDate = v) },
    PrefField("studyLevel", stringPreferencesKey("study_level"), { it.studyLevel }, { s, v -> s.copy(studyLevel = v) }, { it.name }, { StudyLevel.of(it) }),
    plain("alarmEnabled", booleanPreferencesKey("alarm_on"), { it.alarmEnabled }) { s, v -> s.copy(alarmEnabled = v) },
    plain("alarmHour", intPreferencesKey("alarm_h"), { it.alarmHour }) { s, v -> s.copy(alarmHour = v) },
    plain("alarmMinute", intPreferencesKey("alarm_m"), { it.alarmMinute }) { s, v -> s.copy(alarmMinute = v) },
    plain("alarmSound", booleanPreferencesKey("alarm_sound"), { it.alarmSound }) { s, v -> s.copy(alarmSound = v) },
    plain("alarmVibrate", booleanPreferencesKey("alarm_vibrate"), { it.alarmVibrate }) { s, v -> s.copy(alarmVibrate = v) },
    plain("useExactAlarm", booleanPreferencesKey("alarm_exact"), { it.useExactAlarm }) { s, v -> s.copy(useExactAlarm = v) },
    plain("eveningReminder", booleanPreferencesKey("evening_reminder"), { it.eveningReminder }) { s, v -> s.copy(eveningReminder = v) },
    plain("surveyTopics", stringSetPreferencesKey("survey"), { it.surveyTopics }) { s, v -> s.copy(surveyTopics = v) },
    plain("selfLevel", intPreferencesKey("self_level"), { it.selfLevel }) { s, v -> s.copy(selfLevel = v) },
    PrefField("currentGrade", stringPreferencesKey("current_grade"), { it.currentGrade }, { s, v -> s.copy(currentGrade = v) }, { it.name }, { LearnerGrade.of(it, LearnerGrade.IM1) }),
    PrefField("targetGrade", stringPreferencesKey("target_grade"), { it.targetGrade }, { s, v -> s.copy(targetGrade = v) }, { it.name }, { LearnerGrade.of(it, LearnerGrade.IM2) }),
    PrefField("captureMode", stringPreferencesKey("capture_mode"), { it.captureMode }, { s, v -> s.copy(captureMode = v) }, { it.name }, { CaptureMode.of(it) }),
    plain("ttsRate", floatPreferencesKey("tts_rate"), { it.ttsRate }) { s, v -> s.copy(ttsRate = v) },
    plain("ttsVoice", stringPreferencesKey("tts_voice"), { it.ttsVoice }) { s, v -> s.copy(ttsVoice = v) },
    plain("ttsPitch", floatPreferencesKey("tts_pitch"), { it.ttsPitch }) { s, v -> s.copy(ttsPitch = v) },
    plain("aiEnabled", booleanPreferencesKey("ai_on"), { it.aiEnabled }) { s, v -> s.copy(aiEnabled = v) },
    plain("geminiModel", stringPreferencesKey("gemini_model"), { it.geminiModel }) { s, v -> s.copy(geminiModel = v) },
    plain("aiTextConsent", booleanPreferencesKey("ai_text_consent"), { it.aiTextConsent }) { s, v -> s.copy(aiTextConsent = v) },
    plain("cloudSttEnabled", booleanPreferencesKey("cloud_stt"), { it.cloudSttEnabled }) { s, v -> s.copy(cloudSttEnabled = v) },
    plain("audioUploadConsent", booleanPreferencesKey("audio_consent"), { it.audioUploadConsent }) { s, v -> s.copy(audioUploadConsent = v) },
    plain("audioRetentionDays", intPreferencesKey("audio_retention"), { it.audioRetentionDays }) { s, v -> s.copy(audioRetentionDays = v) },
    plain("seedVersion", intPreferencesKey("seed_version"), { it.seedVersion }) { s, v -> s.copy(seedVersion = v) },
    plain("focusTopics", stringSetPreferencesKey("focus_topics"), { it.focusTopics }) { s, v -> s.copy(focusTopics = v) },
    plain("retakeDays", intPreferencesKey("retake_days"), { it.retakeDays }) { s, v -> s.copy(retakeDays = v) },
    plain("retakeVerified", booleanPreferencesKey("retake_verified"), { it.retakeVerified }) { s, v -> s.copy(retakeVerified = v) },
    plain("resultLagDays", intPreferencesKey("result_lag_days"), { it.resultLagDays }) { s, v -> s.copy(resultLagDays = v) },
    // Stored as epoch day; "no deadline" (cleared in Settings) is written as -1 and read back as null.
    // The old reader fell back to the default here, so a cleared deadline came back after a restart.
    PrefField<LocalDate?, Long>(
        "gradeDeadline", longPreferencesKey("grade_deadline"),
        { it.gradeDeadline }, { s, v -> s.copy(gradeDeadline = v) },
        { it?.toEpochDay() ?: -1L }, { if (it < 0) null else LocalDate.ofEpochDay(it) },
    ),
    plain("examSlots", stringSetPreferencesKey("exam_slots"), { it.examSlots }) { s, v -> s.copy(examSlots = v) },
    plain("registerLeadDays", intPreferencesKey("register_lead_days"), { it.registerLeadDays }) { s, v -> s.copy(registerLeadDays = v) },
    plain("examBootstrapVersion", intPreferencesKey("exam_bootstrap_version"), { it.examBootstrapVersion }) { s, v -> s.copy(examBootstrapVersion = v) },
    plain("opicWaivers", intPreferencesKey("opic_waivers"), { it.opicWaivers }) { s, v -> s.copy(opicWaivers = v) },
    date("opicWaiverDeclaredOn", longPreferencesKey("opic_waiver_declared_on"), { it.opicWaiverDeclaredOn }) { s, v -> s.copy(opicWaiverDeclaredOn = v) },
    plain("planWithWaiver", booleanPreferencesKey("plan_with_waiver"), { it.planWithWaiver }) { s, v -> s.copy(planWithWaiver = v) },
    plain("sailSharesOpicLimit", booleanPreferencesKey("sail_shares_opic_limit"), { it.sailSharesOpicLimit }) { s, v -> s.copy(sailSharesOpicLimit = v) },
    plain("aiDailyCap", intPreferencesKey("ai_daily_cap"), { it.aiDailyCap }) { s, v -> s.copy(aiDailyCap = v) },
    plain("aiCallDay", longPreferencesKey("ai_call_day"), { it.aiCallDay }) { s, v -> s.copy(aiCallDay = v) },
    plain("aiCallCount", intPreferencesKey("ai_call_count"), { it.aiCallCount }) { s, v -> s.copy(aiCallCount = v) },
    plain("studyBlocksEnabled", booleanPreferencesKey("study_blocks_on"), { it.studyBlocksEnabled }) { s, v -> s.copy(studyBlocksEnabled = v) },
    plain("studyBlocks", stringPreferencesKey("study_blocks"), { it.studyBlocks }) { s, v -> s.copy(studyBlocks = v) },
    plain("listenQuestionVoice", stringPreferencesKey("listen_q_voice"), { it.listenQuestionVoice }) { s, v -> s.copy(listenQuestionVoice = v) },
    plain("listenAnswerVoice", stringPreferencesKey("listen_a_voice"), { it.listenAnswerVoice }) { s, v -> s.copy(listenAnswerVoice = v) },
    plain("listenGapMs", intPreferencesKey("listen_gap_ms"), { it.listenGapMs }) { s, v -> s.copy(listenGapMs = v) },
    plain("listenRate", floatPreferencesKey("listen_rate"), { it.listenRate }) { s, v -> s.copy(listenRate = v) },
)

internal fun Preferences.toSettings(): Settings = SETTINGS_FIELDS.fold(Settings()) { acc, f -> f.readInto(this, acc) }

/** Writes only the fields that differ between [old] and [new]. */
internal fun MutablePreferences.writeChanges(old: Settings, new: Settings) {
    SETTINGS_FIELDS.forEach { if (it.changed(old, new)) it.write(this, new) }
}

class SettingsRepository(private val context: Context) {
    val flow: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = flow.first()

    suspend fun update(block: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val old = p.toSettings()
            p.writeChanges(old, block(old))
        }
    }
}
