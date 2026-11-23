package com.clauder.reader.translate

import com.clauder.reader.extract.ExtractedChapter

/** 번역 요청에 들어갈 글을 만든다. 안드로이드에 의존하지 않아 단위 테스트가 가능하다. */
object PromptBuilder {

    /**
     * 사용자 프롬프트 뒤에 항상 붙는 앱 고정 지시.
     * 앞의 세 줄은 요구 사항에 정해진 내용이고, 뒤의 두 줄은 앱이 번역문에서 제목을 구분하기 위한 약속이다.
     */
    val FIXED_INSTRUCTIONS = """
        [앱 고정 지시 — 위 지침과 충돌하면 이 지시를 따른다]
        - 최종 번역문만 출력한다. 초안, 검토 과정, 설명, 인사말을 출력하지 않는다.
        - 원문의 문단 구분을 유지한다. 문단과 문단 사이는 빈 줄 하나로 나눈다.
        - 용어집에 있는 인물, 명칭은 한자 병기 없이 용어집 번역을 그대로 쓴다.
        - 입력의 첫 줄이 "# "로 시작하면 그 줄은 회차 제목이다. 번역문의 첫 줄에 "# 번역한 제목" 형식으로 출력하고, 빈 줄 하나를 둔 뒤 본문 번역을 이어서 출력한다.
        - 그 밖의 마크다운 서식(굵게, 목록, 코드 블록 등)은 쓰지 않는다.
    """.trimIndent()

    /** 캐시 대상이 되는 시스템 프롬프트: [사용자 프롬프트] + [앱 고정 지시] */
    fun cachedSystem(userPrompt: String): String = userPrompt.trim() + "\n\n" + FIXED_INSTRUCTIONS

    /**
     * 번역할 원문. 제목이 있으면 첫 줄에 "# 회차 제목"을 두고, 그 뒤로 문단을 빈 줄로 구분한다.
     * 긴 회차를 나눠 번역할 때는 첫 덩어리에만 제목을 넣는다.
     */
    fun userText(title: String, paragraphs: List<String>): String = buildString {
        if (title.isNotBlank()) append("# ").append(title.trim()).append("\n\n")
        append(paragraphs.joinToString("\n\n"))
    }

    fun userText(chapter: ExtractedChapter): String = userText(chapter.chapterTitle, chapter.paragraphs)

    data class Translated(val title: String, val paragraphs: List<String>)

    /** 모델이 내보낸 글(스트리밍 중간 상태 포함)을 제목과 문단으로 나눈다. */
    fun parseTranslation(raw: String): Translated {
        val text = raw.replace("\r\n", "\n").trimStart()
        var title = ""
        var body = text
        if (text.startsWith("#")) {
            val newline = text.indexOf('\n')
            val firstLine = if (newline < 0) text else text.substring(0, newline)
            title = firstLine.trimStart('#').trim()
            body = if (newline < 0) "" else text.substring(newline + 1)
        }
        val paragraphs = body.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return Translated(title, paragraphs)
    }
}
