package com.opicih.coach

import com.opicih.coach.ai.AiJson
import com.opicih.coach.ai.AiException
import com.opicih.coach.ai.AiCoach
import com.opicih.coach.ai.EvalInput
import com.opicih.coach.ai.GeminiAiCoach
import com.opicih.coach.ai.LocalCoach
import com.opicih.coach.ai.Prompts
import com.opicih.coach.analysis.AnalysisInput
import com.opicih.coach.analysis.CompareResult
import com.opicih.coach.analysis.DiscourseAnalyzer
import com.opicih.coach.analysis.ErrorCounts
import com.opicih.coach.analysis.ErrorItem
import com.opicih.coach.analysis.Feedback
import com.opicih.coach.analysis.FeedbackComposer
import com.opicih.coach.analysis.FixItem
import com.opicih.coach.analysis.PauseAnalyzer
import com.opicih.coach.analysis.PauseMetrics
import com.opicih.coach.analysis.TextNormalizer
import com.opicih.coach.analysis.VerbatimMarkup
import com.opicih.coach.data.db.PracticeAttemptEntity
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.data.prefs.CaptureMode
import com.opicih.coach.data.prefs.LearnerProfile
import com.opicih.coach.data.prefs.Settings
import com.opicih.coach.data.repo.PracticeRepository
import com.opicih.coach.data.repo.measuredScore
import com.opicih.coach.learning.CAPTURE_CLOUD_STT
import com.opicih.coach.learning.CapturePolicy
import com.opicih.coach.learning.ProgressCalculator
import com.opicih.coach.learning.RetestAdvisor
import com.opicih.coach.learning.ScoreKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.DriverManager

/** Reproduction probes for issue #40. The printed values describe current behavior, including defects. */
class EvaluationValidityProbeTest {
    private data class Sample(
        val id: String,
        val transcript: String,
        val question: String = "Tell me about a problem you had at the library and how you solved it.",
        val type: String = "PAST_EXPERIENCE",
        val capture: String = "TYPED",
        val pauses: PauseMetrics? = null,
        val mission: String? = null,
        val requiredTense: String? = null,
    ) {
        val discourse get() = DiscourseAnalyzer.analyze(VerbatimMarkup.parse(transcript).clean)
        private val fromDevice get() = capture == "LIVE_STT"

        fun local() = FeedbackComposer.compose(
            AnalysisInput(type, false, transcript, discourse, pauses, null, fromDevice, requiredTense,
                question = question, mission = mission),
        )

        fun evalInput() = EvalInput(question, type, transcript, discourse, pauses, null, fromDevice, mission, capture, requiredTense)

        fun request() = PracticeRepository.Request(
            script = null, topic = "Library", question = question, questionType = type, mode = PracticeMode.FREE,
            transcript = transcript, captureMode = capture, audioPath = null, durationMs = pauses?.totalMs ?: 0,
            pauses = pauses, transcriptFromDeviceStt = fromDevice, requiredTense = requiredTense, mission = mission,
        )

        fun attempt(id: Int, feedback: Feedback = local(), mode: PracticeMode = PracticeMode.FREE) = PracticeAttemptEntity(
            topic = "Library", questionText = question, questionType = type, transcript = transcript,
            mode = mode.name, captureMode = capture, durationMs = pauses?.totalMs ?: 0,
            speechMs = pauses?.speechMs ?: 0, longPauseCount = pauses?.longPauseCount ?: 0,
            wordCount = discourse.wordCount, score = feedback.score, feedbackSource = feedback.source, createdAt = id.toLong(),
        )
    }

