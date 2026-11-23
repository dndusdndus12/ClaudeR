package com.clauder.reader.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 번역해서 읽은 작품 하나와 그 작품 전용 설정. */
@Entity(tableName = "works")
data class WorkEntity(
    /** 작품 열쇠(사이트 + 작품 ID). [com.clauder.reader.translate.WorkKey] 참고. */
    @PrimaryKey val workKey: String,
    val host: String,
    val title: String,
    /** [PROMPT_GLOBAL] 또는 [PROMPT_CUSTOM] */
    val promptMode: String = PROMPT_GLOBAL,
    val customPrompt: String = "",
    /** 작품 용어집에 더해 전역 용어집도 함께 쓸지 */
    val useGlobalGlossary: Boolean = true,
    /** 작품 용어집 원문(한 줄에 `原文=번역`) */
    val glossary: String = "",
    /** 마지막으로 본 회차(이어보기에 사용) */
    val lastChapterUrl: String = "",
    val lastChapterTitle: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val usesCustomPrompt: Boolean get() = promptMode == PROMPT_CUSTOM && customPrompt.isNotBlank()

    companion object {
        const val PROMPT_GLOBAL = "global"
        const val PROMPT_CUSTOM = "custom"
    }
}

/** 번역해 둔 회차 하나. 다시 열 때 API 를 부르지 않도록 번역문을 기기에 저장한다. */
@Entity(tableName = "chapters", indices = [Index("workKey")])
data class ChapterEntity(
    /** 회차 주소(# 뒤는 뺀 것) */
    @PrimaryKey val url: String,
    val workKey: String,
    /** 원문 회차 제목 */
    val title: String,
    /** 번역된 회차 제목 */
    val translatedTitle: String,
    /** 번역문 원본(첫 줄 "# 제목" 포함) */
    val translation: String,
    /** 원문 문단 목록(JSON 배열). 브라우저 없이 "다시 번역"할 때 쓴다. */
    val sourceJson: String,
    val nextUrl: String?,
    val prevUrl: String?,
    val modelName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    val glossaryTerms: Int,
    val chunkCount: Int,
    /** 읽던 위치: 화면 맨 위에 보이던 항목 번호와 그 안에서의 어긋남(px) */
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    /** 처음 저장한 시각(회차 목록의 정렬 기준) */
    val createdAt: Long,
    /** 마지막으로 번역한 시각 */
    val translatedAt: Long,
    /** 마지막으로 읽은 시각 */
    val readAt: Long,
)

/** 회차 목록 화면용: 번역문 본문을 빼고 가볍게 읽는다. */
data class ChapterSummary(
    val url: String,
    val title: String,
    val translatedTitle: String,
    val translatedAt: Long,
    val readAt: Long,
)

data class WorkChapterCount(val workKey: String, val count: Int)

@Dao
interface WorkDao {
    @Query("SELECT * FROM works ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkEntity>>

    @Query("SELECT * FROM works WHERE workKey = :key")
    fun observe(key: String): Flow<WorkEntity?>

    @Query("SELECT * FROM works WHERE workKey = :key")
    suspend fun get(key: String): WorkEntity?

    @Upsert
    suspend fun upsert(work: WorkEntity)

    @Query("DELETE FROM works WHERE workKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE url = :url")
    suspend fun get(url: String): ChapterEntity?

    @Upsert
    suspend fun upsert(chapter: ChapterEntity)

    @Query("UPDATE chapters SET scrollIndex = :index, scrollOffset = :offset, readAt = :now WHERE url = :url")
    suspend fun savePosition(url: String, index: Int, offset: Int, now: Long)

    @Query(
        "SELECT url, title, translatedTitle, translatedAt, readAt FROM chapters " +
            "WHERE workKey = :workKey ORDER BY createdAt",
    )
    fun observeForWork(workKey: String): Flow<List<ChapterSummary>>

    @Query("SELECT workKey, COUNT(*) AS count FROM chapters GROUP BY workKey")
    fun observeCounts(): Flow<List<WorkChapterCount>>

    /** 마지막으로 진입한 회차의 번역이 저장되어 있는 작품들의 열쇠 */
    @Query("SELECT w.workKey FROM works w INNER JOIN chapters c ON c.url = w.lastChapterUrl")
    fun observeWorksWithLastChapterSaved(): Flow<List<String>>

    @Query("DELETE FROM chapters WHERE workKey = :workKey")
    suspend fun deleteForWork(workKey: String)
}

@Database(
    entities = [WorkEntity::class, ChapterEntity::class],
    version = 2,
    exportSchema = true,
    // 버전 1(작품만) → 2(회차 테이블 추가). 기존 작품 설정은 그대로 유지된다.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao
    abstract fun chapterDao(): ChapterDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "clauder.db").build()
    }
}
