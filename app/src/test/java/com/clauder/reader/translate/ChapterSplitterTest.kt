package com.clauder.reader.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    private fun para(length: Int, tag: Char = '가') = tag.toString().repeat(length)

    @Test
    fun `기준보다 짧은 회차는 나누지 않는다`() {
        val paragraphs = listOf(para(1000), para(1500), para(2000))
        assertEquals(listOf(paragraphs), ChapterSplitter.split(paragraphs, 5000))
        assertTrue(ChapterSplitter.split(emptyList(), 5000).isEmpty())
    }

    @Test
    fun `긴 회차는 문단 경계에서 나누고 순서와 내용을 그대로 유지한다`() {
        val paragraphs = (1..40).map { para(300, ('a' + it % 26)) } // 12000자
        val chunks = ChapterSplitter.split(paragraphs, 5000)
        assertEquals(3, chunks.size)
        assertEquals(paragraphs, chunks.flatten())
        chunks.forEach { chunk -> assertTrue(chunk.sumOf { it.length } <= 5000) }
    }

    @Test
    fun `덩어리 크기를 고르게 맞춰 마지막 덩어리만 짧아지지 않게 한다`() {
        val paragraphs = (1..17).map { para(300) } // 5100자: 5000 + 100 이 아니라 반씩 나뉘어야 한다
        val sizes = ChapterSplitter.split(paragraphs, 5000).map { c -> c.sumOf { it.length } }
        assertEquals(2, sizes.size)
        assertTrue("고르지 않음: $sizes", sizes.all { it in 2400..2700 })
    }

    @Test
    fun `문단 중간에서는 자르지 않는다`() {
        val paragraphs = listOf(para(3000, 'a'), para(3000, 'b'), para(3000, 'c'))
        val chunks = ChapterSplitter.split(paragraphs, 5000)
        assertEquals(paragraphs, chunks.flatten())
        chunks.flatten().forEach { assertEquals(3000, it.length) }
    }

    @Test
    fun `혼자서 기준을 넘는 문단은 문장 끝에서 쪼갠다`() {
        val sentence = "他走进了那扇门，心里却有些不安。" // 16자
        val huge = sentence.repeat(700) // 11200자, 문단 구분 없음
        val chunks = ChapterSplitter.split(listOf(huge), 5000)
        assertTrue(chunks.size >= 3)
        assertEquals(huge, chunks.flatten().joinToString(""))
        chunks.flatten().forEach { piece ->
            assertTrue(piece.length <= 5000)
            assertTrue("문장 끝에서 잘라야 한다", piece.endsWith("。"))
        }
    }

    @Test
    fun `문장 부호가 전혀 없는 글도 기준을 넘지 않게 자른다`() {
        val chunks = ChapterSplitter.split(listOf(para(12000)), 5000)
        assertEquals(12000, chunks.flatten().sumOf { it.length })
        chunks.forEach { chunk -> assertTrue(chunk.sumOf { it.length } <= 5000) }
    }

    @Test
    fun `응답이 온전한지 판단한다`() {
        val source = 3000
        assertNull(TranslationCheck.problem("end_turn", para(4000), source))
        assertNotNull(TranslationCheck.problem("refusal", "", source))
        assertNotNull(TranslationCheck.problem("refusal", para(4000), source))
        assertNotNull(TranslationCheck.problem("max_tokens", para(4000), source))
        assertNotNull(TranslationCheck.problem("end_turn", "   ", source))
        assertNotNull(TranslationCheck.problem("pause_turn", para(4000), source))
        // 원문 3000자인데 번역문이 500자뿐이면 일부만 번역된 것으로 본다.
        assertNotNull(TranslationCheck.problem("end_turn", para(500), source))
        // 짧은 원문은 길이 비교를 하지 않는다.
        assertNull(TranslationCheck.problem("end_turn", "네.", 100))
    }

    @Test
    fun `주소에서 작품 열쇠를 만든다`() {
        assertEquals("69shuba.com/txt/21160", WorkKey.of("https://www.69shuba.com/txt/21160/12097699", "青石问道"))
        // 같은 작품의 다른 회차, 모바일 주소, # 뒤 내용은 같은 작품이다.
        assertEquals("69shuba.com/txt/21160", WorkKey.of("https://m.69shuba.com/txt/21160/12097700#top", ""))
        assertEquals("example.com/book/1", WorkKey.of("https://example.com/book/1/13.html", "雨巷"))
        // 경로로 구분할 수 없으면 작품명으로 구분한다.
        assertEquals("example.com#雨巷", WorkKey.of("https://example.com/read.php?bid=1&cid=2", "雨巷"))
        assertEquals("example.com", WorkKey.of("https://example.com/read.php?bid=1", ""))
        assertEquals("69shuba.com", WorkKey.hostOf("69shuba.com/txt/21160"))
        assertEquals("example.com", WorkKey.hostOf("example.com#雨巷"))
    }
}
