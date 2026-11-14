package com.opicih.coach

import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.StoryEntity
import com.opicih.coach.learning.StoryMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryMapTest {
    private val osaka = StoryEntity("S1", "오사카 여행", "타코야키, 온천", "travel", "해외여행, 휴가")
    private val sofa = StoryEntity("S4", "어린 시절 소파", "소파를 부숨", "home", "가구, 어린 시절")
    private val unused = StoryEntity("S9", "안 쓰는 이야기", "", "", "")

    private fun script(id: Long, topic: String, title: String, stories: String?) =
        ScriptEntity(id = id, topic = topic, title = title, question = "Q", original = "text", storyIds = stories)

    private val scripts = listOf(
        script(1, "Overseas Travel", "Recent Trip", "S1"),
        script(2, "Vacation", "Memorable Vacation", "S1"),
        script(3, "Home & Furniture", "Past Experience", "S4"),
        script(4, "Home & Furniture", "Comparison", "S4"),
        script(5, "Movies", "Favorite Movies", null),
        script(6, "Geography", "Neighbors", "S7"),
    )

    @Test fun groupsScriptsByStoryAndFlagsStoriesSharedAcrossTopics() {
        val r = StoryMap.build(listOf(osaka, sofa, unused), scripts)
        assertEquals(listOf("S1", "S4"), r.uses.map { it.story.id })
        val osakaUse = r.uses.first { it.story.id == "S1" }
        assertEquals(listOf("Overseas Travel", "Vacation"), osakaUse.topics)
        assertTrue(osakaUse.sharedAcrossTopics)
        // Two answers in the same topic reuse the story on purpose; that is not a clash.
        assertFalse(r.uses.first { it.story.id == "S4" }.sharedAcrossTopics)
    }

    @Test fun scriptsWithoutAKnownStoryAreListedSeparately() {
        val r = StoryMap.build(listOf(osaka, sofa), scripts)
        // No ids, and an id with no matching story, both count as "no story".
        assertEquals(listOf(5L, 6L), r.withoutStory.map { it.id })
    }

    @Test fun parsesSeveralIdsAndFindsSiblings() {
        val both = script(7, "Vacation", "Home vacation", "S1, S4")
        assertEquals(listOf("S1", "S4"), StoryMap.storyIdsOf(both))
        val siblings = StoryMap.siblings(both, listOf(osaka, sofa), scripts + both)
        assertEquals(listOf(listOf(1L, 2L), listOf(3L, 4L)), siblings.map { (_, list) -> list.map { it.id } })
    }
}
