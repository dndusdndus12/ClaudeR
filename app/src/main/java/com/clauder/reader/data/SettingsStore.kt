package com.clauder.reader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 앱 설정 저장소(DataStore). */
class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val siteRules: Flow<List<SiteRule>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SITE_RULES]?.let { runCatching { json.decodeFromString<List<SiteRule>>(it) }.getOrNull() }
            ?: emptyList()
    }

    val lastUrl: Flow<String> = context.dataStore.data.map { it[KEY_LAST_URL].orEmpty() }

    suspend fun setSiteRules(rules: List<SiteRule>) {
        context.dataStore.edit { it[KEY_SITE_RULES] = json.encodeToString(rules) }
    }

    suspend fun setLastUrl(url: String) {
        context.dataStore.edit { it[KEY_LAST_URL] = url }
    }

    private companion object {
        val KEY_SITE_RULES = stringPreferencesKey("site_rules_json")
        val KEY_LAST_URL = stringPreferencesKey("last_url")
    }
}
