package com.opicih.coach

import android.app.Application
import android.content.Context
import com.opicih.coach.ai.AiCoach
import com.opicih.coach.ai.ClaudeAiCoach
import com.opicih.coach.ai.GeminiAiCoach
import com.opicih.coach.data.prefs.AiProvider
import com.opicih.coach.ai.LocalCoach
import com.opicih.coach.ai.OpenAiTranscriber
import com.opicih.coach.ai.Transcriber
import com.opicih.coach.alarm.AlarmScheduler
import com.opicih.coach.alarm.Notifications
import com.opicih.coach.alarm.ReminderWorker
import com.opicih.coach.data.backup.BackupManager
import com.opicih.coach.data.db.AppDatabase
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.prefs.SettingsRepository
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.data.repo.ScriptRepository
import com.opicih.coach.data.secure.ApiKeyStore
import com.opicih.coach.data.seed.SeedLoader
import com.opicih.coach.importer.ImportService
import com.opicih.coach.speech.AudioPlayer
import com.opicih.coach.speech.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI: one place that builds every dependency (no Hilt, see HANDOFF 2). */
class AppContainer(context: Context) {
    private val ctx = context.applicationContext
    val db = AppDatabase.create(ctx)
    val dao = db.dao()
    val settings = SettingsRepository(ctx)
    val keys = ApiKeyStore(ctx)
    val tts by lazy { TtsManager(ctx) }
    val player = AudioPlayer()
    val scripts = ScriptRepository(dao)
    val practice = PracticeRepository(dao)
    val importer = ImportService(ctx)
    val backup = BackupManager(ctx, db)
    val seed = SeedLoader(ctx, dao, settings)
    val localCoach = LocalCoach()

    private var cachedAi: Pair<String, AiCoach>? = null

    /** Real AI only when the user enabled it, consented to sending text, and stored a key for the chosen provider. */
    fun realAi(s: Settings): AiCoach? {
        if (!s.aiEnabled || !s.aiTextConsent) return null
        val (provider, model) = when (s.aiProvider) {
            AiProvider.GEMINI -> ApiKeyStore.Provider.GEMINI to s.geminiModel
            AiProvider.CLAUDE -> ApiKeyStore.Provider.ANTHROPIC to s.aiModel
        }
        val key = keys.get(provider) ?: return null
        val cacheKey = s.aiProvider.name + key.takeLast(6) + model
        cachedAi?.let { if (it.first == cacheKey) return it.second }
        val coach: AiCoach = when (s.aiProvider) {
            AiProvider.GEMINI -> GeminiAiCoach(key, model)
            AiProvider.CLAUDE -> ClaudeAiCoach(key, model)
        }
        return coach.also { cachedAi = cacheKey to it }
    }

    fun coach(s: Settings): AiCoach = realAi(s) ?: localCoach

    fun transcriber(s: Settings): Transcriber? {
        if (!s.cloudSttEnabled || !s.audioUploadConsent) return null
        val key = keys.get(ApiKeyStore.Provider.OPENAI_STT) ?: return null
        return OpenAiTranscriber(key)
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
            val s = container.settings.current()
            if (s.alarmEnabled) AlarmScheduler.schedule(this@App, s.alarmHour, s.alarmMinute, s.useExactAlarm)
            ReminderWorker.ensure(this@App, s.eveningReminder)
            runCatching { container.backup.purgeAudio(s.audioRetentionDays) }
        }
    }
}
