package com.clauder.reader.translate

import java.net.URI

/**
 * 회차 주소에서 "어느 작품인지"를 나타내는 열쇠를 만든다.
 * 대부분의 소설 사이트는 `/작품ID/회차ID` 꼴이므로, 주소에서 마지막 칸(회차)을 뺀 부분을 작품으로 본다.
 * 예: https://www.69shuba.com/txt/21160/12097699 → "69shuba.com/txt/21160"
 */
object WorkKey {

    fun of(url: String, workTitle: String): String {
        val uri = runCatching { URI(url.substringBefore('#')) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.")
        val segments = uri?.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val title = workTitle.trim()

        // 경로가 두 칸 이상이면 마지막 칸을 회차로 보고 뺀다.
        if (segments.size >= 2) return host + "/" + segments.dropLast(1).joinToString("/")

        // 경로만으로 작품을 알 수 없는 사이트(예: /read.php?bid=1&cid=2)는 작품명으로 구분한다.
        if (title.isNotEmpty()) return "$host#$title"
        return host.ifEmpty { "unknown" }
    }

    /** 열쇠에서 사이트 부분만 */
    fun hostOf(key: String): String = key.substringBefore('/').substringBefore('#')
}
