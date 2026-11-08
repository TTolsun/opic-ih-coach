package com.opicih.coach.playback

import com.opicih.coach.learning.ListenCycle
import com.opicih.coach.learning.ListenLang
import com.opicih.coach.learning.ListenRole
import com.opicih.coach.learning.ShuffleQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** 소리 내어 읽는 쪽. 안드로이드 TTS 구현은 [com.opicih.coach.speech.ListenTts]이고, 테스트에서는 가짜로 바꾼다. */
interface ListenSpeaker {
    /** 이 언어를 읽을 수 있는지. 엔진 초기화를 기다린 뒤 답한다. */
    suspend fun supports(lang: ListenLang): Boolean

    /** 끝까지 읽으면 true, 읽지 못했으면 false. 코루틴이 취소되면 읽기를 즉시 멈춘다. */
    suspend fun speak(text: String, lang: ListenLang, role: ListenRole, rate: Float): Boolean

    fun stop()
}

/** 재생 중에 단계마다 새로 읽는 설정. 설정 화면에서 바꾸면 다음 단계부터 적용된다. */
data class ListenPrefs(
    val questionVoice: String = "",
    val answerVoice: String = "",
    val gapMs: Long = DEFAULT_GAP_MS,
    val rate: Float = 1.0f,
) {
    companion object { const val DEFAULT_GAP_MS = 800L }
}

enum class ListenStatus { IDLE, PLAYING, PAUSED }

data class ListenState(
    val status: ListenStatus = ListenStatus.IDLE,
    val scriptId: Long = 0,
    val scriptTitle: String = "",
    val topic: String = "",
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val stepLabel: String = "",
    val text: String = "",
    /** 사용자에게 알려야 하는 상황(한국어 음성 없음, 반복 실패 등). */
    val notice: String? = null,
    /** true면 한국어 단계를 건너뛰는 중이다(한국어 음성이 설치되지 않은 기기). */
    val koreanSkipped: Boolean = false,
)

/**
 * 하루 종일 듣는 재생 엔진. 화면이나 서비스와 무관한 순수 상태 기계다.
 *
 * - 스크립트는 셔플 큐로 고르고, 한 사이클(질문 → 답변 …)을 단계별로 읽는다.
 * - 일시정지 후 다시 재생하면 멈춘 단계를 처음부터 다시 읽는다. 사이클을 처음부터 다시 하지 않는다.
 * - 읽기에 연속으로 실패하거나 읽을 단계가 하나도 없으면 스스로 멈춘다(무한 반복 방지).
 */
class ListenPlayer(
    private val scope: CoroutineScope,
    private val speaker: ListenSpeaker,
    private val ids: suspend () -> List<Long>,
    private val cycle: suspend (Long) -> ListenCycle?,
    private val prefs: suspend () -> ListenPrefs,
    random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state

    private val queue = ShuffleQueue(random)
    private var job: Job? = null
    private var current: ListenCycle? = null
    private var stepIdx = 0

    val isPlaying: Boolean get() = _state.value.status == ListenStatus.PLAYING

    fun play() {
        if (job?.isActive == true) return
        start()
    }

    fun pause() {
        job?.cancel()
        job = null
        speaker.stop()
        _state.update { if (it.status == ListenStatus.IDLE) it else it.copy(status = ListenStatus.PAUSED) }
    }

    fun toggle() { if (isPlaying) pause() else play() }

    /** 지금 사이클을 버리고 다음 스크립트로 넘어가 재생한다. */
    fun next() {
        job?.cancel()
        job = null
        speaker.stop()
        current = null
        stepIdx = 0
        start()
    }

    fun stop() {
        job?.cancel()
        job = null
        speaker.stop()
        current = null
        stepIdx = 0
        _state.value = ListenState()
    }

    fun reportNotice(message: String) { _state.update { it.copy(notice = message) } }

    private fun start() {
        _state.update { it.copy(status = ListenStatus.PLAYING, notice = null) }
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        var failures = 0
        var emptyCycles = 0
        while (scope.isActive) {
            val c = current ?: loadNext() ?: return giveUp("재생할 스크립트가 없습니다. 스크립트를 먼저 추가해 주세요.")
            current = c
            var spoke = false
            while (stepIdx < c.steps.size) {
                val step = c.steps[stepIdx]
                if (!speaker.supports(step.lang)) {
                    if (step.lang == ListenLang.KO) _state.update { it.copy(koreanSkipped = true, notice = KO_MISSING) }
                    stepIdx++
                    continue
                }
                _state.update {
                    it.copy(
                        scriptId = c.scriptId, scriptTitle = c.title, topic = c.topic,
                        stepIndex = stepIdx + 1, stepCount = c.steps.size, stepLabel = step.label, text = step.text,
                    )
                }
                val p = prefs()
                if (speaker.speak(step.text, step.lang, step.role, p.rate)) {
                    failures = 0
                    spoke = true
                } else if (++failures >= MAX_FAILURES) {
                    return giveUp("음성을 읽지 못해 멈췄습니다. TTS 엔진과 음성 데이터를 확인해 주세요.")
                }
                stepIdx++
                delay(p.gapMs.coerceAtLeast(0))
            }
            current = null
            stepIdx = 0
            if (spoke) emptyCycles = 0 else if (++emptyCycles >= MAX_EMPTY_CYCLES) {
                return giveUp("읽을 수 있는 음성이 없어 멈췄습니다. TTS 설정에서 영어 음성을 설치해 주세요.")
            }
        }
    }

    private suspend fun loadNext(): ListenCycle? {
        val all = ids()
        repeat(all.size) {
            val id = queue.next(all) ?: return null
            cycle(id)?.takeIf { it.steps.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun giveUp(message: String) {
        job = null
        _state.update { it.copy(status = ListenStatus.PAUSED, notice = message) }
    }

    companion object {
        const val MAX_FAILURES = 3
        const val MAX_EMPTY_CYCLES = 3
        const val KO_MISSING = "한국어 음성이 설치되어 있지 않아 영어 단계만 재생합니다."
    }
}
