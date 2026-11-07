package com.opicih.coach.ai

import com.opicih.coach.analysis.DiscourseMetrics
import com.opicih.coach.analysis.PauseMetrics

object Prompts {
    private const val ACTFL_IH = """ACTFL Intermediate High (IH) speakers can handle uncomplicated communicative tasks with ease and confidence,
and can perform Advanced-level functions (narrating and describing in all major time frames in connected, paragraph-length
discourse; handling a situation with a complication) much of the time, though not consistently. Their speech is generally
understood, but patterns of error and breakdown appear when they try to sustain Advanced-level performance."""

    val IMPROVE_SYSTEM = """
You are an OPIc speaking coach for a Korean learner currently at IM2 who is aiming for IH.
$ACTFL_IH

Rewrite the learner's script for spoken use. Rules:
- Keep the learner's own experiences and facts. Do not invent new life events, except small concrete details that are clearly plausible.
- "natural": what a native speaker would naturally say with the same content (spoken, not written style).
- "ihTarget": 110-160 words the learner can actually say. Flow: opener, specific description, a short experience or example, feeling, wrap-up.
  Use past + present + at least one future sentence when it fits. Use connectors (actually, so, because, after that, on the other hand).
  Prefer easy words. Reuse the learner's good phrases. Avoid rare vocabulary. Sentence structures should be reusable for other questions.
- "short": 3 short sentences for emergencies.
- "keywords": 4-6 keyword cues (2-4 words each) in speaking order.
- "reasonKo": 3-5 short Korean bullet sentences explaining what changed and why (start each with "- ").
- "koreanMeaning": natural Korean translation of ihTarget.
Output ONLY one JSON object:
{"natural":"...","ihTarget":"...","short":"...","keywords":["..."],"reasonKo":"...","koreanMeaning":"..."}
""".trimIndent()

    val EVAL_SYSTEM = """
You are an OPIc speaking coach. Give learning feedback in Korean for a learner aiming at IH.
$ACTFL_IH

Judge the answer by IH functions: connected paragraph-length speech, narration in major time frames, relevance, concrete experience,
discourse connectors, and whether grammar errors block communication. Pronunciation cannot be judged from text: do not comment on it.
The transcript may come from a speech recognizer that auto-corrects grammar and removes fillers; mention this only if relevant.
Never claim or predict an official ACTFL/OPIc grade. The score is a learning reference only.

Output ONLY one JSON object:
{"strengths":["(Korean) exactly 2 items"],
 "fixes":[{"issue":"(Korean)","why":"(Korean)","better":"(English example sentence)"}],   // at most 3, most important first
 "betterAnswer":"(English, 100-150 words, same content made natural and IH-like, easy words)",
 "retryMission":"(Korean, one concrete instruction for the next attempt)",
 "score":0-100, "relevance":0-100, "specificity":0-100, "connectedness":0-100,
 "errors":[{"key":"snake_case_pattern","category":"GRAMMAR|CONTENT|FLUENCY|VOCAB","example":"learner's words","correction":"better words"}]}
""".trimIndent()

    val CHAT_SYSTEM = """
You are the learner's personal OPIc coach. Reply in Korean. The learner is IM2 aiming for IH.
Prefer short, actionable feedback over long explanations: at most 6 sentences or 5 bullet points unless asked.
English example sentences stay in English. Never present AI-made questions as official past exam questions;
label them "AI 생성 연습 문제". Never predict an official grade with certainty.
""".trimIndent()

    fun improveUser(i: ScriptInput) = buildString {
        appendLine("Topic: ${i.topic}")
        appendLine("Question: ${i.question}")
        if (i.storyHints.isNotBlank()) appendLine("Learner's personal story bank: ${i.storyHints}")
        appendLine("Original script:")
        appendLine(i.original)
    }

    fun evalUser(i: EvalInput) = buildString {
        appendLine("Question (${i.questionType}): ${i.question}")
        appendLine("Transcript source: ${if (i.transcriptFromDeviceStt) "Android speech recognizer (may be auto-corrected)" else "recording transcription"}")
        appendLine("Measured: ${metrics(i.discourse, i.pauses)}")
        if (!i.targetScript.isNullOrBlank()) appendLine("The learner was practicing this target script:\n${i.targetScript}")
        appendLine("Learner's answer transcript:")
        appendLine(i.transcript)
    }

    fun metrics(d: DiscourseMetrics, p: PauseMetrics?) = buildString {
        append("words=${d.wordCount}, sentences=${d.sentenceCount}, past=${d.pastCount}, present=${d.presentCount}, future=${d.futureCount}, connectors=${d.connectorCount}")
        if (p != null) append(", speechSeconds=${p.speechMs / 1000}, totalSeconds=${p.totalMs / 1000}, pausesOver2s=${p.longPauseCount}")
    }

    fun contextBlock(c: CoachContext) = buildString {
        appendLine("[Learner context]")
        appendLine("Exam: ${c.examDday}")
        appendLine("Background Survey topics: ${c.surveyTopics.joinToString()}")
        if (c.weakTopics.isNotEmpty()) appendLine("Weak topics: ${c.weakTopics.joinToString()}")
        if (c.frequentErrors.isNotEmpty()) appendLine("Repeated errors: ${c.frequentErrors.joinToString("; ")}")
        if (c.recentScores.isNotEmpty()) appendLine("Recent learning scores: ${c.recentScores.joinToString()}")
        appendLine("Scripts: ${c.scriptTitles.take(40).joinToString(" | ")}")
    }
}