    private val a = Sample("A", "Last year I lost my card at the library. The librarian found it under a chair and gave it back.")
    private val b = Sample("B", """
        Last year I went to a cooking class with my neighbor because we wanted to learn how to bake bread.
        First we washed our hands and put the flour into a large wooden bowl. Then the teacher showed us how to mix the dough without making it too dry.
        After that we waited for the dough to rise while we talked about the different kinds of bread we ate as children.
        Finally we took our bread out of the oven and shared it with the rest of the class. I was proud of the loaf I made.
        Now I usually bake on Sundays, and next week I will try a new recipe with olives and cheese.
    """.trimIndent().replace('\n', ' '))
    private val c = Sample("C", "Last year I went to the library. The clock was round. I saw a green sign. I felt hungry. The walls were white. I carried a blue bag.")
    private val cPlus = c.copy(id = "C+", transcript = "Last year I went to the library. Actually, the clock was round. Also, I saw a green sign. However, I felt hungry. For example, the walls were white. Besides, I carried a blue bag.")
    private val d = Sample("D", """
        Last year I borrowed a book from the library for a class. On the day it was due, the return slot was locked.
        I went to the front desk and showed the librarian the book. She checked my account and accepted the book there.
        As a result I returned it on time and avoided a fine. I felt relieved because I needed to borrow another book that day.
    """.trimIndent().replace('\n', ' '))
    private val dPlus = d.copy(id = "D+", transcript = "${d.transcript} Next week I will buy a bicycle.")
    private val dQuestion = d.copy(id = "D-Q", question = "Tell me about a problem you had at a hospital and how you solved it.")
    private val e = Sample(
        "E", "Um, I I go to the library yesterday [self-correction] I went to the library yesterday. Yesterday I see my card on the floor. I wanted to [abandoned] The librarian returned it.",
        capture = CAPTURE_CLOUD_STT, pauses = PauseMetrics(24_000, 15_000, 1, 2_200, 800),
    )
    private val eClean = e.copy(id = "E-clean", transcript = "I went to the library yesterday. Yesterday I saw my card on the floor. The librarian returned it.", capture = "LIVE_STT")
    private val f = Sample("F", "Last weekend I went to the library. I see my card under a chair. The librarian helped me check that it was mine.")
    private val fPlus = f.copy(id = "F+", transcript = f.transcript.replace("I see", "I saw"))
    private val g = Sample("G", "The library is quiet, but the cafe is noisy. I read at the library when I need to concentrate. I meet friends at the cafe because we can talk freely.", question = "Compare your library and your local cafe as they are today.", type = "COMPARISON")
    private val h = Sample("H", "Next week I will go to the library. I will ask at the desk before the building closes, so I can return the book on time.", mission = "이 답변을 다음 주에 할 계획으로 바꾸세요.", requiredTense = "FUTURE")

    @Test
    fun localControls() {
        for (s in listOf(a, b, c, cPlus, d, dPlus, dQuestion, e, eClean, f, fPlus, g, h)) {
            val local = s.local()
            val counts = ErrorCounts.analyze(s.transcript, s.capture == CAPTURE_CLOUD_STT, null).counts
            val m = s.discourse
            println("${s.id} ${s.capture} words=${m.wordCount} sentences=${m.sentenceCount} " +
                "past/present/future=${m.pastCount}/${m.presentCount}/${m.futureCount} connectors=${m.connectorCount} " +
                "fillers=${m.fillerCount} repeated=${m.repeatedWords} score=${local.score} counts=$counts")
            println("${s.id} strengths=${local.strengths} fixes=${local.fixes.map { it.issue }} errors=${local.errors.map { it.key }} limits=${local.limits}")
        }
        assertEquals(e.pauses, eClean.pauses)
        assertTrue(e.discourse.wordCount > eClean.discourse.wordCount)
        assertEquals(null, ErrorCounts.analyze(eClean.transcript, false, null).counts.abandoned)
    }

    @Test
    fun followupTenseSlipAndItsCorrectionReachTheLocalFeedback() = runBlocking {
        val original = PracticeRepository.analyze(f.request(), null)
        val corrected = PracticeRepository.analyze(fPlus.request(), null)
        val evidence = "I see my card under a chair."
        assertEquals(1, original.metrics.errorCounts?.localTense)
        assertEquals(listOf(evidence), original.metrics.errorCounts?.localTenseExamples)
        assertEquals(evidence, original.feedback.errors.single { it.key == "tense_in_past_context" }.example)
        assertEquals(0, corrected.metrics.errorCounts?.localTense)
        assertTrue(corrected.feedback.errors.none { it.key == "tense_in_past_context" })
    }

