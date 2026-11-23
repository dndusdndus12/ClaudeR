package com.clauder.reader.translate

/** 용어집 항목 하나: 원문 표현과 그 번역어. */
data class GlossaryEntry(val source: String, val target: String)

/**
 * 용어집 글을 해석하고, 회차에 실제로 등장하는 항목만 골라낸다.
 * 형식: 한 줄에 하나, `原文=번역`. 본문을 직접 바꿔 치우지 않고, 번역 요청에 참고 자료로 붙인다.
 */
object Glossary {

    data class IgnoredLine(val lineNumber: Int, val text: String)

    data class ParseResult(
        /** 유효한 항목. 같은 원문이 여러 번 나오면 마지막 것만 남는다. */
        val entries: List<GlossaryEntry>,
        /** 형식이 맞지 않아 무시한 줄(빈 줄은 세지 않는다) */
        val ignored: List<IgnoredLine>,
        /** 같은 원문이 다시 나와 앞의 것을 덮어쓴 횟수 */
        val duplicateCount: Int,
    ) {
        fun summary(): String = buildString {
            append("항목 ${entries.size}개")
            if (ignored.isNotEmpty()) append(" · 무시된 줄 ${ignored.size}개")
            if (duplicateCount > 0) append(" · 중복 ${duplicateCount}개")
        }
    }

    fun parse(text: String): ParseResult {
        val byOrder = LinkedHashMap<String, String>()
        val ignored = mutableListOf<IgnoredLine>()
        var duplicates = 0
        text.removePrefix("﻿").lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            // 전각 등호(＝)로 입력한 경우도 받아 준다. 번역어 안에 = 가 있을 수 있어 첫 번째에서만 나눈다.
            val eq = line.indexOfFirst { it == '=' || it == '＝' }
            val source = if (eq > 0) line.substring(0, eq).trim() else ""
            val target = if (eq > 0) line.substring(eq + 1).trim() else ""
            if (source.isEmpty() || target.isEmpty()) {
                ignored += IgnoredLine(index + 1, line)
                return@forEachIndexed
            }
            if (byOrder.containsKey(source)) {
                duplicates++
                byOrder.remove(source) // 뒤에 나온 것이 순서에서도 뒤로 가게 한다.
            }
            byOrder[source] = target
        }
        return ParseResult(byOrder.map { GlossaryEntry(it.key, it.value) }, ignored, duplicates)
    }

    /** 전역 용어집과 작품 용어집을 합친다. 같은 원문이 있으면 작품 용어집이 우선한다. */
    fun merge(global: List<GlossaryEntry>, work: List<GlossaryEntry>): List<GlossaryEntry> {
        val merged = LinkedHashMap<String, String>()
        global.forEach { merged[it.source] = it.target }
        work.forEach { merged[it.source] = it.target }
        return merged.map { GlossaryEntry(it.key, it.value) }
    }

    /**
     * 원문에 실제로 등장하는 항목만 고른다. 등장 순서대로(같은 위치면 긴 표현 먼저) 정렬하므로
     * 같은 원문에 대해서는 항상 같은 결과가 나온다.
     */
    fun select(entries: List<GlossaryEntry>, sourceText: String): List<GlossaryEntry> =
        entries
            .mapNotNull { entry ->
                val at = sourceText.indexOf(entry.source)
                if (at >= 0) at to entry else null
            }
            .sortedWith(compareBy<Pair<Int, GlossaryEntry>> { it.first }.thenByDescending { it.second.source.length })
            .map { it.second }

    /** 번역 요청에 붙일 용어집 글. 고른 항목이 없으면 null. */
    fun promptBlock(selected: List<GlossaryEntry>): String? {
        if (selected.isEmpty()) return null
        return buildString {
            append("[용어집 — 이 원문에 등장하는 항목]\n")
            append("아래 원문 표현은 반드시 오른쪽 번역어로 옮긴다. 한자를 병기하지 않는다.\n")
            selected.forEach { append(it.source).append(" = ").append(it.target).append('\n') }
        }.trimEnd()
    }
}
