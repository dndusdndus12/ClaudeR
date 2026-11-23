package com.clauder.reader.data

import com.clauder.reader.ai.TokenUsage
import kotlinx.serialization.Serializable
import java.util.Locale

/** 모델 하나의 단가. 모두 "토큰 100만 개당 미국 달러"다. */
@Serializable
data class ModelPrice(
    val input: Double,
    val output: Double,
    /** 캐시에서 읽은 입력 */
    val cacheRead: Double,
    /** 캐시에 쓴 입력(5분 유지) */
    val cacheWrite5m: Double,
    /** 캐시에 쓴 입력(1시간 유지) */
    val cacheWrite1h: Double,
)

/**
 * 추정 비용 계산과 기본 단가표.
 * 단가는 바뀔 수 있으므로, 사용자가 설정에서 직접 입력한 값이 있으면 그것을 먼저 쓴다.
 */
object Pricing {

    /** 기본 단가표를 확인한 시점(공식 가격 문서 기준) */
    const val DEFAULTS_AS_OF = "2026년 11월"

    private fun price(input: Double, output: Double, cacheReadRatio: Double = 0.1) = ModelPrice(
        input = input,
        output = output,
        cacheRead = input * cacheReadRatio,
        // 캐시 쓰기는 모든 모델이 입력 단가의 1.25배(5분), 2배(1시간)다.
        cacheWrite5m = input * 1.25,
        cacheWrite1h = input * 2.0,
    )

    /**
     * 모델 id → 기본 단가. https://platform.claude.com/docs/en/about-claude/pricing 의 표를 옮긴 것이다.
     * 캐시 읽기는 보통 입력의 0.1배이고, 일부 모델만 0.05배 또는 0.025배다.
     * Claude Haiku 5.5 는 프롬프트가 10만 토큰을 넘으면 단가가 달라지지만, 소설 한 회차는 그보다 훨씬 짧으므로 낮은 쪽만 둔다.
     */
    val DEFAULTS: Map<String, ModelPrice> = mapOf(
        "claude-fable-5-2" to price(8.0, 40.0, cacheReadRatio = 0.025),
        "claude-mythos-5-2" to price(8.0, 40.0, cacheReadRatio = 0.025),
        "claude-fable-5-1" to price(10.0, 50.0, cacheReadRatio = 0.025),
        "claude-mythos-5-1" to price(10.0, 50.0, cacheReadRatio = 0.025),
        "claude-fable-5" to price(10.0, 50.0),
        "claude-mythos-5" to price(10.0, 50.0),
        "claude-opus-5-5" to price(4.0, 20.0, cacheReadRatio = 0.05),
        "claude-opus-5" to price(5.0, 25.0),
        "claude-opus-4-8" to price(5.0, 25.0),
        "claude-opus-4-7" to price(5.0, 25.0),
        "claude-opus-4-6" to price(5.0, 25.0),
        "claude-opus-4-5" to price(5.0, 25.0),
        "claude-sonnet-5-5" to price(2.0, 10.0, cacheReadRatio = 0.05),
        "claude-sonnet-5" to price(2.0, 10.0),
        "claude-sonnet-4-6" to price(3.0, 15.0),
        "claude-sonnet-4-5" to price(3.0, 15.0),
        "claude-haiku-5-5" to price(0.10, 0.50),
        "claude-haiku-4-5" to price(1.0, 5.0),
    )

    private val DATED_SUFFIX = Regex("-\\d{8}$")

    /**
     * 기본 단가를 찾는다. 모델 id 가 표의 이름과 같거나, 뒤에 날짜(예: -20251001)만 붙은 경우에만 인정한다.
     * 이름이 비슷한 새 모델에 엉뚱한 단가를 적용하지 않기 위함이다.
     */
    fun defaultFor(modelId: String): ModelPrice? =
        DEFAULTS[modelId] ?: DEFAULTS[modelId.replace(DATED_SUFFIX, "")]

    /** 사용자가 입력한 단가가 있으면 그것을, 없으면 기본 단가를 돌려준다. 둘 다 없으면 null. */
    fun priceFor(modelId: String, custom: Map<String, ModelPrice>): ModelPrice? =
        custom[modelId] ?: defaultFor(modelId)

    /** 토큰 사용량의 추정 비용(달러). [cacheTtl] 은 캐시에 쓸 때의 유지 시간("5m" 또는 "1h")이다. */
    fun cost(usage: TokenUsage, price: ModelPrice, cacheTtl: String): Double {
        val write = if (cacheTtl == "1h") price.cacheWrite1h else price.cacheWrite5m
        return (
            usage.inputTokens * price.input +
                usage.outputTokens * price.output +
                usage.cacheReadTokens * price.cacheRead +
                usage.cacheWriteTokens * write
            ) / 1_000_000.0
    }

    /** 화면 표시용. 1달러 미만은 소수 넷째 자리까지 보여 준다. */
    fun formatUsd(value: Double): String =
        "$" + String.format(Locale.US, if (value >= 1.0) "%.2f" else "%.4f", value)

    /** 단가 입력 칸에 넣을 글자(불필요한 0 은 뗀다). */
    fun formatRate(value: Double): String =
        String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
}
