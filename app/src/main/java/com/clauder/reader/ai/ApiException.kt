package com.clauder.reader.ai

/** 오류의 성격. 키 순환(M3)에서 "다음 키로 넘어갈지"를 이 값으로 판단한다. */
enum class ApiErrorKind {
    /** 키가 틀렸거나 권한이 없음 (401, 403) */
    AUTH,

    /** 크레딧 소진, 결제 문제 (402 등) */
    BILLING,

    /** 분당 요청/토큰 한도 초과 (429) */
    RATE_LIMIT,

    /** 월 사용 한도 도달 (기다려도 풀리지 않는 429 등) */
    SPEND_LIMIT,

    /** 서버 과부하 (529) */
    OVERLOADED,

    /** 서버 내부 오류, 시간 초과 (500, 504) */
    SERVER,

    /** 요청 자체가 잘못됨 (400, 404, 413). 키를 바꿔도 해결되지 않는다. */
    INVALID_REQUEST,

    /** 인터넷 연결 문제, 응답 중단 */
    NETWORK,
    UNKNOWN,
}

class ApiException(
    val kind: ApiErrorKind,
    val httpStatus: Int? = null,
    /** API 가 알려 준 오류 종류(예: rate_limit_error) */
    val apiType: String? = null,
    message: String? = null,
    /** 서버가 알려 준 대기 시간(초) */
    val retryAfterSeconds: Long? = null,
    val requestId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 화면에 보여 줄 한국어 설명 */
    fun userMessage(): String {
        val base = when (kind) {
            ApiErrorKind.AUTH -> "API 키가 올바르지 않거나 권한이 없습니다. 설정 > AI 설정에서 키를 확인하세요."
            ApiErrorKind.BILLING -> "크레딧이 부족하거나 결제 정보에 문제가 있습니다."
            ApiErrorKind.RATE_LIMIT -> "요청 한도를 넘었습니다. 잠시 후 다시 시도하세요."
            ApiErrorKind.SPEND_LIMIT -> "API 사용 한도에 도달했습니다."
            ApiErrorKind.OVERLOADED -> "AI 서버가 혼잡합니다. 잠시 후 다시 시도하세요."
            ApiErrorKind.SERVER -> "AI 서버에서 오류가 발생했습니다. 잠시 후 다시 시도하세요."
            ApiErrorKind.INVALID_REQUEST -> "요청이 거부되었습니다."
            ApiErrorKind.NETWORK -> "네트워크 연결에 문제가 있습니다."
            ApiErrorKind.UNKNOWN -> "알 수 없는 오류가 발생했습니다."
        }
        val detail = buildList {
            httpStatus?.let { add("HTTP $it") }
            apiType?.let { add(it) }
            message?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
        return if (detail.isEmpty()) base else "$base\n($detail)"
    }

    companion object {
        /**
         * HTTP 상태와 오류 본문으로 오류 성격을 정한다.
         * 근거: https://platform.claude.com/docs/en/api/errors , /api/rate-limits
         */
        fun classify(status: Int?, apiType: String?, message: String?, errorCode: String?): ApiErrorKind {
            val msg = message.orEmpty().lowercase()
            return when {
                errorCode == "enforced_spend_limit_reached" -> ApiErrorKind.SPEND_LIMIT
                apiType == "authentication_error" || apiType == "permission_error" -> ApiErrorKind.AUTH
                apiType == "billing_error" -> ApiErrorKind.BILLING
                apiType == "overloaded_error" -> ApiErrorKind.OVERLOADED
                apiType == "rate_limit_error" -> ApiErrorKind.RATE_LIMIT
                apiType == "api_error" || apiType == "timeout_error" -> ApiErrorKind.SERVER
                // 크레딧 소진과 사용자가 직접 정한 지출 한도는 400 invalid_request_error 로 오므로
                // 오류 종류만으로는 구분할 수 없어 메시지를 본다.
                apiType == "invalid_request_error" && "credit balance" in msg -> ApiErrorKind.BILLING
                apiType == "invalid_request_error" && ("spend limit" in msg || "usage limit" in msg) -> ApiErrorKind.SPEND_LIMIT
                status == 401 || status == 403 -> ApiErrorKind.AUTH
                status == 402 -> ApiErrorKind.BILLING
                status == 429 -> ApiErrorKind.RATE_LIMIT
                status == 529 -> ApiErrorKind.OVERLOADED
                status != null && status >= 500 -> ApiErrorKind.SERVER
                status != null && status in 400..499 -> ApiErrorKind.INVALID_REQUEST
                apiType == "invalid_request_error" || apiType == "not_found_error" || apiType == "request_too_large" -> ApiErrorKind.INVALID_REQUEST
                else -> ApiErrorKind.UNKNOWN
            }
        }
    }
}
