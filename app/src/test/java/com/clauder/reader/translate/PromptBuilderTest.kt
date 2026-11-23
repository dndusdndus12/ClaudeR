package com.clauder.reader.translate

import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private fun chapter(title: String, paragraphs: List<String>) = ExtractedChapter(
        url = "https://example.com/1", host = "example.com", workTitle = "作品", chapterTitle = title,
        paragraphs = paragraphs, nextUrl = null, prevUrl = null, nextClickable = false, prevClickable = false,
        status = ExtractionStatus.OK, contentSource = "auto", contentPath = "div", warnings = emptyList(), removedLines = 0,
    )

    @Test
    fun `시스템 프롬프트는 사용자 프롬프트 뒤에 고정 지시를 붙인다`() {
        val system = PromptBuilder.cachedSystem("  사용자 프롬프트  \n")
        assertTrue(system.startsWith("사용자 프롬프트\n\n[앱 고정 지시"))
        assertTrue(system.contains("최종 번역문만 출력한다"))
        assertTrue(system.contains("원문의 문단 구분을 유지한다"))
        assertTrue(system.contains("한자 병기 없이 용어집 번역을 그대로 쓴다"))
    }

    @Test
    fun `같은 프롬프트면 시스템 프롬프트가 글자 하나까지 같다`() {
        // 프롬프트 캐싱은 앞부분이 완전히 같아야 적용되므로, 시각 같은 변하는 값이 섞이면 안 된다.
        assertEquals(PromptBuilder.cachedSystem("p"), PromptBuilder.cachedSystem("p"))
    }

    @Test
    fun `원문은 제목 줄과 빈 줄로 구분한 문단으로 만든다`() {
        assertEquals("# 第一章 起\n\n一段\n\n二段", PromptBuilder.userText(chapter("第一章 起", listOf("一段", "二段"))))
        assertEquals("一段", PromptBuilder.userText(chapter("", listOf("一段"))))
    }

    @Test
    fun `번역문을 제목과 문단으로 나눈다`() {
        val t = PromptBuilder.parseTranslation("# 제1장 시작\n\n첫 문단.\n\n둘째 문단.\n  \n셋째 문단.\n")
        assertEquals("제1장 시작", t.title)
        assertEquals(listOf("첫 문단.", "둘째 문단.", "셋째 문단."), t.paragraphs)
    }

    @Test
    fun `스트리밍 중간 상태도 깨지지 않게 나눈다`() {
        assertEquals(PromptBuilder.Translated("", emptyList()), PromptBuilder.parseTranslation(""))
        // 제목 줄이 아직 끝나지 않은 상태
        assertEquals(PromptBuilder.Translated("제1장", emptyList()), PromptBuilder.parseTranslation("# 제1장"))
        // 문단을 쓰는 도중
        val mid = PromptBuilder.parseTranslation("# 제1장\n\n첫 문단.\n\n둘째 문")
        assertEquals(listOf("첫 문단.", "둘째 문"), mid.paragraphs)
    }

    @Test
    fun `제목 줄이 없는 번역문은 전부 본문으로 본다`() {
        val t = PromptBuilder.parseTranslation("첫 문단.\r\n\r\n둘째 문단.")
        assertEquals("", t.title)
        assertEquals(listOf("첫 문단.", "둘째 문단."), t.paragraphs)
    }
}
