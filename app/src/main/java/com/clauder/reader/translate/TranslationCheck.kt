package com.clauder.reader.translate

/** 모델의 응답이 온전한 번역인지 판단한다. 거절했거나 일부만 출력했으면 자동 재시도 대상이다. */
object TranslationCheck {

    /** 원문이 이 글자 수보다 짧으면 길이 비교를 하지 않는다(짧은 글은 비율이 크게 흔들린다). */
    private const val MIN_SOURCE_FOR_RATIO = 200

    /** 한국어 번역문은 보통 중국어 원문보다 글자 수가 많다. 이 비율에도 못 미치면 일부만 번역된 것으로 본다. */
    private const val MIN_LENGTH_RATIO = 0.3

    /** @return 문제가 없으면 null, 있으면 화면에 보여 줄 사유 */
    fun problem(stopReason: String, text: String, sourceChars: Int): String? = when {
        stopReason == "refusal" -> "모델이 번역을 거절했습니다."
        text.isBlank() -> "모델이 번역문을 내보내지 않았습니다 (종료 사유: $stopReason)."
        stopReason == "max_tokens" -> "출력 한도에 걸려 번역이 중간에서 끊겼습니다."
        stopReason != "end_turn" && stopReason != "stop_sequence" -> "번역이 예상과 다르게 끝났습니다 (종료 사유: $stopReason)."
        sourceChars >= MIN_SOURCE_FOR_RATIO && text.length < sourceChars * MIN_LENGTH_RATIO ->
            "번역문이 원문에 비해 너무 짧습니다. 일부만 번역된 것으로 보입니다."

        else -> null
    }
}
