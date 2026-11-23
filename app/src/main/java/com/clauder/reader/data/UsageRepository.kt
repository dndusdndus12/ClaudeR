package com.clauder.reader.data

import com.clauder.reader.ClauderApp
import com.clauder.reader.ai.ModelInfo
import com.clauder.reader.ai.TokenUsage
import com.clauder.reader.data.db.MonthlyUsage
import com.clauder.reader.data.db.UsageDao
import com.clauder.reader.data.db.UsageLogEntity
import com.clauder.reader.data.db.WorkEntity
import com.clauder.reader.extract.ExtractedChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 번역 요청의 사용 기록과 월별 합계. */
class UsageRepository(private val dao: UsageDao) {

    val monthly: Flow<List<MonthlyUsage>> = dao.observeMonthly()

    fun recent(limit: Int = 30): Flow<List<UsageLogEntity>> = dao.observeRecent(limit)

    /** 번역 한 번의 사용량을 기록한다. 비용은 지금의 단가로 계산해 함께 적는다. */
    suspend fun record(
        work: WorkEntity,
        chapter: ExtractedChapter,
        model: ModelInfo,
        usage: TokenUsage,
        cacheTtl: String,
        costUsd: Double?,
        success: Boolean,
    ) {
        dao.insert(
            UsageLogEntity(
                time = System.currentTimeMillis(),
                workKey = work.workKey,
                workTitle = work.title,
                chapterUrl = chapter.url,
                chapterTitle = chapter.chapterTitle,
                modelId = model.id,
                modelName = model.label,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                cacheWriteTokens = usage.cacheWriteTokens,
                cacheReadTokens = usage.cacheReadTokens,
                cacheTtl = cacheTtl,
                costUsd = costUsd,
                success = success,
            ),
        )
    }
}

/** 설정 내보내기와 가져오기. API 키는 다루지 않는다. */
object BackupManager {

    suspend fun export(app: ClauderApp): SettingsBackup {
        val settings = app.settings
        return SettingsBackup(
            exportedAt = System.currentTimeMillis(),
            siteRules = settings.siteRules.first(),
            globalPrompt = settings.globalPrompt.first(),
            globalGlossary = settings.globalGlossary.first(),
            viewer = settings.viewerSettings.first(),
            aiProvider = settings.aiProvider.first(),
            aiModels = app.providers.filter { it.implemented }
                .associate { it.id to settings.aiModelId(it.id).first() }
                .filterValues { it.isNotBlank() },
            aiEffort = settings.aiEffort.first(),
            aiCacheTtl = settings.aiCacheTtl.first(),
            modelPrices = settings.modelPrices.first(),
            works = app.works.exportSettings(),
        )
    }

    /** 가져온 설정으로 지금의 설정을 바꾼다. 저장된 번역, 읽던 위치, API 키는 건드리지 않는다. */
    suspend fun import(app: ClauderApp, backup: SettingsBackup) {
        val settings = app.settings
        settings.setSiteRules(backup.siteRules)
        settings.setGlobalPrompt(backup.globalPrompt)
        settings.setGlobalGlossary(backup.globalGlossary)
        settings.setViewerSettings(backup.viewer)
        settings.setAiProvider(backup.aiProvider)
        backup.aiModels.forEach { (provider, model) -> settings.setAiModelId(provider, model) }
        settings.setAiEffort(backup.aiEffort)
        if (backup.aiCacheTtl.isNotBlank()) settings.setAiCacheTtl(backup.aiCacheTtl)
        settings.setModelPrices(backup.modelPrices)
        app.works.importSettings(backup.works)
    }
}
