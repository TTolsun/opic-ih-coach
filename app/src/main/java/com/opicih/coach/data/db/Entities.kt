package com.opicih.coach.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Memorization stage of one script. */
enum class MasteryState(val label: String) {
    NEW("처음 학습"),
    LEARNING("암기 진행"),
    NO_SCRIPT_OK("스크립트 없이 가능"),
    VARIATION_OK("응용 가능"),
    NEEDS_REVIEW("재복습 필요"),
}

enum class QuestionType(val label: String) {
    SELF_INTRO("자기소개"),
    DESCRIPTION("묘사"),
    ROUTINE("루틴/습관"),
    PAST_EXPERIENCE("과거 경험"),
    COMPARISON("과거·현재 비교"),
    ROLEPLAY("롤플레이"),
    ISSUE("이슈/사회"),
    UNEXPECTED("돌발"),
    OTHER("기타");

    companion object {
        fun of(name: String?): QuestionType = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

enum class ScriptVersion(val label: String) {
    ORIGINAL("Original"), NATURAL("Natural"), IH_TARGET("IH Target"), SHORT("Short"), KEYWORDS("Keywords");

    companion object {
        fun of(name: String?): ScriptVersion = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/** Where content came from. The UI always shows this so sample/AI data is never mistaken for the user's. */
enum class ContentSource(val label: String) {
    SEED_USER("내 스크립트 (Drive)"),
    USER_IMPORT("가져오기/직접 작성"),
    AI_SAMPLE("AI 생성 예제");

    companion object {
        fun of(name: String?): ContentSource = entries.firstOrNull { it.name == name } ?: USER_IMPORT
    }
}

@Entity(tableName = "scripts", indices = [Index("topic"), Index("dueEpochDay")])
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val title: String,
    val question: String,
    val questionType: String = QuestionType.OTHER.name,
    /** Imported text. Never overwritten automatically. */
    val original: String,
    val natural: String? = null,
    val ihTarget: String? = null,
    val shortVersion: String? = null,
    /** Newline separated. */
    val keywords: String? = null,
    /** Which version the user approved as the study text. */
    val activeVersion: String = ScriptVersion.ORIGINAL.name,
    val koreanMeaning: String? = null,
    val difficulty: Int = 3,
    val estSeconds: Int = 60,
    val source: String = ContentSource.USER_IMPORT.name,
    val masteryState: String = MasteryState.NEW.name,
    /** 0..100 */
    val masteryScore: Int = 0,
    val srsStep: Int = 0,
    val dueEpochDay: Long = 0,
    val lastReviewedAt: Long? = null,
    val linkedGroupId: String? = null,
    /** Comma separated story ids (S1..S7). */
    val storyIds: String? = null,
    val importId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val type: QuestionType get() = QuestionType.of(questionType)
    val mastery: MasteryState get() = MasteryState.entries.firstOrNull { it.name == masteryState } ?: MasteryState.NEW
    val active: ScriptVersion get() = ScriptVersion.of(activeVersion)

    /** Text used for practice: the approved version, falling back to the original. */
    val studyText: String
        get() = when (active) {
            ScriptVersion.ORIGINAL -> original
            ScriptVersion.NATURAL -> natural ?: original
            ScriptVersion.IH_TARGET -> ihTarget ?: original
            ScriptVersion.SHORT -> shortVersion ?: original
            ScriptVersion.KEYWORDS -> original
        }

    fun keywordList(): List<String> = keywords?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

enum class RevisionStatus { PENDING, APPROVED, REJECTED }

enum class RevisionOrigin(val label: String) {
    AI_CLAUDE("Claude API 생성"), PREBUILT("앱 내장 개선안 (AI 작성)"), USER("직접 수정"), LOCAL("로컬 규칙 생성")
}

/** Improvement proposals. Kept separately so the original is never overwritten without approval. */
@Entity(tableName = "script_revisions", indices = [Index("scriptId")])
data class ScriptRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val version: String,
    val text: String,
    val reasonKo: String = "",
    val origin: String = RevisionOrigin.PREBUILT.name,
    val status: String = RevisionStatus.PENDING.name,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "import_sources")
data class ImportSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val mime: String,
    val rawText: String,
    val parsedCount: Int,
    val importedAt: Long = System.currentTimeMillis(),
)

enum class PracticeMode(val label: String, val usesScript: Boolean) {
    READ_LISTEN("Read & Listen", true),
    SHADOWING("Shadowing", true),
    FILL_BLANK("빈칸 채우기", true),
    KEYWORD("키워드 말하기", true),
    BLIND("Blind", true),
    RANDOM("다른 표현 질문", true),
    VARIATION("응용 챌린지", true),
    FREE("자유 발화", false),
    MOCK("모의시험", false),
    /** One turn of a SAIL practice conversation (v3). */
    SAIL_CONVERSATION("SAIL 대화", false);

    companion object {
        fun of(name: String?): PracticeMode = entries.firstOrNull { it.name == name } ?: FREE
    }
}

@Entity(tableName = "attempts", indices = [Index("scriptId"), Index("createdAt"), Index("mockId")])
data class PracticeAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long? = null,
    val topic: String,
    val questionText: String,
    val questionType: String = QuestionType.OTHER.name,
    val mode: String,
    val transcript: String = "",
    /** LIVE_STT, RECORD_ONLY, CLOUD_STT, TYPED */
    val captureMode: String = "LIVE_STT",
    val audioPath: String? = null,
    val durationMs: Long = 0,
    val speechMs: Long = 0,
    val longPauseCount: Int = 0,
    val wordCount: Int = 0,
    val wpm: Int = 0,
    val coverage: Float? = null,
    /** Learning reference score, 0..100. Not an ACTFL rating. */
    val score: Int = 0,
    val metricsJson: String = "{}",
    val feedbackJson: String = "{}",
    /** LOCAL or AI */
    val feedbackSource: String = "LOCAL",
    val mockId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** v3: groups the turns of one exam training session (e.g. a SAIL conversation). */
    val trainingSessionId: Long? = null,
)

@Entity(tableName = "error_patterns", indices = [Index(value = ["key"], unique = true)])
data class ErrorPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    /** GRAMMAR / CONTENT / FLUENCY / VOCAB */
    val category: String,
    val example: String,
    val correction: String,
    val count: Int = 1,
    val lastSeen: Long = System.currentTimeMillis(),
)

