package com.clauder.reader.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
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
    /** 이 번역의 추정 비용(달러). 번역할 때 단가를 몰랐으면 null. */
    val costUsd: Double? = null,
)

/**
 * 번역 요청 한 번(회차 하나)의 사용 기록. 월별 누적 비용을 내는 데 쓴다.
 * 비용은 요청할 때의 단가로 계산해 함께 적어 두므로, 나중에 단가를 바꿔도 지난 기록은 변하지 않는다.
 */
@Entity(tableName = "usage_log", indices = [Index("time")])
data class UsageLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val workKey: String,
    val workTitle: String,
    val chapterUrl: String,
    val chapterTitle: String,
    val modelId: String,
    val modelName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    /** 캐시에 쓸 때의 유지 시간("5m" 또는 "1h") */
    val cacheTtl: String,
    /** 추정 비용(달러). 단가를 몰랐으면 null. */
    val costUsd: Double?,
    /** 번역이 끝까지 됐는지(실패해도 쓴 만큼은 요금이 나간다) */
    val success: Boolean,
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

/** 한 달 동안의 합계. [month] 는 "2026-11" 꼴(기기의 현지 시간 기준). */
data class MonthlyUsage(
    val month: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    /** 단가를 아는 요청들의 비용 합. 하나도 없으면 null. */
    val costUsd: Double?,
    /** 단가를 몰라 비용에 넣지 못한 요청 수 */
    val unpriced: Int,
)

@Dao
interface WorkDao {
    @Query("SELECT * FROM works ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkEntity>>

    @Query("SELECT * FROM works")
    suspend fun getAll(): List<WorkEntity>

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

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(entry: UsageLogEntity)

    @Query(
        "SELECT strftime('%Y-%m', time / 1000, 'unixepoch', 'localtime') AS month, " +
            "COUNT(*) AS requests, " +
            "SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens, " +
            "SUM(cacheWriteTokens) AS cacheWriteTokens, SUM(cacheReadTokens) AS cacheReadTokens, " +
            "SUM(costUsd) AS costUsd, " +
            "SUM(CASE WHEN costUsd IS NULL THEN 1 ELSE 0 END) AS unpriced " +
            "FROM usage_log GROUP BY month ORDER BY month DESC",
    )
    fun observeMonthly(): Flow<List<MonthlyUsage>>

    @Query("SELECT * FROM usage_log ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<UsageLogEntity>>
}

@Database(
    entities = [WorkEntity::class, ChapterEntity::class, UsageLogEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // 1(작품만) → 2: 회차 테이블 추가
        AutoMigration(from = 1, to = 2),
        // 2 → 3: 사용 기록 테이블과 회차의 추정 비용 칸 추가
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao
    abstract fun chapterDao(): ChapterDao
    abstract fun usageDao(): UsageDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "clauder.db").build()
    }
}
