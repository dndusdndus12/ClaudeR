package com.clauder.reader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clauder.reader.ai.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 앱 설정 저장소(DataStore). */
class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun string(name: String): Flow<String> {
        val key = stringPreferencesKey(name)
        return context.dataStore.data.map { it[key].orEmpty() }
    }

    private suspend fun setString(name: String, value: String) {
        val key = stringPreferencesKey(name)
        context.dataStore.edit { it[key] = value }
    }

    // ---------- 본문 추출 ----------

    val siteRules: Flow<List<SiteRule>> = string(KEY_SITE_RULES).map { raw ->
        runCatching { json.decodeFromString<List<SiteRule>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setSiteRules(rules: List<SiteRule>) = setString(KEY_SITE_RULES, json.encodeToString(rules))

    val lastUrl: Flow<String> = string(KEY_LAST_URL)
    suspend fun setLastUrl(url: String) = setString(KEY_LAST_URL, url)

    // ---------- AI ----------

    /** 선택한 공급자 id. 비어 있으면 기본값(Claude). */
    val aiProvider: Flow<String> = string(KEY_AI_PROVIDER)
    suspend fun setAiProvider(id: String) = setString(KEY_AI_PROVIDER, id)

    /** 공급자별로 선택한 모델 id */
    fun aiModelId(provider: String): Flow<String> = string("ai_model_$provider")
    suspend fun setAiModelId(provider: String, modelId: String) = setString("ai_model_$provider", modelId)

    /** 공급자별로 마지막에 불러온 모델 목록 */
    fun aiModels(provider: String): Flow<List<ModelInfo>> = string("ai_models_$provider").map { raw ->
        runCatching { json.decodeFromString<List<ModelInfo>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setAiModels(provider: String, models: List<ModelInfo>) =
        setString("ai_models_$provider", json.encodeToString(models))

    /** "" (모델 기본값) 또는 low / medium / high */
    val aiEffort: Flow<String> = string(KEY_AI_EFFORT)
    suspend fun setAiEffort(effort: String) = setString(KEY_AI_EFFORT, effort)

    /** 프롬프트 캐시 유지 시간: "5m" 또는 "1h". 비어 있으면 기본값(1h). */
    val aiCacheTtl: Flow<String> = string(KEY_AI_CACHE_TTL).map { it.ifBlank { DEFAULT_CACHE_TTL } }
    suspend fun setAiCacheTtl(ttl: String) = setString(KEY_AI_CACHE_TTL, ttl)

    // ---------- 프롬프트와 용어집(전역) ----------

    /** 사용자가 고친 전역 프롬프트. 비어 있으면 앱에 내장된 기본 프롬프트를 쓴다. */
    val globalPrompt: Flow<String> = string(KEY_GLOBAL_PROMPT)
    suspend fun setGlobalPrompt(prompt: String) = setString(KEY_GLOBAL_PROMPT, prompt)

    /** 전역 용어집 원문(한 줄에 `原文=번역`) */
    val globalGlossary: Flow<String> = string(KEY_GLOBAL_GLOSSARY)
    suspend fun setGlobalGlossary(text: String) = setString(KEY_GLOBAL_GLOSSARY, text)

    /** 암호화된 API 키 묶음. [ApiKeyStore] 만 사용한다. */
    val encryptedApiKeys: Flow<String> = string(KEY_API_KEYS)
    suspend fun setEncryptedApiKeys(value: String) = setString(KEY_API_KEYS, value)

    companion object {
        /**
         * 회차 하나를 읽는 데 보통 5분이 넘게 걸리므로, 5분 캐시는 다음 화를 번역할 때 이미 만료되어 있다.
         * 그래서 1시간 캐시를 기본으로 한다(쓰기 비용은 더 들지만 그 뒤로는 읽기 요금만 낸다).
         */
        const val DEFAULT_CACHE_TTL = "1h"
        private const val KEY_AI_CACHE_TTL = "ai_cache_ttl"
        private const val KEY_GLOBAL_PROMPT = "global_prompt"
        private const val KEY_GLOBAL_GLOSSARY = "global_glossary"
        private const val KEY_SITE_RULES = "site_rules_json"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_AI_EFFORT = "ai_effort"
        private const val KEY_API_KEYS = "api_keys_enc"
    }
}
