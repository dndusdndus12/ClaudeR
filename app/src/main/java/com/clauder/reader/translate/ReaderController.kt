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
import com.clauder.reader.data.WorkRepository
import com.clauder.reader.data.db.ChapterEntity
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 뷰어가 지금 무엇을 하고 있는지 */
enum class ReaderPhase {
    /** 회차 페이지나 저장된 번역을 불러오는 중 */
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
    /** 기기에 저장해 둔 번역을 다시 보여 준 것인지(API 를 다시 부르지 않음) */
    val fromCache: Boolean = false,
    /** 저장된 회차를 열 때 돌아갈 읽던 위치 */
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    /** 회차를 열 때마다 1씩 늘어난다. 같은 회차를 다시 열어도 뷰어가 위치를 새로 맞추게 하기 위함. */
    val openCount: Int = 0,
    /** 미리 번역의 진행 상황 안내(예: "다음 화를 미리 번역하는 중…"). 없으면 null. */
    val nextNote: String? = null,
) {
    val busy: Boolean
        get() = phase == ReaderPhase.LOADING_PAGE || phase == ReaderPhase.REQUESTING ||
            phase == ReaderPhase.THINKING || phase == ReaderPhase.STREAMING
}

/**
 * 번역 흐름을 제어한다: 추출된 회차를 번역하고, 다음/이전 화로 넘어가 다시 번역한다.
 * 번역한 회차는 기기에 저장해 두고, 다시 열 때는 저장된 것을 보여 준다.
 *
 * 번역은 회차 주소별로 따로 돌아가며, 뷰어는 "지금 보고 있는 회차"의 진행 상황만 비춘다.
 * 그래서 화면에 보이지 않는 번역(미리 번역)도 같은 방식으로 돌릴 수 있고,
 * 미리 번역 중인 회차로 넘어가면 새로 요청하지 않고 진행 중인 번역을 이어서 보여 준다.
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

    private var opens = 0

    /** 뷰어가 지금 보여 주고 있는 회차의 주소(# 뒤를 뺀 것). 뷰어가 닫혀 있으면 null. */
    private var shownUrl: String? = null

    /** 진행 중인 번역들: 회차 주소 → 그 번역의 최신 상태와 작업 */
    private val live = mutableMapOf<String, ReaderState>()
    private val runs = mutableMapOf<String, Job>()

    /** 다음/이전 화 페이지를 불러오는 작업(사용자가 버튼을 눌러 시작한 것) */
    private var navJob: Job? = null

    /** 미리 번역: 다음 화 페이지를 불러와 추출하는 작업과, 그것이 어느 회차에서 시작됐는지 */
    private var pendingNext: Deferred<ExtractedChapter>? = null
    private var pendingFrom: String? = null
    private var prefetchJob: Job? = null

    /** 미리 번역 중인 회차의 주소와, 그 번역을 시작하게 한 회차(안내 문구를 붙일 곳) */
    private var prefetchTarget: String? = null
    private var prefetchSource: String? = null

    // ---------- 회차 열기 ----------

    /**
     * 브라우저에서 추출한 회차를 연다. 저장해 둔 번역이 있으면 그것을 보여 주고, 없으면 번역을 시작한다.
     * @param forceRetranslate 저장된 번역이 있어도 새로 번역한다("다시 번역").
     */
    fun open(chapter: ExtractedChapter, forceRetranslate: Boolean = false) {
        val url = strip(chapter.url)
        navJob?.cancel()
        cancelPrefetchNavigation()
        // 다른 회차의 번역은 멈춘다(지금 열려는 회차의 번역이 이미 돌고 있으면 그것은 살려 둔다).
        runs.keys.filter { it != url }.forEach(::cancelRun)
        if (forceRetranslate) cancelRun(url)
        shownUrl = url

        val running = live[url]
        if (running != null) {
            // 미리 번역으로 이미 시작된 회차: 진행 중인 번역을 그대로 이어서 보여 준다.
            state = running.copy(openCount = ++opens)
            scope.launch { app.works.touch(chapter) }
            return
        }

        state = ReaderState(
            chapter = chapter,
            workKey = WorkKey.of(chapter.url, chapter.workTitle),
            phase = ReaderPhase.REQUESTING,
            openCount = ++opens,
        )
        navJob = scope.launch {
            // 번역보다 먼저 "이 회차에 진입했다"는 것부터 기록한다.
            // 번역 도중에 앱이 꺼져도 이어보기가 이 회차를 가리키게 하기 위함이다.
            app.works.touch(chapter)
            val saved = if (forceRetranslate) null else app.works.chapter(url)
            if (saved != null) showSaved(saved, live = chapter) else startRun(chapter, url)
        }
    }

    /**
     * 서재에서 저장된 회차를 바로 연다(브라우저와 API 를 쓰지 않는다).
     * @param onMissing 저장된 번역이 없을 때 호출된다.
     */
    fun openSaved(url: String, onMissing: () -> Unit) {
        cancelEverything()
        // 앞서 보던 회차가 잠깐 비치지 않도록 화면을 먼저 비운다.
        state = ReaderState(phase = ReaderPhase.LOADING_PAGE, openCount = ++opens)
        navJob = scope.launch {
            val saved = app.works.chapter(strip(url))
            if (saved == null) onMissing() else showSaved(saved, live = null)
        }
    }

    /** 저장된 번역을 화면에 올린다. [live] 는 방금 브라우저에서 추출한 같은 회차(있으면 그쪽의 이동 정보가 더 정확하다). */
    private suspend fun showSaved(saved: ChapterEntity, live: ExtractedChapter?) {
        val work = app.works.get(saved.workKey)
        val chapter = live ?: WorkRepository.toExtracted(saved, work?.title.orEmpty())
        shownUrl = saved.url
        state = ReaderState(
            chapter = chapter,
            workKey = saved.workKey,
            phase = ReaderPhase.DONE,
            text = saved.translation,
            usage = TokenUsage(saved.inputTokens, saved.outputTokens, saved.cacheWriteTokens, saved.cacheReadTokens),
            modelName = saved.modelName,
            glossaryTerms = saved.glossaryTerms,
            chunkCount = saved.chunkCount,
            fromCache = true,
            scrollIndex = saved.scrollIndex,
            scrollOffset = saved.scrollOffset,
            openCount = ++opens,
        )
        // 이어보기가 이 회차를 가리키도록 한다.
        app.works.touch(chapter)
        maybePrefetchNext(chapter)
    }

    fun retranslate() {
        state.chapter?.let { open(it, forceRetranslate = true) }
    }

    /** 읽던 위치를 저장한다(번역이 끝난 회차만). */
    fun savePosition(url: String, index: Int, offset: Int) {
        val current = state
        if (current.phase != ReaderPhase.DONE || current.chapter?.url != url) return
        scope.launch { app.works.savePosition(strip(url), index, offset) }
    }

    /** 뷰어를 닫을 때: 진행 중인 요청을 모두 취소한다(미리 번역 포함). 뷰어를 떠나면 더는 요금이 나가지 않는다. */
    fun close() {
        cancelEverything()
        shownUrl = null
    }

    private fun cancelEverything() {
        navJob?.cancel()
        navJob = null
        cancelPrefetchNavigation()
        runs.keys.toList().forEach(::cancelRun)
    }

    private fun cancelRun(url: String) {
        runs.remove(url)?.cancel()
        live.remove(url)
        if (prefetchTarget == url) {
            prefetchTarget = null
            prefetchSource = null
        }
    }

    private fun cancelPrefetchNavigation() {
        pendingNext?.cancel()
        pendingNext = null
        pendingFrom = null
        prefetchJob?.cancel()
        prefetchJob = null
    }

    // ---------- 다음 화, 이전 화 ----------

    fun goNext() = move(next = true)
    fun goPrev() = move(next = false)

    private fun move(next: Boolean) {
        val current = state.chapter ?: return
        if (if (next) !current.hasNext else !current.hasPrev) return
        val currentUrl = strip(current.url)
        val target = (if (next) current.nextUrl else current.prevUrl)?.let(::strip)
        navJob?.cancel()

        // 미리 번역이 이 "다음 화"를 불러오는 중이면, 그 결과를 넘겨받는다(같은 페이지를 두 번 불러오지 않는다).
        val adopted = pendingNext.takeIf { next && pendingFrom == currentUrl }
        if (adopted != null) {
            pendingNext = null
            pendingFrom = null
        } else {
            cancelPrefetchNavigation()
        }

        navJob = scope.launch {
            // 이미 번역해 둔 회차면 브라우저를 거치지 않고 바로 보여 준다.
            val saved = target?.let { app.works.chapter(it) }
            if (saved != null) {
                adopted?.cancel()
                runs.keys.toList().forEach(::cancelRun)
                showSaved(saved, live = null)
                return@launch
            }
            // 미리 번역으로 이미 번역이 시작된 회차면 그 번역을 이어서 보여 준다.
            val running = target?.let { live[it] }?.chapter
            if (running != null) {
                open(running)
                return@launch
            }
            // 주소 없이 버튼으로만 이동하는 사이트인데 브라우저가 다른 페이지에 있으면, 그 회차 페이지부터 열어야 한다.
            if (target == null && strip(browser.url) != currentUrl) {
                browser.load(current.url)
                onNeedBrowser("이 사이트는 다음 화 주소를 알 수 없어 브라우저에서 이동해야 합니다. 페이지가 열리면 다음 화로 이동한 뒤 번역 버튼을 눌러 주세요.")
                return@launch
            }
            runs.keys.toList().forEach(::cancelRun)
            state = ReaderState(
                chapter = current, workKey = state.workKey, phase = ReaderPhase.LOADING_PAGE, openCount = state.openCount,
            )
            val chapter = if (adopted != null) {
                try {
                    adopted.await()
                } catch (e: CancellationException) {
                    // 기다리다가 취소되면(뷰어를 닫는 등) 넘겨받은 작업도 함께 멈춘다.
                    adopted.cancel()
                    throw e
                }
            } else {
                val rules = app.settings.siteRules.first()
                browser.navigateAndExtract(current, next) { host -> SiteRule.match(rules, host) }
            }
            if (chapter.status == ExtractionStatus.OK) {
                open(chapter)
            } else {
                // 보안 확인 등으로 추출에 실패: 브라우저 화면을 보여 주고 사용자가 직접 통과하게 한다.
                state = state.copy(phase = ReaderPhase.DONE)
                onNeedBrowser(failureMessage(chapter))
            }
        }
    }

    // ---------- 미리 번역 ----------

    /**
     * 설정이 켜져 있으면, 지금 보고 있는 회차의 다음 1회차를 화면 뒤에서 미리 번역해 저장해 둔다.
     * 다음 화의 주소를 아는 경우에만 한다(버튼으로만 이동하는 사이트는 하지 않는다).
     */
    private suspend fun maybePrefetchNext(current: ExtractedChapter) {
        val currentUrl = strip(current.url)
        if (shownUrl != currentUrl || !app.settings.prefetchNext.first()) return
        val nextUrl = current.nextUrl?.let(::strip) ?: return
        if (app.works.chapter(nextUrl) != null) {
            note(currentUrl, "다음 화가 번역되어 있습니다")
            return
        }
        if (live[nextUrl] != null || (pendingNext != null && pendingFrom == currentUrl)) return
        val rules = app.settings.siteRules.first()
        // 설정과 저장소를 읽는 사이에 뷰어를 닫았거나 다른 회차로 갔으면 브라우저를 건드리지 않는다.
        if (shownUrl != currentUrl) return

        cancelPrefetchNavigation()
        note(currentUrl, "다음 화를 미리 불러오는 중…")
        val extraction = scope.async {
            browser.navigateAndExtract(current, next = true) { host -> SiteRule.match(rules, host) }
        }
        pendingNext = extraction
        pendingFrom = currentUrl
        prefetchJob = scope.launch {
            val chapter = extraction.await()
            // 그 사이에 사용자가 "다음 화"를 눌러 이 결과를 넘겨받았거나, 다른 회차로 가 버렸으면 여기서 그만둔다.
            if (pendingNext !== extraction) return@launch
            pendingNext = null
            pendingFrom = null
            if (shownUrl != currentUrl) return@launch
            if (chapter.status != ExtractionStatus.OK) {
                Log.i(TAG, "prefetch skipped: ${chapter.status}")
                note(currentUrl, "다음 화를 미리 번역하지 못했습니다 (페이지를 읽지 못함)")
                return@launch
            }
            val url = strip(chapter.url)
            if (app.works.chapter(url) != null || live[url] != null) return@launch
            prefetchTarget = url
            prefetchSource = currentUrl
            note(currentUrl, "다음 화를 미리 번역하는 중…")
            startRun(chapter, url)
        }
    }

    /** 그 회차를 보고 있는 동안에만 미리 번역 안내를 붙인다. */
    private fun note(forUrl: String, text: String?) {
        if (shownUrl == forUrl && state.chapter != null) state = state.copy(nextNote = text)
    }

    // ---------- 번역 실행 ----------

    /** 그 회차의 번역 상태를 바꾸고, 뷰어가 그 회차를 보고 있으면 화면에도 반영한다. */
    private fun update(url: String, change: ReaderState.() -> ReaderState) {
        val next = (live[url] ?: return).change()
        live[url] = next
        if (shownUrl == url) state = next.copy(openCount = state.openCount)
    }

    private fun startRun(chapter: ExtractedChapter, url: String) {
        live[url] = ReaderState(
            chapter = chapter,
            workKey = WorkKey.of(chapter.url, chapter.workTitle),
            phase = ReaderPhase.REQUESTING,
        )
        // 작업을 목록에 올린 뒤에 시작한다(시작하자마자 끝나도 정리 순서가 꼬이지 않게).
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            var succeeded = false
            try {
                succeeded = translate(chapter, url)
            } finally {
                // 취소된 뒤 같은 회차의 번역이 새로 시작됐을 수 있다("다시 번역"). 그때는 새 번역의 기록을 건드리지 않는다.
                if (runs[url] === job) {
                    val wasPrefetch = prefetchTarget == url
                    val source = prefetchSource
                    runs.remove(url)
                    live.remove(url)
                    if (wasPrefetch) {
                        prefetchTarget = null
                        prefetchSource = null
                        if (source != null) note(source, if (succeeded) "다음 화 번역 완료" else "다음 화를 미리 번역하지 못했습니다")
                    }
                }
            }
            // 보고 있는 회차의 번역이 끝났으면, 설정에 따라 그다음 회차를 미리 번역한다.
            if (succeeded && shownUrl == url) maybePrefetchNext(chapter)
        }
        runs[url] = job
        job.start()
    }

    /** @return 번역이 끝까지 되어 저장됐으면 true */
    private suspend fun translate(chapter: ExtractedChapter, url: String): Boolean {
        val settings = app.settings
        val providerId = settings.aiProvider.first().ifBlank { app.providers.first().id }
        val provider = app.provider(providerId)
        val keys = app.apiKeys.keysFor(provider.id)
        val model = settings.aiModels(provider.id).first()
            .firstOrNull { it.id == settings.aiModelId(provider.id).first() }
        if (keys.isEmpty() || model == null) {
            update(url) {
                copy(
                    phase = ReaderPhase.ERROR,
                    needsSetup = true,
                    error = if (keys.isEmpty()) "API 키가 등록되어 있지 않습니다." else "번역에 쓸 모델을 선택하지 않았습니다.",
                )
            }
            return false
        }

        // 작품별 설정 읽기. 미리 번역일 수도 있으므로 "마지막으로 본 회차"는 여기서 건드리지 않는다.
        val work = app.works.ensure(chapter)
        val prompt = if (work.usesCustomPrompt) work.customPrompt else settings.globalPrompt.first().ifBlank { app.defaultPrompt }
        val globalTerms = if (work.useGlobalGlossary) Glossary.parse(settings.globalGlossary.first()).entries else emptyList()
        val glossary = Glossary.merge(globalTerms, Glossary.parse(work.glossary).entries)

        val cachedSystem = PromptBuilder.cachedSystem(prompt)
        val effort = settings.aiEffort.first().ifBlank { null }
        val cacheTtl = settings.aiCacheTtl.first()
        val chunks = ChapterSplitter.split(chapter.paragraphs)
        update(url) { copy(workKey = work.workKey, modelName = model.label, chunkCount = chunks.size) }

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
                update(url) { copy(chunkIndex = index + 1, glossaryTerms = sentTerms.size, phase = ReaderPhase.REQUESTING) }
                val result = translateChunk(url, provider, keys, request, userText.length, prefix = done.toString()) { spent ->
                    usage += spent
                }
                done.append(result.trim()).append("\n\n")
            }
            val text = done.toString().trimEnd()
            Log.i(TAG, "translate done chunks=${chunks.size} terms=${sentTerms.size} usage=$usage chars=${text.length}")
            update(url) { copy(phase = ReaderPhase.DONE, text = text, usage = usage, retrying = false) }
            app.usage.record(work, chapter, model, usage, cacheTtl, success = true)

            // 다시 열 때 API 를 부르지 않도록 기기에 저장한다.
            val now = System.currentTimeMillis()
            app.works.saveChapter(
                ChapterEntity(
                    url = url,
                    workKey = work.workKey,
                    title = chapter.chapterTitle,
                    translatedTitle = PromptBuilder.parseTranslation(text).title,
                    translation = text,
                    sourceJson = WorkRepository.encodeParagraphs(chapter.paragraphs),
                    nextUrl = chapter.nextUrl,
                    prevUrl = chapter.prevUrl,
                    modelName = model.label,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    cacheWriteTokens = usage.cacheWriteTokens,
                    cacheReadTokens = usage.cacheReadTokens,
                    glossaryTerms = sentTerms.size,
                    chunkCount = chunks.size,
                    createdAt = now,
                    translatedAt = now,
                    readAt = now,
                ),
            )
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "번역 실패: ${e.javaClass.simpleName} ${e.message}")
            // 실패했어도 받은 만큼은 요금이 나가므로 사용 기록에 남긴다.
            if (usage != TokenUsage()) {
                app.usage.record(work, chapter, model, usage, cacheTtl, success = false)
            }
            update(url) {
                copy(
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
            return false
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
        url: String,
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
                update(url) { copy(retrying = true, phase = ReaderPhase.REQUESTING, text = prefix) }
            }
            try {
                val (text, finished) = streamWithRotation(url, provider, keys, request, prefix)
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
        url: String,
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
            update(url) { copy(text = prefix, phase = ReaderPhase.REQUESTING) }
            provider.streamTranslation(byId.getValue(ref.id).value, request).collect { event ->
                when (event) {
                    is StreamEvent.Started -> Unit
                    StreamEvent.Thinking -> if (text.isEmpty()) update(url) { copy(phase = ReaderPhase.THINKING) }
                    is StreamEvent.Text -> {
                        text.append(event.delta)
                        update(url) { copy(phase = ReaderPhase.STREAMING, text = prefix + text) }
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

        private fun strip(url: String) = BrowserController.stripHash(url)

        fun failureMessage(chapter: ExtractedChapter): String = when (chapter.status) {
            ExtractionStatus.BLOCKED -> "보안 확인 페이지가 나타났습니다. 직접 통과한 뒤 번역 버튼을 다시 눌러 주세요."
            ExtractionStatus.TOO_SHORT -> "이 페이지에서 본문을 찾지 못했습니다. 회차 페이지가 맞는지 확인한 뒤 번역 버튼을 눌러 주세요."
            ExtractionStatus.ERROR -> chapter.errorMessage ?: "본문 추출에 실패했습니다."
            ExtractionStatus.OK -> ""
        }
    }
}
