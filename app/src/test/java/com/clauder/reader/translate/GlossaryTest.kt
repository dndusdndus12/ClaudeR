package com.clauder.reader.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {

    @Test
    fun `한 줄에 하나씩 원문과 번역을 읽는다`() {
        val r = Glossary.parse("林远=임원\n守山人=수산인\n")
        assertEquals(listOf(GlossaryEntry("林远", "임원"), GlossaryEntry("守山人", "수산인")), r.entries)
        assertTrue(r.ignored.isEmpty())
        assertEquals(0, r.duplicateCount)
        assertEquals("항목 2개", r.summary())
    }

    @Test
    fun `빈 줄은 조용히 건너뛰고 형식이 틀린 줄은 무시된 줄로 알려 준다`() {
        val text = listOf(
            "林远=임원",
            "",
            "   ",
            "등호가 없는 줄",
            "=번역만 있음",
            "原文만 있음=",
            "  苏晴 = 소청  ",
        ).joinToString("\n")
        val r = Glossary.parse(text)
        assertEquals(listOf(GlossaryEntry("林远", "임원"), GlossaryEntry("苏晴", "소청")), r.entries)
        assertEquals(listOf(4, 5, 6), r.ignored.map { it.lineNumber })
        assertEquals("등호가 없는 줄", r.ignored[0].text)
        assertEquals("항목 2개 · 무시된 줄 3개", r.summary())
    }

    @Test
    fun `같은 원문이 다시 나오면 뒤의 것이 남고 중복 수를 센다`() {
        val r = Glossary.parse("林远=임원\n苏晴=소청\n林远=림원\n林远=임원2")
        assertEquals(listOf(GlossaryEntry("苏晴", "소청"), GlossaryEntry("林远", "임원2")), r.entries)
        assertEquals(2, r.duplicateCount)
        assertEquals("항목 2개 · 중복 2개", r.summary())
    }

    @Test
    fun `번역어 안의 등호와 전각 등호, 윈도우 줄바꿈, BOM 을 처리한다`() {
        val r = Glossary.parse("﻿E=mc²=에너지=질량\r\n青石秘境＝청석비경\r\n")
        assertEquals(listOf(GlossaryEntry("E", "mc²=에너지=질량"), GlossaryEntry("青石秘境", "청석비경")), r.entries)
        assertTrue(r.ignored.isEmpty())
    }

    @Test
    fun `큰 용어집을 한 번에 붙여넣어도 모두 읽는다`() {
        val text = (1..5000).joinToString("\n") { "原文$it=번역$it" }
        val r = Glossary.parse(text)
        assertEquals(5000, r.entries.size)
        assertEquals(GlossaryEntry("原文5000", "번역5000"), r.entries.last())
    }

    @Test
    fun `전역과 작품 용어집을 합치면 작품 쪽이 우선한다`() {
        val global = listOf(GlossaryEntry("林远", "임원"), GlossaryEntry("山盟", "산맹"))
        val work = listOf(GlossaryEntry("林远", "림원"), GlossaryEntry("苏晴", "소청"))
        assertEquals(
            listOf(GlossaryEntry("林远", "림원"), GlossaryEntry("山盟", "산맹"), GlossaryEntry("苏晴", "소청")),
            Glossary.merge(global, work),
        )
    }

    @Test
    fun `원문에 실제로 등장하는 항목만 등장 순서대로 고른다`() {
        val entries = listOf(
            GlossaryEntry("守山人", "수산인"),
            GlossaryEntry("林远", "임원"),
            GlossaryEntry("苏晴", "소청"),
            GlossaryEntry("青石秘境", "청석비경"),
            GlossaryEntry("青石", "청석"),
        )
        val source = "林远进入了青石秘境，守山人在一旁看着。"
        assertEquals(
            listOf(
                GlossaryEntry("林远", "임원"),
                // 같은 자리에서 시작하면 긴 표현이 먼저
                GlossaryEntry("青石秘境", "청석비경"),
                GlossaryEntry("青石", "청석"),
                GlossaryEntry("守山人", "수산인"),
            ),
            Glossary.select(entries, source),
        )
        assertTrue(Glossary.select(entries, "没有任何名字的一段话。").isEmpty())
    }

    @Test
    fun `요청에 붙일 용어집 글을 만든다`() {
        assertNull(Glossary.promptBlock(emptyList()))
        val block = Glossary.promptBlock(listOf(GlossaryEntry("林远", "임원"), GlossaryEntry("苏晴", "소청")))!!
        assertTrue(block.startsWith("[용어집"))
        assertTrue(block.endsWith("林远 = 임원\n苏晴 = 소청"))
    }
}
