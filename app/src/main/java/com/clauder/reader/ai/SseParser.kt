package com.clauder.reader.ai

/**
 * SSE(Server-Sent Events) 스트림을 한 줄씩 받아 사건 단위로 묶는다.
 * 형식: "event: 이름" / "data: 내용" 줄들이 이어지고 빈 줄에서 사건 하나가 끝난다.
 */
class SseParser {
    data class Event(val name: String?, val data: String)

    private var name: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** 줄 하나를 넣는다. 사건이 완성되면 돌려주고, 아니면 null. */
    fun feed(line: String): Event? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null // 주석(연결 유지용)
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> name = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** 스트림이 빈 줄 없이 끝났을 때 남은 사건을 꺼낸다. */
    fun flush(): Event? {
        val event = if (hasData) Event(name, data.toString()) else null
        name = null
        data.setLength(0)
        hasData = false
        return event
    }
}
