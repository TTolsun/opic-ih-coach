package com.opicih.coach

import com.opicih.coach.data.db.ContentSource
import com.opicih.coach.data.db.RevisionOrigin
import com.opicih.coach.data.db.RevisionStatus
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptRevisionEntity
import com.opicih.coach.data.db.ScriptVersion
import com.opicih.coach.data.seed.SeedScript
import com.opicih.coach.data.seed.SeedScripts
import com.opicih.coach.data.seed.planBundledRevisionRefresh
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledRevisionRefreshTest {
    private val bundled = SeedScript(
        topic = "Home & Furniture", title = "Comparison", type = "COMPARISON", question = "What changed?",
        original = "My old sofa was small.", ihTarget = "Back then, my sofa was small.",
        short = "My sofa was small back then.", keywords = listOf("back then", "small sofa"), refreshRevisions = true,
    )
    private val script = ScriptEntity(
        id = 1, topic = bundled.topic, title = bundled.title, question = bundled.question, original = bundled.original,
        source = ContentSource.SEED_USER.name, ihTarget = "An approved study version.", activeVersion = ScriptVersion.IH_TARGET.name,
        masteryScore = 40, srsStep = 2,
    )

    @Test fun refreshReplacesOnlyObsoletePendingBundledVersionsAndCanBeRetried() {
        val pending = revision(1, ScriptVersion.IH_TARGET, "An old proposal.")
        val approved = revision(2, ScriptVersion.IH_TARGET, script.studyText, RevisionStatus.APPROVED)
        val rejected = revision(3, ScriptVersion.SHORT, bundled.short, RevisionStatus.REJECTED)
        val personal = revision(4, ScriptVersion.IH_TARGET, "A personal proposal.").copy(origin = RevisionOrigin.USER.name)
        val currentKeywords = revision(5, ScriptVersion.KEYWORDS, bundled.keywords.joinToString("\n"))
        val existing = listOf(pending, approved, rejected, personal, currentKeywords)

        val refresh = planBundledRevisionRefresh(listOf(bundled), listOf(script), existing)

        assertEquals(listOf(pending), refresh.obsolete)
        assertEquals(listOf(bundled.ihTarget), refresh.additions.map { it.text })
        assertEquals(RevisionStatus.PENDING.name, refresh.additions.single().status)

        val retried = planBundledRevisionRefresh(listOf(bundled), listOf(script), existing - refresh.obsolete.toSet() + refresh.additions)
        assertTrue(retried.obsolete.isEmpty())
        assertTrue(retried.additions.isEmpty())
    }

    @Test fun refreshSkipsEditedImportedAndUnmarkedScripts() {
        val unmarked = bundled.copy(title = "Another script", refreshRevisions = false)
        val scripts = listOf(
            script.copy(original = "Edited by the learner."),
            script.copy(id = 2, question = "What did you do?"),
            script.copy(id = 3, source = ContentSource.USER_IMPORT.name),
            script.copy(id = 4, title = unmarked.title),
        )
        val refresh = planBundledRevisionRefresh(listOf(bundled, unmarked), scripts, emptyList())
        assertTrue(refresh.obsolete.isEmpty())
        assertTrue(refresh.additions.isEmpty())
    }

    @Test fun bundledPilotOffersThreeVersionsForExactlySevenExistingQuestions() {
        val path = "src/main/assets/seed/user_scripts_2026.json"
        val asset = listOf(File(path), File("app", path)).first { it.isFile }
        val scripts = Json.decodeFromString(SeedScripts.serializer(), asset.readText()).scripts
        val pilot = scripts.filter { it.refreshRevisions }

        assertEquals(23, scripts.size)
        assertEquals(mapOf("Home & Furniture" to 5, "Overseas Travel" to 2), pilot.groupingBy { it.topic }.eachCount())
        assertTrue(pilot.all { it.ihTarget.isNotBlank() && it.short.length in 1 until it.ihTarget.length && it.keywords.isNotEmpty() })
        assertTrue(pilot.filter { it.topic == "Overseas Travel" }.all { it.ihTarget.startsWith("Last summer, I went to Osaka, Japan.") })
        val currentSofa = pilot.filter { it.title in setOf("Description of Your House", "Favorite Furniture") }
        assertTrue(currentSofa.all { "I usually play video games there or take a nap." in it.ihTarget })
    }

    private fun revision(id: Long, version: ScriptVersion, text: String, status: RevisionStatus = RevisionStatus.PENDING) =
        ScriptRevisionEntity(id = id, scriptId = script.id, version = version.name, text = text, status = status.name)
}
