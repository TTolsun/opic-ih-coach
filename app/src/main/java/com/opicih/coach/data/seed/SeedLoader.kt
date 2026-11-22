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
import com.opicih.coach.learning.BundledItem
import com.opicih.coach.learning.BundledSegments
import com.opicih.coach.learning.HotelRoleplay
import com.opicih.coach.learning.QuestionSource
import com.opicih.coach.learning.StoryMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val reuseQuestions: List<String> = emptyList(),
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

/**
 * Story links for bundled scripts that were seeded before their story existed. Only scripts never linked
 * (storyIds == null) are filled; matching is by topic and title, which do not change for bundled scripts.
 */
internal fun planStoryLinks(seed: List<SeedScript>, scripts: List<ScriptEntity>): List<ScriptEntity> =
    scripts.filter { it.source == ContentSource.SEED_USER.name && it.storyIds == null }.mapNotNull { s ->
        seed.firstOrNull { it.topic == s.topic && it.title == s.title }?.stories?.takeIf { it.isNotBlank() }?.let { s.copy(storyIds = it) }
    }

internal fun curatedReuseQuestions(script: ScriptEntity, seed: List<SeedScript>, stories: List<StoryEntity>, sceneAnswer: String? = null): List<String> {
    if (script.source != ContentSource.SEED_USER.name) return emptyList()
    val bundled = seed.firstOrNull { it.topic == script.topic && it.title == script.title } ?: return emptyList()
    if (bundled.stories !in StoryMap.storyIdsOf(script) || stories.none { it.id == bundled.stories }) return emptyList()
    val studyText = TextNormalizer.normalize(script.studyText)
    val bundledVersions = listOfNotNull(bundled.original, bundled.ihTarget, bundled.short, sceneAnswer)
    if (studyText.isEmpty() || bundledVersions.none { TextNormalizer.normalize(it) == studyText }) return emptyList()
    return bundled.reuseQuestions
}

/** Install only on the matching shipped question. The presence of the scene also survives backup/restore
 * and prevents subsequent launches from resetting the learner's version choice or edits. */
