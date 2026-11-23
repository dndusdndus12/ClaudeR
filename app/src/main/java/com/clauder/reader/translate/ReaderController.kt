package com.clauder.reader.translate

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clauder.reader.ClauderApp
import com.clauder.reader.ai.AiProvider
import com.clauder.reader.ai.ApiErrorKind
import com.clauder.reader.ai.ApiException
import com.clauder.reader.ai.KeyRotator
import com.clauder.reader.ai.StreamEvent
import com.clauder.reader.ai.TokenUsage
import com.clauder.reader.ai.TranslationRequest
import com.clauder.reader.browser.BrowserController
import com.clauder.reader.data.ApiKeyEntry
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
    /** 이 회차가 속한 작품의 열쇠(작품별 설정 화면을 열 때 사용) */
    val workKey: String = "",
    val phase: ReaderPhase = ReaderPhase.DONE,
    /** 지금까지 받은 번역문 원본(제목 줄 포함) */
    val text: String = "",
    val error: String? = null,
    /** true 면 오류가 "AI 설정이 필요함"이라는 뜻(설정 화면으로 가는 버튼을 보여 준다) */
    val needsSetup: Boolean = false,
    /** 긴 회차를 나눠 번역할 때: 지금 몇 번째 덩어리인지(1부터)와 전체 덩어리 수 */
    val chunkIndex: Int = 0,
    val chunkCount: Int = 0,
    /** 거절이나 중단으로 자동 재시도하는 중인지 */
    val retrying: Boolean = false,
    /** 이번 회차에 보낸 용어집 항목 수 */
    val glossaryTerms: Int = 0,
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

    private data class Cached(val text: String, val usage: TokenUsage?, val modelName: String, val glossaryTerms: Int)

    /** 이번 실행 동안 번역한 회차(주소 → 번역문). 기기 저장은 서재 기능(M4)에서 추가한다. */
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 30
    }

    /** 추출이 끝난 회차의 번역을 시작한다. 이미 번역한 회차면 저장된 것을 보여 준다. */
    fun open(chapter: ExtractedChapter, forceRetranslate: Boolean = false) {
        job?.cancel()
        val key = BrowserController.stripHash(chapter.url)
        val workKey = WorkKey.of(chapter.url, chapter.workTitle)
        val cached = if (forceRetranslate) null else cache[key]
        if (cached != null) {
            state = ReaderState(
                chapter = chapter, workKey = workKey, phase = ReaderPhase.DONE, text = cached.text,
                usage = cached.usage, modelName = cached.modelName, glossaryTerms = cached.glossaryTerms, fromCache = true,
            )
            return
        }
        state = ReaderState(chapter = chapter, workKey = workKey, phase = ReaderPhase.REQUESTING)
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
        state = ReaderState(chapter = current, workKey = state.workKey, phase = ReaderPhase.LOADING_PAGE)
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
        val settings = app.settings
        val providerId = settings.aiProvider.first().ifBlank { app.providers.first().id }
        val provider = app.provider(providerId)
        val keys = app.apiKeys.keysFor(provider.id)
        val model = settings.aiModels(provider.id).first()
            .firstOrNull { it.id == settings.aiModelId(provider.id).first() }
        if (keys.isEmpty() || model == null) {
            state = state.copy(
                phase = ReaderPhase.ERROR,
                needsSetup = true,
                error = if (keys.isEmpty()) "API 키가 등록되어 있지 않습니다." else "번역에 쓸 모델을 선택하지 않았습니다.",
            )
            return
        }

        // 작품 등록(처음이면)과 작품별 설정 읽기
        val work = app.works.touch(chapter)
        val prompt = if (work.usesCustomPrompt) work.customPrompt else settings.globalPrompt.first().ifBlank { app.defaultPrompt }
        val globalTerms = if (work.useGlobalGlossary) Glossary.parse(settings.globalGlossary.first()).entries else emptyList()
        val glossary = Glossary.merge(globalTerms, Glossary.parse(work.glossary).entries)

        val cachedSystem = PromptBuilder.cachedSystem(prompt)
        val effort = settings.aiEffort.first().ifBlank { null }
        val cacheTtl = settings.aiCacheTtl.first()
        val chunks = ChapterSplitter.split(chapter.paragraphs)
        state = state.copy(workKey = work.workKey, modelName = model.label, chunkCount = chunks.size)

        val done = StringBuilder()
        var usage = TokenUsage()
        val sentTerms = LinkedHashSet<String>()
        try {
            chunks.forEachIndexed { index, paragraphs ->
                // 제목은 첫 덩어리에만 넣는다.
                val userText = PromptBuilder.userText(if (index == 0) chapter.chapterTitle else "", paragraphs)
                // 용어집은 이 덩어리의 원문에 실제로 나오는 항목만 보낸다.
                val terms = Glossary.select(glossary, userText)
                terms.forEach { sentTerms += it.source }
                val request = TranslationRequest(
                    model = model,
                    cachedSystem = cachedSystem,
                    volatileSystem = Glossary.promptBlock(terms),
                    userText = userText,
                    effort = effort,
                    cacheTtl = cacheTtl,
                )
                state = state.copy(chunkIndex = index + 1, glossaryTerms = sentTerms.size, phase = ReaderPhase.REQUESTING)
                val result = translateChunk(provider, keys, request, userText.length, prefix = done.toString()) { spent ->
                    usage += spent
                }
                done.append(result.trim()).append("\n\n")
            }
            val text = done.toString().trimEnd()
            Log.i(TAG, "translate done chunks=${chunks.size} terms=${sentTerms.size} usage=$usage chars=${text.length}")
            state = state.copy(phase = ReaderPhase.DONE, text = text, usage = usage, retrying = false)
            cache[cacheKey] = Cached(text, usage, model.label, sentTerms.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "번역 실패: ${e.javaClass.simpleName} ${e.message}")
            state = state.copy(
                phase = ReaderPhase.ERROR,
                usage = usage.takeIf { it != TokenUsage() },
                retrying = false,
                error = when (e) {
                    is ApiException -> e.userMessage()
                    is KeyRotator.AllKeysFailedException, is IncompleteTranslationException -> e.message
                    else -> "번역 중 오류가 발생했습니다: ${e.message}"
                },
            )
        }
    }

    private class IncompleteTranslationException(message: String) : Exception(message)

    /**
     * 덩어리 하나를 번역한다. 모델이 거절하거나 일부만 출력하면(연결이 중간에 끊긴 경우 포함)
     * 한 번만 자동으로 다시 시도하고, 그래도 안 되면 실패로 끝낸다.
     * @param prefix 앞 덩어리들의 번역문(화면에 이어서 보여 주기 위함)
     * @param onUsage 시도마다 쓴 토큰 수(실패한 시도도 요금이 나가므로 모두 더한다)
     */
    private suspend fun translateChunk(
        provider: AiProvider,
        keys: List<ApiKeyEntry>,
        request: TranslationRequest,
        sourceChars: Int,
        prefix: String,
        onUsage: (TokenUsage) -> Unit,
    ): String {
        var problem = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                Log.i(TAG, "translate retry: $problem")
                state = state.copy(retrying = true, phase = ReaderPhase.REQUESTING, text = prefix)
            }
            try {
                val (text, finished) = streamWithRotation(provider, keys, request, prefix)
                onUsage(finished.usage)
                problem = TranslationCheck.problem(finished.stopReason, text, sourceChars) ?: return text
            } catch (e: ApiException) {
                // 연결이 중간에 끊긴 것은 "일부만 출력"으로 보고 재시도한다. 그 밖의 오류는 그대로 올린다.
                if (e.kind != ApiErrorKind.NETWORK) throw e
                problem = e.userMessage()
            }
        }
        throw IncompleteTranslationException("$problem\n자동으로 한 번 다시 시도했지만 해결되지 않았습니다.")
    }

    /** 키 순환을 적용해 한 번 요청한다. 키를 바꿔 다시 보낼 때는 이 덩어리의 화면 글을 처음부터 다시 쓴다. */
    private suspend fun streamWithRotation(
        provider: AiProvider,
        keys: List<ApiKeyEntry>,
        request: TranslationRequest,
        prefix: String,
    ): Pair<String, StreamEvent.Finished> {
        val byId = keys.associateBy { it.id }
        val refs = keys.map { KeyRotator.KeyRef(it.id, it.masked()) }
        return app.keyRotator.run(refs) { ref ->
            val text = StringBuilder()
            var finished: StreamEvent.Finished? = null
            state = state.copy(text = prefix, phase = ReaderPhase.REQUESTING)
            provider.streamTranslation(byId.getValue(ref.id).value, request).collect { event ->
                when (event) {
                    is StreamEvent.Started -> Unit
                    StreamEvent.Thinking -> if (text.isEmpty()) state = state.copy(phase = ReaderPhase.THINKING)
                    is StreamEvent.Text -> {
                        text.append(event.delta)
                        state = state.copy(phase = ReaderPhase.STREAMING, text = prefix + text)
                    }

                    is StreamEvent.Finished -> finished = event
                }
            }
            val end = finished ?: throw ApiException(ApiErrorKind.NETWORK, message = "응답이 끝나기 전에 연결이 끊겼습니다.")
            text.toString() to end
        }
    }

    companion object {
        private const val TAG = "Clauder"

        /** 처음 한 번 + 자동 재시도 한 번 */
        private const val MAX_ATTEMPTS = 2

        fun failureMessage(chapter: ExtractedChapter): String = when (chapter.status) {
            ExtractionStatus.BLOCKED -> "보안 확인 페이지가 나타났습니다. 직접 통과한 뒤 번역 버튼을 다시 눌러 주세요."
            ExtractionStatus.TOO_SHORT -> "이 페이지에서 본문을 찾지 못했습니다. 회차 페이지가 맞는지 확인한 뒤 번역 버튼을 눌러 주세요."
            ExtractionStatus.ERROR -> chapter.errorMessage ?: "본문 추출에 실패했습니다."
            ExtractionStatus.OK -> ""
        }
    }
}
