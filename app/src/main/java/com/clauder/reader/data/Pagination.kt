package com.clauder.reader.data

/**
 * 페이지 넘김 전용 모드에서 글을 화면 단위로 나눈다.
 * 한 페이지에는 온전한 줄만 들어가므로 화면 끝에서 글자가 잘리지 않는다.
 * 안드로이드에 의존하지 않아 단위 테스트가 가능하다.
 */
object Pagination {

    /** 한 페이지에 보이는 줄의 범위: [firstLine]부터 [endLine] 바로 앞까지 */
    data class Page(val firstLine: Int, val endLine: Int)

    /**
     * @param lineTops, lineBottoms 줄마다의 위쪽/아래쪽 위치(px, 글 전체 기준)
     * @param blank 내용이 없는 줄(문단 사이 간격)인지
     * @param pageHeight 한 페이지에 글을 그릴 수 있는 높이(px)
     * @param overlapLines 다음 페이지로 넘어갈 때 앞 페이지의 끝에서 다시 보여 줄 줄 수
     */
    fun paginate(
        lineTops: FloatArray,
        lineBottoms: FloatArray,
        blank: BooleanArray,
        pageHeight: Float,
        overlapLines: Int,
    ): List<Page> {
        val count = lineTops.size
        val pages = mutableListOf<Page>()
        var start = 0
        while (start < count) {
            // 페이지가 빈 줄(문단 간격)로 시작하지 않게 건너뛴다.
            while (start < count - 1 && blank[start]) start++
            val top = lineTops[start]
            var end = start
            while (end < count && lineBottoms[end] - top <= pageHeight) end++
            // 한 줄이 페이지보다 큰 극단적인 경우에도 한 줄씩은 넘어가야 한다.
            if (end == start) end = start + 1
            pages += Page(start, end)
            if (end >= count) break
            // 앞 페이지의 끝에서 글이 있는 줄을 겹칠 만큼 거슬러 올라가 다음 페이지를 시작한다.
            // 문단 사이의 빈 줄은 겹치는 줄 수에 넣지 않으며, 반드시 한 줄 이상은 앞으로 나아간다.
            var next = end
            var remaining = overlapLines.coerceAtLeast(0)
            while (remaining > 0 && next > start + 1) {
                next--
                if (!blank[next]) remaining--
            }
            start = maxOf(start + 1, next)
        }
        return pages
    }

    /** 그 줄부터 읽기 시작하려면 몇 번째 페이지를 열어야 하는지: 그 줄 이전에서 시작하는 마지막 페이지. */
    fun pageOfLine(pages: List<Page>, line: Int): Int {
        var result = 0
        for (i in pages.indices) {
            if (pages[i].firstLine <= line) result = i else break
        }
        return result
    }
}

/**
 * 읽던 위치의 저장 형식.
 * 위치는 (항목 번호, 어긋남) 두 수로 저장한다. 항목 번호는 0 이 제목, 1 부터가 문단이다.
 * 어긋남은 스크롤 모드에서는 그 항목 안에서의 픽셀(0 이상), 페이지 모드에서는 그 문단 안에서의 글자 위치인데,
 * 둘을 구분하려고 글자 위치는 음수로 바꿔 저장한다.
 */
object ReadingPosition {
    fun encodeChars(chars: Int): Int = -(chars.coerceAtLeast(0) + 1)

    /** 페이지 모드에서 쓸 글자 위치. 스크롤 모드에서 저장한 값이면 문단의 처음으로 본다. */
    fun chars(offset: Int): Int = if (offset < 0) -offset - 1 else 0

    /** 스크롤 모드에서 쓸 픽셀 위치. 페이지 모드에서 저장한 값이면 항목의 처음으로 본다. */
    fun pixels(offset: Int): Int = offset.coerceAtLeast(0)
}