internal fun ScriptEntity.installScene(item: BundledItem): ScriptEntity {
    if (sceneText != null || title != item.title || question != item.question ||
        source !in setOf(ContentSource.SEED_USER.name, ContentSource.AI_SAMPLE.name)) return this
    val text = item.answerText()
    return copy(sceneText = text, sceneMeaning = item.sentences.joinToString(" ") { it.ko },
        sceneKeywords = item.keywords.joinToString("\n"), activeVersion = ScriptVersion.SCENE.name,
        estSeconds = (TextNormalizer.words(text).size / 2.2).toInt().coerceAtLeast(10))
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
 * The original scripts, scene scripts, and roleplay templates are optional seed assets.
 */
class SeedLoader(private val context: Context, private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao = db.dao()
    private val json = Json { ignoreUnknownKeys = true }
    private val seedLock = Mutex()
    private val bundledScripts by lazy {
        readAsset("seed/user_scripts_2026.json")?.let { json.decodeFromString(SeedScripts.serializer(), it).scripts }.orEmpty()
    }

    private val scenes by lazy {
        readAsset("seed/scene_scripts.json")?.let { text ->
            BundledSegments.parse(text).items.associateBy { it.question }
        }.orEmpty()
    }

    suspend fun seedIfNeeded() = seedLock.withLock {
        seedOriginal()
        addLaterContent()
        db.withTransaction {
            val installed = dao.scripts().mapNotNull { script ->
                val scene = scenes[script.question] ?: return@mapNotNull null
                script.installScene(scene).takeUnless { it === script }
            }
            if (installed.isNotEmpty()) {
                dao.updateScripts(installed)
                val ids = installed.map { it.id }.toSet()
                val old = dao.revisions().filter { it.scriptId in ids && it.status == RevisionStatus.PENDING.name &&
                    it.origin == RevisionOrigin.PREBUILT.name }
                if (old.isNotEmpty()) dao.deleteRevisions(old)
            }
        }
    }

    suspend fun hotelQuestions() = withContext(Dispatchers.IO) {
        val bank = dao.questions().filter { it.source == "AI_GENERATED" }
            .map { QuestionSource(it.topic, it.text, it.type, it.source) }
        HotelRoleplay.questions(bank).ifEmpty {
            val bundled = readAsset("seed/content.json")?.let {
                json.decodeFromString(SeedContent.serializer(), it)
            }
            HotelRoleplay.questions(bundled?.questions.orEmpty().map { QuestionSource(it.topic, it.text, it.type, it.source) })
        }
    }

    suspend fun reuseQuestions(script: ScriptEntity): List<String> = withContext(Dispatchers.IO) {
        val stories = dao.stories()
        val curated = curatedReuseQuestions(script, bundledScripts, stories, scenes[script.question]?.answerText())
        StoryMap.reuseQuestions(script, stories, dao.scripts(), curated)
    }

    private suspend fun seedOriginal() {
        val initialSeed = settings.current().seedVersion < SEED_VERSION
        val today = LocalDate.now().toEpochDay()
        val content = if (initialSeed) readAsset("seed/content.json")?.let {
            json.decodeFromString(SeedContent.serializer(), it)
        } else null
        val seed = readAsset("seed/user_scripts_2026.json")?.let {
            json.decodeFromString(SeedScripts.serializer(), it).scripts
        }.orEmpty()
        if (!initialSeed && seed.none { it.refreshRevisions }) return

        db.withTransaction {
            if (content != null) seedReferenceContent(content, today)
            if (initialSeed && dao.scriptCount() == 0) {
                insertScripts(seed, today)
            } else if (seed.any { it.refreshRevisions }) {
                val refresh = planBundledRevisionRefresh(seed.filterNot { it.question in scenes }, dao.scripts(), dao.revisions())
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
            if (sc.question !in scenes) dao.insertRevisions(sc.revisionsFor(id))
        }
    }

    /**
     * Content added after the first seed: stories that were missing (S8, S9), story links for bundled scripts that
     * never had one, and the roleplay templates. Links fill only scripts whose storyIds is null; a script the learner
     * set to "no story" stores "" and is left alone. Templates are inserted once, so a deleted template stays deleted.
     */
    private suspend fun addLaterContent() {
        val content = readAsset("seed/content.json")?.let { json.decodeFromString(SeedContent.serializer(), it) }
        val seed = readAsset("seed/user_scripts_2026.json")?.let { json.decodeFromString(SeedScripts.serializer(), it).scripts }.orEmpty()
        db.withTransaction {
            content?.let { c ->
                val have = dao.stories().map { it.id }.toSet()
                val missing = c.stories.filter { it.id !in have }
                if (missing.isNotEmpty()) dao.insertStories(missing.map { StoryEntity(it.id, it.titleKo, it.summary, it.tags, it.reusableFor) })
            }
            planStoryLinks(seed, dao.scripts()).forEach { dao.updateScript(it) }
        }
        val templateVersion = settings.current().templateSeedVersion
        if (templateVersion < 1) {
            val templates = readAsset("seed/roleplay_templates.json")?.let {
                json.decodeFromString(SeedScripts.serializer(), it).scripts
            }.orEmpty()
            val today = LocalDate.now().toEpochDay()
            db.withTransaction {
                val existing = dao.scripts().map { it.topic to it.title }.toSet()
                templates.filter { (it.topic to it.title) !in existing }.forEach { t ->
                    dao.insertScript(
                        ScriptEntity(
                            topic = t.topic, title = t.title, question = t.question, questionType = t.type, original = t.original,
                            keywords = t.keywords.joinToString("\n").ifBlank { null }, source = ContentSource.AI_SAMPLE.name,
                            storyIds = t.stories.ifBlank { null },
                            estSeconds = (TextNormalizer.words(t.original).size / 2.2).toInt().coerceAtLeast(10), dueEpochDay = today,
                        )
                    )
                }
            }
        }
        if (templateVersion < TEMPLATE_VERSION) {
            val appointments = readAsset("seed/appointments.json")?.let {
                json.decodeFromString<List<AppointmentSeed>>(it)
            }.orEmpty()
            val today = LocalDate.now().toEpochDay()
            db.withTransaction {
                val scripts = dao.scripts()
                for (item in appointments) {
                    val script = scripts.firstOrNull(item::matches) ?: item.newScript(today).let {
                        it.copy(id = dao.insertScript(it))
                    }
                    val rows = item.translationsFor(script, dao.segmentsFor(script.id))
                    if (rows.isNotEmpty()) {
                        dao.deleteSegmentsFor(script.id)
                        dao.insertSegments(rows)
                    }
                }
            }
            settings.update { it.copy(templateSeedVersion = TEMPLATE_VERSION) }
        }
    }

    private fun readAsset(path: String): String? =
        runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()

    companion object {
        const val SEED_VERSION = 1
        /** 1: hotel roleplay. 2: appointment scripts with aligned translations. */
        const val TEMPLATE_VERSION = 2
    }
}
