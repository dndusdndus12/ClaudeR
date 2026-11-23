package com.clauder.reader

import android.app.Application
import com.clauder.reader.ai.AiProvider
import com.clauder.reader.ai.ClaudeProvider
import com.clauder.reader.ai.GeminiProvider
import com.clauder.reader.ai.KeyRotator
import com.clauder.reader.data.ApiKeyStore
import com.clauder.reader.data.SecretCrypto
import com.clauder.reader.data.SettingsStore
import com.clauder.reader.data.UsageRepository
import com.clauder.reader.data.WorkRepository
import com.clauder.reader.data.db.AppDatabase
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ClauderApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val apiKeys: ApiKeyStore by lazy { ApiKeyStore(settings, SecretCrypto()) }
    private val database: AppDatabase by lazy { AppDatabase.create(this) }
    val works: WorkRepository by lazy { WorkRepository(database.workDao(), database.chapterDao()) }
    val usage: UsageRepository by lazy { UsageRepository(database.usageDao()) }

    /** 키 순환 상태(어떤 키가 쉬는 중인지). 앱이 켜져 있는 동안 유지된다. */
    val keyRotator = KeyRotator()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // 스트리밍 중에는 글자가 안 와도 서버가 주기적으로 신호(ping)를 보내므로,
            // 이 시간 동안 아무것도 안 오면 연결이 죽은 것으로 본다.
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    /** 설정 화면에 보이는 순서대로. */
    val providers: List<AiProvider> by lazy { listOf(ClaudeProvider(httpClient), GeminiProvider) }

    fun provider(id: String): AiProvider =
        providers.firstOrNull { it.id == id && it.implemented } ?: providers.first()

    val defaultPrompt: String by lazy {
        assets.open("default_prompt.txt").bufferedReader().use { it.readText() }.trim()
    }
}
