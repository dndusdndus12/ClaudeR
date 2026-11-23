package com.clauder.reader.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Anthropic Claude 공급자. 공식 문서의 Messages API 를 HTTP 로 직접 호출한다.
 * - 모델 목록: GET /v1/models
 * - 번역: POST /v1/messages (stream=true, SSE)
 */
class ClaudeProvider(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.anthropic.com",
) : AiProvider {

    override val id = ID
    override val displayName = "Claude"
    override val implemented = true

    private val json = Json { ignoreUnknownKeys = true }

    private fun Request.Builder.commonHeaders(apiKey: String) = apply {
        header("x-api-key", apiKey)
        header("anthropic-version", API_VERSION)
    }

    override suspend fun listModels(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val models = mutableListOf<ModelInfo>()
        var afterId: String? = null
        do {
            val url = "$baseUrl/v1/models".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "1000")
                .apply { afterId?.let { addQueryParameter("after_id", it) } }
                .build()
            val request = Request.Builder().url(url).commonHeaders(apiKey).get().build()
            val page = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw errorFrom(response)
                    json.parseToJsonElement(response.body.string()).jsonObject
                }
            } catch (e: IOException) {
                throw ApiException(ApiErrorKind.NETWORK, message = e.message, cause = e)
            }
            page["data"]?.jsonArray?.forEach { models += parseModel(it.jsonObject) }
            val hasMore = page["has_more"]?.jsonPrimitive?.booleanOrNull == true
            afterId = if (hasMore) page["last_id"]?.jsonPrimitive?.contentOrNull else null
        } while (afterId != null)
        models
    }

    private fun parseModel(o: JsonObject): ModelInfo {
        val effort = (o["capabilities"] as? JsonObject)?.get("effort") as? JsonObject
        val levels = if (effort?.supported() == true) {
            EFFORT_ORDER.filter { (effort[it] as? JsonObject)?.supported() == true }
        } else {
            emptyList()
        }
        return ModelInfo(
            id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            displayName = o["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            maxOutputTokens = o["max_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            maxInputTokens = o["max_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            effortLevels = levels,
            lifecycle = o["lifecycle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun JsonObject.supported() = this["supported"]?.jsonPrimitive?.booleanOrNull == true

    override fun streamTranslation(apiKey: String, request: TranslationRequest): Flow<StreamEvent> = callbackFlow {
        val body = buildRequestBody(request).toString().toRequestBody(JSON_MEDIA)
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .commonHeaders(apiKey)
            .header("accept", "text/event-stream")
            .post(body)
            .build()
        val call = client.newCall(httpRequest)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(ApiException(ApiErrorKind.NETWORK, message = e.message, cause = e))
            }

            override fun onResponse(call: Call, response: Response) {
                val failure: Throwable? = try {
                    response.use { readStream(it) }
                    null
                } catch (e: ApiException) {
                    e
                } catch (e: IOException) {
                    ApiException(ApiErrorKind.NETWORK, message = e.message, cause = e)
                } catch (e: Exception) {
                    ApiException(ApiErrorKind.UNKNOWN, message = e.message, cause = e)
                }
                close(failure)
            }

            private fun readStream(response: Response) {
                if (!response.isSuccessful) throw errorFrom(response)
                val source = response.body.source()
                val sse = SseParser()
                val interpreter = ClaudeStreamInterpreter(json)
                fun dispatch(event: SseParser.Event?) {
                    if (event == null) return
                    interpreter.onEvent(event.data).forEach { trySendBlocking(it) }
                }
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    dispatch(sse.feed(line))
                }
                dispatch(sse.flush())
                if (!interpreter.finished) {
                    throw ApiException(ApiErrorKind.NETWORK, message = "응답이 끝나기 전에 연결이 끊겼습니다.")
                }
            }
        })

        awaitClose { call.cancel() }
    }

    private fun errorFrom(response: Response): ApiException {
        val raw = runCatching { response.body.string() }.getOrDefault("")
        val error = runCatching { json.parseToJsonElement(raw).jsonObject["error"] as? JsonObject }.getOrNull()
        val type = error?.get("type")?.jsonPrimitive?.contentOrNull
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.take(300).ifBlank { null }
        val code = (error?.get("details") as? JsonObject)?.get("error_code")?.jsonPrimitive?.contentOrNull
        return ApiException(
            kind = ApiException.classify(response.code, type, message, code),
            httpStatus = response.code,
            apiType = type,
            message = message,
            retryAfterSeconds = response.header("retry-after")?.toLongOrNull(),
            requestId = response.header("request-id"),
        )
    }

    companion object {
        const val ID = "claude"

        /** 공식 문서가 권장하는 최신 API 버전 */
        const val API_VERSION = "2026-10-21"

        private val JSON_MEDIA = "application/json".toMediaType()
        private val EFFORT_ORDER = listOf("low", "medium", "high", "xhigh", "max")

        /** 요청 본문. 단위 테스트에서 모양을 확인할 수 있게 공개한다. */
        fun buildRequestBody(request: TranslationRequest): JsonObject = buildJsonObject {
            put("model", request.model.id)
            put("max_tokens", maxTokensFor(request.model))
            put("stream", true)
            // 시스템 프롬프트: 매번 같은 부분에 캐시 표시를 하고, 회차마다 달라지는 부분은 그 뒤에 둔다.
            putJsonArray("system") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", request.cachedSystem)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    },
                )
                request.volatileSystem?.takeIf { it.isNotBlank() }?.let { extra ->
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", extra)
                        },
                    )
                }
            }
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", request.userText)
                        },
                    )
                },
            )
            // effort 는 모델이 지원한다고 알려 준 단계만 보낸다(지원하지 않는 모델에 보내면 400).
            request.effort?.takeIf { it in request.model.effortLevels }?.let { effort ->
                putJsonObject("output_config") { put("effort", effort) }
            }
            // thinking 은 일부러 보내지 않는다. 최신 모델은 생략하면 스스로 조절하고,
            // 일부 모델은 값을 명시하면 400 을 돌려준다.
        }

        /** 스트리밍에서는 출력이 잘리지 않도록 넉넉히 주되, 모델의 최대치를 넘지 않게 한다. */
        fun maxTokensFor(model: ModelInfo): Int {
            val wanted = 64_000
            return if (model.maxOutputTokens > 0) minOf(wanted, model.maxOutputTokens) else 16_000
        }
    }
}
