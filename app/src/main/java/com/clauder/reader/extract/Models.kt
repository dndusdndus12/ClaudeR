package com.clauder.reader.extract

import kotlinx.serialization.Serializable

/** extractor.js 가 돌려주는 값 그대로. */
@Serializable
data class RawExtraction(
    val url: String = "",
    val host: String = "",
    val docTitle: String = "",
    val workTitle: String = "",
    val chapterTitle: String = "",
    val paragraphs: List<String> = emptyList(),
    val nextUrl: String? = null,
    val prevUrl: String? = null,
    val nextClickable: Boolean = false,
    val prevClickable: Boolean = false,
    val blocked: Boolean = false,
    val contentSource: String = "none",
    val contentPath: String = "",
    val warnings: List<String> = emptyList(),
)

enum class ExtractionStatus {
    /** 본문을 정상적으로 찾음 */
    OK,

    /** 보안 확인(봇 검사) 페이지로 보임 */
    BLOCKED,

    /** 본문이 없거나 너무 짧음 */
    TOO_SHORT,

    /** 스크립트 실행 자체가 실패 */
    ERROR,
}

/** 정리까지 끝난 한 회차. */
data class ExtractedChapter(
    val url: String,
    val host: String,
    val workTitle: String,
    val chapterTitle: String,
    val paragraphs: List<String>,
    val nextUrl: String?,
    val prevUrl: String?,
    /** 주소는 없지만 눌러서 이동할 수 있는 "다음" 버튼이 페이지에 있음 */
    val nextClickable: Boolean,
    val prevClickable: Boolean,
    val status: ExtractionStatus,
    /** "rule"(사이트 규칙), "auto"(자동), "none" */
    val contentSource: String,
    /** 본문으로 채택한 요소(예: div.txtnav). 규칙을 직접 쓸 때 참고용. */
    val contentPath: String,
    val warnings: List<String>,
    /** 광고 문구, 작가 후기 등으로 판단해 제거한 줄 수 */
    val removedLines: Int,
    val errorMessage: String? = null,
) {
    val charCount: Int get() = paragraphs.sumOf { it.length }
    val hasNext: Boolean get() = nextUrl != null || nextClickable
    val hasPrev: Boolean get() = prevUrl != null || prevClickable

    companion object {
        fun error(url: String, message: String) = ExtractedChapter(
            url = url, host = "", workTitle = "", chapterTitle = "", paragraphs = emptyList(),
            nextUrl = null, prevUrl = null, nextClickable = false, prevClickable = false,
            status = ExtractionStatus.ERROR, contentSource = "none", contentPath = "",
            warnings = emptyList(), removedLines = 0, errorMessage = message,
        )
    }
}
