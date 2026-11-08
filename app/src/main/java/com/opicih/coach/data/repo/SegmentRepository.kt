package com.opicih.coach.data.repo

import androidx.room.withTransaction
import com.opicih.coach.ai.AiCoach
import com.opicih.coach.data.db.AppDatabase
import com.opicih.coach.data.db.ScriptEntity
import com.opicih.coach.data.db.ScriptSegmentEntity
import com.opicih.coach.data.db.SegmentKind
import com.opicih.coach.learning.ListenSegment
import com.opicih.coach.learning.ListenSegments
import com.opicih.coach.learning.BundledSegments
import com.opicih.coach.learning.ScriptSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 한국어 번역 생성 진행 상황. 화면을 벗어나도 계속되므로 화면이 아니라 저장소가 들고 있다. */
data class SegmentGenState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentTitle: String = "",
    val error: String? = null,
)

/**
 * 스크립트별 영한 정렬 데이터를 만들고 읽는다.
 * 만드는 일은 사용자가 시작할 때만 한다(스크립트 텍스트가 AI로 나가므로 자동으로 보내지 않는다).
 */
class SegmentRepository(
    private val db: AppDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val dao = db.dao()
    private val _state = MutableStateFlow(SegmentGenState())
    val state: StateFlow<SegmentGenState> = _state
    private var job: Job? = null

    private fun hashOf(s: ScriptEntity) = ScriptSegmenter.sourceHash(s.question, s.studyText)

    /** 지금 질문과 학습본에 맞는 번역이 이미 있는 스크립트 id. 텍스트가 바뀐 스크립트는 낡았으므로 빠진다. */
    private suspend fun freshIds(scripts: List<ScriptEntity>): Set<Long> {
        val byScript = dao.segments().groupBy { it.scriptId }
        return scripts.filter { s ->
            val rows = byScript[s.id].orEmpty()
            val hash = hashOf(s)
            rows.isNotEmpty() && rows.all { it.sourceHash == hash }
        }.map { it.id }.toSet()
    }

    /** (번역이 끝난 스크립트 수, 전체 스크립트 수). */
    suspend fun counts(): Pair<Int, Int> {
        val all = dao.scripts().filter { ScriptSegmenter.plan(it.studyText).sentences.isNotEmpty() }
        return freshIds(all).size to all.size
    }

    /** 번역을 만들 수 있고 아직 없거나 낡은 스크립트. 영어 문장이 없는 스크립트(빈 텍스트, 한글만 있는 텍스트)는 영원히 번역할 수 없어서 뺀다. */
    suspend fun pending(): List<ScriptEntity> {
        val all = dao.scripts().filter { ScriptSegmenter.plan(it.studyText).sentences.isNotEmpty() }
        val fresh = freshIds(all)
        return all.filter { it.id !in fresh }
    }

    /** 플레이어용 데이터. 번역이 없거나 낡았으면 null이라서 플레이어는 문단·문장 단계와 한국어를 건너뛴다. */
    suspend fun forPlayback(s: ScriptEntity): ListenSegments? {
        val rows = dao.segmentsFor(s.id)
        if (rows.isEmpty() || rows.any { it.sourceHash != hashOf(s) }) return null
        fun of(kind: SegmentKind) = rows.filter { it.kind == kind.name }.sortedBy { it.idx }
        return ListenSegments(
            paragraphs = of(SegmentKind.PARAGRAPH).map { ListenSegment(it.en, it.ko) },
            sentences = of(SegmentKind.SENTENCE).map { ListenSegment(it.en, it.ko) },
            questionKo = of(SegmentKind.QUESTION).firstOrNull()?.ko?.takeIf { it.isNotBlank() },
            fullKo = of(SegmentKind.FULL).firstOrNull()?.ko?.takeIf { it.isNotBlank() },
        )
    }

    /** 스크립트 하나의 번역을 만들어 저장한다. 개수가 맞지 않거나 실패하면 아무것도 저장하지 않는다. */
    suspend fun generateOne(s: ScriptEntity, ai: AiCoach): Result<Unit> {
        val plan = ScriptSegmenter.plan(s.studyText)
        if (plan.sentences.isEmpty()) return Result.failure(IllegalStateException("스크립트에 문장이 없습니다."))
        return ai.translateToKorean(listOf(s.question) + plan.sentences).mapCatching { ko ->
            val rows = ScriptSegmenter.assemble(s.question, plan, ko.first(), ko.drop(1))
            val hash = hashOf(s)
            val entities = rows.map {
                ScriptSegmentEntity(scriptId = s.id, version = s.activeVersion, sourceHash = hash, kind = it.kind.name, idx = it.idx, en = it.en, ko = it.ko)
            }
            db.withTransaction { dao.deleteSegmentsFor(s.id); dao.insertSegments(entities) }
        }
    }

    /**
     * 내장 번역([BundledSegments])을 가져온다. 번역이 없거나 낡은 스크립트 중 해시가 맞는 것만 채우고,
     * 이미 있는 번역(AI가 만든 것 포함)은 덮어쓰지 않는다. 가져온 스크립트 수를 돌려준다.
     */
    suspend fun importBundled(bundledJson: String): Int {
        val todo = pending()
        if (todo.isEmpty()) return 0
        val items = runCatching { BundledSegments.parse(bundledJson).items }.getOrNull().orEmpty()
        if (items.isEmpty()) return 0
        var imported = 0
        for (s in todo) {
            val rows = BundledSegments.rowsFor(s.question, s.studyText, items) ?: continue
            val hash = hashOf(s)
            val entities = rows.map {
                ScriptSegmentEntity(scriptId = s.id, version = s.activeVersion, sourceHash = hash, kind = it.kind.name, idx = it.idx, en = it.en, ko = it.ko)
            }
            db.withTransaction { dao.deleteSegmentsFor(s.id); dao.insertSegments(entities) }
            imported++
        }
        return imported
    }

    /**
     * 번역이 없거나 낡은 스크립트를 차례로 만든다. 처음 실패에서 멈추고, 이미 만든 것은 남는다.
     * [limit]으로 앞의 몇 개만 만들 수 있다(시범 생성: 말투를 귀로 확인한 뒤 나머지를 만들기 위해).
     */
    fun start(ai: AiCoach, limit: Int = Int.MAX_VALUE) {
        if (job?.isActive == true) return
        job = scope.launch {
            val todo = pending().take(limit)
            _state.value = SegmentGenState(running = true, total = todo.size)
            try {
                for (s in todo) {
                    if (!isActive) break
                    _state.update { it.copy(currentTitle = s.title) }
                    val r = generateOne(s, ai)
                    if (r.isFailure) {
                        _state.update { it.copy(error = "‘${s.title}’ 번역에 실패해 멈췄습니다. ${r.exceptionOrNull()?.message.orEmpty()}") }
                        break
                    }
                    _state.update { it.copy(done = it.done + 1) }
                    delay(REQUEST_GAP_MS)
                }
            } finally {
                _state.update { it.copy(running = false, currentTitle = "") }
            }
        }
    }

    fun cancel() { job?.cancel() }

    companion object {
        /** 무료 등급의 분당 한도(429)를 피하려고 요청 사이에 두는 간격. */
        const val REQUEST_GAP_MS = 1500L

        /** 처음 만들 때 먼저 번역하는 스크립트 수. 들어 보고 괜찮으면 나머지를 만든다. */
        const val PILOT_COUNT = 2
    }
}
