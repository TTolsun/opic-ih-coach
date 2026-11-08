package com.opicih.coach

import android.app.Application
import android.content.Context
import com.opicih.coach.ai.AiCoach
import com.opicih.coach.ai.GeminiAiCoach
import com.opicih.coach.ai.LocalCoach
import com.opicih.coach.ai.GeminiTranscriber
import com.opicih.coach.ai.Transcriber
import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.alarm.Notifications
import com.opicih.coach.alarm.ReminderWorker
import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.db.AppDatabase
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.prefs.SettingsRepository
import com.opicih.coach.data.repo.ExamRepository
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.data.repo.ScriptRepository
import com.opicih.coach.data.secure.ApiKeyStore
import com.opicih.coach.data.seed.SeedLoader
import com.opicih.coach.importer.ImportService
import com.opicih.coach.learning.ListenCycleBuilder
import com.opicih.coach.playback.ListenPlayer
import com.opicih.coach.playback.ListenPrefs
import com.opicih.coach.speech.AudioPlayer
import com.opicih.coach.speech.ListenTts
import com.opicih.coach.speech.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual DI: one place that builds every dependency (no Hilt, see HANDOFF 2). */
class AppContainer(context: Context) {
    private val ctx = context.applicationContext
    val db = AppDatabase.create(ctx)
    val dao = db.dao()
    val settings = SettingsRepository(ctx)
    val studyTime = com.opicih.coach.data.prefs.StudyTimeTracker(ctx)
    val keys = ApiKeyStore(ctx)
    val tts by lazy { TtsManager(ctx) }
    val player = AudioPlayer()

    /** 스크립트 듣기: 설정에서 읽은 값을 단계마다 새로 적용한다. */
    private suspend fun listenPrefs(): ListenPrefs = settings.current().let {
        ListenPrefs(it.listenQuestionVoice, it.listenAnswerVoice, it.listenGapMs.toLong(), it.listenRate)
    }
    val listenTts by lazy { ListenTts(ctx, ::listenPrefs) }
    val listen by lazy {
        ListenPlayer(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            speaker = listenTts,
            ids = { dao.scripts().map { it.id } },
            cycle = { id ->
                val s = dao.script(id)
                s?.let { sc ->
                    val q = sc.question.trim()
                    val hint = dao.questions().firstOrNull { it.text.trim().equals(q, ignoreCase = true) }?.hintKo?.takeIf { it.isNotBlank() }
                    ListenCycleBuilder.forScript(sc, hint, segments.forPlayback(sc))
                }
            },
            prefs = ::listenPrefs,
        )
    }
    val scripts = ScriptRepository(dao)
    val segments = com.opicih.coach.data.repo.SegmentRepository(db)
    val glossary = com.opicih.coach.data.repo.GlossaryRepository(ctx, dao)
    val frames = com.opicih.coach.data.repo.FrameRepository(dao)
    val practice = PracticeRepository(db)
    val importer = ImportService(ctx)
    val backup = BackupManager(ctx, db)
    val exams = ExamRepository(db, settings)
    val seed = SeedLoader(ctx, dao, settings)
    val localCoach = LocalCoach()

    private var cachedAi: Pair<String, AiCoach>? = null

    /** Real AI only when the user enabled it, consented to sending text, and stored a Gemini key. */
    fun realAi(s: Settings): AiCoach? {
        if (!s.aiEnabled || !s.aiTextConsent) return null
        val key = keys.get(ApiKeyStore.Provider.GEMINI) ?: return null
        val cacheKey = key.takeLast(6) + s.geminiModel + s.currentGrade.name + s.targetGrade.name
        cachedAi?.let { if (it.first == cacheKey) return it.second }
        return GeminiAiCoach(key, s.geminiModel, s.profile).also { cachedAi = cacheKey to it }
    }

    fun coach(s: Settings): AiCoach = realAi(s) ?: localCoach

    fun transcriber(s: Settings): Transcriber? {
        if (!s.cloudSttEnabled || !s.audioUploadConsent) return null
        val key = keys.get(ApiKeyStore.Provider.GEMINI) ?: return null
        return GeminiTranscriber(key, s.geminiModel)
    }
}

class App : Application() {
    lateinit var container: AppContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        appScope.launch {
            runCatching { container.seed.seedIfNeeded() }
            runCatching { container.exams.bootstrapIfNeeded() }
            // 내장 한국어 번역을 가져온다(번역이 없거나 낡은 스크립트 중 해시가 맞는 것만).
            runCatching {
                this@App.assets.open("seed/segments_ko.json").bufferedReader(Charsets.UTF_8).use { container.segments.importBundled(it.readText()) }
            }
            val s = container.settings.current()
            com.opicih.coach.alarm.AlarmCoordinator.applyAll(this@App, s)
            runCatching { container.backup.purgeAudio(s.audioRetentionDays) }
        }
        // Voice and pitch follow the settings (also re-applied once the engine becomes ready).
        appScope.launch {
            container.settings.flow
                .map { it.ttsVoice to it.ttsPitch }
                .distinctUntilChanged()
                .collect { (voice, pitch) -> container.tts.configure(voice, pitch) }
        }
        // Exam reminders follow the schedule: any registration change re-plans them.
        appScope.launch {
            container.exams.flow.collect { regs ->
                runCatching { com.opicih.coach.alarm.ExamReminders.sync(this@App, regs, com.opicih.coach.data.repo.ExamRepository.nowKst()) }
            }
        }
    }
}
