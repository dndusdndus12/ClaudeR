package com.clauder.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSettingsTest {

    @Test
    fun `범위를 벗어난 저장값은 범위 안으로 맞춘다`() {
        val s = ViewerSettings(fontSize = 999, lineSpacing = 1, margin = -5, theme = "무지개").sanitized()
        assertEquals(ViewerSettings.FONT_MAX, s.fontSize)
        assertEquals(ViewerSettings.SPACING_MIN, s.lineSpacing)
        assertEquals(ViewerSettings.MARGIN_MIN, s.margin)
        assertEquals(ViewerSettings.THEME_LIGHT, s.theme)
        assertEquals(ViewerSettings(), ViewerSettings().sanitized())
    }

    @Test
    fun `줄 높이는 글자 크기에 줄 간격 배수를 곱한 값이다`() {
        assertEquals(34f, ViewerSettings(fontSize = 20, lineSpacing = 17).lineHeight, 0.001f)
    }

    @Test
    fun `탭으로 넘기기가 꺼져 있으면 어디를 눌러도 막대 숨김이다`() {
        for (y in listOf(0f, 100f, 1000f, 1999f)) {
            assertEquals(TapZone.TOGGLE_BARS, ViewerTaps.zoneOf(y, 2000f, tapPaging = false))
        }
    }

    @Test
    fun `탭으로 넘기기가 켜져 있으면 위는 이전, 아래는 다음, 가운데는 막대 숨김이다`() {
        assertEquals(TapZone.PREVIOUS_PAGE, ViewerTaps.zoneOf(100f, 2000f, tapPaging = true))
        assertEquals(TapZone.TOGGLE_BARS, ViewerTaps.zoneOf(1000f, 2000f, tapPaging = true))
        assertEquals(TapZone.NEXT_PAGE, ViewerTaps.zoneOf(1900f, 2000f, tapPaging = true))
        // 경계: 정확히 1/4, 3/4 지점은 가운데로 본다.
        assertEquals(TapZone.TOGGLE_BARS, ViewerTaps.zoneOf(500f, 2000f, tapPaging = true))
        assertEquals(TapZone.TOGGLE_BARS, ViewerTaps.zoneOf(1500f, 2000f, tapPaging = true))
    }

    @Test
    fun `한 번에 넘기는 거리는 보이는 높이에서 한 줄을 뺀 만큼이다`() {
        // 화면 2000, 위 100과 아래 150이 가려짐 → 보이는 높이 1750, 한 줄 60
        assertEquals(1690f, ViewerTaps.pageDistance(2000f, 100f, 150f, 60f), 0.001f)
        // 막대를 숨기면 가려지는 부분이 줄어 더 많이 넘긴다.
        assertTrue(ViewerTaps.pageDistance(2000f, 80f, 0f, 60f) > ViewerTaps.pageDistance(2000f, 240f, 250f, 60f))
    }

    @Test
    fun `리모컨의 밀기 동작을 넘김 방향으로 바꾼다`() {
        val min = 100f
        // R1 리모컨에서 실제로 기록한 값: 한 버튼은 +373px, 다른 버튼은 -372px 가로 이동(세로 이동 없음)
        assertEquals(false, ViewerTaps.remoteSwipeForward(373f, 0f, min, reversed = false))
        assertEquals(true, ViewerTaps.remoteSwipeForward(-372f, 0f, min, reversed = false))
        // 방향 반대로 설정
        assertEquals(true, ViewerTaps.remoteSwipeForward(373f, 0f, min, reversed = true))
        assertEquals(false, ViewerTaps.remoteSwipeForward(-372f, 0f, min, reversed = true))
        // 위아래로 미는 리모컨: 위로 밀면 다음, 아래로 밀면 이전
        assertEquals(true, ViewerTaps.remoteSwipeForward(10f, -500f, min, reversed = false))
        assertEquals(false, ViewerTaps.remoteSwipeForward(-10f, 500f, min, reversed = false))
        // 거의 움직이지 않은 터치는 밀기가 아니다.
        assertEquals(null, ViewerTaps.remoteSwipeForward(30f, -20f, min, reversed = false))
    }

    @Test
    fun `줄이 화면보다 클 만큼 글자가 커도 최소 절반은 넘긴다`() {
        assertEquals(500f, ViewerTaps.pageDistance(1000f, 0f, 0f, 900f), 0.001f)
        assertEquals(0f, ViewerTaps.pageDistance(100f, 80f, 80f, 20f), 0.001f)
    }
}