    @Test
    fun originalTranscriptCountsTheRepeatedIWithoutCountingAcrossTheSentenceBoundary() = runBlocking {
        val raw = PracticeRepository.analyze(e.request(), null)
        val corrected = PracticeRepository.analyze(eClean.request(), null)
        assertEquals(1, raw.metrics.discourse.repeatedWords)
        assertEquals(0, corrected.metrics.discourse.repeatedWords)
    }

    @Test
    fun connectorsAndAnUnrequestedFutureSentenceDoNotRaiseEitherDisplayedScore() = runBlocking {
        val sameAudio = PauseMetrics(24_000, 15_000, 1, 2_200, 800)
        val model = ai { input ->
            val padded = input.transcript == cPlus.transcript || input.transcript == dPlus.transcript
            Feedback(source = "AI", score = if (padded) 95 else 40, connectedness = if (padded) 99 else 25)
        }
        for ((base, edited) in listOf(c to cPlus, d to dPlus)) {
            val original = base.copy(capture = "LIVE_STT", pauses = sameAudio).request()
            val decorated = edited.copy(capture = "LIVE_STT", pauses = sameAudio).request()
            for (coach in listOf(null, model)) {
                val left = PracticeRepository.analyze(original, coach)
                val right = PracticeRepository.analyze(decorated, coach)
                assertEquals(84, left.attempt.measuredScore())
                assertEquals(left.attempt.measuredScore(), right.attempt.measuredScore())
                assertEquals(left.feedback.score, right.feedback.score)
            }
            assertFalse(base.local().scoreMeasured)
            assertFalse(edited.local().scoreMeasured)
        }
        assertTrue(d.local().fixes.none { "미래" in it.issue })
        assertTrue(cPlus.local().strengths.none { "연결 표현" in it })
    }

    @Test
    fun actualQuestionAndMissionDetermineTheFeedback() = runBlocking {
        for (sample in listOf(a, b, d, dQuestion)) {
            val feedback = PracticeRepository.analyze(sample.request(), null).feedback
            assertNull(feedback.relevance)
            assertTrue(feedback.limits.any { "적합성" in it })
        }
        val taskCoach = ai { input ->
            val relevant = input.question == d.question
            Feedback(source = "AI", relevance = if (relevant) 90 else 10)
        }
        assertEquals(90, PracticeRepository.analyze(d.request(), taskCoach).feedback.relevance)
        assertEquals(10, PracticeRepository.analyze(dQuestion.request(), taskCoach).feedback.relevance)

        val echo = d.copy(transcript = d.question.uppercase()).request()
        val echoFeedback = PracticeRepository.analyze(echo, ai { error("An exact question echo must be handled locally") }).feedback
        assertEquals("question_echo", echoFeedback.errors.first().key)
        assertTrue(echoFeedback.fixes.first().issue.contains("질문 문장"))
        assertFalse(echoFeedback.scoreMeasured)

        assertTrue(g.local().fixes.isEmpty())
        assertTrue(g.local().retryMission.contains("같은 기준"))
        assertTrue(h.local().fixes.isEmpty())
        assertTrue(h.local().retryMission.contains("계획"))
        assertTrue(h.copy(transcript = d.transcript).local().fixes.first().issue.contains("미래 계획"))
        val futureQuestion = d.copy(question = "What will you do next weekend?")
        assertTrue(futureQuestion.local().fixes.first().issue.contains("미래 계획"))

        val replaced = d.request().copy(mission = "새 질문에 답하세요.", missionQuestion = dQuestion.question)
        val replacementEcho = PracticeRepository.analyze(replaced.copy(transcript = dQuestion.question), null)
        assertEquals("question_echo", replacementEcho.feedback.errors.first().key)
        val result = PracticeRepository.analyze(replaced, ai {
            assertTrue(Prompts.evalUser(it).contains("Replacement question (the actual question to answer): ${dQuestion.question}"))
            Feedback(source = "AI", relevance = 10)
        })
        assertEquals(10, result.feedback.relevance)

        val offTopic = PracticeRepository.analyze(b.request(), ai {
            assertEquals(a.question, it.question)
            Feedback(source = "AI", relevance = 10, fixes = listOf(FixItem("연결 표현을 다듬으세요.", better = "Then ...")),
                errors = listOf(ErrorItem("off_topic_response", "CONTENT", "I went to a cooking class", "Describe a problem at the library.")))
        }).feedback
        assertTrue(offTopic.fixes.first().issue.contains("주제에서 벗어났습니다"))
        assertTrue(offTopic.fixes.first().why.contains("I went to a cooking class"))
        assertEquals("Describe a problem at the library.", offTopic.retryMission)
    }

