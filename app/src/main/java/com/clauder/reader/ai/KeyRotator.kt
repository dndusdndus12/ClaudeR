package com.clauder.reader.ai

/**
 * API 키 여러 개를 장애 대응용으로 돌려 쓴다.
 *
 * - 인증 실패, 속도 제한, 크레딧 소진, 사용 한도, 과부하, 서버 오류가 나면 그 키를 잠시 쉬게 하고 다음 키로 재시도한다.
 * - 요청 자체가 잘못된 오류(400 등)와 네트워크 오류는 키를 바꿔도 소용없으므로 바로 실패시킨다.
 * - 모든 키가 실패하면 키별 원인을 담은 [AllKeysFailedException] 을 던진다.
 * - 평소에는 마지막으로 성공한 키를 계속 쓴다(쓸데없이 키를 바꾸면 프롬프트 캐시도 못 쓰게 된다).
 *
 * 시계를 주입받으므로 단위 테스트에서 시간을 마음대로 흘릴 수 있다.
 */
class KeyRotator(private val now: () -> Long = System::currentTimeMillis) {

    /** 키 값 자체는 들고 다니지 않고 id 와 화면 표시용 이름만 다룬다. */
    data class KeyRef(val id: String, val label: String)

    data class Cooldown(val until: Long, val kind: ApiErrorKind, val message: String?)

    data class Failure(val key: KeyRef, val kind: ApiErrorKind, val message: String?, val restSeconds: Long)

    class NoKeysException : Exception("등록된 API 키가 없습니다.")

    class AllKeysFailedException(val failures: List<Failure>) : Exception(describe(failures)) {
        companion object {
            fun describe(failures: List<Failure>): String = buildString {
                append(if (failures.size == 1) "API 키가 실패했습니다." else "등록된 키 ${failures.size}개가 모두 실패했습니다.")
                failures.forEachIndexed { i, f ->
                    append("\n${i + 1}) ${f.key.label}: ${reason(f.kind)}")
                    f.message?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.take(160)) }
                    append(" (${restText(f.restSeconds)} 쉼)")
                }
            }
        }
    }

    private val cooldowns = mutableMapOf<String, Cooldown>()
    private var preferredId: String? = null

    /** 쉬는 중이면 그 정보를, 아니면 null (시간이 지난 기록은 지운다) */
    fun cooldownOf(keyId: String): Cooldown? {
        val c = cooldowns[keyId] ?: return null
        if (c.until <= now()) {
            cooldowns.remove(keyId)
            return null
        }
        return c
    }

    fun remainingSeconds(keyId: String): Long = cooldownOf(keyId)?.let { (it.until - now() + 999) / 1000 } ?: 0

    /**
     * 시도할 순서. 쉬지 않는 키들을 (마지막으로 성공한 키부터) 먼저 쓴다.
     * 전부 쉬는 중이면 가장 빨리 풀리는 키 하나만 시도한다 — 키가 하나뿐인 사용자가 아예 막히지 않도록.
     */
    fun order(keys: List<KeyRef>): List<KeyRef> {
        val available = keys.filter { cooldownOf(it.id) == null }
        if (available.isEmpty()) {
            return listOfNotNull(keys.minByOrNull { cooldowns[it.id]?.until ?: 0L })
        }
        val start = available.indexOfFirst { it.id == preferredId }.coerceAtLeast(0)
        return available.drop(start) + available.take(start)
    }

    suspend fun <T> run(keys: List<KeyRef>, block: suspend (KeyRef) -> T): T {
        if (keys.isEmpty()) throw NoKeysException()
        val failures = mutableListOf<Failure>()
        for (key in order(keys)) {
            try {
                val result = block(key)
                preferredId = key.id
                cooldowns.remove(key.id)
                return result
            } catch (e: ApiException) {
                if (!shouldRotate(e.kind)) throw e
                val rest = restMillis(e)
                cooldowns[key.id] = Cooldown(now() + rest, e.kind, e.message)
                failures += Failure(key, e.kind, e.message, rest / 1000)
            }
        }
        throw AllKeysFailedException(failures)
    }

    companion object {
        fun shouldRotate(kind: ApiErrorKind): Boolean = when (kind) {
            ApiErrorKind.AUTH, ApiErrorKind.BILLING, ApiErrorKind.RATE_LIMIT, ApiErrorKind.SPEND_LIMIT,
            ApiErrorKind.OVERLOADED, ApiErrorKind.SERVER,
            -> true

            ApiErrorKind.INVALID_REQUEST, ApiErrorKind.NETWORK, ApiErrorKind.UNKNOWN -> false
        }

        /** 실패한 키를 쉬게 할 시간. 서버가 대기 시간을 알려 주면 그 값을 따른다. */
        fun restMillis(e: ApiException): Long = when (e.kind) {
            ApiErrorKind.RATE_LIMIT -> (e.retryAfterSeconds ?: 60L).coerceIn(5L, 600L) * 1000
            ApiErrorKind.OVERLOADED -> (e.retryAfterSeconds ?: 30L).coerceIn(5L, 600L) * 1000
            ApiErrorKind.SERVER -> 15_000L
            ApiErrorKind.AUTH, ApiErrorKind.BILLING -> 30 * 60_000L
            ApiErrorKind.SPEND_LIMIT -> 60 * 60_000L
            else -> 0L
        }

        fun reason(kind: ApiErrorKind): String = when (kind) {
            ApiErrorKind.AUTH -> "인증 실패(키가 틀렸거나 권한 없음)"
            ApiErrorKind.BILLING -> "크레딧 소진 또는 결제 문제"
            ApiErrorKind.RATE_LIMIT -> "요청 한도 초과"
            ApiErrorKind.SPEND_LIMIT -> "사용 한도 도달"
            ApiErrorKind.OVERLOADED -> "서버 과부하"
            ApiErrorKind.SERVER -> "서버 오류"
            ApiErrorKind.INVALID_REQUEST -> "잘못된 요청"
            ApiErrorKind.NETWORK -> "네트워크 오류"
            ApiErrorKind.UNKNOWN -> "알 수 없는 오류"
        }

        fun restText(seconds: Long): String = when {
            seconds >= 3600 -> "${seconds / 3600}시간"
            seconds >= 60 -> "${seconds / 60}분"
            else -> "${seconds}초"
        }
    }
}
