package com.clauder.reader.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Claude Messages API 의 스트리밍 사건(JSON)을 앱의 [StreamEvent] 로 바꾼다.
 * 네트워크와 분리되어 있어 녹화한 응답으로 단위 테스트할 수 있다.
 * 근거: https://platform.claude.com/docs/en/build-with-claude/streaming
 */
class ClaudeStreamInterpreter(private val json: Json = Json { ignoreUnknownKeys = true }) {

    private var model = ""
    private var usage = TokenUsage()
    private var stopReason: String? = null

    /** message_stop 까지 정상적으로 받았는지 */
    var finished = false
        private set

    /** 사건 하나를 해석한다. 오류 사건이면 [ApiException] 을 던진다. */
    fun onEvent(data: String): List<StreamEvent> {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        return when (obj.str("type")) {
            "message_start" -> {
                val message = obj.obj("message")
                model = message?.str("model").orEmpty()
                mergeUsage(message?.obj("usage"))
                listOf(StreamEvent.Started(model))
            }

            "content_block_start" ->
                if (obj.obj("content_block")?.str("type") == "thinking") listOf(StreamEvent.Thinking) else emptyList()

            "content_block_delta" -> {
                val delta = obj.obj("delta")
                val text = if (delta?.str("type") == "text_delta") delta.str("text") else null
                if (text.isNullOrEmpty()) emptyList() else listOf(StreamEvent.Text(text))
            }

            "message_delta" -> {
                obj.obj("delta")?.str("stop_reason")?.let { stopReason = it }
                // message_delta 의 usage 는 누적값이므로 더하지 않고 덮어쓴다.
                mergeUsage(obj.obj("usage"))
                emptyList()
            }

            "message_stop" -> {
                finished = true
                listOf(StreamEvent.Finished(stopReason ?: "end_turn", usage, model))
            }

            "error" -> {
                val error = obj.obj("error")
                val type = error?.str("type")
                val message = error?.str("message")
                val code = error?.obj("details")?.str("error_code")
                throw ApiException(
                    kind = ApiException.classify(null, type, message, code),
                    apiType = type,
                    message = message,
                )
            }

            // ping 과 앞으로 추가될 수 있는 모르는 사건은 무시한다.
            else -> emptyList()
        }
    }

    private fun mergeUsage(u: JsonObject?) {
        if (u == null) return
        usage = TokenUsage(
            inputTokens = u.long("input_tokens") ?: usage.inputTokens,
            outputTokens = u.long("output_tokens") ?: usage.outputTokens,
            cacheWriteTokens = u.long("cache_creation_input_tokens") ?: usage.cacheWriteTokens,
            cacheReadTokens = u.long("cache_read_input_tokens") ?: usage.cacheReadTokens,
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun JsonObject.long(key: String): Long? = runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()
}
