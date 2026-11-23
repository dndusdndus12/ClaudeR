package com.clauder.reader.data

import com.clauder.reader.data.db.ChapterDao
import com.clauder.reader.data.db.ChapterEntity
import com.clauder.reader.data.db.ChapterSummary
import com.clauder.reader.data.db.WorkChapterCount
import com.clauder.reader.data.db.WorkDao
import com.clauder.reader.data.db.WorkEntity
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import com.clauder.reader.translate.WorkKey
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

/** 서재: 작품 목록, 작품별 설정, 저장해 둔 회차 번역. */
class WorkRepository(private val works: WorkDao, private val chapters: ChapterDao) {

    val all: Flow<List<WorkEntity>> = works.observeAll()

    /** 작품별 저장된 회차 수 */
    val chapterCounts: Flow<List<WorkChapterCount>> = chapters.observeCounts()

    /** 마지막으로 진입한 회차가 번역까지 끝나 저장되어 있는 작품들 */
    val worksWithLastChapterSaved: Flow<List<String>> = chapters.observeWorksWithLastChapterSaved()

    fun observe(key: String): Flow<WorkEntity?> = works.observe(key)

    suspend fun get(key: String): WorkEntity? = works.get(key)

    /**
     * 회차에 진입했음을 기록한다: 작품을 등록(이미 있으면 마지막으로 진입한 회차만 갱신)하고 돌려준다.
     * 번역이 끝나기 전에 앱이 꺼져도 "어디까지 갔는지"는 남도록, 번역을 시작하기 전에 부른다.
     * 작품 전용 프롬프트와 용어집은 건드리지 않는다.
     */
    suspend fun touch(chapter: ExtractedChapter): WorkEntity {
        val key = WorkKey.of(chapter.url, chapter.workTitle)
        val now = System.currentTimeMillis()
        val existing = works.get(key)
        val work = (existing ?: WorkEntity(workKey = key, host = WorkKey.hostOf(key), title = "", createdAt = now)).copy(
            // 작품명은 처음 알게 된 것을 유지하되, 비어 있었으면 채운다.
            title = existing?.title?.takeIf { it.isNotBlank() } ?: chapter.workTitle.ifBlank { key },
            // 저장된 회차의 주소(# 뒤를 뺀 것)와 같은 모양으로 맞춰야 서로 비교할 수 있다.
            lastChapterUrl = chapter.url.substringBefore('#'),
            lastChapterTitle = chapter.chapterTitle,
            updatedAt = now,
        )
        works.upsert(work)
        return work
    }

    suspend fun update(key: String, change: (WorkEntity) -> WorkEntity) {
        val current = works.get(key) ?: return
        works.upsert(change(current).copy(updatedAt = System.currentTimeMillis()))
    }

    /** 작품과 그 작품의 저장된 회차를 모두 지운다. */
    suspend fun delete(key: String) {
        chapters.deleteForWork(key)
        works.delete(key)
    }

    // ---------- 저장된 회차 ----------

    suspend fun chapter(url: String): ChapterEntity? = chapters.get(url)

    fun chaptersOf(workKey: String): Flow<List<ChapterSummary>> = chapters.observeForWork(workKey)

    /** 번역을 저장한다. 다시 번역한 경우에는 처음 저장한 시각을 유지하고 읽던 위치는 처음으로 돌린다. */
    suspend fun saveChapter(chapter: ChapterEntity) {
        val existing = chapters.get(chapter.url)
        chapters.upsert(chapter.copy(createdAt = existing?.createdAt ?: chapter.createdAt))
    }

    suspend fun savePosition(url: String, index: Int, offset: Int) =
        chapters.savePosition(url, index, offset, System.currentTimeMillis())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encodeParagraphs(paragraphs: List<String>): String = json.encodeToString(paragraphs)

        fun decodeParagraphs(raw: String): List<String> =
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

        /** 저장된 회차를 뷰어가 다루는 회차 모양으로 바꾼다. */
        fun toExtracted(saved: ChapterEntity, workTitle: String): ExtractedChapter = ExtractedChapter(
            url = saved.url,
            host = runCatching { URI(saved.url).host }.getOrNull().orEmpty(),
            workTitle = workTitle,
            chapterTitle = saved.title,
            paragraphs = decodeParagraphs(saved.sourceJson),
            nextUrl = saved.nextUrl,
            prevUrl = saved.prevUrl,
            nextClickable = false,
            prevClickable = false,
            status = ExtractionStatus.OK,
            contentSource = "saved",
            contentPath = "",
            warnings = emptyList(),
            removedLines = 0,
        )
    }
}
