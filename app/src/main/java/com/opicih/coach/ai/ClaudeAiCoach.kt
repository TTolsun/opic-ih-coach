package com.opicih.coach.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.opicih.coach.analysis.Feedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Real AI via the official Anthropic Java SDK. Only constructed when the user entered their own key,
 * enabled AI and agreed to send text. Audio is never sent to Claude.
 */
class ClaudeAiCoach(private val apiKey: String, private val model: String) : AiCoach {
    override val isRealAi = true
    override val engineName: String get() = "Claude"

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(90))
            .maxRetries(1)
            .build()
    }

    override suspend fun improveScript(input: ScriptInput): Result<ScriptVersions> = call {
        val text = send(Prompts.IMPROVE_SYSTEM, listOf(ChatTurn(true, Prompts.improveUser(input))), OutputConfig.Effort.MEDIUM)
        AiJson.json.decodeFromString(ScriptVersions.serializer(), AiJson.extractObject(text))
    }

    override suspend fun evaluate(input: EvalInput): Result<Feedback> = call {
        val text = send(Prompts.EVAL_SYSTEM, listOf(ChatTurn(true, Prompts.evalUser(input))), OutputConfig.Effort.MEDIUM)
        val fb = AiJson.json.decodeFromString(Feedback.serializer(), AiJson.extractObject(text))
        fb.copy(source = "AI", engine = engineName, strengths = fb.strengths.take(2), fixes = fb.fixes.take(3), score = fb.score.coerceIn(0, 100))
    }

    override suspend fun chat(history: List<ChatTurn>, context: CoachContext): Result<String> = call {
        val system = Prompts.CHAT_SYSTEM + "\n\n" + Prompts.contextBlock(context)
        send(system, history.takeLast(16), OutputConfig.Effort.LOW)
    }

    private suspend fun send(system: String, turns: List<ChatTurn>, effort: OutputConfig.Effort): String = withContext(Dispatchers.IO) {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .outputConfig(OutputConfig.builder().effort(effort).build())
        // Server-side refusal fallback (routes a declined request to a suitable model). Not available on Haiku.
        if (!model.contains("haiku")) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        // Conversation must start with a user turn.
        turns.dropWhile { !it.fromUser }.forEach { t ->
            if (t.fromUser) builder.addUserMessage(t.text) else builder.addAssistantMessage(t.text)
        }
        val msg = client.messages().create(builder.build())
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) throw AiException("AI가 이 요청을 처리하지 않았습니다 (refusal). 표현을 바꿔 다시 시도하세요.", retryable = false)
        val text = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
        if (stop.contains("max_tokens", ignoreCase = true) && text.isBlank()) throw AiException("AI 응답이 잘렸습니다. 다시 시도하세요.")
        if (text.isBlank()) throw AiException("AI 응답이 비어 있습니다.")
        text
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: UnauthorizedException) {
        Result.failure(AiException("API 키가 올바르지 않습니다. 설정에서 키를 확인하세요.", retryable = false))
    } catch (e: PermissionDeniedException) {
        Result.failure(AiException("이 키로는 선택한 모델($model)을 사용할 권한이 없습니다.", retryable = false))
    } catch (e: RateLimitException) {
        Result.failure(AiException("요청 한도를 초과했습니다 (429). 잠시 후 다시 시도하세요."))
    } catch (e: AnthropicServiceException) {
        Result.failure(AiException("AI 서버 오류 (${e.statusCode()}): ${e.message?.take(160)}"))
    } catch (e: AnthropicIoException) {
        Result.failure(AiException("네트워크 오류로 AI에 연결하지 못했습니다. 로컬 분석 결과를 사용합니다."))
    } catch (e: AiException) {
        Result.failure(e)
    } catch (e: kotlinx.serialization.SerializationException) {
        Result.failure(AiException("AI 응답 형식을 해석하지 못했습니다. 다시 시도하세요."))
    } catch (e: Exception) {
        Result.failure(AiException("AI 요청 실패: ${e.message ?: e.javaClass.simpleName}"))
    }
}
