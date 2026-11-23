package com.clauder.reader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 사이트(도메인)별 CSS 선택자 규칙. 빈 칸은 자동 인식에 맡긴다. */
@Serializable
data class SiteRule(
    val domain: String,
    val contentSelector: String = "",
    val titleSelector: String = "",
    val nextSelector: String = "",
    val prevSelector: String = "",
    val workTitleSelector: String = "",
) {
    val isEmpty: Boolean
        get() = listOf(contentSelector, titleSelector, nextSelector, prevSelector, workTitleSelector).all { it.isBlank() }

    /** extractor.js 에 넘길 JSON */
    fun toScriptArg(): String = JSON.encodeToString(
        mapOf(
            "content" to contentSelector.trim(),
            "title" to titleSelector.trim(),
            "next" to nextSelector.trim(),
            "prev" to prevSelector.trim(),
            "workTitle" to workTitleSelector.trim(),
        ),
    )

    companion object {
        private val JSON = Json

        /** "https://www.Example.com/path" → "example.com" */
        fun normalizeDomain(input: String): String =
            input.trim().lowercase()
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore(':')
                .removePrefix("www.")
                .trim('.')

        /** 호스트에 맞는 규칙을 고른다. 여러 개가 맞으면 더 구체적인(긴) 도메인이 우선. */
        fun match(rules: List<SiteRule>, host: String): SiteRule? {
            val h = normalizeDomain(host)
            if (h.isEmpty()) return null
            return rules
                .filter { rule ->
                    val d = normalizeDomain(rule.domain)
                    d.isNotEmpty() && (h == d || h.endsWith(".$d"))
                }
                .maxByOrNull { normalizeDomain(it.domain).length }
        }
    }
}
