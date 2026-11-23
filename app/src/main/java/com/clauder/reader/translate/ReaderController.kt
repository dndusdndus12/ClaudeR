package com.clauder.reader.translate

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clauder.reader.ClauderApp
import com.clauder.reader.ai.ApiException
import com.clauder.reader.ai.StreamEvent
import com.clauder.reader.ai.TokenUsage
import com.clauder.reader.ai.TranslationRequest
import com.clauder.reader.browser.BrowserController
import com.clauder.reader.data.SiteRule
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 뷰어가 지금 무엇을 하고 있는지 */
enum class ReaderPhase {
    /** 다음/이전 화 페이지를 불러오는 중 */
    LOADING_PAGE,

    /** AI 에 요청을 보내고 첫 응답을 기다리는 중 */
    REQUESTING,

    /** 모델이 답을 쓰기 전에 생각하는 중 */
    THINKING,

    /** 번역문이 오고 있는 중 */
    STREAMING,
    DONE,
    ERROR,
}

data class ReaderState(
    val chapter: ExtractedChapter? = null,
    val phase: ReaderPhase = ReaderPhase.DONE,
    /** 지금까지 받은 번역문 원본(제목 줄 포함) */
    val text: String = "",
    val error: String? = null,
    /** true 면 오류가 "AI 설정이 필요함"이라는 뜻(설정 화면으로 가는 버튼을 보여 준다) */
    val needsSetup: Boolean = false,
    /** 번역은 끝났지만 알려 둘 점(예: 출력이 잘림) */
    val notice: String? = null,
    val usage: TokenUsage? = null,
    val modelName: String = "",
    /** 이미 번역해 둔 것을 다시 보여 준 것인지(API 를 다시 부르지 않음) */
    val fromCache: Boolean = false,
) {
    val busy: Boolean
        get() = phase == ReaderPhase.LOADING_PAGE || phase == ReaderPhase.REQUESTING ||
            phase == ReaderPhase.THINKING || phase == ReaderPhase.STREAMING
}

/**
 * 번역 흐름을 제어한다: 추출된 회차를 번역하고, 다음/이전 화로 넘어가 다시 번역한다.
 * 화면(뷰어)은 [state] 를 보여 주기만 한다.
 */
