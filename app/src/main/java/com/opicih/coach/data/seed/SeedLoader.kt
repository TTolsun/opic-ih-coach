package com.opicih.coach.data.seed

import android.content.Context
import androidx.room.withTransaction
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.data.db.AppDatabase
import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ErrorPatternEntity
import com.opicih.coach.data.db.QuestionEntity
import com.opicih.coach.data.db.RevisionOrigin
import com.opicih.coach.data.db.RevisionStatus
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptRevisionEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.data.db.StoryEntity
import com.opicih.coach.data.prefs.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class SeedScript(
    val topic: String, val title: String, val type: String, val stories: String = "", val question: String,
    val original: String, val ihTarget: String = "", val short: String = "", val keywords: List<String> = emptyList(), val reasonKo: String = "",
    val refreshRevisions: Boolean = false,
)

@Serializable data class SeedScripts(val source: String = "", val note: String = "", val scripts: List<SeedScript>)
@Serializable data class SeedStory(val id: String, val titleKo: String, val summary: String, val tags: String, val reusableFor: String)
@Serializable data class SeedError(val key: String, val category: String, val example: String, val correction: String)
@Serializable data class SeedChunk(val phrase: String, val meaningKo: String, val example: String, val category: String)
@Serializable data class SeedQuestion(val topic: String, val type: String, val source: String, val text: String, val hintKo: String = "")
@Serializable data class SeedContent(val stories: List<SeedStory>, val errors: List<SeedError>, val chunks: List<SeedChunk>, val questions: List<SeedQuestion>)

private fun SeedScript.revisionsFor(scriptId: Long) = buildList {
    if (ihTarget.isNotBlank()) add(ScriptRevisionEntity(scriptId = scriptId, version = ScriptVersion.IH_TARGET.name, text = ihTarget, reasonKo = reasonKo))
    if (short.isNotBlank()) add(ScriptRevisionEntity(scriptId = scriptId, version = ScriptVersion.SHORT.name, text = short, reasonKo = "긴급 상황용 핵심 3문장입니다."))
    if (keywords.isNotEmpty()) add(ScriptRevisionEntity(scriptId = scriptId, version = ScriptVersion.KEYWORDS.name, text = keywords.joinToString("\n"), reasonKo = "말하는 순서대로 정리한 키워드입니다."))
}

internal data class BundledRevisionRefresh(
    val obsolete: List<ScriptRevisionEntity>,
    val additions: List<ScriptRevisionEntity>,
)

internal fun planBundledRevisionRefresh(
    seed: List<SeedScript>,
    scripts: List<ScriptEntity>,
    revisions: List<ScriptRevisionEntity>,
): BundledRevisionRefresh {
    val refreshable = seed.filter { it.refreshRevisions }
    val byScript = revisions.filter { it.origin == RevisionOrigin.PREBUILT.name }.groupBy { it.scriptId }
    val obsolete = mutableListOf<ScriptRevisionEntity>()
    val additions = mutableListOf<ScriptRevisionEntity>()
    for (script in scripts.filter { it.source == ContentSource.SEED_USER.name }) {
        val bundled = refreshable.firstOrNull {
            it.topic == script.topic && it.title == script.title && it.question == script.question && it.original == script.original
        } ?: continue
        val existing = byScript[script.id].orEmpty()
        for (replacement in bundled.revisionsFor(script.id)) {
            val sameVersion = existing.filter { it.version == replacement.version }
            obsolete += sameVersion.filter { it.status == RevisionStatus.PENDING.name && it.text != replacement.text }
            if (sameVersion.none { it.text == replacement.text }) additions += replacement
        }
    }
    return BundledRevisionRefresh(obsolete, additions)
}

/**
 * Seeds original content once and refreshes selected bundled proposals after upgrades or restores.
 * The original and approved study versions stay in ScriptEntity, separate from pending revisions.
 * To publish the repo without personal scripts, delete assets/seed/user_scripts_2026.json (the app still works).
 */
class SeedLoader(private val context: Context, private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao = db.dao()
    private val json = Json { ignoreUnknownKeys = true }
    private val seedLock = Mutex()

    suspend fun seedIfNeeded() = seedLock.withLock {
        val initialSeed = settings.current().seedVersion < SEED_VERSION
        val today = LocalDate.now().toEpochDay()
        val content = if (initialSeed) readAsset("seed/content.json")?.let {
            json.decodeFromString(SeedContent.serializer(), it)
        } else null
        val seed = readAsset("seed/user_scripts_2026.json")?.let {
            json.decodeFromString(SeedScripts.serializer(), it).scripts
        }.orEmpty()
        if (!initialSeed && seed.none { it.refreshRevisions }) return@withLock

        db.withTransaction {
            if (content != null) seedReferenceContent(content, today)
            if (initialSeed && dao.scriptCount() == 0) {
                insertScripts(seed, today)
            } else if (seed.any { it.refreshRevisions }) {
                val refresh = planBundledRevisionRefresh(seed, dao.scripts(), dao.revisions())
                if (refresh.obsolete.isNotEmpty()) dao.deleteRevisions(refresh.obsolete)
                if (refresh.additions.isNotEmpty()) dao.insertRevisions(refresh.additions)
            }
        }
        if (initialSeed) settings.update { it.copy(seedVersion = SEED_VERSION) }
    }

    private suspend fun seedReferenceContent(c: SeedContent, today: Long) {
        dao.insertStories(c.stories.map { StoryEntity(it.id, it.titleKo, it.summary, it.tags, it.reusableFor) })
        c.errors.forEach { e -> if (dao.error(e.key) == null) dao.upsertError(ErrorPatternEntity(key = e.key, category = e.category, example = e.example, correction = e.correction, count = 1)) }
        if (dao.chunks().isEmpty()) dao.insertChunks(c.chunks.map { ChunkEntity(phrase = it.phrase, meaningKo = it.meaningKo, example = it.example, category = it.category, dueEpochDay = today) })
        if (dao.questions().isEmpty()) dao.insertQuestions(c.questions.map { QuestionEntity(topic = it.topic, text = it.text, type = it.type, hintKo = it.hintKo, source = it.source) })
    }

    private suspend fun insertScripts(seed: List<SeedScript>, today: Long) {
        for (sc in seed) {
            val id = dao.insertScript(
                ScriptEntity(
                    topic = sc.topic, title = sc.title, question = sc.question, questionType = sc.type,
                    original = sc.original, source = ContentSource.SEED_USER.name, storyIds = sc.stories.ifBlank { null },
                    estSeconds = (TextNormalizer.words(sc.original).size / 2.2).toInt().coerceAtLeast(10),
                    dueEpochDay = today,
                )
            )
            dao.insertRevisions(sc.revisionsFor(id))
        }
    }

    private fun readAsset(path: String): String? =
        runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()

    companion object { const val SEED_VERSION = 1 }
}
