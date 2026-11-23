package com.clauder.reader.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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
}

@Database(entities = [WorkEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "clauder.db").build()
    }
}
