package com.clauder.reader.data

import com.clauder.reader.data.db.ChapterEntity
import com.clauder.reader.extract.ExtractionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkRepositoryTest {

    private fun saved(nextUrl: String? = "https://www.example.com/book/1/3.html", prevUrl: String? = null) = ChapterEntity(
        url = "https://www.example.com/book/1/2.html",
        workKey = "example.com/book/1",
        title = "第二章 入门",
        translatedTitle = "제2장 입문",
        translation = "# 제2장 입문\n\n첫 문단.\n\n둘째 문단.",
        sourceJson = WorkRepository.encodeParagraphs(listOf("第一段，带\"引号\"。", "第二段\n有换行。")),
        nextUrl = nextUrl,
        prevUrl = prevUrl,
        modelName = "Claude Sonnet 5",
        inputTokens = 5000, outputTokens = 7000, cacheWriteTokens = 0, cacheReadTokens = 1800,
        glossaryTerms = 12, chunkCount = 1,
        scrollIndex = 14, scrollOffset = 230,
        createdAt = 1, translatedAt = 2, readAt = 3,
    )

    @Test
    fun `원문 문단은 따옴표와 줄바꿈이 있어도 그대로 저장하고 되읽는다`() {
        val paragraphs = listOf("第一段，带\"引号\"。", "第二段\n有换行。", "")
        assertEquals(paragraphs, WorkRepository.decodeParagraphs(WorkRepository.encodeParagraphs(paragraphs)))
    }

    @Test
    fun `깨진 저장값은 빈 목록으로 읽는다`() {
        assertTrue(WorkRepository.decodeParagraphs("이건 JSON 이 아님").isEmpty())
        assertTrue(WorkRepository.decodeParagraphs("").isEmpty())
    }

    @Test
    fun `저장된 회차를 뷰어용 회차로 바꾼다`() {
        val chapter = WorkRepository.toExtracted(saved(), workTitle = "雨巷")
        assertEquals("https://www.example.com/book/1/2.html", chapter.url)
        assertEquals("www.example.com", chapter.host)
        assertEquals("雨巷", chapter.workTitle)
        assertEquals("第二章 入门", chapter.chapterTitle)
        assertEquals(listOf("第一段，带\"引号\"。", "第二段\n有换行。"), chapter.paragraphs)
        assertEquals(ExtractionStatus.OK, chapter.status)
        // 다음 화 주소가 있으므로 뷰어의 "다음 화"가 켜지고, 이전 화는 없으므로 꺼진다.
        assertTrue(chapter.hasNext)
        assertFalse(chapter.hasPrev)
    }

    @Test
    fun `이동 주소가 없는 회차는 다음 화와 이전 화가 모두 꺼진다`() {
        val chapter = WorkRepository.toExtracted(saved(nextUrl = null, prevUrl = null), workTitle = "")
        assertFalse(chapter.hasNext)
        assertFalse(chapter.hasPrev)
    }
}