    @Test
    fun captureEvidenceAndModelEstimatesStaySeparateAndTaskKeysAreStable() = runBlocking {
        assertTrue(Prompts.evalUser(a.evalInput()).contains("typed answer"))
        assertTrue(Prompts.evalUser(eClean.evalInput()).contains("may be auto-corrected"))
        assertTrue(Prompts.evalUser(e.evalInput()).contains("verbatim recording transcript"))
        val system = Prompts.evalSystem(LearnerProfile.DEFAULT)
        assertTrue(system.contains("only extra discourse markers"))
        assertTrue(system.contains("future sentence that does not support the task earns no credit"))

        val model = ai { input ->
            val alias = if (input.transcript == c.transcript) "irrelevant_description" else "missing_problem_solving"
            Feedback(source = "AI", tenseErrors = 0, abandonedSentences = 3, selfCorrections = 4,
                errors = listOf(ErrorItem(alias, "CONTENT", "The clock was round", "Describe the problem.")))
        }
        val raw = PracticeRepository.analyze(e.request(), model).metrics.errorCounts!!
        assertEquals(1, raw.abandoned)
        assertEquals(1, raw.selfCorrection)
        assertEquals(3, raw.aiAbandoned)
        assertEquals(4, raw.aiSelfCorrection)
        assertEquals(1, raw.localTense)
        assertEquals(0, raw.aiTense)
        assertTrue(raw.localTenseExamples.any { "I see" in it })
        val clean = PracticeRepository.analyze(eClean.request(), model)
        assertNull(clean.metrics.errorCounts?.abandoned)
        assertNull(clean.metrics.errorCounts?.aiAbandoned)
        assertNull(clean.feedback.selfCorrections)
        val typed = PracticeRepository.analyze(c.request(), model)
        assertNull(typed.metrics.errorCounts?.selfCorrection)
        assertEquals("task_incomplete", typed.feedback.errors.single().key)
        assertEquals("task_incomplete", PracticeRepository.analyze(cPlus.request(), model).feedback.errors.single().key)
    }

    private fun ai(response: (EvalInput) -> Feedback): AiCoach = object : AiCoach by LocalCoach() {
        override val isRealAi = true
        override suspend fun evaluate(input: EvalInput): Result<Feedback> = Result.success(response(input))
    }

    @Test
    fun captureAndReadiness() {
        val consented = Settings(captureMode = CaptureMode.LIVE_STT, cloudSttEnabled = true, audioUploadConsent = true)
        assertTrue(CapturePolicy.choose(PracticeMode.FREE, consented, true).verbatim)
        assertEquals(CaptureMode.RECORD_ONLY, CapturePolicy.choose(PracticeMode.MOCK, consented, true).mode)
        assertNotNull(CapturePolicy.choose(PracticeMode.FREE, consented.copy(audioUploadConsent = false), true).note)
        assertEquals(CaptureMode.LIVE_STT, CapturePolicy.choose(PracticeMode.BLIND, consented, true).mode)
        val typed = (1..3).map { b.attempt(it) }
        val noPauseMeasurement = b.copy(capture = "LIVE_STT").attempt(4)
        val mixed = listOf(a.attempt(1), b.attempt(2, Feedback(score = 85, source = "AI")), d.attempt(3))
        val withBlind = typed + b.attempt(4, Feedback(score = 100), PracticeMode.BLIND)
        val mock = (1..5).map { id -> d.attempt(id, mode = PracticeMode.MOCK).copy(mockId = 1, wordCount = 0, score = 0, captureMode = "RECORD_ONLY") }
        assertEquals(ProgressCalculator.readiness(typed, emptyList(), 0, 0), ProgressCalculator.readiness(withBlind, emptyList(), 0, 0))
        for ((label, attempts) in listOf("typed" to typed, "one live without pauses" to (typed + noPauseMeasurement), "mixed" to mixed, "audio-only mock" to mock)) {
            val r = ProgressCalculator.readiness(attempts, emptyList(), 0, 0)
            println("$label $r ihReadiness=${ProgressCalculator.ihReadiness(emptyList(), attempts)} " +
                "gates=${RetestAdvisor.gates(r).map { it.id to it.passed }}")
        }
    }

