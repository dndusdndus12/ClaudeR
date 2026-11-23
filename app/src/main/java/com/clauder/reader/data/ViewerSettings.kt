package com.clauder.reader.data

import kotlinx.serialization.Serializable

/** 뷰어(읽기 화면) 설정. 안드로이드에 의존하지 않아 단위 테스트가 가능하다. */
@Serializable
data class ViewerSettings(
    /** 글꼴 크기(sp) */
    val fontSize: Int = 19,
    /** 줄 간격: 글꼴 크기의 몇 배인지를 10배 한 값(17 = 1.7배) */
    val lineSpacing: Int = 17,
    /** 좌우 여백(dp) */
    val margin: Int = 22,
    /** [THEME_LIGHT], [THEME_DARK], [THEME_SEPIA] */
    val theme: String = THEME_LIGHT,
    /** 볼륨 키(블루투스 리모컨 포함)로 한 화면씩 넘기기 */
    val volumeKeyPaging: Boolean = true,
    /** 회차 끝에서 "아래" 키를 한 번 더 누르면 다음 화로 넘어가 번역을 시작 */
    val volumeKeyNextChapter: Boolean = false,
    /** 화면 위쪽/아래쪽을 탭하면 한 화면씩 넘기기(가운데 탭은 항상 막대 숨김/표시) */
    val tapPaging: Boolean = false,
    /**
     * 블루투스 리모컨이 보내는 "밀기" 터치로 한 화면씩 넘기기.
     * 키 신호 대신 화면을 미는 동작을 흉내 내는 리모컨(예: R1)을 위한 설정이다.
     */
    val remoteSwipePaging: Boolean = true,
    /** 리모컨의 넘김 방향을 반대로(다음 버튼이 이전으로 동작할 때 켠다) */
    val remoteSwipeReversed: Boolean = false,
) {
    /** 저장된 값이 범위를 벗어나 있어도 화면이 깨지지 않게 맞춘다. */
    fun sanitized() = copy(
        fontSize = fontSize.coerceIn(FONT_MIN, FONT_MAX),
        lineSpacing = lineSpacing.coerceIn(SPACING_MIN, SPACING_MAX),
        margin = margin.coerceIn(MARGIN_MIN, MARGIN_MAX),
        theme = if (theme in THEMES) theme else THEME_LIGHT,
    )

    /** 줄 높이(sp) */
    val lineHeight: Float get() = fontSize * lineSpacing / 10f

    companion object {
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SEPIA = "sepia"
        val THEMES = listOf(THEME_LIGHT, THEME_DARK, THEME_SEPIA)

        const val FONT_MIN = 12
        const val FONT_MAX = 40
        const val SPACING_MIN = 11
        const val SPACING_MAX = 26
        const val MARGIN_MIN = 4
        const val MARGIN_MAX = 48
    }
}

/** 뷰어에서 화면을 가르는 탭 위치의 뜻 */
enum class TapZone { PREVIOUS_PAGE, TOGGLE_BARS, NEXT_PAGE }

object ViewerTaps {
    /**
     * 리모컨이 보낸 밀기 동작을 페이지 넘김 방향으로 바꾼다.
     * 왼쪽이나 위로 밀면 다음 화면(책장을 넘기거나 글을 위로 올리는 방향), 오른쪽이나 아래로 밀면 이전 화면이다.
     *
     * @param dx, dy 터치를 뗀 지점에서 누른 지점을 뺀 값(px)
     * @param minDistance 이보다 짧게 움직였으면 밀기가 아니라 탭으로 본다
     * @return true 다음 화면, false 이전 화면, null 밀기가 아님
     */
    fun remoteSwipeForward(dx: Float, dy: Float, minDistance: Float, reversed: Boolean): Boolean? {
        val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
        val moved = if (horizontal) dx else dy
        if (kotlin.math.abs(moved) < minDistance) return null
        val forward = moved < 0
        return if (reversed) !forward else forward
    }

    /**
     * 탭한 높이가 화면의 어느 구역인지 정한다.
     * 탭으로 넘기기가 꺼져 있으면 어디를 눌러도 막대 숨김/표시이고,
     * 켜져 있으면 위 1/4 은 이전 화면, 아래 1/4 은 다음 화면, 가운데는 막대 숨김/표시다.
     */
    fun zoneOf(y: Float, height: Float, tapPaging: Boolean): TapZone {
        if (!tapPaging || height <= 0f) return TapZone.TOGGLE_BARS
        val ratio = y / height
        return when {
            ratio < 0.25f -> TapZone.PREVIOUS_PAGE
            ratio > 0.75f -> TapZone.NEXT_PAGE
            else -> TapZone.TOGGLE_BARS
        }
    }

    /**
     * 한 번에 넘길 거리(px). 화면에 보이는 높이에서 한 줄을 뺀 만큼 넘겨,
     * 마지막 줄이 다음 화면의 첫 줄로 다시 보이게 한다(읽던 자리를 놓치지 않도록).
     */
    fun pageDistance(viewportHeight: Float, coveredTop: Float, coveredBottom: Float, lineHeight: Float): Float {
        val visible = (viewportHeight - coveredTop - coveredBottom).coerceAtLeast(0f)
        return (visible - lineHeight).coerceAtLeast(visible * 0.5f)
    }
}
