package com.clauder.reader.data

import com.clauder.reader.ai.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PricingTest {

    private val sonnet5 = Pricing.defaultFor("claude-sonnet-5")!!

    @Test
    fun `기본 단가표가 공식 가격표와 같다`() {
        // 입력, 출력, 캐시 읽기, 캐시 쓰기(5분), 캐시 쓰기(1시간) — 100만 토큰당 달러
        fun check(id: String, input: Double, output: Double, read: Double, write5m: Double, write1h: Double) {
            val p = Pricing.defaultFor(id) ?: return fail("$id 단가 없음")
            assertEquals("$id 입력", input, p.input, 1e-9)
            assertEquals("$id 출력", output, p.output, 1e-9)
            assertEquals("$id 캐시 읽기", read, p.cacheRead, 1e-9)
            assertEquals("$id 캐시 쓰기 5분", write5m, p.cacheWrite5m, 1e-9)
            assertEquals("$id 캐시 쓰기 1시간", write1h, p.cacheWrite1h, 1e-9)
        }
        check("claude-fable-5-2", 8.0, 40.0, 0.20, 10.0, 16.0)
        check("claude-fable-5-1", 10.0, 50.0, 0.25, 12.50, 20.0)
        check("claude-fable-5", 10.0, 50.0, 1.0, 12.50, 20.0)
        check("claude-opus-5-5", 4.0, 20.0, 0.20, 5.0, 8.0)
        check("claude-opus-5", 5.0, 25.0, 0.50, 6.25, 10.0)
        check("claude-opus-4-8", 5.0, 25.0, 0.50, 6.25, 10.0)
        check("claude-sonnet-5-5", 2.0, 10.0, 0.10, 2.50, 4.0)
        check("claude-sonnet-5", 2.0, 10.0, 0.20, 2.50, 4.0)
        check("claude-sonnet-4-6", 3.0, 15.0, 0.30, 3.75, 6.0)
        check("claude-haiku-5-5", 0.10, 0.50, 0.01, 0.125, 0.20)
        check("claude-haiku-4-5", 1.0, 5.0, 0.10, 1.25, 2.0)
    }

    @Test
    fun `날짜가 붙은 모델 id 는 같은 모델의 단가를 쓴다`() {
        assertEquals(Pricing.defaultFor("claude-haiku-4-5"), Pricing.defaultFor("claude-haiku-4-5-20251001"))
        assertEquals(Pricing.defaultFor("claude-opus-4-5"), Pricing.defaultFor("claude-opus-4-5-20251101"))
    }

    @Test
    fun `이름만 비슷한 모르는 모델에는 단가를 적용하지 않는다`() {
        assertNull(Pricing.defaultFor("claude-opus-5-7"))
        assertNull(Pricing.defaultFor("claude-sonnet-6"))
        assertNull(Pricing.defaultFor("gemini-pro"))
        // 긴 이름과 짧은 이름을 혼동하지 않는다.
        assertEquals(4.0, Pricing.defaultFor("claude-opus-5-5")!!.input, 1e-9)
        assertEquals(5.0, Pricing.defaultFor("claude-opus-5")!!.input, 1e-9)
    }

    @Test
    fun `직접 입력한 단가가 기본 단가보다 우선한다`() {
        val mine = ModelPrice(input = 1.0, output = 2.0, cacheRead = 0.1, cacheWrite5m = 1.5, cacheWrite1h = 3.0)
        assertEquals(mine, Pricing.priceFor("claude-sonnet-5", mapOf("claude-sonnet-5" to mine)))
        assertEquals(sonnet5, Pricing.priceFor("claude-sonnet-5", emptyMap()))
        // 기본 단가가 없는 모델도 직접 입력하면 계산할 수 있다.
        assertEquals(mine, Pricing.priceFor("claude-sonnet-6", mapOf("claude-sonnet-6" to mine)))
        assertNull(Pricing.priceFor("claude-sonnet-6", emptyMap()))
    }

    @Test
    fun `추정 비용은 토큰 종류별 단가를 곱해 더한다`() {
        // 실제로 본 회차 기록과 비슷한 값: 입력 2,758 · 출력 4,183 · 캐시 읽기 1,801 (Claude Sonnet 5)
        val usage = TokenUsage(inputTokens = 2758, outputTokens = 4183, cacheReadTokens = 1801)
        val expected = (2758 * 2.0 + 4183 * 10.0 + 1801 * 0.20) / 1_000_000
        assertEquals(expected, Pricing.cost(usage, sonnet5, "1h"), 1e-12)
        assertEquals(0.0477062, Pricing.cost(usage, sonnet5, "1h"), 1e-7)
    }

    @Test
    fun `캐시 쓰기 비용은 유지 시간에 따라 다르다`() {
        val usage = TokenUsage(cacheWriteTokens = 1_000_000)
        assertEquals(2.50, Pricing.cost(usage, sonnet5, "5m"), 1e-9)
        assertEquals(4.00, Pricing.cost(usage, sonnet5, "1h"), 1e-9)
    }

    @Test
    fun `금액과 단가를 읽기 좋게 적는다`() {
        assertEquals("$0.0477", Pricing.formatUsd(0.0477062))
        assertEquals("$12.35", Pricing.formatUsd(12.345))
        assertEquals("$0.0000", Pricing.formatUsd(0.0))
        assertEquals("2", Pricing.formatRate(2.0))
        assertEquals("0.125", Pricing.formatRate(0.125))
        assertEquals("12.5", Pricing.formatRate(12.50))
    }

    // ---------- 설정 내보내기와 가져오기 ----------

    private val backup = SettingsBackup(
        exportedAt = 1_800_000_000_000,
        siteRules = listOf(SiteRule(domain = "example.com", contentSelector = "#content", nextSelector = "a.next")),
        globalPrompt = "번역 지침\n둘째 줄",
        globalGlossary = "甲=갑\n乙=을",
        viewer = ViewerSettings(fontSize = 24, theme = ViewerSettings.THEME_DARK, pagedMode = true),
        aiProvider = "claude",
        aiModels = mapOf("claude" to "claude-sonnet-5"),
        aiEffort = "low",
        aiCacheTtl = "1h",
        modelPrices = mapOf("claude-sonnet-5" to sonnet5),
        works = listOf(WorkBackup(workKey = "example.com/book/1", title = "作品", promptMode = "custom", customPrompt = "전용", glossary = "丙=병")),
    )

    @Test
    fun `내보낸 설정을 그대로 다시 읽는다`() {
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
    }

    @Test
    fun `내보내기 파일에는 API 키가 들어갈 자리가 없다`() {
        val text = BackupCodec.encode(backup).lowercase()
        assertFalse(text.contains("apikey"))
        assertFalse(text.contains("api_key"))
        assertFalse(text.contains("sk-ant"))
        assertNotNull(text)
    }

    @Test
    fun `다른 프로그램의 파일이나 깨진 파일은 이유를 알려 주며 거절한다`() {
        // 마지막 두 개가 중요하다: 표시가 없는 아무 JSON 이 "빈 설정"으로 읽히면 기존 설정을 지워 버리게 된다.
        val badFiles = listOf(
            "",
            "이건 JSON 이 아님",
            """{"app":"OtherApp","formatVersion":1}""",
            """{"hello":"world"}""",
            """[1, 2, 3]""",
        )
        for (bad in badFiles) {
            try {
                BackupCodec.decode(bad)
                fail("거절해야 하는 파일을 받아들였다: $bad")
            } catch (e: BackupFormatException) {
                assertFalse(e.message.isNullOrBlank())
            }
        }
    }

    @Test
    fun `더 새로운 형식의 파일은 거절하고 모르는 항목은 무시한다`() {
        try {
            BackupCodec.decode("""{"app":"Clauder","formatVersion":99}""")
            fail("예외가 나야 한다")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains("최신 버전"))
        }
        val withExtra = BackupCodec.decode("""{"app":"Clauder","formatVersion":1,"futureField":123,"globalPrompt":"p"}""")
        assertEquals("p", withExtra.globalPrompt)
    }

    @Test
    fun `가져온 뷰어 설정이 범위를 벗어나 있으면 맞춘다`() {
        val decoded = BackupCodec.decode("""{"app":"Clauder","formatVersion":1,"viewer":{"fontSize":999,"theme":"neon"}}""")
        assertEquals(ViewerSettings.FONT_MAX, decoded.viewer.fontSize)
        assertEquals(ViewerSettings.THEME_LIGHT, decoded.viewer.theme)
    }

    @Test
    fun `가져오기 전에 보여 줄 요약을 만든다`() {
        assertEquals(
            "사이트 규칙 1개 · 수정한 프롬프트 · 전역 용어집 2줄 · 작품 설정 1개 · 직접 입력한 단가 1개",
            backup.summary(),
        )
    }
}