class ReaderController(
    private val app: ClauderApp,
    private val scope: CoroutineScope,
    private val browser: BrowserController,
    /** 다음 화 추출에 실패해 사용자가 브라우저 화면에서 직접 해결해야 할 때 호출된다. */
    private val onNeedBrowser: (message: String) -> Unit,
) {
    var state by mutableStateOf(ReaderState())
        private set

    private var job: Job? = null

    private data class Cached(val text: String, val usage: TokenUsage?, val modelName: String, val notice: String?)

    /** 이번 실행 동안 번역한 회차(주소 → 번역문). 기기 저장은 서재 기능(M4)에서 추가한다. */
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 30
    }

    /** 추출이 끝난 회차의 번역을 시작한다. 이미 번역한 회차면 저장된 것을 보여 준다. */
    fun open(chapter: ExtractedChapter, forceRetranslate: Boolean = false) {
        job?.cancel()
        val key = BrowserController.stripHash(chapter.url)
        val cached = if (forceRetranslate) null else cache[key]
        if (cached != null) {
            state = ReaderState(
                chapter = chapter, phase = ReaderPhase.DONE, text = cached.text, usage = cached.usage,
                modelName = cached.modelName, notice = cached.notice, fromCache = true,
            )
            return
        }
        state = ReaderState(chapter = chapter, phase = ReaderPhase.REQUESTING)
        job = scope.launch { translate(chapter, key) }
    }

    fun retranslate() {
        state.chapter?.let { open(it, forceRetranslate = true) }
    }

    /** 뷰어를 닫을 때: 진행 중인 요청을 취소한다. */
    fun close() {
        job?.cancel()
        job = null
    }

    fun goNext() = move(next = true)
    fun goPrev() = move(next = false)

    private fun move(next: Boolean) {
        val current = state.chapter ?: return
        if (if (next) !current.hasNext else !current.hasPrev) return
        job?.cancel()
        state = ReaderState(chapter = current, phase = ReaderPhase.LOADING_PAGE)
        job = scope.launch {
            val rules = app.settings.siteRules.first()
            val chapter = browser.navigateAndExtract(current, next) { host -> SiteRule.match(rules, host) }
            if (chapter.status == ExtractionStatus.OK) {
                open(chapter)
            } else {
                // 보안 확인 등으로 추출에 실패: 브라우저 화면을 보여 주고 사용자가 직접 통과하게 한다.
                state = state.copy(phase = ReaderPhase.DONE)
                onNeedBrowser(failureMessage(chapter))
            }
        }
    }

    private suspend fun translate(chapter: ExtractedChapter, cacheKey: String) {
        val providerId = app.settings.aiProvider.first().ifBlank { app.providers.first().id }
        val provider = app.provider(providerId)
        val key = app.apiKeys.keysFor(provider.id).firstOrNull()
        val models = app.settings.aiModels(provider.id).first()
        val model = models.firstOrNull { it.id == app.settings.aiModelId(provider.id).first() }
        if (key == null || model == null) {
            state = state.copy(
                phase = ReaderPhase.ERROR,
                needsSetup = true,
                error = if (key == null) "API 키가 등록되어 있지 않습니다." else "번역에 쓸 모델을 선택하지 않았습니다.",
            )
            return
        }

        val request = TranslationRequest(
            model = model,
            cachedSystem = PromptBuilder.cachedSystem(app.defaultPrompt),
            userText = PromptBuilder.userText(chapter),
            effort = app.settings.aiEffort.first().ifBlank { null },
        )
        state = state.copy(modelName = model.label)

        try {
            val text = StringBuilder()
            provider.streamTranslation(key.value, request).collect { event ->
                when (event) {
                    is StreamEvent.Started -> Unit
                    StreamEvent.Thinking -> if (text.isEmpty()) state = state.copy(phase = ReaderPhase.THINKING)
                    is StreamEvent.Text -> {
                        text.append(event.delta)
                        state = state.copy(phase = ReaderPhase.STREAMING, text = text.toString())
                    }

                    is StreamEvent.Finished -> finish(event, text.toString(), cacheKey, model.label)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            Log.w(TAG, "번역 실패: ${e.kind} ${e.httpStatus} ${e.apiType} ${e.message}")
            state = state.copy(phase = ReaderPhase.ERROR, error = e.userMessage())
        } catch (e: Exception) {
            Log.w(TAG, "번역 실패", e)
            state = state.copy(phase = ReaderPhase.ERROR, error = "번역 중 오류가 발생했습니다: ${e.message}")
        }
    }

    private fun finish(event: StreamEvent.Finished, text: String, cacheKey: String, modelName: String) {
        Log.i(TAG, "translate done stop=${event.stopReason} model=${event.model} usage=${event.usage} chars=${text.length}")
        when {
            event.stopReason == "refusal" -> state = state.copy(
                phase = ReaderPhase.ERROR,
                usage = event.usage,
                error = "모델이 이 회차의 번역을 거절했습니다. 다시 번역을 눌러 재시도하거나 다른 모델을 선택해 보세요.",
            )

            text.isBlank() -> state = state.copy(
                phase = ReaderPhase.ERROR,
                usage = event.usage,
                error = "모델이 번역문을 내보내지 않았습니다 (종료 사유: ${event.stopReason}).",
            )

            else -> {
                val notice = when (event.stopReason) {
                    "end_turn", "stop_sequence" -> null
                    "max_tokens" -> "출력 한도에 걸려 번역이 중간에서 끊겼습니다."
                    else -> "번역이 예상과 다르게 끝났습니다 (종료 사유: ${event.stopReason})."
                }
                state = state.copy(phase = ReaderPhase.DONE, text = text, usage = event.usage, notice = notice)
                // 온전히 끝난 번역만 다시 쓸 수 있게 보관한다.
                if (notice == null) cache[cacheKey] = Cached(text, event.usage, modelName, null)
            }
        }
    }

    companion object {
        private const val TAG = "Clauder"

        fun failureMessage(chapter: ExtractedChapter): String = when (chapter.status) {
            ExtractionStatus.BLOCKED -> "보안 확인 페이지가 나타났습니다. 직접 통과한 뒤 번역 버튼을 다시 눌러 주세요."
            ExtractionStatus.TOO_SHORT -> "이 페이지에서 본문을 찾지 못했습니다. 회차 페이지가 맞는지 확인한 뒤 번역 버튼을 눌러 주세요."
            ExtractionStatus.ERROR -> chapter.errorMessage ?: "본문 추출에 실패했습니다."
            ExtractionStatus.OK -> ""
        }
    }
}