    @Test
    fun aiResponseFixtures() {
        for (response in listOf("{}", """{"score":80,"tenseErrors":0}""", """{"score":80,"tenseErrors":1,"abandonedSentences":3,"selfCorrections":2}""")) {
            val envelope = """{"candidates":[{"content":{"parts":[{"text":${AiJson.json.encodeToString(response)}}]},"finishReason":"STOP"}]}"""
            val text = GeminiAiCoach.parseText(envelope)
            val decoded = AiJson.json.decodeFromString<Feedback>(AiJson.extractObject(text)).copy(source = "AI", engine = "Gemini")
            val analysis = ErrorCounts.analyze(e.transcript, true, decoded)
            println("fixture=$response score=${decoded.score} counts=${analysis.counts} notebook=${analysis.notebook.map { it.key }}")
        }
        val source = Prompts.evalUser(a.evalInput()).lineSequence().first { it.startsWith("Transcript source:") }
        println("TYPED prompt: $source")
    }

    @Test
    fun replayPrivateArchive() {
        val archivePath = System.getenv("OPIC_EVAL_LOCAL_ARCHIVE")
        assumeTrue("Opt in to local-only archive replay", !archivePath.isNullOrBlank())
        val archive = File(requireNotNull(archivePath))
        val recordings = mapOf(3 to "rec.wav", 7 to "rec_latest.wav")
        val database = File(archive, "db/opic_ih_coach.db")
        require(database.isFile)
        val url = "jdbc:sqlite:${database.toURI()}?mode=ro&immutable=1"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM attempts WHERE id IN (2, 3, 5, 6, 7) ORDER BY id").use { rows ->
                    while (rows.next()) {
                        val id = rows.getInt("id")
                        val duration = rows.getLong("durationMs")
                        val capture = rows.getString("captureMode")
                        val transcript = rows.getString("transcript")
                        val question = rows.getString("questionText")
                        val wave = recordings[id]?.let { readPcm(File(archive, it)) }
                        if (wave != null) assertEquals(duration, wave.totalMs)
                        val metrics = AiJson.json.parseToJsonElement(rows.getString("metricsJson")).jsonObject
                        val pauses = wave ?: metrics["pauses"]?.let { AiJson.json.decodeFromJsonElement<PauseMetrics>(it) }
                        val compare = metrics["compare"]?.let { AiJson.json.decodeFromJsonElement<CompareResult>(it) }
                        val mode = PracticeMode.of(rows.getString("mode"))
                        val sample = Sample("archive-$id", transcript, question, rows.getString("questionType"), capture, pauses)
                        val feedback = if (transcript.isBlank() && pauses != null) {
                            FeedbackComposer.acousticOnly(pauses)
                        } else {
                            FeedbackComposer.compose(AnalysisInput(sample.type, compare != null, transcript, sample.discourse,
                                pauses, compare, capture == "LIVE_STT", scoreKind = ScoreKind.of(mode)))
                        }
                        val matchesQuestion = transcript.isNotBlank() && TextNormalizer.normalize(transcript) == TextNormalizer.normalize(question)
                        val saved = AiJson.json.decodeFromString<Feedback>(rows.getString("feedbackJson"))
                        val readBack = saved.fixes.any { "그대로" in it.issue }
                        println("archive-$id mode=${rows.getString("mode")} capture=$capture duration=$duration " +
                            "words=${sample.discourse.wordCount} saved=${rows.getInt("score")}/${rows.getString("feedbackSource")} " +
                            "relevance=${saved.relevance} matchesQuestion=$matchesQuestion flaggedReadBack=$readBack " +
                            "recordedSpeech=${rows.getInt("speechMs")} recordedPauses=${rows.getInt("longPauseCount")} " +
                            "replayedAcoustics=$wave replayScore=${feedback.score} replayFixes=${feedback.fixes.size}")
                    }
                }
            }
        }
    }

    private fun readPcm(file: File): PauseMetrics {
        // The app's recorder writes a fixed 44-byte header for mono 16-bit PCM at 16 kHz.
        val wav = file.readBytes()
        require(wav.size >= 44 && wav.decodeToString(0, 4) == "RIFF" && wav.decodeToString(8, 16) == "WAVEfmt ")
        val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.getInt(16) == 16 && bytes.getShort(20).toInt() == 1 && bytes.getShort(22).toInt() == 1)
        require(bytes.getInt(24) == 16_000 && bytes.getShort(34).toInt() == 16 && wav.decodeToString(36, 40) == "data")
        require(bytes.getInt(40) == wav.size - 44 && (wav.size - 44) % 2 == 0)
        bytes.position(44)
        val levels = buildList {
            while (bytes.hasRemaining()) {
                val frame = ShortArray(minOf(320, bytes.remaining() / 2)) { bytes.short }
                add(PauseAnalyzer.rms(frame))
            }
        }
        return PauseAnalyzer.analyze(levels, 20, PauseAnalyzer.adaptiveThreshold(levels))
    }

    @Test
    fun geminiSyntheticRepeats() = runBlocking {
        val model = System.getenv("OPIC_EVAL_GEMINI_MODEL")
        assumeTrue("Opt in only for the synthetic evaluation cases", !model.isNullOrBlank())
        val key = System.getenv("GEMINI_API_KEY")
        assumeTrue("Use an existing Gemini key", !key.isNullOrBlank())
        val coach = GeminiAiCoach(requireNotNull(key), requireNotNull(model), LearnerProfile.DEFAULT)
        val samples = listOf(a, b, c, cPlus, d, dPlus, dQuestion, e, eClean, f, fPlus, g, h)
        val repeated = listOf(a, b, c, cPlus, d, dPlus, e)
        val selection = System.getenv("OPIC_EVAL_GEMINI_CASES")?.split(',')?.map(String::trim)
        val selected = selection?.map { id -> samples.single { it.id == id } }
        val runs = System.getenv("OPIC_EVAL_GEMINI_RUNS")?.toInt() ?: 3
        require(runs in 1..3)
        val failures = mutableListOf<String>()
        var consecutiveFailures = 0
        for (repeat in 1..runs) {
            for (s in selected ?: if (repeat == 1) samples else repeated) {
                val input = s.evalInput()
                var result = coach.evaluate(input)
                val firstError = result.exceptionOrNull() as? AiException
                if (firstError?.retryable == true) {
                    println("Gemini model=$model case=${s.id} repeat=$repeat retrying=${firstError.message}")
                    delay(5_000)
                    result = coach.evaluate(input)
                }
                if (result.isFailure) {
                    val failure = result.exceptionOrNull()
                    println("Gemini model=$model case=${s.id} repeat=$repeat failed=${failure?.message}")
                    failures += "${s.id}/$repeat"
                    consecutiveFailures++
                    check((failure as? AiException)?.retryable != false && consecutiveFailures < 2) {
                        "Stopping after Gemini evaluation failures $failures"
                    }
                    continue
                }
                consecutiveFailures = 0
                val fb = result.getOrThrow()
                println("Gemini model=$model case=${s.id} repeat=$repeat score=${fb.score} relevance=${fb.relevance} " +
                    "specificity=${fb.specificity} connectedness=${fb.connectedness} tense/abandoned/self=${fb.tenseErrors}/${fb.abandonedSentences}/${fb.selfCorrections} " +
                    "errors=${fb.errors.map { it.key }} strengths=${fb.strengths} fixes=${fb.fixes.map { it.issue }} retry=${fb.retryMission}")
            }
        }
        assertTrue("Gemini evaluation failures $failures", failures.isEmpty())
    }
}
