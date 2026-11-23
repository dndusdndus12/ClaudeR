package com.clauder.reader.ai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClaudeStreamTest {

    /** SSE 글 전체를 줄 단위로 넣어 사건 목록으로 바꾼다. */
    private fun sseEvents(text: String): List<SseParser.Event> {
        val parser = SseParser()
        val out = mutableListOf<SseParser.Event>()
        text.split("\n").forEach { line -> parser.feed(line.trimEnd('\r'))?.let(out::add) }
        parser.flush()?.let(out::add)
        return out
    }

    private fun interpret(text: String): Pair<List<StreamEvent>, ClaudeStreamInterpreter> {
        val interpreter = ClaudeStreamInterpreter()
        val events = sseEvents(text).flatMap { interpreter.onEvent(it.data) }
        return events to interpreter
    }

    // 공식 문서의 스트리밍 예시 형식을 따른 응답(생각 블록 → 글 블록)
    private val transcript = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-opus-5-5","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":2679,"cache_creation_input_tokens":1200,"cache_read_input_tokens":0,"output_tokens":3}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"abc"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: ping
        data: {"type":"ping"}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"# 제7장 산문\n\n"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"\"생각은 정했느냐?\""}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":1}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":510}}

        event: message_stop
        data: {"type":"message_stop"}
    """.trimIndent()

    @Test
    fun `SSE 줄을 사건 단위로 묶는다`() {
        val events = sseEvents("event: a\ndata: 1\n\n: 주석\nevent: b\ndata: x\ndata: y\n\ndata: 이름 없는 사건\n")
        assertEquals(3, events.size)
        assertEquals(SseParser.Event("a", "1"), events[0])
        assertEquals(SseParser.Event("b", "x\ny"), events[1])
        assertEquals(SseParser.Event(null, "이름 없는 사건"), events[2])
    }

    @Test
    fun `정상 응답을 시작, 생각, 글, 종료로 해석한다`() {
        val (events, interpreter) = interpret(transcript)
        assertEquals(StreamEvent.Started("claude-opus-5-5"), events[0])
        assertEquals(StreamEvent.Thinking, events[1])
        val text = events.filterIsInstance<StreamEvent.Text>().joinToString("") { it.delta }
        assertEquals("# 제7장 산문\n\n\"생각은 정했느냐?\"", text)
        val finished = events.last() as StreamEvent.Finished
        assertEquals("end_turn", finished.stopReason)
        assertEquals("claude-opus-5-5", finished.model)
        // 입력/캐시 토큰은 message_start 에서, 출력 토큰은 message_delta 의 누적값에서 온다.
        assertEquals(TokenUsage(inputTokens = 2679, outputTokens = 510, cacheWriteTokens = 1200, cacheReadTokens = 0), finished.usage)
        assertTrue(interpreter.finished)
    }

    @Test
    fun `message_delta 가 입력 토큰까지 다시 주면 그 값으로 덮어쓴다`() {
        val (events, _) = interpret(
            """
            data: {"type":"message_start","message":{"model":"m","usage":{"input_tokens":10,"output_tokens":1}}}

            data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"input_tokens":12,"cache_read_input_tokens":900,"output_tokens":64000}}

            data: {"type":"message_stop"}
            """.trimIndent(),
        )
        val finished = events.last() as StreamEvent.Finished
        assertEquals("max_tokens", finished.stopReason)
        assertEquals(TokenUsage(inputTokens = 12, outputTokens = 64000, cacheReadTokens = 900), finished.usage)
    }

    @Test
    fun `거절로 끝난 응답의 종료 사유를 전달한다`() {
        val (events, _) = interpret(
            """
            data: {"type":"message_start","message":{"model":"m","usage":{"input_tokens":10,"output_tokens":1}}}

            data: {"type":"message_delta","delta":{"stop_reason":"refusal"},"usage":{"output_tokens":4}}

            data: {"type":"message_stop"}
            """.trimIndent(),
        )
        assertEquals("refusal", (events.last() as StreamEvent.Finished).stopReason)
    }

    @Test
    fun `중간에 온 오류 사건은 예외가 된다`() {
        val interpreter = ClaudeStreamInterpreter()
        try {
            interpreter.onEvent("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
            fail("예외가 나야 한다")
        } catch (e: ApiException) {
            assertEquals(ApiErrorKind.OVERLOADED, e.kind)
            assertEquals("overloaded_error", e.apiType)
        }
        assertFalse(interpreter.finished)
    }

    @Test
    fun `모르는 사건과 깨진 JSON 은 무시한다`() {
        val interpreter = ClaudeStreamInterpreter()
        assertTrue(interpreter.onEvent("""{"type":"something_new","x":1}""").isEmpty())
        assertTrue(interpreter.onEvent("not json").isEmpty())
        assertTrue(interpreter.onEvent("""{"type":"ping"}""").isEmpty())
    }

    @Test
    fun `오류를 성격별로 분류한다`() {
        fun kind(status: Int?, type: String?, msg: String? = null, code: String? = null) =
            ApiException.classify(status, type, msg, code)

        assertEquals(ApiErrorKind.AUTH, kind(401, "authentication_error"))
        assertEquals(ApiErrorKind.AUTH, kind(403, "permission_error"))
        assertEquals(ApiErrorKind.BILLING, kind(402, "billing_error"))
        assertEquals(ApiErrorKind.BILLING, kind(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API."))
        assertEquals(ApiErrorKind.RATE_LIMIT, kind(429, "rate_limit_error"))
        assertEquals(ApiErrorKind.SPEND_LIMIT, kind(429, "rate_limit_error", "You have reached your API usage limits", "enforced_spend_limit_reached"))
        assertEquals(ApiErrorKind.OVERLOADED, kind(529, "overloaded_error"))
        assertEquals(ApiErrorKind.SERVER, kind(500, "api_error"))
        assertEquals(ApiErrorKind.SERVER, kind(504, "timeout_error"))
        assertEquals(ApiErrorKind.INVALID_REQUEST, kind(400, "invalid_request_error", "max_tokens: too large"))
        assertEquals(ApiErrorKind.INVALID_REQUEST, kind(404, "not_found_error", "model: nope"))
        assertEquals(ApiErrorKind.INVALID_REQUEST, kind(413, "request_too_large"))
        // 본문을 읽지 못해 종류를 모를 때는 상태 코드로 판단한다.
        assertEquals(ApiErrorKind.RATE_LIMIT, kind(429, null))
        assertEquals(ApiErrorKind.SERVER, kind(503, null))
        assertEquals(ApiErrorKind.UNKNOWN, kind(null, null))
    }

    @Test
    fun `요청 본문은 캐시 대상 뒤에 가변 부분을 둔다`() {
        val model = ModelInfo(id = "claude-opus-5-5", maxOutputTokens = 128_000, effortLevels = listOf("low", "medium", "high"))
        val body = ClaudeProvider.buildRequestBody(
            TranslationRequest(model, cachedSystem = "프롬프트", volatileSystem = "용어집", userText = "原文", effort = "low"),
        )
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(64_000, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)

        val system = body["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertEquals("프롬프트", system[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("용어집", system[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(system[1].jsonObject["cache_control"])

        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("原文", message["content"]!!.jsonPrimitive.content)
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        // 일부 모델은 이 값들을 보내면 400 을 돌려주므로 보내지 않는다.
        assertNull(body["thinking"])
        assertNull(body["temperature"])
    }

    @Test
    fun `모델이 지원하지 않는 effort 는 보내지 않고 출력 한도는 모델 최대치를 넘지 않는다`() {
        val old = ModelInfo(id = "claude-haiku-4-5", maxOutputTokens = 32_000, effortLevels = emptyList())
        val body = ClaudeProvider.buildRequestBody(TranslationRequest(old, cachedSystem = "p", userText = "u", effort = "low"))
        assertNull(body["output_config"])
        assertEquals(32_000, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["system"]!!.jsonArray.size)

        val unknownLimit = ModelInfo(id = "x")
        assertEquals(16_000, ClaudeProvider.maxTokensFor(unknownLimit))
    }
}
