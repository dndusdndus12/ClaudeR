package com.clauder.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 설정 내보내기와 가져오기 */
class SettingsBackupTest {

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
        prefetchNext = true,
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
        // 미리 번역 항목이 없던 예전 파일은 "꺼짐"으로 읽는다.
        assertFalse(withExtra.prefetchNext)
    }

    @Test
    fun `단가가 들어 있던 예전 파일도 읽는다`() {
        // 예전 버전은 모델별 단가를 함께 내보냈다. 지금은 그 항목을 쓰지 않으므로 무시하고 나머지를 읽는다.
        val old = """{"app":"Clauder","formatVersion":1,"globalGlossary":"甲=갑",""" +
            """"modelPrices":{"claude-sonnet-5":{"input":2.0,"output":10.0,"cacheRead":0.2,"cacheWrite5m":2.5,"cacheWrite1h":4.0}}}"""
        assertEquals("甲=갑", BackupCodec.decode(old).globalGlossary)
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
            "사이트 규칙 1개 · 수정한 프롬프트 · 전역 용어집 2줄 · 작품 설정 1개",
            backup.summary(),
        )
    }
}
