package com.opicih.coach.ui.screens

import android.app.Application
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opicih.coach.App
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.repo.AttemptMetrics
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.learning.HotelRoleplay
import com.opicih.coach.learning.HotelStage
import com.opicih.coach.learning.QuestionSource
import com.opicih.coach.speech.AnswerCapture
import com.opicih.coach.speech.CaptureResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HotelSet(
    val id: Long,
    val answers: Map<HotelStage, PracticeAttemptEntity> = emptyMap(),
) {
    val nextStage get() = HotelRoleplay.nextStage(answers.keys)
    val startedAt get() = answers.values.minOfOrNull { it.createdAt } ?: 0L
}

internal data class HotelUi(
    val set: HotelSet? = null,
    val recordingMode: CaptureMode? = null,
    val busy: Boolean = false,
    val captured: CaptureResult? = null,
    val draft: String = "",
    val edited: Boolean = false,
    val error: String? = null,
) {
    val recording get() = recordingMode != null
}

internal class HotelRoleplayViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as App).container
    private val capture = AnswerCapture(app, c.backup.audioDir())
    private var closeWhenReady = false
    private var cleared = false
    val ui = MutableStateFlow(HotelUi())
    val sets = c.dao.hotelAttemptsFlow().map { attempts ->
        attempts.filter { it.trainingSessionId != null }.groupBy { requireNotNull(it.trainingSessionId) }
            .map { (id, rows) -> hotelSet(id, rows) }.filter { it.answers.isNotEmpty() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recognition get() = capture.liveState
    val onDeviceAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
    val questions = MutableStateFlow<Map<HotelStage, QuestionSource>?>(null)

    init {
        viewModelScope.launch {
            try {
                questions.value = c.seed.hotelQuestions()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                questions.value = emptyMap()
            }
        }
    }

    fun start() {
        if (ui.value.set != null || sets.value == null || questions.value.isNullOrEmpty()) return
        val used = sets.value.orEmpty().mapTo(mutableSetOf()) { it.id }
        val id = generateSequence { UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE }
            .first { it != 0L && it !in used }
        ui.value = HotelUi(set = HotelSet(id))
    }

    fun open(set: HotelSet) {
        if (ui.value.set == null) ui.value = HotelUi(set = set)
    }

    fun close() {
        if (ui.value.busy) {
            closeWhenReady = true
            return
        }
        capture.cancel()
        stopAudio()
        ui.value.captured?.audioPath?.let { File(it).delete() }
        ui.value = HotelUi()
    }

    fun playQuestion() {
        val set = ui.value.set ?: return
        val stage = set.nextStage ?: return
        val question = questions.value?.get(stage) ?: return
        if (ui.value.recording || ui.value.busy) return
        viewModelScope.launch {
            val rate = c.settings.current().ttsRate
            if (ui.value.set != set || ui.value.recording || ui.value.busy) return@launch
            c.player.stop()
            if (!c.tts.speakOffline(question.text, rate)) error("질문을 재생할 수 없습니다.")
        }
    }

    fun playAnswer(path: String) {
        c.tts.stop()
        c.player.play(File(path)) { error(it) }
    }

    fun stopAudio() {
        c.tts.stop()
        c.player.stop()
    }

    fun startCapture(mode: CaptureMode) {
        val state = ui.value
        if (state.set?.nextStage == null || state.recording || state.busy || state.captured != null) return
        stopAudio()
        val failure = runCatching {
            capture.start(viewModelScope, mode, preferOffline = true, requireOnDevice = true)
        }.getOrElse { "마이크를 시작하지 못했습니다." }
        if (failure != null) {
            capture.cancel()
            error(failure)
        } else ui.update { it.copy(recordingMode = mode, error = null) }
    }

    fun stopCapture() {
        if (!ui.value.recording) return
        ui.update { it.copy(recordingMode = null, busy = true) }
        viewModelScope.launch {
            withContext(NonCancellable) { finishCapture() }
        }
    }

    private suspend fun finishCapture() {
        try {
            val result = capture.stop(transcriber = null)
            val hasAnswer = result.audioPath != null || result.transcript.isNotBlank()
            ui.update {
                it.copy(
                    busy = false,
                    captured = result.takeIf { hasAnswer },
                    draft = result.transcript.ifBlank { it.draft },
                    edited = result.transcript.isBlank() && it.edited,
                    error = result.warning ?: if (hasAnswer) null else "음성에서 답변을 찾지 못했습니다.",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            capture.cancel()
            ui.update { it.copy(busy = false, error = "답변을 가져오지 못했습니다.") }
        } finally {
            finishPendingWork()
        }
    }

    fun edit(text: String) = ui.update { it.copy(draft = text, edited = true) }

    fun discardCapture() {
        if (ui.value.busy || ui.value.recording) return
        stopAudio()
        ui.value.captured?.audioPath?.let { File(it).delete() }
        ui.update { it.copy(captured = null, draft = "", edited = false, error = null) }
    }

    fun save() {
        val state = ui.value
        val set = state.set ?: return
        val stage = set.nextStage ?: return
        val question = questions.value?.get(stage) ?: return
        val captured = state.captured
        val transcript = state.draft.trim()
        if (state.busy || state.recording || (transcript.isBlank() && captured?.audioPath == null)) return
        stopAudio()
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            withContext(NonCancellable) { saveAnswer(set, stage, question, state, transcript) }
        }
    }

    private suspend fun saveAnswer(
        set: HotelSet,
        stage: HotelStage,
        question: QuestionSource,
        state: HotelUi,
        transcript: String,
    ) {
        val captured = state.captured
        try {
            val attempts = c.practice.saveHotelAnswer(
                PracticeRepository.Request(
                    script = null, topic = question.topic, question = question.text,
                    questionType = question.type, mode = PracticeMode.HOTEL_ROLEPLAY,
                    transcript = transcript,
                    captureMode = if (transcript.isNotBlank() && (captured?.fromDeviceStt != true || state.edited)) {
                        "TYPED"
                    } else captured?.captureMode ?: "TYPED",
                    audioPath = captured?.audioPath, durationMs = captured?.durationMs ?: 0L,
                    pauses = captured?.pauses,
                    transcriptFromDeviceStt = captured?.fromDeviceStt == true && !state.edited,
                    trainingSessionId = set.id, hotelStage = stage,
                ),
            )
            val saved = hotelSet(set.id, attempts)
            captured?.audioPath?.takeIf { it != saved.answers[stage]?.audioPath }?.let { File(it).delete() }
            ui.value = HotelUi(set = saved)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ui.update { it.copy(busy = false, error = "답변을 저장하지 못했습니다.") }
        } finally {
            finishPendingWork()
        }
    }

    private fun error(message: String) = ui.update { it.copy(error = message) }

    override fun onCleared() {
        cleared = true
        stopAudio()
        if (ui.value.busy) closeWhenReady = true
        else {
            close()
            capture.release()
        }
    }

    private fun finishPendingWork() {
        if (closeWhenReady) {
            closeWhenReady = false
            close()
        }
        if (cleared) capture.release()
    }

    private fun hotelSet(id: Long, rows: List<PracticeAttemptEntity>) = HotelSet(
        id,
        rows.mapNotNull { row -> AttemptMetrics.from(row)?.hotelStage?.let { it to row } }.toMap(),
    )
}
