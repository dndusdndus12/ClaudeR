package com.clauder.reader.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterCleanerTest {

    private val prose = listOf(
        "林远走到石阶尽头的时候，天色已经完全暗了下来，四下无人。",
        "守门老者抬眼看他：“怎么，想好了？”",
        "林远用力点点头：“我想试一试。”",
        "试一试，总比原地要好！",
        "老者看着这少年，心里觉得有些意外。他守山多年见过不少求道人，可是像他这样将“诚”字写在脸上的，确实是头一回遇到。",
        "在读过和山下《草木与山石》颇为相近的《山石草木论》之后，林远便想到，山下人能做到的事情，山上的修行应该也能做到。",
    )

    @Test
    fun `본문 앞에 반복된 회차 제목을 지운다`() {
        val r = ChapterCleaner.cleanParagraphs(listOf("　　第29章 山门初 开之时") + prose, "第29章 山门初开之时", "青石问道")
        assertEquals(prose, r.paragraphs)
        assertEquals(1, r.removed)
    }

    @Test
    fun `전각 공백과 빈 줄을 정리한다`() {
        val r = ChapterCleaner.cleanParagraphs(listOf("　　첫 줄 ", "   ", "", "둘째   줄"), "", "")
        assertEquals(listOf("첫 줄", "둘째 줄"), r.paragraphs)
    }

    @Test
    fun `본장완 표시부터 뒤는 버린다`() {
        val r = ChapterCleaner.cleanParagraphs(prose + listOf("(本章完)", "求月票求推荐"), "", "")
        assertEquals(prose, r.paragraphs)
        val r2 = ChapterCleaner.cleanParagraphs(prose + listOf("（本章完）"), "", "")
        assertEquals(prose, r2.paragraphs)
    }

    @Test
    fun `사이트 홍보와 저작권 문구를 지운다`() {
        val junk = listOf(
            "请记住本站最新网址：www.example-novel.com",
            "手机用户请浏览 m.example.com 阅读",
            "本章未完，点击下一页继续阅读",
            "Copyright © 2024 某某小说网 版权所有",
            "上一章 目录 下一章",
            "天才一秒记住本站地址",
        )
        val mixed = listOf(prose[0], junk[0], prose[1], junk[1], junk[2], prose[2], prose[3], prose[4], junk[3], junk[4], junk[5], prose[5])
        val r = ChapterCleaner.cleanParagraphs(mixed, "", "")
        assertEquals(prose, r.paragraphs)
        assertEquals(junk.size, r.removed)
    }

    @Test
    fun `실제 사이트에서 본 긴 홍보 문구도 지운다`() {
        // 69shuba 회차 끝에 실제로 붙어 있던 줄(HTML 태그 글자가 그대로 섞여 있음)
        val promo = "网欢迎广大书友光临阅读，最新、最快、最火的连载作品尽在原创！</a><a>手机用户请到阅读。</a>"
        val trailer = "（未完待续。如果您喜欢这部作品，欢迎您来投推荐票、月票，您的支持，就是我最大的动力。手机用户请到阅读。）"
        val r = ChapterCleaner.cleanParagraphs(prose + trailer + promo, "", "")
        assertEquals(prose, r.paragraphs)
        assertEquals(2, r.removed)
    }

    @Test
    fun `긴 소설 문장은 홍보 단어가 들어 있어도 지우지 않는다`() {
        val long = "他在心里默默记住了这个网址一样复杂的口诀，又想起前世在网上看过的那些东西，什么最新网址、什么版权所有，如今想来恍如隔世，不由得轻轻叹了一口气。"
        assertFalse(ChapterCleaner.isBoilerplate(long))
        val r = ChapterCleaner.cleanParagraphs(prose + long, "", "")
        assertTrue(long in r.paragraphs)
    }

    @Test
    fun `뒷부분의 구분선 이후 작가 후기를 지운다`() {
        val r = ChapterCleaner.cleanParagraphs(prose + listOf("-----", "今天只有一更，明天补上。"), "", "")
        assertEquals(prose, r.paragraphs)
    }

    @Test
    fun `PS로 시작하는 작가 후기를 지운다`() {
        val r = ChapterCleaner.cleanParagraphs(prose + listOf("PS：求月票，求推荐票。", "感谢各位打赏。"), "", "")
        assertEquals(prose, r.paragraphs)
    }

    @Test
    fun `본문 중간의 장면 전환 구분선은 유지한다`() {
        val lines = prose.take(2) + "------" + prose.drop(2)
        val r = ChapterCleaner.cleanParagraphs(lines, "", "")
        assertEquals(lines, r.paragraphs)
    }

    @Test
    fun `구분선 뒤 내용이 길면 본문으로 보고 유지한다`() {
        val lines = prose.take(4) + "=====" + prose.drop(4)
        val r = ChapterCleaner.cleanParagraphs(lines, "", "")
        assertEquals(lines, r.paragraphs)
    }

    @Test
    fun `페이지 제목에서 작품명을 추정한다`() {
        assertEquals("青石问道", ChapterCleaner.workTitleFromDocTitle("青石问道-第29章 山门初开之时-69书吧", "第29章 山门初开之时"))
        assertEquals("雨巷", ChapterCleaner.workTitleFromDocTitle("第12章 夜雨_雨巷_某某小说网", "第12章 夜雨"))
        assertEquals("灯下旧事", ChapterCleaner.workTitleFromDocTitle("《灯下旧事》第一章 灯火 - 某某中文网", ""))
        assertEquals("", ChapterCleaner.workTitleFromDocTitle("제목 하나뿐", ""))
    }

    @Test
    fun `글자 수에 따라 상태를 정한다`() {
        val ok = ChapterCleaner.finish(RawExtraction(paragraphs = prose, chapterTitle = "第29章", workTitle = "青石问道", nextUrl = "https://a/2"))
        assertEquals(ExtractionStatus.OK, ok.status)
        assertTrue(ok.hasNext)
        assertFalse(ok.hasPrev)

        val blocked = ChapterCleaner.finish(RawExtraction(paragraphs = listOf("Verifying you are human."), blocked = true))
        assertEquals(ExtractionStatus.BLOCKED, blocked.status)

        val short = ChapterCleaner.finish(RawExtraction(paragraphs = listOf("짧은 글")))
        assertEquals(ExtractionStatus.TOO_SHORT, short.status)

        // 실제로 겪은 사례: 보안 확인 페이지의 안내문이 100자를 넘어도 성공으로 보면 안 된다.
        val notice = "This website uses a security service to protect against malicious bots. This page is displayed while the website verifies you are not a bot."
        val challenge = ChapterCleaner.finish(RawExtraction(paragraphs = listOf(notice), blocked = true))
        assertEquals(ExtractionStatus.BLOCKED, challenge.status)

        // 사람 확인 위젯만 있는 정상 페이지는 본문이 충분하면 성공, 본문이 없으면 보안 확인으로 본다.
        assertEquals(ExtractionStatus.OK, ChapterCleaner.finish(RawExtraction(paragraphs = prose, captchaWidget = true)).status)
        assertEquals(ExtractionStatus.BLOCKED, ChapterCleaner.finish(RawExtraction(paragraphs = listOf("확인 중"), captchaWidget = true)).status)
    }

    @Test
    fun `작품명이 없으면 페이지 제목에서 채운다`() {
        val c = ChapterCleaner.finish(RawExtraction(paragraphs = prose, chapterTitle = "第29章 山门初", docTitle = "青石问道-第29章 山门初-69书吧"))
        assertEquals("青石问道", c.workTitle)
    }
}
