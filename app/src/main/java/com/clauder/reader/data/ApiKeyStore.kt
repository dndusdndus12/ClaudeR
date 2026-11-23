package com.clauder.reader.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 에 보관한 키로 문자열을 암호화/복호화한다(AES-256-GCM).
 * 암호화 키 자체는 기기의 보안 영역에 있어 앱 밖으로 꺼낼 수 없다.
 */
class SecretCrypto(private val alias: String = "clauder_api_keys") {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** 결과: Base64( 초기화 벡터 12바이트 + 암호문 ) */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /** 복호화에 실패하면(키가 사라졌거나 데이터가 손상됨) null */
    fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w("Clauder", "API 키 복호화 실패", e)
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

@Serializable
data class ApiKeyEntry(
    val id: String,
    /** 공급자 id (예: "claude") */
    val provider: String,
    val value: String,
    val addedAt: Long,
) {
    /** 화면 표시용. 앞뒤 일부만 보여 준다. */
    fun masked(): String = ApiKeyStore.mask(value)
}

/** API 키 목록. 전체를 JSON 으로 만든 뒤 암호화해서 DataStore 에 저장한다. */
class ApiKeyStore(private val settings: SettingsStore, private val crypto: SecretCrypto) {

    private val json = Json { ignoreUnknownKeys = true }

    val keys: Flow<List<ApiKeyEntry>> = settings.encryptedApiKeys
        .map { decode(it) }
        .flowOn(Dispatchers.Default)

    private fun decode(encrypted: String): List<ApiKeyEntry> {
        if (encrypted.isBlank()) return emptyList()
        val plain = crypto.decrypt(encrypted) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ApiKeyEntry>>(plain) }.getOrDefault(emptyList())
    }

    private suspend fun save(list: List<ApiKeyEntry>) {
        settings.setEncryptedApiKeys(crypto.encrypt(json.encodeToString(list)))
    }

    /** @return 추가했으면 true, 빈 값이거나 이미 있는 키면 false */
    suspend fun add(provider: String, rawValue: String): Boolean {
        val value = rawValue.trim()
        if (value.isEmpty()) return false
        val current = keys.first()
        if (current.any { it.provider == provider && it.value == value }) return false
        save(current + ApiKeyEntry(UUID.randomUUID().toString(), provider, value, System.currentTimeMillis()))
        return true
    }

    suspend fun remove(id: String) {
        save(keys.first().filterNot { it.id == id })
    }

    suspend fun keysFor(provider: String): List<ApiKeyEntry> = keys.first().filter { it.provider == provider }

    companion object {
        fun mask(value: String): String =
            if (value.length <= 16) "•".repeat(value.length.coerceAtLeast(4))
            else value.take(10) + "…" + value.takeLast(4)
    }
}
