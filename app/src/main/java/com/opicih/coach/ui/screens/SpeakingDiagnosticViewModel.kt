package com.opicih.coach.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.opicih.coach.App
import com.opicih.coach.ai.GeminiAiCoach
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.db.QuestionType
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.learning.DiagnosticQuestion
import com.opicih.coach.learning.DiagnosticSession
import com.opicih.coach.learning.SpeakingDiagnostic
import com.opicih.coach.speech.AnswerCapture
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DiagnosticData(val sessions: List<DiagnosticSession>, val exposed: List<String>) {
    fun nextQuestions(session: DiagnosticSession? = null) = SpeakingDiagnostic.remaining(session?.answers?.keys.orEmpty(), exposed)
}

internal data class DiagnosticOperation(
    val recording: Boolean = false,
    val saving: Boolean = false,
    val reviewing: Long? = null,
    val error: String? = null,
) {
    val busy get() = recording || saving || reviewing != null
}

internal class SpeakingDiagnosticViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val c = (app as App).container
    private val capture = AnswerCapture(app, c.backup.audioDir())
    private var recordingLimit: Job? = null
    private var cleared = false
    private var questionPlayback: Job? = null

    val selectedId = savedState.getStateFlow<Long?>("diagnosticSessionId", null)
    val operation = MutableStateFlow(DiagnosticOperation())
    val data = combine(c.dao.diagnosticAttemptsFlow(), c.dao.scriptsFlow()) { attempts, scripts ->
        DiagnosticData(DiagnosticSession.from(attempts), attempts.map { it.questionText } + scripts.map { it.question })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun start() {
        val snapshot = data.value ?: return
        if (selectedId.value != null || snapshot.sessions.any { !it.complete } || snapshot.nextQuestions() == null) return
        val id = generateSequence { UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE }
            .first { candidate -> candidate != 0L && snapshot.sessions.none { it.id == candidate } }
        open(id)
    }

    fun open(id: Long) {
        if (!operation.value.busy) {
            operation.value = DiagnosticOperation()
            savedState["diagnosticSessionId"] = id
        }
    }

    fun close() {
        if (operation.value.saving || operation.value.reviewing != null) return
        onBackground()
        savedState["diagnosticSessionId"] = null
        operation.value = DiagnosticOperation()
    }

    fun playQuestion(question: DiagnosticQuestion) {
        if (operation.value.busy) return
        stopAudio()
        questionPlayback = viewModelScope.launch {
            val rate = c.settings.current().ttsRate
            if (!c.tts.speakOffline(question.text, rate)) error("질문을 재생할 수 없습니다.")
        }
    }

    fun playAnswer(path: String) {
        if (operation.value.recording || operation.value.saving) return
        stopAudio()
        c.player.play(File(path)) { error(it) }
    }

    fun startRecording(question: DiagnosticQuestion) {
        if (operation.value.busy || currentQuestion() != question) return
        stopAudio()
        val issue = runCatching { capture.start(viewModelScope, CaptureMode.RECORD_ONLY) }
            .getOrElse { "녹음을 시작하지 못했습니다." }
        if (issue != null) {
            capture.cancel()
            error(issue)
            return
        }
        operation.value = DiagnosticOperation(recording = true)
        recordingLimit = viewModelScope.launch {
            delay(SpeakingDiagnostic.MAX_RECORDING_MS)
            recordingLimit = null
            saveRecording(question)
        }
    }

    fun saveRecording(question: DiagnosticQuestion) {
        val id = selectedId.value ?: return
        if (!operation.value.recording) return
        recordingLimit?.cancel()
        recordingLimit = null
        operation.value = DiagnosticOperation(saving = true)
        viewModelScope.launch {
            withContext(NonCancellable) {
                var unsavedFile: String? = null
                try {
                    val recording = capture.stop(transcriber = null)
                    unsavedFile = recording.audioPath
                    if (recording.audioPath == null || recording.durationMs <= 0L) {
                        error(recording.warning ?: "저장할 녹음이 없습니다.")
                        return@withContext
                    }
                    val saved = c.practice.saveDiagnosticAnswer(PracticeRepository.Request(
                        script = null, topic = question.type.label, question = question.text, questionType = question.type.name,
                        mode = PracticeMode.DIAGNOSTIC, transcript = "", captureMode = recording.captureMode,
                        audioPath = recording.audioPath, durationMs = recording.durationMs, pauses = recording.pauses,
                        transcriptFromDeviceStt = false, trainingSessionId = id,
                    ))
                    if (saved.audioPath == unsavedFile) unsavedFile = null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    capture.cancel()
                    error("녹음을 저장하지 못했습니다.")
                } finally {
                    unsavedFile?.let { File(it).delete() }
                    operation.update { it.copy(saving = false) }
                    if (cleared) capture.release()
                }
            }
        }
    }

    fun review(answer: PracticeAttemptEntity) {
        if (operation.value.busy || answer.trainingSessionId != selectedId.value) return
        stopAudio()
        operation.value = DiagnosticOperation(reviewing = answer.id)
        viewModelScope.launch {
            try {
                val settings = c.settings.current()
                val coach = c.realAi(settings) as? GeminiAiCoach
                if (coach == null) {
                    error("설정에서 AI 평가를 켜 주세요.")
                    return@launch
                }
                val saved = if (SpeakingDiagnostic.hasUsableTranscript(answer.transcript)) answer else {
                    val transcriber = c.transcriber(settings, forDiagnostic = true)
                    if (transcriber == null) {
                        error("설정에서 녹음 전사를 켜 주세요.")
                        return@launch
                    }
                    val file = answer.audioPath?.let(::File)
                    if (file?.isFile != true) {
                        error("저장된 녹음을 찾을 수 없습니다.")
                        return@launch
                    }
                    val transcript = transcriber.transcribe(file).getOrThrow()
                    c.practice.saveDiagnosticTranscript(answer, transcript) ?: return@launch error("변경된 답변을 다시 열어 주세요.")
                }
                val type = QuestionType.of(saved.questionType)
                val transcript = saved.transcript
                val report = if (SpeakingDiagnostic.hasUsableTranscript(transcript)) {
                    coach.observeDiagnostic(saved.questionText, type, transcript).getOrThrow()
                } else {
                    SpeakingDiagnostic.validate(type, transcript, settings.geminiModel, emptyMap())
                }
                if (!c.practice.saveDiagnosticReport(saved, report)) error("변경된 답변을 다시 열어 주세요.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error("관찰 결과를 가져오지 못했습니다. 다시 시도해 주세요.")
            } finally {
                operation.update { it.copy(reviewing = null) }
            }
        }
    }

    fun onBackground() {
        stopAudio()
        if (operation.value.recording) {
            recordingLimit?.cancel()
            recordingLimit = null
            capture.cancel()
            operation.value = DiagnosticOperation(error = "녹음이 중단됐습니다. 다시 답해 주세요.")
        }
    }

    private fun currentQuestion(): DiagnosticQuestion? = data.value?.let { snapshot ->
        val session = snapshot.sessions.firstOrNull { it.id == selectedId.value }
        snapshot.nextQuestions(session)?.firstOrNull()
    }

    private fun stopAudio() {
        questionPlayback?.cancel()
        c.tts.stop()
        c.player.stop()
    }

    private fun error(message: String) = operation.update { it.copy(error = message) }

    override fun onCleared() {
        cleared = true
        onBackground()
        if (!operation.value.saving) capture.release()
    }
}
