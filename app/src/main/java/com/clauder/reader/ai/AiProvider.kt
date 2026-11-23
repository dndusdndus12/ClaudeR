package com.clauder.reader.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** 공급자가 제공하는 모델 하나. 모델 목록 API 에서 받아 설정에 저장해 둔다. */
@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String = "",
    /** 한 번의 응답으로 낼 수 있는 최대 출력 토큰 수(모르면 0) */
    val maxOutputTokens: Int = 0,
    val maxInputTokens: Int = 0,
    /** 이 모델이 받는 effort 단계(low, medium, high, ...). 비어 있으면 effort 를 보내지 않는다. */
    val effortLevels: List<String> = emptyList(),
    val lifecycle: String = "",
) {
    val label: String get() = displayName.ifBlank { id }
}

@Serializable
data class TokenUsage(
    /** 캐시되지 않고 정가로 처리된 입력 토큰 */
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** 이번 요청에서 캐시에 새로 쓴 토큰 */
    val cacheWriteTokens: Long = 0,
    /** 캐시에서 읽어 온 토큰 */
    val cacheReadTokens: Long = 0,
) {
    operator fun plus(o: TokenUsage) = TokenUsage(
        inputTokens + o.inputTokens,
        outputTokens + o.outputTokens,
        cacheWriteTokens + o.cacheWriteTokens,
        cacheReadTokens + o.cacheReadTokens,
    )
}

/**
 * 번역 요청 하나.
 * 시스템 프롬프트는 캐시 대상(매번 같은 부분)과 그 뒤에 붙는 가변 부분(회차별 용어집)으로 나눈다.
 */
data class TranslationRequest(
    val model: ModelInfo,
    val cachedSystem: String,
    val volatileSystem: String? = null,
    val userText: String,
    /** "low" 등. null 이면 모델 기본값. */
    val effort: String? = null,
    /** 프롬프트 캐시 유지 시간: "5m" 또는 "1h" */
    val cacheTtl: String = "5m",
)

/** 스트리밍 번역 중에 공급자가 내보내는 사건. */
sealed interface StreamEvent {
    /** 응답이 시작됨(실제로 처리한 모델 이름) */
    data class Started(val model: String) : StreamEvent

    /** 모델이 답을 쓰기 전에 생각하는 중(이 동안은 글자가 오지 않는다) */
    data object Thinking : StreamEvent

    /** 번역문 조각 */
    data class Text(val delta: String) : StreamEvent

    /** 응답 종료. stopReason: end_turn(정상), max_tokens(잘림), refusal(거절) 등 */
    data class Finished(val stopReason: String, val usage: TokenUsage, val model: String) : StreamEvent
}

/** 번역 AI 공급자. Claude 를 먼저 구현하고, 다른 공급자는 같은 인터페이스로 추가한다. */
interface AiProvider {
    val id: String
    val displayName: String

    /** false 면 설정 화면에 자리만 보여 주고 선택할 수 없다. */
    val implemented: Boolean

    /** API 의 모델 목록. 실패하면 [ApiException]. */
    suspend fun listModels(apiKey: String): List<ModelInfo>

    /** 스트리밍 번역. 실패하면 흐름이 [ApiException] 으로 끝난다. 수집을 취소하면 요청도 취소된다. */
    fun streamTranslation(apiKey: String, request: TranslationRequest): Flow<StreamEvent>
}

/** 지원 예정 공급자의 자리. */
object GeminiProvider : AiProvider {
    override val id = "gemini"
    override val displayName = "Gemini"
    override val implemented = false

    override suspend fun listModels(apiKey: String): List<ModelInfo> =
        throw ApiException(ApiErrorKind.INVALID_REQUEST, message = "Gemini 는 아직 지원하지 않습니다.")

    override fun streamTranslation(apiKey: String, request: TranslationRequest): Flow<StreamEvent> =
        throw ApiException(ApiErrorKind.INVALID_REQUEST, message = "Gemini 는 아직 지원하지 않습니다.")
}
