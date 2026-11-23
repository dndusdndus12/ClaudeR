package com.clauder.reader.data

import com.clauder.reader.data.db.ChapterDao
import com.clauder.reader.data.db.ChapterEntity
import com.clauder.reader.data.db.ChapterSummary
import com.clauder.reader.data.db.WorkChapterCount
import com.clauder.reader.data.db.WorkDao
import com.clauder.reader.data.db.WorkEntity
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * "회차에 진입함"(touch)과 "작품만 확인함"(ensure)의 차이를 확인한다.
 * 미리 번역은 사용자가 아직 들어가지 않은 회차를 번역하므로, 이어보기 위치를 바꾸면 안 된다.
 */
class WorkEntryTest {

    private class FakeWorkDao : WorkDao {
        val rows = LinkedHashMap<String, WorkEntity>()
        override fun observeAll(): Flow<List<WorkEntity>> = emptyFlow()
        override suspend fun getAll(): List<WorkEntity> = rows.values.toList()
        override fun observe(key: String): Flow<WorkEntity?> = emptyFlow()
        override suspend fun get(key: String): WorkEntity? = rows[key]
        override suspend fun upsert(work: WorkEntity) {
            rows[work.workKey] = work
        }

        override suspend fun delete(key: String) {
            rows.remove(key)
        }
    }

    private class FakeChapterDao : ChapterDao {
        override suspend fun get(url: String): ChapterEntity? = null
        override suspend fun upsert(chapter: ChapterEntity) = Unit
        override suspend fun savePosition(url: String, index: Int, offset: Int, now: Long) = Unit
        override fun observeForWork(workKey: String): Flow<List<ChapterSummary>> = emptyFlow()
        override fun observeCounts(): Flow<List<WorkChapterCount>> = emptyFlow()
        override fun observeWorksWithLastChapterSaved(): Flow<List<String>> = emptyFlow()
        override suspend fun deleteForWork(workKey: String) = Unit
    }

    private fun chapter(number: Int) = ExtractedChapter(
        url = "https://www.example.com/book/1/$number.html",
        host = "www.example.com",
        workTitle = "雨巷",
        chapterTitle = "第${number}章",
        paragraphs = listOf("第一段。"),
        nextUrl = "https://www.example.com/book/1/${number + 1}.html",
        prevUrl = null,
        nextClickable = false,
        prevClickable = false,
        status = ExtractionStatus.OK,
        contentSource = "test",
        contentPath = "",
        warnings = emptyList(),
        removedLines = 0,
    )

    @Test
    fun `미리 번역한 회차는 이어보기 위치를 바꾸지 않는다`() = runBlocking {
        val dao = FakeWorkDao()
        val repo = WorkRepository(dao, FakeChapterDao())

        val entered = repo.touch(chapter(29))
        assertEquals("https://www.example.com/book/1/29.html", entered.lastChapterUrl)

        // 30화를 미리 번역: 같은 작품을 돌려주되, 마지막으로 진입한 회차는 29화 그대로여야 한다.
        val work = repo.ensure(chapter(30))
        assertEquals(entered.workKey, work.workKey)
        assertEquals("https://www.example.com/book/1/29.html", dao.rows.getValue(work.workKey).lastChapterUrl)
        assertEquals("第29章", dao.rows.getValue(work.workKey).lastChapterTitle)

        // 실제로 30화에 들어가면 그때 바뀐다.
        repo.touch(chapter(30))
        assertEquals("https://www.example.com/book/1/30.html", dao.rows.getValue(work.workKey).lastChapterUrl)
    }

    @Test
    fun `작품 전용 설정은 ensure 와 touch 로 지워지지 않는다`() = runBlocking {
        val dao = FakeWorkDao()
        val repo = WorkRepository(dao, FakeChapterDao())
        val key = repo.touch(chapter(1)).workKey
        repo.update(key) { it.copy(promptMode = WorkEntity.PROMPT_CUSTOM, customPrompt = "나만의 프롬프트", glossary = "雨巷=우항") }

        assertEquals("나만의 프롬프트", repo.ensure(chapter(2)).customPrompt)
        assertEquals("雨巷=우항", repo.touch(chapter(2)).glossary)
    }

    @Test
    fun `처음 보는 작품이면 ensure 가 등록하되 진입한 회차는 비워 둔다`() = runBlocking {
        val dao = FakeWorkDao()
        val repo = WorkRepository(dao, FakeChapterDao())

        val work = repo.ensure(chapter(5))
        assertNotNull(dao.rows[work.workKey])
        assertEquals("雨巷", work.title)
        assertEquals("", work.lastChapterUrl)
    }
}
