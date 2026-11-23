package com.clauder.reader.translate

import kotlin.math.ceil

/** 긴 회차를 문단 경계에서 비슷한 크기의 덩어리로 나눈다. */
object ChapterSplitter {

    /** 한 번에 번역할 원문 글자 수의 기준. 보통의 웹소설 한 회차(2~4천 자)는 나뉘지 않는다. */
    const val DEFAULT_MAX_CHARS = 5000

    /**
     * @return 덩어리 목록. 각 덩어리는 원래 순서의 문단들이며, 모두 이으면 입력과 같다
     *         (문단 하나가 기준보다 훨씬 길어 문장 단위로 쪼갠 경우는 그 문단만 여러 조각이 된다).
     */
    fun split(paragraphs: List<String>, maxChars: Int = DEFAULT_MAX_CHARS): List<List<String>> {
        require(maxChars > 0)
        // 문단 구분이 없는 사이트에 대비: 혼자서 기준을 넘는 문단은 문장 끝에서 먼저 쪼갠다.
        val units = paragraphs.flatMap { if (it.length > maxChars) splitLongParagraph(it, maxChars) else listOf(it) }
        val total = units.sumOf { it.length }
        if (units.isEmpty()) return emptyList()
        if (total <= maxChars) return listOf(units)

        // 마지막 덩어리만 유난히 짧아지지 않게, 덩어리 수를 먼저 정하고 고르게 나눈다.
        val count = ceil(total.toDouble() / maxChars).toInt()
        val target = ceil(total.toDouble() / count).toInt()

        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var size = 0
        for (unit in units) {
            val wouldExceedLimit = size + unit.length > maxChars
            val reachedTarget = size >= target
            if (current.isNotEmpty() && (wouldExceedLimit || reachedTarget)) {
                chunks += current
                current = mutableListOf()
                size = 0
            }
            current += unit
            size += unit.length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private val SENTENCE_END = Regex("(?<=[。！？!?…」』”\"])")

    private fun splitLongParagraph(paragraph: String, maxChars: Int): List<String> {
        val sentences = paragraph.split(SENTENCE_END).filter { it.isNotEmpty() }
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length > maxChars) {
                pieces += current.toString()
                current.setLength(0)
            }
            // 문장 부호가 전혀 없는 글은 어쩔 수 없이 글자 수로 자른다.
            var rest = sentence
            while (rest.length > maxChars) {
                pieces += rest.substring(0, maxChars)
                rest = rest.substring(maxChars)
            }
            current.append(rest)
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }
}
