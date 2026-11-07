package com.opicih.coach.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Optional recording → text. Interface keeps the provider replaceable (server relay later). */
interface Transcriber {
    suspend fun transcribe(audio: File): Result<String>
}

/**
 * OpenAI audio transcription (whisper-1) with a prompt that asks to keep fillers ("um", "uh"), so the transcript is
 * closer to what was actually said than the Android recognizer. Requires the user's own OpenAI key and explicit
 * consent to upload audio. Claude does not take audio input, which is why a separate provider is used here.
 */
class OpenAiTranscriber(private val apiKey: String) : Transcriber {
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()

    @Serializable private data class Resp(val text: String = "")

    override suspend fun transcribe(audio: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (audio.length() > 24L * 1024 * 1024) throw AiException("녹음 파일이 25MB 제한을 넘습니다.", false)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "en")
                .addFormDataPart("response_format", "json")
                .addFormDataPart("prompt", "Umm, let me think, uh... I mean, like, I went there, uh, last summer.")
                .addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
                .build()
            val req = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey").post(body).build()
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                when {
                    r.code == 401 -> throw AiException("OpenAI 키가 올바르지 않습니다.", false)
                    r.code == 429 -> throw AiException("전사 요청 한도를 초과했습니다 (429).")
                    !r.isSuccessful -> throw AiException("전사 실패 (${r.code}): ${s.take(120)}")
                }
                AiJson.json.decodeFromString(Resp.serializer(), s).text.trim()
            }
        }.recoverCatching { e -> throw if (e is AiException) e else AiException("전사 중 네트워크 오류: ${e.message}") }
    }
}
