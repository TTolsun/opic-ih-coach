package com.opicih.coach.ai

import com.opicih.coach.analysis.Feedback
import com.opicih.coach.data.prefs.LearnerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Google Gemini via the REST API (generativelanguage.googleapis.com, v1beta generateContent).
 * Prompts and the JSON contract live in Prompts / AiJson, so another provider can be added behind AiCoach later.
 * The key comes from the user's Keystore-encrypted settings and is sent in the x-goog-api-key header, never in the URL.
 */
class GeminiAiCoach(
    private val apiKey: String,
    private val model: String,
    private val profile: LearnerProfile = LearnerProfile.DEFAULT,
) : AiCoach {
    override val isRealAi = true
    override val engineName: String get() = "Gemini"

    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

    override suspend fun improveScript(input: ScriptInput): Result<ScriptVersions> = call {
        val text = send(Prompts.improveSystem(profile), listOf(ChatTurn(true, Prompts.improveUser(input))), json = true)
        AiJson.json.decodeFromString(ScriptVersions.serializer(), AiJson.extractObject(text))
    }

    override suspend fun evaluate(input: EvalInput): Result<Feedback> = call {
        val text = send(Prompts.evalSystem(profile), listOf(ChatTurn(true, Prompts.evalUser(input))), json = true)
        val fb = AiJson.json.decodeFromString(Feedback.serializer(), AiJson.extractObject(text))
        fb.copy(source = "AI", engine = engineName, strengths = fb.strengths.take(2), fixes = fb.fixes.take(3), score = fb.score.coerceIn(0, 100),
            tenseErrors = fb.tenseErrors?.coerceAtLeast(0), abandonedSentences = fb.abandonedSentences?.coerceAtLeast(0), selfCorrections = fb.selfCorrections?.coerceAtLeast(0))
    }

    override suspend fun chat(history: List<ChatTurn>, context: CoachContext): Result<String> = call {
        send(Prompts.chatSystem(profile) +"\n\n" + Prompts.contextBlock(context), history.takeLast(16), json = false)
    }

    override suspend fun followUp(history: List<ConvExchange>): Result<String> = call {
        val text = send(Prompts.FOLLOWUP_SYSTEM, listOf(ChatTurn(true, Prompts.followUpUser(history))), json = false, maxTokens = 512)
        // Keep only the question itself, even if the model adds a label or quotes.
        text.lines().map { it.trim().trim('"').removePrefix("Follow-up:").trim() }.firstOrNull { it.endsWith("?") } ?: text.lines().first().trim()
    }

    override suspend fun summarizeConversation(history: List<ConvExchange>, localFindings: String): Result<String> = call {
        send(Prompts.convSummarySystem(profile), listOf(ChatTurn(true, Prompts.convSummaryUser(history, localFindings))), json = false, maxTokens = 2048)
    }

    override suspend fun translateToKorean(items: List<String>): Result<List<String>> = call {
        if (items.isEmpty()) return@call emptyList()
        val text = send(Prompts.TRANSLATE_SYSTEM, listOf(ChatTurn(true, Prompts.translateUser(items))), json = true)
        parseTranslations(text, items.size)
    }

    private suspend fun send(system: String, turns: List<ChatTurn>, json: Boolean, maxTokens: Int = 8192): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            put("contents", buildJsonArray {
                turns.dropWhile { !it.fromUser }.forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.fromUser) "user" else "model")
                        putJsonArray("parts") { add(buildJsonObject { put("text", t.text) }) }
                    })
                }
            })
            putJsonObject("generationConfig") {
                put("temperature", 0.4)
                put("maxOutputTokens", maxTokens)
                if (json) put("responseMimeType", "application/json")
            }
        }
        val req = Request.Builder()
            .url("$BASE/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code, s)
            parseText(s)
        }
    }

    private fun httpError(code: Int, body: String): AiException {
        val msg = runCatching { AiJson.json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return when {
            code == 400 && msg.contains("API key", true) -> AiException("Gemini API 키가 올바르지 않습니다. 설정에서 키를 확인하세요.", false)
            code == 403 -> AiException("이 키로 Gemini API를 사용할 권한이 없습니다. Google AI Studio에서 API가 켜져 있는지 확인하세요.", false)
            code == 404 -> AiException("모델 '$model'을 찾을 수 없습니다. 설정에서 '모델 목록 불러오기'로 사용 가능한 모델을 고르세요.", false)
            code == 429 -> AiException("Gemini 요청 한도를 초과했습니다 (429). 무료 등급은 분당·일일 한도가 있습니다. 잠시 후 다시 시도하세요.")
            else -> AiException("Gemini 오류 ($code): ${msg.take(160)}")
        }
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: AiException) {
        Result.failure(e)
    } catch (e: kotlinx.serialization.SerializationException) {
        Result.failure(AiException("AI 응답 형식을 해석하지 못했습니다. 다시 시도하세요."))
    } catch (e: java.io.IOException) {
        Result.failure(AiException("네트워크 오류로 Gemini에 연결하지 못했습니다. 로컬 분석 결과를 사용합니다."))
    } catch (e: Exception) {
        Result.failure(AiException("AI 요청 실패: ${e.message ?: e.javaClass.simpleName}"))
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** Pure: extracts the answer text from a generateContent response, or explains why there is none. Unit tested. */
        fun parseText(responseBody: String): String {
            val root = AiJson.json.parseToJsonElement(responseBody).jsonObject
            root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content?.let {
                throw AiException("Gemini가 요청을 차단했습니다 ($it). 표현을 바꿔 다시 시도하세요.", false)
            }
            val cand = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: throw AiException("Gemini 응답이 비어 있습니다.")
            val text = (cand["content"] as? JsonObject)?.get("parts")?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
                ?.joinToString("")?.trim().orEmpty()
            val finish = cand["finishReason"]?.jsonPrimitive?.content.orEmpty()
            if (text.isBlank()) {
                throw when (finish) {
                    "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST" -> AiException("Gemini가 응답을 막았습니다 ($finish).", false)
                    "MAX_TOKENS" -> AiException("Gemini 응답이 잘렸습니다. 다시 시도하세요.")
                    else -> AiException("Gemini 응답이 비어 있습니다 ($finish).")
                }
            }
            return text
        }

        /**
         * Pure: reads {"ko": [...]} and insists on exactly [expected] non-blank strings. A wrong count means the model merged or
         * split items, so the alignment would be wrong; that is rejected instead of guessed at. Unit tested.
         */
        fun parseTranslations(text: String, expected: Int): List<String> {
            val ko = (AiJson.json.parseToJsonElement(AiJson.extractObject(text)).jsonObject["ko"] as? JsonArray)
                ?.map { it.jsonPrimitive.content.trim() }
                ?: throw AiException("번역 응답에 ko 목록이 없습니다. 다시 시도하세요.")
            if (ko.size != expected) throw AiException("번역 개수가 맞지 않습니다 (요청 ${expected}개, 응답 ${ko.size}개). 다시 시도하세요.")
            if (ko.any { it.isBlank() }) throw AiException("비어 있는 번역이 있습니다. 다시 시도하세요.")
            return ko
        }

        /** Lists models usable with generateContent for this key, so the user never has to guess model ids. */
        suspend fun listModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
            runCatching {
                val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
                val req = Request.Builder().url("$BASE/models?pageSize=200").header("x-goog-api-key", apiKey).get().build()
                http.newCall(req).execute().use { r ->
                    val s = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw AiException(if (r.code == 400 || r.code == 403) "키를 확인하세요 (${r.code})." else "모델 목록을 가져오지 못했습니다 (${r.code}).", false)
                    parseModels(s)
                }
            }
        }

        fun parseModels(body: String): List<String> =
            (AiJson.json.parseToJsonElement(body).jsonObject["models"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { m -> (m["supportedGenerationMethods"] as? JsonArray)?.any { it.jsonPrimitive.content == "generateContent" } == true }
                .mapNotNull { it["name"]?.jsonPrimitive?.content?.removePrefix("models/") }
                .filter { it.startsWith("gemini") && !it.contains("embedding") && !it.contains("tts") && !it.contains("image") }
                .distinct()
    }
}
