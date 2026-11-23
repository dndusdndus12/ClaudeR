package com.clauder.reader.extract

/**
 * extractor.js 가 뽑은 줄들에서 본문이 아닌 것(제목 반복, 사이트 홍보 문구, 저작권 문구,
 * 작가 후기 등)을 걷어낸다. 안드로이드에 의존하지 않아 단위 테스트가 가능하다.
 */
object ChapterCleaner {

    /** 이 글자 수보다 짧으면 본문 추출 실패로 본다. */
    const val MIN_CHARS = 100

    /** 홍보/안내 문구는 짧은 줄일 때만 지운다(소설 문장 속 단어를 잘못 지우지 않도록). */
    private const val BOILERPLATE_MAX_LEN = 60

    private val BOILERPLATE = listOf(
        Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE),
        Regex("""[a-z0-9-]+\.(com|net|org|cc|la|info|xyz|top|tw|cn|me|vip|app)\b""", RegexOption.IGNORE_CASE),
        Regex("""最新(网址|地址|域名|網址)"""),
        Regex("""请?(收藏|记住|牢记)(本站|本书|本網|网址|地址|域名)"""),
        Regex("""(一秒|秒)记住"""),
        Regex("""手机(用户|版|端)?请?(访问|浏览|阅读)"""),
        Regex("""本章未完"""),
        Regex("""(点击|點擊)下一[页頁]"""),
        Regex("""(继续|繼續)(阅读|閱讀).{0,6}(下一[页頁章])"""),
        Regex("""(加入书签|投推荐票|返回目录|返回书页|章节报错|加入书架)"""),
        Regex("""(版权所有|版權所有|all rights reserved|copyright|©)""", RegexOption.IGNORE_CASE),
        Regex("""(无错|無錯|无弹窗|無彈窗|最快更新|更新最快|全文免费阅读|免费阅读全文)"""),
        Regex("""(首发|首發|转载|轉載).{0,12}(小说|小說|书吧|文学|阅读网|中文网)"""),
        Regex("""^(上一[章页頁节]|下一[章页頁节]|目录|目錄|书签|書籤|\s|[|｜·])+$"""),
    )

    /** 소설 문장에 나올 수 없는 문구. 줄 길이와 무관하게 지운다. */
    private val BOILERPLATE_STRONG = listOf(
        Regex("""欢迎广大书友|歡迎廣大書友"""),
        Regex("""最新、最快、最火"""),
        Regex("""手机用户请到|手機用戶請到"""),
        Regex("""^[（(【\[]?\s*未完待[续續]"""),
        Regex("""您的支持，就是我最大的动力"""),
        Regex("""</?(a|p|div|span|br|script)\b[^>]*>""", RegexOption.IGNORE_CASE),
    )

    /** "(本章完)" 같은 회차 끝 표시. 이 줄부터 뒤는 모두 버린다. */
    private val CHAPTER_END = Regex("""^[（(【\[〔]?\s*本章完\s*[）)】\]〕]?[。.]?$""")

    /** 작가 후기 시작으로 보이는 줄. 회차 뒷부분에 있을 때만 적용한다. */
    private val AUTHOR_NOTE = Regex(
        """^[（(【\[]?\s*(ps|p\.s\.?|作者的话|作者有话说|作者有話說|作者有话要说|作者说|作者感言|作者留言|作者按|题外话|題外話)\s*[)）】\]]?\s*[:：]?""",
        RegexOption.IGNORE_CASE,
    )

    /** 구분선: 기호만으로 이루어진 줄 */
    private val DIVIDER = Regex("""^[-=—–_*~·•…＊﹡\s]{3,}$""")

    fun normalizeLine(s: String): String =
        s.replace(Regex("[\\u00a0\\u3000\\u2002\\u2003\\u200b\\ufeff]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun compact(s: String) = s.filterNot { it.isWhitespace() }

    fun isBoilerplate(line: String): Boolean =
        BOILERPLATE_STRONG.any { it.containsMatchIn(line) } ||
            (line.length <= BOILERPLATE_MAX_LEN && BOILERPLATE.any { it.containsMatchIn(line) })

    fun isDivider(line: String): Boolean = DIVIDER.matches(line)

    data class Cleaned(val paragraphs: List<String>, val removed: Int)

    fun cleanParagraphs(raw: List<String>, chapterTitle: String, workTitle: String): Cleaned {
        var lines = raw.map(::normalizeLine).filter { it.isNotEmpty() }
        val before = lines.size

        // 1) 본문 맨 앞에 반복된 회차 제목/작품명 제거
        val titles = listOf(chapterTitle, workTitle).map(::compact).filter { it.isNotEmpty() }
        var head = 0
        while (head < lines.size && head < 2 && compact(lines[head]) in titles) head++
        lines = lines.drop(head)

        // 2) "(本章完)" 이후 제거
        val endIdx = lines.indexOfFirst { CHAPTER_END.matches(it) }
        if (endIdx >= 0) lines = lines.take(endIdx)

        // 3) 뒷부분의 작가 후기 제거: 구분선 또는 "PS:" 류 표시부터 끝까지
        lines = cutAuthorNote(lines)

        // 4) 홍보/안내/저작권 문구 제거
        lines = lines.filterNot(::isBoilerplate)

        // 5) 앞뒤에 남은 구분선 제거
        lines = lines.dropWhile(::isDivider).dropLastWhile(::isDivider)

        return Cleaned(lines, before - lines.size)
    }

    private fun cutAuthorNote(lines: List<String>): List<String> {
        if (lines.size < 4) return lines
        val total = lines.sumOf { it.length }.coerceAtLeast(1)
        val tailStart = (lines.size * 0.6).toInt()
        for (i in tailStart until lines.size) {
            val line = lines[i]
            val restChars = lines.subList(i, lines.size).sumOf { it.length }
            val restIsSmall = restChars * 4 <= total
            if (!restIsSmall) continue
            val dividerThenText = isDivider(line) && i < lines.lastIndex
            if (dividerThenText || AUTHOR_NOTE.containsMatchIn(line)) return lines.take(i)
        }
        return lines
    }

    /**
     * 페이지 제목(<title>)에서 작품명을 추정한다. 예: "作品名-第1章 标题-站名" → "作品名".
     * 확신할 수 없으면 빈 문자열.
     */
    fun workTitleFromDocTitle(docTitle: String, chapterTitle: String): String {
        Regex("《([^》]{1,40})》").find(docTitle)?.let { return it.groupValues[1].trim() }
        val chapter = compact(chapterTitle)
        val parts = docTitle.split(Regex("""\s*[_|｜–—]\s*|\s*-\s*|\s+·\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return ""
        val candidates = parts.filterNot { part ->
            val c = compact(part)
            (chapter.isNotEmpty() && (c.contains(chapter) || chapter.contains(c))) ||
                CHAPTER_LIKE.containsMatchIn(part) ||
                SITE_LIKE.containsMatchIn(part)
        }
        return candidates.firstOrNull().orEmpty()
    }

    private val CHAPTER_LIKE = Regex("""^第?\s*[0-9零一二三四五六七八九十百千两]+\s*[章节節回话話卷]|^\d+\s*화|^제\s*\d+\s*[화장편]|^chapter\s*\d+""", RegexOption.IGNORE_CASE)
    private val SITE_LIKE = Regex("""(小说|小說|书吧|書吧|文学|文學|阅读|閱讀|中文网|中文網|笔趣|筆趣|书库|書庫|\.(com|net|org|cc)|网$|網$|最新章节|全文阅读)""", RegexOption.IGNORE_CASE)

    fun finish(raw: RawExtraction): ExtractedChapter {
        val chapterTitle = normalizeLine(raw.chapterTitle)
        val workTitle = normalizeLine(raw.workTitle).ifEmpty { workTitleFromDocTitle(raw.docTitle, chapterTitle) }
        val cleaned = cleanParagraphs(raw.paragraphs, chapterTitle, workTitle)
        val chars = cleaned.paragraphs.sumOf { it.length }
        val status = when {
            // 보안 확인 페이지의 안내문도 글자 수가 꽤 되므로, 확실한 신호가 있으면 글자 수와 무관하게 실패로 본다.
            raw.blocked -> ExtractionStatus.BLOCKED
            chars >= MIN_CHARS -> ExtractionStatus.OK
            raw.captchaWidget -> ExtractionStatus.BLOCKED
            else -> ExtractionStatus.TOO_SHORT
        }
        return ExtractedChapter(
            url = raw.url,
            host = raw.host,
            workTitle = workTitle,
            chapterTitle = chapterTitle,
            paragraphs = cleaned.paragraphs,
            nextUrl = raw.nextUrl?.takeIf { it.isNotBlank() },
            prevUrl = raw.prevUrl?.takeIf { it.isNotBlank() },
            nextClickable = raw.nextClickable,
            prevClickable = raw.prevClickable,
            status = status,
            contentSource = raw.contentSource,
            contentPath = raw.contentPath,
            warnings = raw.warnings,
            removedLines = cleaned.removed,
        )
    }
}
