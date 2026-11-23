package com.clauder.reader.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.data.Pagination
import com.clauder.reader.data.ReadingPosition
import com.clauder.reader.data.ViewerSettings
import com.clauder.reader.data.ViewerTaps
import com.clauder.reader.translate.PromptBuilder
import kotlinx.coroutines.delay

/** 페이지 모드의 현재 상태. 뷰어가 키 입력으로 페이지를 넘길 때 이것을 통해 조작한다. */
@Stable
class PagedState {
    var page by mutableIntStateOf(0)
        internal set
    var pageCount by mutableIntStateOf(1)
        internal set

    /** 실제로 페이지를 옮기는 동작. 화면이 그려진 뒤 [PagedContent] 가 채워 넣는다. */
    internal var turnImpl: (forward: Boolean) -> Unit = {}

    val canGoForward: Boolean get() = page < pageCount - 1
    val canGoBackward: Boolean get() = page > 0

    fun turn(forward: Boolean) = turnImpl(forward)
}

/** 페이지로 나눌 글 전체와, 항목(0 = 제목, 1 부터 문단)마다의 시작 글자 위치 */
private class PageDocument(val text: AnnotatedString, val itemStarts: IntArray) {
    /** 글자 위치가 속한 항목 번호 */
    fun itemOf(char: Int): Int {
        var item = 0
        for (i in itemStarts.indices) if (itemStarts[i] <= char) item = i else break
        return item
    }
}

/** 문단 사이 간격으로 쓰는, 눈에 보이지 않는 글자 */
private const val GAP_CHAR = '​'

private fun buildDocument(content: PromptBuilder.Translated, settings: ViewerSettings): PageDocument {
    val starts = ArrayList<Int>(content.paragraphs.size + 1)
    val builder = AnnotatedString.Builder()
    // 문단 사이의 간격: 스크롤 모드와 같은 크기의 빈 줄을 하나 둔다.
    // 한 줄짜리 문단은 기본 설정에서 줄 높이가 글꼴 크기로 깎이므로(여기서는 거의 0), 깎지 않도록 지정한다.
    val gapStyle = ParagraphStyle(
        lineHeight = (settings.fontSize * 0.9f).sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    fun gap() = builder.withStyle(gapStyle) {
        withStyle(SpanStyle(fontSize = 1.sp)) { append(GAP_CHAR) }
    }
    starts += builder.length
    if (content.title.isNotBlank()) {
        val titleSize = settings.fontSize + 3
        builder.withStyle(ParagraphStyle(lineHeight = (titleSize * 1.45f).sp)) {
            withStyle(SpanStyle(fontSize = titleSize.sp, fontWeight = FontWeight.Bold)) { append(content.title) }
        }
        gap()
    }
    content.paragraphs.forEach { paragraph ->
        starts += builder.length
        builder.withStyle(ParagraphStyle(lineHeight = settings.lineHeight.sp)) { append(paragraph) }
        gap()
    }
    return PageDocument(builder.toAnnotatedString(), starts.toIntArray())
}

private fun pagesOf(layout: TextLayoutResult, text: String, pageHeight: Float, overlapLines: Int): List<Pagination.Page> {
    val count = if (text.isEmpty()) 0 else layout.lineCount
    val tops = FloatArray(count) { layout.getLineTop(it) }
    val bottoms = FloatArray(count) { layout.getLineBottom(it) }
    val blank = BooleanArray(count) { line ->
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line).coerceAtMost(text.length)
        (start until end).all { text[it].isWhitespace() || text[it] == GAP_CHAR }
    }
    return Pagination.paginate(tops, bottoms, blank, pageHeight, overlapLines)
}

/**
 * 페이지 넘김 전용 모드의 본문. 스크롤하지 않고, 온전한 줄만 담은 페이지를 한 장씩 보여 준다.
 *
 * @param topInset, bottomInset 상태 표시줄과 시스템 버튼 때문에 비워 둘 높이
 * @param restoreKey 값이 바뀌면 [startIndex], [startOffset] 위치의 페이지로 이동한다(회차를 새로 열었을 때)
 * @param onPosition 보고 있는 페이지의 첫 줄 위치를 알린다(항목 번호, 저장용 어긋남 값)
 * @param onSwipe 화면을 밀어서 넘길 때 호출된다. 인자는 "앞으로 넘기는지".
 */
