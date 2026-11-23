package com.clauder.reader.browser

import java.net.URLEncoder

object UrlInput {
    /** 주소창에 입력한 글자를 열 수 있는 주소로 바꾼다. 주소가 아니면 검색으로 보낸다. */
    fun toUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)) return text
        val looksLikeHost = !text.contains(' ') && Regex("""^[^\s/]+\.[^\s/.]{2,}(/.*)?$""").matches(text)
        return if (looksLikeHost) "https://$text"
        else "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
    }
}
