package com.clauder.reader.data

import com.clauder.reader.browser.UrlInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteRuleTest {

    @Test
    fun `도메인 입력을 정리한다`() {
        assertEquals("69shuba.com", SiteRule.normalizeDomain("https://www.69shuba.com/txt/21160/12097699"))
        assertEquals("69shuba.com", SiteRule.normalizeDomain(" WWW.69shuba.com "))
        assertEquals("m.example.org", SiteRule.normalizeDomain("m.example.org:8080/a?b=1"))
        assertEquals("", SiteRule.normalizeDomain("   "))
    }

    @Test
    fun `호스트에 맞는 규칙을 고르고 더 구체적인 도메인을 우선한다`() {
        val general = SiteRule(domain = "example.com", contentSelector = "#a")
        val mobile = SiteRule(domain = "m.example.com", contentSelector = "#b")
        val other = SiteRule(domain = "69shuba.com", contentSelector = "#c")
        val rules = listOf(general, mobile, other)

        assertEquals(general, SiteRule.match(rules, "www.example.com"))
        assertEquals(general, SiteRule.match(rules, "read.example.com"))
        assertEquals(mobile, SiteRule.match(rules, "m.example.com"))
        assertEquals(other, SiteRule.match(rules, "www.69shuba.com"))
        assertNull(SiteRule.match(rules, "notexample.com"))
        assertNull(SiteRule.match(rules, ""))
    }

    @Test
    fun `스크립트 인자는 JSON 문자열로 안전하게 만든다`() {
        val arg = SiteRule(domain = "a.com", contentSelector = "div[data-x=\"y\"] > p", nextSelector = " a.next ").toScriptArg()
        assertTrue(arg.contains("\"content\":\"div[data-x=\\\"y\\\"] > p\""))
        assertTrue(arg.contains("\"next\":\"a.next\""))
    }

    @Test
    fun `주소창 입력을 주소나 검색으로 바꾼다`() {
        assertEquals("https://www.69shuba.com/txt/1/2", UrlInput.toUrl("https://www.69shuba.com/txt/1/2"))
        assertEquals("https://69shuba.com", UrlInput.toUrl(" 69shuba.com "))
        assertEquals("https://example.com/a/b?c=1", UrlInput.toUrl("example.com/a/b?c=1"))
        assertEquals("https://www.google.com/search?q=%E9%9D%92%E7%9F%B3%E9%97%AE%E9%81%93", UrlInput.toUrl("青石问道"))
        assertEquals("https://www.google.com/search?q=hello+world", UrlInput.toUrl("hello world"))
        assertNull(UrlInput.toUrl("   "))
    }
}
