package com.clauder.reader.ai

import com.clauder.reader.ai.KeyRotator.KeyRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyRotatorTest {

    private var clock = 1_000_000L
    private val rotator = KeyRotator { clock }
    private val a = KeyRef("a", "키A")
    private val b = KeyRef("b", "키B")
    private val c = KeyRef("c", "키C")

    private fun err(kind: ApiErrorKind, retryAfter: Long? = null, message: String? = null) =
        ApiException(kind, message = message, retryAfterSeconds = retryAfter)

    /** 키별로 정해 둔 결과대로 동작하는 가짜 요청. 시도한 키를 순서대로 기록한다. */
    private fun runWith(keys: List<KeyRef>, outcome: Map<String, ApiException?>): Pair<String?, List<String>> {
        val tried = mutableListOf<String>()
        val result = runBlocking {
            rotator.run(keys) { key ->
                tried += key.id
                outcome[key.id]?.let { throw it }
                "ok:${key.id}"
            }
        }
        return result to tried
    }

    @Test
    fun `정상이면 첫 번째 키만 쓴다`() {
        val (result, tried) = runWith(listOf(a, b, c), emptyMap())
        assertEquals("ok:a", result)
        assertEquals(listOf("a"), tried)
    }

    @Test
    fun `속도 제한, 인증 실패, 크레딧 소진, 과부하는 다음 키로 넘어간다`() {
        for (kind in listOf(
            ApiErrorKind.RATE_LIMIT, ApiErrorKind.AUTH, ApiErrorKind.BILLING,
            ApiErrorKind.SPEND_LIMIT, ApiErrorKind.OVERLOADED, ApiErrorKind.SERVER,
        )) {
            val fresh = KeyRotator { clock }
            val tried = mutableListOf<String>()
            val result = runBlocking {
                fresh.run(listOf(a, b)) { key ->
                    tried += key.id
                    if (key.id == "a") throw err(kind)
                    "ok:${key.id}"
                }
            }
            assertEquals("$kind", "ok:b", result)
            assertEquals("$kind", listOf("a", "b"), tried)
            assertNotNull("$kind 실패한 키는 쉬어야 한다", fresh.cooldownOf("a"))
            assertNull(fresh.cooldownOf("b"))
        }
    }

    @Test
    fun `잘못된 요청과 네트워크 오류는 키를 바꾸지 않고 바로 실패한다`() {
        for (kind in listOf(ApiErrorKind.INVALID_REQUEST, ApiErrorKind.NETWORK, ApiErrorKind.UNKNOWN)) {
            val fresh = KeyRotator { clock }
            val tried = mutableListOf<String>()
            try {
                runBlocking {
                    fresh.run(listOf(a, b)) { key ->
                        tried += key.id
                        throw err(kind)
                    }
                }
                fail("예외가 나야 한다")
            } catch (e: ApiException) {
                assertEquals(kind, e.kind)
            }
            assertEquals("$kind", listOf("a"), tried)
            assertNull("$kind 키 잘못이 아니므로 쉬게 하지 않는다", fresh.cooldownOf("a"))
        }
    }

    @Test
    fun `실패한 키는 쉬는 동안 건너뛰고 시간이 지나면 다시 쓴다`() {
        runWith(listOf(a, b), mapOf("a" to err(ApiErrorKind.RATE_LIMIT, retryAfter = 120)))
        assertEquals(120, rotator.remainingSeconds("a"))

        // 쉬는 중: a 는 시도조차 하지 않는다.
        clock += 60_000
        assertEquals(listOf("b"), runWith(listOf(a, b), emptyMap()).second)
        assertEquals(60, rotator.remainingSeconds("a"))

        // 쉬는 시간이 끝나면 다시 후보가 된다. 다만 마지막으로 성공한 b 를 먼저 쓴다.
        clock += 61_000
        assertNull(rotator.cooldownOf("a"))
        assertEquals(listOf(b, a), rotator.order(listOf(a, b)))
    }

    @Test
    fun `마지막으로 성공한 키를 계속 쓴다`() {
        runWith(listOf(a, b, c), mapOf("a" to err(ApiErrorKind.OVERLOADED)))
        clock += 10 * 60_000 // a 의 쉬는 시간이 끝난 뒤에도
        assertEquals(listOf("b"), runWith(listOf(a, b, c), emptyMap()).second)
        assertEquals(listOf(b, c, a), rotator.order(listOf(a, b, c)))
    }

    @Test
    fun `모든 키가 실패하면 키별 원인을 담아 알린다`() {
        try {
            runWith(
                listOf(a, b, c),
                mapOf(
                    "a" to err(ApiErrorKind.AUTH, message = "invalid x-api-key"),
                    "b" to err(ApiErrorKind.RATE_LIMIT, retryAfter = 30),
                    "c" to err(ApiErrorKind.BILLING),
                ),
            )
            fail("예외가 나야 한다")
        } catch (e: KeyRotator.AllKeysFailedException) {
            assertEquals(listOf("a", "b", "c"), e.failures.map { it.key.id })
            assertEquals(listOf(ApiErrorKind.AUTH, ApiErrorKind.RATE_LIMIT, ApiErrorKind.BILLING), e.failures.map { it.kind })
            val text = e.message!!
            assertTrue(text.contains("등록된 키 3개가 모두 실패했습니다."))
            assertTrue(text.contains("키A: 인증 실패"))
            assertTrue(text.contains("invalid x-api-key"))
            assertTrue(text.contains("키B: 요청 한도 초과 (30초 쉼)"))
            assertTrue(text.contains("키C: 크레딧 소진"))
        }
    }

    @Test
    fun `전부 쉬는 중이면 가장 빨리 풀리는 키 하나로 시도한다`() {
        runCatching {
            runWith(
                listOf(a, b),
                mapOf("a" to err(ApiErrorKind.AUTH), "b" to err(ApiErrorKind.RATE_LIMIT, retryAfter = 20)),
            )
        }
        // a 는 30분, b 는 20초 쉰다 → b 를 고른다.
        assertEquals(listOf(b), rotator.order(listOf(a, b)))
        val (result, tried) = runWith(listOf(a, b), emptyMap())
        assertEquals("ok:b", result)
        assertEquals(listOf("b"), tried)
        assertNull("성공하면 쉬는 기록을 지운다", rotator.cooldownOf("b"))
    }

    @Test
    fun `키가 하나도 없으면 따로 알린다`() {
        try {
            runWith(emptyList(), emptyMap())
            fail("예외가 나야 한다")
        } catch (e: KeyRotator.NoKeysException) {
            assertFalse(e.message.isNullOrBlank())
        }
    }

    @Test
    fun `쉬는 시간은 오류 종류와 서버 안내에 따라 정한다`() {
        assertEquals(60_000L, KeyRotator.restMillis(err(ApiErrorKind.RATE_LIMIT)))
        assertEquals(120_000L, KeyRotator.restMillis(err(ApiErrorKind.RATE_LIMIT, retryAfter = 120)))
        // 서버가 터무니없이 길거나 짧은 값을 줘도 범위 안으로 맞춘다.
        assertEquals(600_000L, KeyRotator.restMillis(err(ApiErrorKind.RATE_LIMIT, retryAfter = 99_999)))
        assertEquals(5_000L, KeyRotator.restMillis(err(ApiErrorKind.RATE_LIMIT, retryAfter = 0)))
        assertEquals(30_000L, KeyRotator.restMillis(err(ApiErrorKind.OVERLOADED)))
        assertEquals(30 * 60_000L, KeyRotator.restMillis(err(ApiErrorKind.AUTH)))
        assertEquals(60 * 60_000L, KeyRotator.restMillis(err(ApiErrorKind.SPEND_LIMIT)))
    }
}
