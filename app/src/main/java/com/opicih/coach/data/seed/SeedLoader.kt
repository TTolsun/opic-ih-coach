package com.opicih.coach.data.seed

import android.content.Context
import com.opicih.coach.data.db.AppDao
import com.opicih.coach.data.db.ChunkEntity
import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.ErrorPatternEntity
import com.opicih.coach.data.db.QuestionEntity
import com.opicih.coach.data.db.RevisionOrigin
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptRevisionEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.data.db.StoryEntity
import com.opicih.coach.data.prefs.SettingsRepository
import com.opicih.coach.analysis.TextNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class SeedScript(
    val topic: String, val title: String, val type: String, val stories: String = "", val question: String,
    val original: String, val ihTarget: String = "", val short: String = "", val keywords: List<String> = emptyList(), val reasonKo: String = "",
)

@Serializable data class SeedScripts(val source: String = "", val note: String = "", val scripts: List<SeedScript>)
@Serializable data class SeedStory(val id: String, val titleKo: String, val summary: String, val tags: String, val reusableFor: String)
@Serializable data class SeedError(val key: String, val category: String, val example: String, val correction: String)
@Serializable data class SeedChunk(val phrase: String, val meaningKo: String, val example: String, val category: String)
@Serializable data class SeedQuestion(val topic: String, val type: String, val source: String, val text: String, val hintKo: String = "")
@Serializable data class SeedContent(val stories: List<SeedStory>, val errors: List<SeedError>, val chunks: List<SeedChunk>, val questions: List<SeedQuestion>)

/**
 * Loads assets/seed/ once. The user's own scripts become ScriptEntity (source SEED_USER) and the prebuilt IH versions
 * become PENDING revisions, so nothing replaces the original without approval.
 * To publish the repo without personal scripts, delete assets/seed/user_scripts_2026.json (the app still works).
 */
class SeedLoader(private val context: Context, private val dao: AppDao, private val settings: SettingsRepository) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun seedIfNeeded() {
        val s = settings.current()
        if (s.seedVersion >= SEED_VERSION) return
        val today = LocalDate.now().toEpochDay()

        readAsset("seed/content.json")?.let { text ->
            val c = json.decodeFromString(SeedContent.serializer(), text)
            dao.insertStories(c.stories.map { StoryEntity(it.id, it.titleKo, it.summary, it.tags, it.reusableFor) })
            c.errors.forEach { e -> if (dao.error(e.key) == null) dao.upsertError(ErrorPatternEntity(key = e.key, category = e.category, example = e.example, correction = e.correction, count = 1)) }
            if (dao.chunks().isEmpty()) dao.insertChunks(c.chunks.map { ChunkEntity(phrase = it.phrase, meaningKo = it.meaningKo, example = it.example, category = it.category, dueEpochDay = today) })
            if (dao.questions().isEmpty()) dao.insertQuestions(c.questions.map { QuestionEntity(topic = it.topic, text = it.text, type = it.type, hintKo = it.hintKo, source = it.source) })
        }

        if (dao.scriptCount() == 0) {
            readAsset("seed/user_scripts_2026.json")?.let { text ->
                val seed = json.decodeFromString(SeedScripts.serializer(), text)
                seed.scripts.forEach { sc ->
                    val id = dao.insertScript(
                        ScriptEntity(
                            topic = sc.topic, title = sc.title, question = sc.question, questionType = sc.type,
                            original = sc.original, source = ContentSource.SEED_USER.name, storyIds = sc.stories.ifBlank { null },
                            estSeconds = (TextNormalizer.words(sc.original).size / 2.2).toInt().coerceAtLeast(10),
                            dueEpochDay = today,
                        )
                    )
                    val revs = buildList {
                        if (sc.ihTarget.isNotBlank()) add(ScriptRevisionEntity(scriptId = id, version = ScriptVersion.IH_TARGET.name, text = sc.ihTarget, reasonKo = sc.reasonKo, origin = RevisionOrigin.PREBUILT.name))
                        if (sc.short.isNotBlank()) add(ScriptRevisionEntity(scriptId = id, version = ScriptVersion.SHORT.name, text = sc.short, reasonKo = "긴급 상황용 핵심 3문장입니다.", origin = RevisionOrigin.PREBUILT.name))
                        if (sc.keywords.isNotEmpty()) add(ScriptRevisionEntity(scriptId = id, version = ScriptVersion.KEYWORDS.name, text = sc.keywords.joinToString("\n"), reasonKo = "말하는 순서대로 정리한 키워드입니다.", origin = RevisionOrigin.PREBUILT.name))
                    }
                    dao.insertRevisions(revs)
                }
            }
        }
        settings.update { it.copy(seedVersion = SEED_VERSION) }
    }

    private fun readAsset(path: String): String? =
        runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()

    companion object { const val SEED_VERSION = 1 }
}
