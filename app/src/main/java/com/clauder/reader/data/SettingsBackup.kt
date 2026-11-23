package com.clauder.reader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** 내보내기 파일에 들어가는 한 작품의 설정(번역문과 읽던 위치는 넣지 않는다). */
@Serializable
data class WorkBackup(
    val workKey: String,
    val host: String = "",
    val title: String = "",
    val promptMode: String = "global",
    val customPrompt: String = "",
    val useGlobalGlossary: Boolean = true,
    val glossary: String = "",
)

/**
 * 설정 내보내기/가져오기 파일의 내용.
 * API 키는 일부러 넣지 않는다. 이 자료형에 키를 담을 자리 자체가 없다.
 */
@Serializable
data class SettingsBackup(
    /** 이 앱의 파일인지 확인하는 표시 */
    val app: String = APP_MARK,
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAt: Long = 0,
    val siteRules: List<SiteRule> = emptyList(),
    /** 비어 있으면 앱의 기본 프롬프트 사용 */
    val globalPrompt: String = "",
    val globalGlossary: String = "",
    val viewer: ViewerSettings = ViewerSettings(),
    val aiProvider: String = "",
    /** 공급자 id → 선택한 모델 id */
    val aiModels: Map<String, String> = emptyMap(),
    val aiEffort: String = "",
    val aiCacheTtl: String = "",
    /** 다음 화 미리 번역을 켰는지 */
    val prefetchNext: Boolean = false,
    /** 사용자가 직접 입력한 모델별 단가 */
    val modelPrices: Map<String, ModelPrice> = emptyMap(),
    val works: List<WorkBackup> = emptyList(),
) {
    /** 가져오기 전에 보여 줄 요약 */
    fun summary(): String = buildList {
        add("사이트 규칙 ${siteRules.size}개")
        add(if (globalPrompt.isBlank()) "기본 프롬프트" else "수정한 프롬프트")
        add("전역 용어집 ${globalGlossary.lines().count { it.isNotBlank() }}줄")
        add("작품 설정 ${works.size}개")
        if (modelPrices.isNotEmpty()) add("직접 입력한 단가 ${modelPrices.size}개")
    }.joinToString(" · ")

    companion object {
        const val APP_MARK = "Clauder"
        const val FORMAT_VERSION = 1
    }
}

class BackupFormatException(message: String) : Exception(message)

object BackupCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(backup: SettingsBackup): String = json.encodeToString(backup)

    /** 잘못된 파일이면 사용자에게 보여 줄 한국어 설명을 담아 [BackupFormatException] 을 던진다. */
    fun decode(text: String): SettingsBackup {
        val root = try {
            json.parseToJsonElement(text.removePrefix("﻿")).jsonObject
        } catch (e: Exception) {
            throw BackupFormatException("설정 파일을 읽지 못했습니다. Clauder 에서 내보낸 JSON 파일이 맞는지 확인하세요.")
        }
        // 표시가 실제로 적혀 있는 파일만 받는다. 아무 JSON 이나 "빈 설정"으로 읽혀서 기존 설정을 지우면 안 된다.
        val mark = (root["app"] as? JsonPrimitive)?.contentOrNull
        if (mark != SettingsBackup.APP_MARK) {
            throw BackupFormatException("Clauder 에서 내보낸 설정 파일이 아닙니다.")
        }
        val backup = try {
            json.decodeFromJsonElement<SettingsBackup>(root)
        } catch (e: Exception) {
            throw BackupFormatException("설정 파일의 내용이 올바르지 않습니다: ${e.message?.take(120)}")
        }
        if (backup.formatVersion > SettingsBackup.FORMAT_VERSION) {
            throw BackupFormatException("더 새로운 버전의 앱에서 내보낸 파일이라 읽을 수 없습니다. 앱을 최신 버전으로 올린 뒤 다시 시도하세요.")
        }
        return backup.copy(viewer = backup.viewer.sanitized())
    }
}
