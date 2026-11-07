package com.opicih.coach.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Optional recording → text. Interface keeps the provider replaceable (server relay later). */
interface Transcriber {
    suspend fun transcribe(audio: File): Result<String>
}

/**
 * Transcribes a recorded WAV with Gemini (audio input via inline_data, same key as the AI coach).
 * The prompt asks for a verbatim transcript that keeps fillers, repetitions and grammar mistakes, so the
 * analysis sees what was actually said instead of a cleaned-up version (the Android recognizer's weakness).
 * Requires explicit consent to upload audio.
 */
class GeminiTranscriber(private val apiKey: String, private val model: String) : Transcriber {
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun transcribe(audio: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (audio.length() > MAX_INLINE_BYTES) throw AiException("녹음이 너무 깁니다 (${audio.length() / 1024 / 1024}MB). 3분 이내로 답하세요.", false)
            val b64 = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
            val req = Request.Builder()
                .url("${GeminiAiCoach.BASE}/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(requestBody(b64, "audio/wav").toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                when {
                    r.code == 400 && s.contains("API key", true) -> throw AiException("Gemini 키가 올바르지 않습니다.", false)
                    r.code == 429 -> throw AiException("전사 요청 한도를 초과했습니다 (429). 잠시 후 다시 시도하세요.")
                    !r.isSuccessful -> throw AiException("전사 실패 (${r.code}).")
                }
                clean(GeminiAiCoach.parseText(s))
            }
        }.recoverCatching { e -> throw if (e is AiException) e else AiException("전사 중 네트워크 오류: ${e.message}") }
    }

    companion object {
        /** Gemini inline data limit is about 20 MB per request; base64 adds a third. 16 kHz WAV: 3 min ≈ 5.8 MB. */
        const val MAX_INLINE_BYTES = 12L * 1024 * 1024

        const val PROMPT = "Transcribe this English speaking-test answer verbatim. " +
            "Keep filler words (um, uh, er, like, you know), repetitions, false starts and grammar mistakes exactly as spoken. " +
            "Do not correct, summarize or translate. Use normal punctuation for sentence breaks. " +
            "If there is no speech, return an empty string. Output only the transcript text."

        /** Pure: request JSON for an inline audio transcription. Unit tested. */
        fun requestBody(base64Audio: String, mime: String): JsonObject = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", PROMPT) })
                        add(buildJsonObject { putJsonObject("inline_data") { put("mime_type", mime); put("data", base64Audio) } })
                    }
                })
            })
            putJsonObject("generationConfig") { put("temperature", 0.0) }
        }

        /** Strips quotes or code fences a model sometimes wraps around the transcript. */
        fun clean(text: String): String =
            text.trim().removePrefix("```").removeSuffix("```").trim().trim('"').trim()
    }
}