@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phrase: String,
    val meaningKo: String,
    val example: String,
    val category: String,
    val srsStep: Int = 0,
    val dueEpochDay: Long = 0,
    /** NONE / OK / FAIL */
    val lastResult: String = "NONE",
    val lastReviewedEpochDay: Long? = null,
    val failCount: Int = 0,
    val source: String = ContentSource.AI_SAMPLE.name,
)

@Entity(tableName = "daily_logs")
data class DailyLogEntity(
    @PrimaryKey val epochDay: Long,
    val speakingMs: Long = 0,
    val attempts: Int = 0,
    val scriptsReviewed: Int = 0,
    val chunksReviewed: Int = 0,
    val mockDone: Boolean = false,
    val morningDone: Boolean = false,
)

@Entity(tableName = "mock_tests")
data class MockTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val questionCount: Int,
    val selfLevel: Int,
    val reportJson: String? = null,
)

@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    /** FIRST / RETAKE */
    val type: String = "FIRST",
    val resultGrade: String? = null,
    val note: String = "",
    // Post-exam debrief (written right after the exam, before the result).
    val topicsSeen: String = "",
    val stuckPoints: String = "",
    /** 1 (very hard) .. 5 (easy), 0 = not recorded */
    val feeling: Int = 0,
    val selfLevelUsed: Int = 0,
    val debriefAt: Long? = null,
)

@Entity(tableName = "stories")
data class StoryEntity(
    @PrimaryKey val id: String,
    val titleKo: String,
    val summary: String,
    val tags: String,
    val reusableFor: String,
)

@Entity(tableName = "questions", indices = [Index("topic")])
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val text: String,
    val type: String,
    val hintKo: String = "",
    /** USER_LIST (사용자 정리 자료, 비공식) / AI_GENERATED */
    val source: String,
)
