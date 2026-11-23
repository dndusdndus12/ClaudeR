package com.clauder.reader.data

import com.clauder.reader.data.db.WorkDao
import com.clauder.reader.data.db.WorkEntity
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.translate.WorkKey
import kotlinx.coroutines.flow.Flow

/** 작품 목록과 작품별 설정. */
class WorkRepository(private val dao: WorkDao) {

    val all: Flow<List<WorkEntity>> = dao.observeAll()

    fun observe(key: String): Flow<WorkEntity?> = dao.observe(key)

    suspend fun get(key: String): WorkEntity? = dao.get(key)

    /**
     * 번역하려는 회차의 작품을 등록(이미 있으면 마지막으로 본 회차만 갱신)하고 돌려준다.
     * 작품 전용 프롬프트와 용어집은 건드리지 않는다.
     */
    suspend fun touch(chapter: ExtractedChapter): WorkEntity {
        val key = WorkKey.of(chapter.url, chapter.workTitle)
        val now = System.currentTimeMillis()
        val existing = dao.get(key)
        val work = (existing ?: WorkEntity(workKey = key, host = WorkKey.hostOf(key), title = "", createdAt = now)).copy(
            // 작품명은 처음 알게 된 것을 유지하되, 비어 있었으면 채운다.
            title = existing?.title?.takeIf { it.isNotBlank() } ?: chapter.workTitle.ifBlank { key },
            lastChapterUrl = chapter.url,
            lastChapterTitle = chapter.chapterTitle,
            updatedAt = now,
        )
        dao.upsert(work)
        return work
    }

    suspend fun update(key: String, change: (WorkEntity) -> WorkEntity) {
        val current = dao.get(key) ?: return
        dao.upsert(change(current).copy(updatedAt = System.currentTimeMillis()))
    }
}