@Composable
fun PagedContent(
    content: PromptBuilder.Translated,
    streaming: Boolean,
    settings: ViewerSettings,
    palette: ViewerPalette,
    topInset: Dp,
    bottomInset: Dp,
    state: PagedState,
    restoreKey: Int,
    startIndex: Int,
    startOffset: Int,
    statusText: String?,
    onPosition: (index: Int, offset: Int) -> Unit,
    onSwipe: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // 번역문이 흘러들어오는 동안 글자 하나마다 전체를 다시 나누면 버벅이므로, 잠깐씩 모아서 반영한다.
    val latestContent by rememberUpdatedState(content)
    var sampled by remember { mutableStateOf(content) }
    LaunchedEffect(streaming) {
        while (streaming) {
            if (sampled != latestContent) sampled = latestContent
            delay(250)
        }
    }
    val shown = if (streaming) sampled else content

    BoxWithConstraints(modifier.fillMaxSize()) {
        val marginPx = with(density) { settings.margin.dp.roundToPx() }
        val topPx = with(density) { (topInset + 10.dp).roundToPx() }
        // 아래쪽에는 페이지 번호를 적을 자리를 남긴다.
        val bottomPx = with(density) { (bottomInset + 34.dp).roundToPx() }
        val textWidth = (constraints.maxWidth - marginPx * 2).coerceAtLeast(1)
        val pageHeight = (constraints.maxHeight - topPx - bottomPx).coerceAtLeast(1).toFloat()

        val document = remember(shown, settings.fontSize, settings.lineSpacing) { buildDocument(shown, settings) }
        val layout = remember(document, textWidth, palette.text) {
            measurer.measure(
                text = document.text,
                style = TextStyle(color = palette.text, fontSize = settings.fontSize.sp),
                constraints = Constraints(maxWidth = textWidth),
            )
        }
        val pages = remember(layout, pageHeight, settings.pageOverlapLines) {
            pagesOf(layout, document.text.text, pageHeight, settings.pageOverlapLines)
        }

        // 지금 보고 있는 자리를 글자 위치로 기억해 둔다. 글자 크기를 바꾸거나 번역문이 더 들어와
        // 페이지가 다시 나뉘어도, 같은 글이 있는 페이지를 계속 보여 주기 위함이다.
        var anchorChar by remember { mutableIntStateOf(0) }
        var restoredKey by remember { mutableIntStateOf(Int.MIN_VALUE) }

        fun pageOfChar(char: Int): Int {
            if (pages.isEmpty()) return 0
            val safe = char.coerceIn(0, (document.text.length - 1).coerceAtLeast(0))
            return Pagination.pageOfLine(pages, layout.getLineForOffset(safe))
        }

        // 회차를 새로 열었을 때: 저장된 읽던 위치의 페이지로 간다.
        if (restoredKey != restoreKey) {
            val start = document.itemStarts.getOrElse(startIndex) { 0 }
            anchorChar = start + ReadingPosition.chars(startOffset)
            restoredKey = restoreKey
        }

        val pageIndex = pageOfChar(anchorChar)
        // 화면을 다 그린 뒤, 뷰어가 키 입력으로 넘길 수 있도록 현재 상태와 넘김 동작을 알려 둔다.
        SideEffect {
            state.page = pageIndex
            state.pageCount = pages.size.coerceAtLeast(1)
            state.turnImpl = { forward ->
                val target = pageIndex + if (forward) 1 else -1
                pages.getOrNull(target)?.let { page -> anchorChar = layout.getLineStart(page.firstLine) }
            }
        }
        val latestOnSwipe by rememberUpdatedState(onSwipe)

        // 읽던 위치 알림: 이 페이지 첫 줄이 어느 문단의 몇 번째 글자인지
        val latestOnPosition by rememberUpdatedState(onPosition)
        LaunchedEffect(anchorChar, pages) {
            val page = pages.getOrNull(pageIndex) ?: return@LaunchedEffect
            val char = layout.getLineStart(page.firstLine)
            val item = document.itemOf(char)
            latestOnPosition(item, ReadingPosition.encodeChars(char - document.itemStarts[item]))
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 스크롤이 없으므로 화면을 밀어서도 넘길 수 있게 한다(왼쪽이나 위로 밀면 다음 페이지).
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            ViewerTaps.remoteSwipeForward(dx, dy, 48.dp.toPx(), reversed = false)?.let { latestOnSwipe(it) }
                        },
                    ) { change, drag ->
                        change.consume()
                        dx += drag.x
                        dy += drag.y
                    }
                },
        ) {
            val page = pages.getOrNull(pageIndex) ?: return@Canvas
            val top = layout.getLineTop(page.firstLine)
            val bottom = layout.getLineBottom(page.endLine - 1)
            // 이 페이지에 속한 줄들의 영역만 그린다. 그 밖의 줄은 한 글자도 비치지 않는다.
            clipRect(left = 0f, top = topPx.toFloat(), right = size.width, bottom = topPx + (bottom - top)) {
                drawText(layout, topLeft = Offset(marginPx.toFloat(), topPx - top))
            }
        }

        // 아래쪽: 진행 상태(번역 중 등)가 있으면 그것을, 없으면 페이지 번호를 보여 준다.
        Text(
            text = statusText ?: "${pageIndex + 1} / ${pages.size.coerceAtLeast(1)}",
            fontSize = 12.sp,
            color = palette.secondary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 10.dp),
        )
    }
}
