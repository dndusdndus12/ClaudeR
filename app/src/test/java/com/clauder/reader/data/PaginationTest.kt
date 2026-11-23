package com.clauder.reader.data

import com.clauder.reader.data.Pagination.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTest {

    /** 높이가 같은 줄 [count]개. [blankAt] 은 빈 줄(문단 간격)의 번호. */
    private fun lines(count: Int, height: Float = 50f, blankAt: Set<Int> = emptySet()): Triple<FloatArray, FloatArray, BooleanArray> {
        val tops = FloatArray(count) { it * height }
        val bottoms = FloatArray(count) { (it + 1) * height }
        val blank = BooleanArray(count) { it in blankAt }
        return Triple(tops, bottoms, blank)
    }

    private fun paginate(count: Int, pageHeight: Float, overlap: Int, blankAt: Set<Int> = emptySet()): List<Page> {
        val (tops, bottoms, blank) = lines(count, blankAt = blankAt)
        return Pagination.paginate(tops, bottoms, blank, pageHeight, overlap)
    }

    @Test
    fun `한 페이지에는 온전히 들어가는 줄만 넣는다`() {
        // 줄 높이 50, 페이지 높이 230 → 4줄(200)까지 들어가고 5번째 줄은 잘리므로 다음 페이지로 넘긴다.
        val pages = paginate(count = 10, pageHeight = 230f, overlap = 0)
        assertEquals(listOf(Page(0, 4), Page(4, 8), Page(8, 10)), pages)
    }

    @Test
    fun `겹치는 줄 수만큼 앞 페이지의 끝을 다시 보여 준다`() {
        val pages = paginate(count = 10, pageHeight = 200f, overlap = 1)
        assertEquals(listOf(Page(0, 4), Page(3, 7), Page(6, 10)), pages)
    }

    @Test
    fun `문단 사이의 빈 줄은 겹치는 줄 수에 넣지 않는다`() {
        // 실제로 겪은 사례: 페이지가 빈 줄로 끝나면, 겹침 1줄이 그 빈 줄에 쓰여 글이 하나도 겹치지 않았다.
        // 줄 0~2 글, 3 빈 줄, 4~7 글. 첫 페이지는 0~3(빈 줄까지)이고, 다음 페이지는 글이 있는 2번 줄부터여야 한다.
        val pages = paginate(count = 8, pageHeight = 200f, overlap = 1, blankAt = setOf(3))
        assertEquals(Page(0, 4), pages[0])
        assertEquals(2, pages[1].firstLine)

        // 겹침 2줄이면 빈 줄을 건너뛰고 글 두 줄(1, 2번)을 겹친다.
        assertEquals(1, paginate(count = 8, pageHeight = 200f, overlap = 2, blankAt = setOf(3))[1].firstLine)
    }

    @Test
    fun `겹치는 줄이 페이지보다 많아도 한 줄씩은 나아간다`() {
        val pages = paginate(count = 6, pageHeight = 100f, overlap = 10)
        assertEquals(listOf(Page(0, 2), Page(1, 3), Page(2, 4), Page(3, 5), Page(4, 6)), pages)
    }

    @Test
    fun `페이지가 문단 사이의 빈 줄로 시작하지 않게 한다`() {
        // 4번 줄이 빈 줄: 두 번째 페이지는 5번 줄에서 시작해야 한다.
        val pages = paginate(count = 10, pageHeight = 200f, overlap = 0, blankAt = setOf(4))
        assertEquals(Page(0, 4), pages[0])
        assertEquals(5, pages[1].firstLine)
    }

    @Test
    fun `줄 높이가 제각각이어도 페이지 높이를 넘지 않는다`() {
        val tops = floatArrayOf(0f, 80f, 130f, 180f, 260f, 310f)
        val bottoms = floatArrayOf(80f, 130f, 180f, 260f, 310f, 360f)
        val pages = Pagination.paginate(tops, bottoms, BooleanArray(6), pageHeight = 200f, overlapLines = 0)
        assertEquals(listOf(Page(0, 3), Page(3, 6)), pages)
        pages.forEach { page ->
            assertTrue(bottoms[page.endLine - 1] - tops[page.firstLine] <= 200f)
        }
    }

    @Test
    fun `한 줄이 페이지보다 커도 멈추지 않고 넘어간다`() {
        val pages = paginate(count = 3, pageHeight = 10f, overlap = 0)
        assertEquals(listOf(Page(0, 1), Page(1, 2), Page(2, 3)), pages)
    }

    @Test
    fun `글이 없으면 페이지도 없다`() {
        assertTrue(Pagination.paginate(FloatArray(0), FloatArray(0), BooleanArray(0), 200f, 3).isEmpty())
    }

    @Test
    fun `어떤 줄을 읽으려면 그 줄 이전에서 시작하는 마지막 페이지를 연다`() {
        val pages = listOf(Page(0, 4), Page(3, 7), Page(6, 10))
        assertEquals(0, Pagination.pageOfLine(pages, 0))
        assertEquals(0, Pagination.pageOfLine(pages, 2))
        // 3번 줄은 첫 페이지의 끝이자 둘째 페이지의 시작인데, 거기서부터 읽으려면 둘째 페이지다.
        assertEquals(1, Pagination.pageOfLine(pages, 3))
        assertEquals(2, Pagination.pageOfLine(pages, 9))
        assertEquals(2, Pagination.pageOfLine(pages, 999))
        assertEquals(0, Pagination.pageOfLine(emptyList(), 5))
    }

    @Test
    fun `읽던 위치는 스크롤 모드와 페이지 모드의 값을 서로 구분해 저장한다`() {
        // 페이지 모드: 문단 안의 글자 위치를 음수로 바꿔 저장하고 그대로 되읽는다.
        assertEquals(0, ReadingPosition.chars(ReadingPosition.encodeChars(0)))
        assertEquals(137, ReadingPosition.chars(ReadingPosition.encodeChars(137)))
        assertTrue(ReadingPosition.encodeChars(0) < 0)
        // 스크롤 모드에서 저장한 값(픽셀)을 페이지 모드가 읽으면 문단의 처음으로 본다.
        assertEquals(0, ReadingPosition.chars(230))
        // 페이지 모드에서 저장한 값을 스크롤 모드가 읽으면 항목의 처음으로 본다.
        assertEquals(0, ReadingPosition.pixels(ReadingPosition.encodeChars(137)))
        assertEquals(230, ReadingPosition.pixels(230))
    }
}
