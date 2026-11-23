package com.clauder.reader

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.clauder.reader.ai.ApiException
import com.clauder.reader.ai.KeyRotator
import com.clauder.reader.browser.BrowserController
import com.clauder.reader.browser.BrowserScreen
import com.clauder.reader.data.SettingsStore
import com.clauder.reader.data.SiteRule
import com.clauder.reader.data.ViewerSettings as ViewerSettingsData
import com.clauder.reader.data.ViewerTaps
import com.clauder.reader.data.db.WorkEntity
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import com.clauder.reader.translate.Glossary
import com.clauder.reader.translate.ReaderController
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.library.ChapterListScreen
import com.clauder.reader.ui.library.LibraryScreen
import com.clauder.reader.ui.preview.ExtractionPreviewScreen
import com.clauder.reader.ui.settings.AiKeysScreen
import com.clauder.reader.ui.settings.AiSettingsScreen
import com.clauder.reader.ui.settings.LongTextEditScreen
import com.clauder.reader.ui.settings.PromptHubScreen
import com.clauder.reader.ui.settings.SettingsScreen
import com.clauder.reader.ui.settings.SiteRuleEditScreen
import com.clauder.reader.ui.settings.SiteRulesScreen
import com.clauder.reader.ui.settings.WorkSettingsScreen
import com.clauder.reader.ui.theme.AppColors
import com.clauder.reader.ui.theme.ClauderTheme
import com.clauder.reader.ui.viewer.PageInput
import com.clauder.reader.ui.viewer.ViewerScreen
import com.clauder.reader.ui.viewer.ViewerSettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var browser: BrowserController
    private lateinit var reader: ReaderController

    /** 브라우저 화면 아래쪽에 보여 줄 안내(추출 실패 등). 값이 생기면 브라우저 화면으로 돌아간다. */
    private val browserNotice = mutableStateOf<String?>(null)

    /** 뷰어가 열려 있는 동안에만 값이 있다. 볼륨 키와 리모컨 입력을 뷰어의 페이지 넘김으로 돌린다. */
    private var pageInput: PageInput? = null

    /** 리모컨이 보낸 터치 한 번(누름~뗌)을 처리하는 중인지와 누른 위치 */
    private var remoteTouchActive = false
    private var remoteDownX = 0f
    private var remoteDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = false)

        val app = application as ClauderApp
        browser = BrowserController(this) { url ->
            if (url.startsWith("http")) lifecycleScope.launch { app.settings.setLastUrl(url) }
        }
        reader = ReaderController(app, lifecycleScope, browser) { message -> browserNotice.value = message }

        // 마지막으로 보던 페이지를 다시 연다.
        lifecycleScope.launch {
            val start = debugUrl(intent) ?: app.settings.lastUrl.first()
            if (start.isNotBlank()) browser.load(start)
        }

        setContent {
            ClauderTheme {
                AppRoot(
                    app = app,
                    browser = browser,
                    reader = reader,
                    browserNotice = browserNotice,
                    registerPageInput = { input -> pageInput = input },
                    setDarkSystemBars = ::applySystemBars,
                    setSystemBarsHidden = ::setSystemBarsHidden,
                )
            }
        }
    }

    /**
     * 상태 표시줄과 시스템 버튼의 아이콘 색을 정한다. 앱 화면은 밝은 배경이라 어두운 아이콘을 쓰고,
     * 뷰어가 어두운 테마일 때만 밝은 아이콘으로 바꾼다(폰의 다크 모드 설정과는 무관하게).
     */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /**
     * 뷰어에서 읽는 동안 상태 표시줄과 시스템 버튼을 숨기거나 다시 보인다.
     * 숨겨진 동안에도 화면 가장자리에서 쓸어 넘기면 잠깐 나타난다.
     */
    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * 볼륨 키와 리모컨의 페이지 키를 뷰어의 페이지 넘김으로 돌린다.
     * 누름과 뗌을 모두 가로채야 볼륨 조절 창이 뜨지 않는다.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = pageInput?.takeIf { it.keys }?.turn
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> true
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
            else -> null
        }
        if (handler != null && forward != null) {
            // 길게 눌러 생기는 반복 입력은 무시한다(한 번 누를 때 한 화면).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handler(forward)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 블루투스 리모컨 중에는 키 신호 대신 "화면을 빠르게 미는 터치"를 보내는 것이 있다(R1 등).
     * 뷰어가 열려 있을 때 외부 터치 장치에서 온 밀기 동작을 페이지 넘김으로 바꾼다.
     * 손가락으로 폰 화면을 만지는 것은 내장 장치라서 여기에 걸리지 않는다.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val input = pageInput?.takeIf { it.remoteSwipe }
        val fromRemote = ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ev.device?.isExternal == true
        if (!fromRemote || (input == null && !remoteTouchActive)) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                remoteTouchActive = true
                remoteDownX = ev.x
                remoteDownY = ev.y
            }

            MotionEvent.ACTION_UP -> {
                if (remoteTouchActive && input != null) {
                    val minDistance = 40 * resources.displayMetrics.density
                    ViewerTaps.remoteSwipeForward(ev.x - remoteDownX, ev.y - remoteDownY, minDistance, input.remoteSwipeReversed)
                        ?.let(input.turn)
                }
                remoteTouchActive = false
            }

            MotionEvent.ACTION_CANCEL -> remoteTouchActive = false
        }
        // 리모컨의 터치가 화면의 버튼이나 글을 건드리지 않게 여기서 끝낸다.
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugUrl(intent)?.let(browser::load)
    }

    /** 개발용: adb 로 주소를 넘겨 실행하면 그 페이지를 연다. */
    private fun debugUrl(intent: Intent?): String? {
        if (!BuildConfig.DEBUG) return null
        return intent?.getStringExtra("url")?.takeIf { it.isNotBlank() }
    }

    override fun onPause() {
        browser.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser.onResume()
    }

    override fun onDestroy() {
        reader.close()
        browser.destroy()
        super.onDestroy()
    }
}

private enum class Tab(val label: String, val iconRes: Int) {
    Translate("번역", R.drawable.ic_tab_translate),
    Library("서재", R.drawable.ic_tab_library),
    Settings("설정", R.drawable.ic_tab_settings),
}

/** 탭 위에 전체 화면으로 덮어 띄우는 화면들 */
private sealed interface Screen {
    data object Viewer : Screen
    data class Preview(val chapter: ExtractedChapter) : Screen
    data object SiteRules : Screen
    data class SiteRuleEdit(val index: Int?) : Screen
    data object AiSettings : Screen
    data class AiKeys(val providerId: String) : Screen
    data object PromptHub : Screen
    data object ViewerSettings : Screen
    data class ChapterList(val workKey: String) : Screen
    data object EditGlobalPrompt : Screen
    data object EditGlobalGlossary : Screen
    data class WorkSettings(val workKey: String) : Screen
    data class EditWorkPrompt(val workKey: String) : Screen
    data class EditWorkGlossary(val workKey: String) : Screen
}

@Composable
private fun AppRoot(
    app: ClauderApp,
    browser: BrowserController,
    reader: ReaderController,
    browserNotice: MutableState<String?>,
    registerPageInput: (PageInput?) -> Unit,
    setDarkSystemBars: (Boolean) -> Unit,
    setSystemBarsHidden: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings = app.settings
    var tab by rememberSaveable { mutableStateOf(Tab.Translate) }
    val stack = remember { mutableStateListOf<Screen>() }
    val rules by settings.siteRules.collectAsState(initial = emptyList())
    var extracting by remember { mutableStateOf(false) }

    // 마지막으로 추출에 성공한 작품명과 그 사이트(다른 사이트로 가면 표시하지 않음)
    var lastWork by remember { mutableStateOf("") }
    var lastWorkHost by remember { mutableStateOf("") }
    val currentHost = browser.currentHost

    // AI 설정
    val providerId = app.providers.first().id
    val apiKeys by app.apiKeys.keys.collectAsState(initial = emptyList())
    val models by remember(providerId) { settings.aiModels(providerId) }.collectAsState(initial = emptyList())
    val modelId by remember(providerId) { settings.aiModelId(providerId) }.collectAsState(initial = "")
    val effort by settings.aiEffort.collectAsState(initial = "")
    val cacheTtl by settings.aiCacheTtl.collectAsState(initial = SettingsStore.DEFAULT_CACHE_TTL)

    val viewerSettings by settings.viewerSettings.collectAsState(initial = ViewerSettingsData())
    val saveViewerSettings: (ViewerSettingsData) -> Unit = { value -> scope.launch { settings.setViewerSettings(value) } }

    // 프롬프트와 용어집
    val globalPrompt by settings.globalPrompt.collectAsState(initial = "")
    val globalGlossary by settings.globalGlossary.collectAsState(initial = "")
    val works by app.works.all.collectAsState(initial = emptyList())
    val chapterCountList by app.works.chapterCounts.collectAsState(initial = emptyList())
    val chapterCounts = remember(chapterCountList) { chapterCountList.associate { it.workKey to it.count } }
    val lastSavedList by app.works.worksWithLastChapterSaved.collectAsState(initial = emptyList())
    val lastChapterSaved = remember(lastSavedList) { lastSavedList.toSet() }

    /**
     * 회차를 이어서 본다. 번역이 저장되어 있으면 뷰어로 바로 열고,
     * 진입만 하고 번역이 끝나지 않은 회차면 사이트에서 그 페이지를 열어 번역 버튼을 누르게 한다.
     */
    fun openSavedChapter(url: String, title: String = "") {
        if (url.isBlank()) return
        reader.openSaved(url) {
            browser.load(url)
            val name = if (title.isBlank()) "이 회차" else "\"$title\""
            browserNotice.value = "${name}는 아직 번역되지 않았습니다. 페이지가 열리면 번역 버튼을 눌러 주세요."
        }
        stack.add(Screen.Viewer)
    }
    val globalGlossarySummary = remember(globalGlossary) {
        if (globalGlossary.isBlank()) "항목 없음" else Glossary.parse(globalGlossary).summary()
    }
    var loadingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /** 지금 페이지에서 본문을 추출한다. 성공하면 [onOk] 를 부르고, 실패하면 브라우저에 안내를 띄운다. */
    fun extractThen(showFailureAsNotice: Boolean, onOk: (ExtractedChapter) -> Unit, onFail: (ExtractedChapter) -> Unit = {}) {
        if (extracting) return
        scope.launch {
            extracting = true
            try {
                val chapter = browser.extract(SiteRule.match(rules, currentHost))
                if (chapter.status == ExtractionStatus.OK) {
                    if (chapter.workTitle.isNotBlank()) {
                        lastWork = chapter.workTitle
                        lastWorkHost = currentHost
                    }
                    browserNotice.value = null
                    onOk(chapter)
                } else {
                    if (showFailureAsNotice) browserNotice.value = ReaderController.failureMessage(chapter)
                    onFail(chapter)
                }
            } finally {
                extracting = false
            }
        }
    }

    fun refreshModels() {
        if (loadingModels) return
        scope.launch {
            loadingModels = true
            modelError = null
            try {
                val provider = app.provider(providerId)
                val key = app.apiKeys.keysFor(provider.id).firstOrNull()
                if (key == null) {
                    modelError = "API 키를 먼저 등록하세요."
                    return@launch
                }
                val list = provider.listModels(key.value)
                settings.setAiModels(provider.id, list)
                // 고른 모델이 없으면 기본 모델을 골라 둔다(설정에서 바꿀 수 있다).
                if (list.none { it.id == modelId }) {
                    val default = list.firstOrNull { it.id == DEFAULT_MODEL_ID } ?: list.firstOrNull()
                    if (default != null) settings.setAiModelId(provider.id, default.id)
                }
            } catch (e: ApiException) {
                modelError = e.userMessage()
            } catch (e: Exception) {
                modelError = "모델 목록을 불러오지 못했습니다: ${e.message}"
            } finally {
                loadingModels = false
            }
        }
    }

    // 다음 화 추출 실패 등으로 안내가 생기면 뷰어를 닫고 브라우저 화면을 보여 준다.
    LaunchedEffect(browserNotice.value) {
        if (browserNotice.value != null) {
            stack.removeAll { it == Screen.Viewer }
            tab = Tab.Translate
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) {
        if (stack.lastOrNull() == Screen.Viewer) reader.close()
        pop()
    }

    Box(Modifier.fillMaxSize()) {
        // 브라우저(WebView)는 다른 화면에 가려져 있어도 계속 붙어 있어야
        // 뷰어에서 다음 화를 불러올 때 페이지가 정상적으로 로드된다.
        MainTabs(
            tab = tab,
            onTab = { tab = it },
            browser = browser,
            browserActive = stack.isEmpty() && tab == Tab.Translate,
            workLabel = if (lastWorkHost == currentHost) lastWork else "",
            extracting = extracting,
            notice = browserNotice.value,
            onDismissNotice = { browserNotice.value = null },
            onTranslate = {
                extractThen(showFailureAsNotice = true, onOk = { chapter ->
                    reader.open(chapter)
                    stack.add(Screen.Viewer)
                })
            },
            onPreview = {
                extractThen(
                    showFailureAsNotice = false,
                    onOk = { stack.add(Screen.Preview(it)) },
                    onFail = { stack.add(Screen.Preview(it)) },
                )
            },
            siteRuleCount = rules.size,
            aiSummary = when {
                apiKeys.none { it.provider == providerId } -> "API 키를 등록하세요"
                modelId.isBlank() -> "모델을 선택하세요"
                else -> models.firstOrNull { it.id == modelId }?.label ?: modelId
            },
            promptSummary = (if (globalPrompt.isBlank()) "기본 프롬프트" else "수정한 프롬프트") + " · 전역 용어집 " + globalGlossarySummary,
            onOpenSiteRules = { stack.add(Screen.SiteRules) },
            onOpenAiSettings = { stack.add(Screen.AiSettings) },
            onOpenPrompts = { stack.add(Screen.PromptHub) },
            viewerSummary = viewerSummary(viewerSettings),
            onOpenViewerSettings = { stack.add(Screen.ViewerSettings) },
            works = works,
            chapterCounts = chapterCounts,
            lastChapterSaved = lastChapterSaved,
            onResumeWork = { work -> openSavedChapter(work.lastChapterUrl, work.lastChapterTitle) },
            onOpenWorkChapters = { work -> stack.add(Screen.ChapterList(work.workKey)) },
            onOpenWorkSettings = { work -> stack.add(Screen.WorkSettings(work.workKey)) },
            onOpenWorkInBrowser = { work ->
                if (work.lastChapterUrl.isNotBlank()) {
                    browser.load(work.lastChapterUrl)
                    tab = Tab.Translate
                }
            },
            onDeleteWork = { work -> scope.launch { app.works.delete(work.workKey) } },
        )

        val top = stack.lastOrNull()
        if (top != null) {
            // 아래에 깔린 화면으로 터치가 새지 않게 막는다.
            Box(Modifier.fillMaxSize().background(AppColors.Background).pointerInput(Unit) {}) {
                when (top) {
                    Screen.Viewer -> ViewerScreen(
                        state = reader.state,
                        settings = viewerSettings,
                        onSettingsChange = saveViewerSettings,
                        registerPageInput = registerPageInput,
                        onPosition = reader::savePosition,
                        setDarkSystemBars = setDarkSystemBars,
                        setSystemBarsHidden = setSystemBarsHidden,
                        onBack = {
                            reader.close()
                            pop()
                        },
                        onPrev = reader::goPrev,
                        onNext = reader::goNext,
                        onRetranslate = reader::retranslate,
                        onOpenAiSettings = { stack.add(Screen.AiSettings) },
                        onOpenWorkSettings = { stack.add(Screen.WorkSettings(reader.state.workKey)) },
                    )

                    is Screen.Preview -> ExtractionPreviewScreen(
                        chapter = top.chapter,
                        onBack = ::pop,
                        onOpenNext = {
                            browser.navigate(top.chapter, next = true)
                            pop()
                        },
                        onOpenPrev = {
                            browser.navigate(top.chapter, next = false)
                            pop()
                        },
                    )

                    Screen.SiteRules -> SiteRulesScreen(
                        rules = rules,
                        onBack = ::pop,
                        onAdd = { stack.add(Screen.SiteRuleEdit(null)) },
                        onEdit = { stack.add(Screen.SiteRuleEdit(it)) },
                    )

                    is Screen.SiteRuleEdit -> {
                        val existing = top.index?.let(rules::getOrNull)
                        SiteRuleEditScreen(
                            // 새 규칙은 지금 브라우저에 열려 있는 사이트 도메인을 미리 채워 준다.
                            initial = existing ?: SiteRule(domain = SiteRule.normalizeDomain(browser.url)),
                            isNew = existing == null,
                            onBack = ::pop,
                            onSave = { saved ->
                                val updated = if (existing != null) {
                                    rules.map { if (it === existing) saved else it }
                                } else {
                                    rules.filterNot { it.domain == saved.domain } + saved
                                }
                                scope.launch { settings.setSiteRules(updated) }
                                pop()
                            },
                            onDelete = {
                                scope.launch { settings.setSiteRules(rules.filterNot { it === existing }) }
                                pop()
                            },
                        )
                    }

                    Screen.AiSettings -> AiSettingsScreen(
                        providers = app.providers,
                        keyCounts = apiKeys.groupingBy { it.provider }.eachCount(),
                        models = models,
                        selectedModelId = modelId,
                        effort = effort,
                        cacheTtl = cacheTtl,
                        loadingModels = loadingModels,
                        modelError = modelError,
                        onBack = ::pop,
                        onOpenKeys = { stack.add(Screen.AiKeys(it)) },
                        onRefreshModels = ::refreshModels,
                        onSelectModel = { model -> scope.launch { settings.setAiModelId(providerId, model.id) } },
                        onSelectEffort = { value -> scope.launch { settings.setAiEffort(value) } },
                        onSelectCacheTtl = { value -> scope.launch { settings.setAiCacheTtl(value) } },
                    )

                    is Screen.AiKeys -> AiKeysScreen(
                        providerName = app.providers.firstOrNull { it.id == top.providerId }?.displayName.orEmpty(),
                        keys = apiKeys.filter { it.provider == top.providerId },
                        restingStatus = { key ->
                            app.keyRotator.cooldownOf(key.id)?.let { rest ->
                                val left = KeyRotator.restText(app.keyRotator.remainingSeconds(key.id))
                                "쉬는 중 · ${KeyRotator.reason(rest.kind)} · $left 남음"
                            }
                        },
                        onBack = ::pop,
                        onAdd = { value ->
                            scope.launch {
                                val added = app.apiKeys.add(top.providerId, value)
                                // 첫 키를 등록하면 모델 목록을 바로 불러온다.
                                if (added && models.isEmpty()) refreshModels()
                            }
                        },
                        onDelete = { id -> scope.launch { app.apiKeys.remove(id) } },
                    )

                    Screen.ViewerSettings -> ViewerSettingsScreen(viewerSettings, saveViewerSettings, onBack = ::pop)

                    is Screen.ChapterList -> {
                        val saved by remember(top.workKey) { app.works.chaptersOf(top.workKey) }.collectAsState(initial = emptyList())
                        ChapterListScreen(
                            work = works.firstOrNull { it.workKey == top.workKey },
                            chapters = saved,
                            onBack = ::pop,
                            onOpen = ::openSavedChapter,
                        )
                    }

                    Screen.PromptHub -> PromptHubScreen(
                        promptCustomized = globalPrompt.isNotBlank(),
                        glossarySummary = globalGlossarySummary,
                        works = works,
                        onBack = ::pop,
                        onEditPrompt = { stack.add(Screen.EditGlobalPrompt) },
                        onEditGlossary = { stack.add(Screen.EditGlobalGlossary) },
                        onOpenWork = { stack.add(Screen.WorkSettings(it)) },
                    )

                    Screen.EditGlobalPrompt -> LongTextEditScreen(
                        title = "번역 프롬프트",
                        description = "모든 작품에 쓰는 번역 지침입니다. 앱이 이 뒤에 \"최종 번역문만 출력\" 같은 고정 지시를 덧붙입니다.",
                        initial = globalPrompt.ifBlank { app.defaultPrompt },
                        isGlossary = false,
                        restoreLabel = "기본값 복원",
                        restoreText = app.defaultPrompt,
                        onBack = ::pop,
                        onSave = { text ->
                            // 기본 프롬프트와 같으면 "수정 안 함"으로 저장해, 앱의 기본값이 바뀌면 따라가게 한다.
                            val value = if (text.trim() == app.defaultPrompt.trim()) "" else text.trim()
                            scope.launch { settings.setGlobalPrompt(value) }
                            pop()
                        },
                    )

                    Screen.EditGlobalGlossary -> LongTextEditScreen(
                        title = "전역 용어집",
                        description = "한 줄에 하나씩 原文=번역 형식으로 적습니다. 긴 목록을 한 번에 붙여넣어도 됩니다.",
                        initial = globalGlossary,
                        isGlossary = true,
                        onBack = ::pop,
                        onSave = { text ->
                            scope.launch { settings.setGlobalGlossary(text.trim()) }
                            pop()
                        },
                    )

                    is Screen.WorkSettings -> {
                        val work = works.firstOrNull { it.workKey == top.workKey }
                        if (work == null) {
                            // 아직 목록을 읽는 중이거나 등록되지 않은 작품
                            Column(Modifier.fillMaxSize()) {
                                BackTopBar("작품 설정", ::pop)
                                Text(
                                    "작품 정보를 찾지 못했습니다. 이 작품을 한 번 번역한 뒤 다시 열어 주세요.",
                                    color = AppColors.TextSecondary,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        } else {
                            WorkSettingsScreen(
                                work = work,
                                onBack = ::pop,
                                onSetCustomPrompt = { custom ->
                                    scope.launch {
                                        app.works.update(work.workKey) {
                                            it.copy(promptMode = if (custom) WorkEntity.PROMPT_CUSTOM else WorkEntity.PROMPT_GLOBAL)
                                        }
                                    }
                                },
                                onEditPrompt = { stack.add(Screen.EditWorkPrompt(work.workKey)) },
                                onSetUseGlobalGlossary = { use ->
                                    scope.launch { app.works.update(work.workKey) { it.copy(useGlobalGlossary = use) } }
                                },
                                onEditGlossary = { stack.add(Screen.EditWorkGlossary(work.workKey)) },
                            )
                        }
                    }

                    is Screen.EditWorkPrompt -> {
                        val work = works.firstOrNull { it.workKey == top.workKey }
                        val currentGlobal = globalPrompt.ifBlank { app.defaultPrompt }
                        LongTextEditScreen(
                            title = "작품 전용 프롬프트",
                            description = "${work?.title.orEmpty()} 에만 쓰는 번역 지침입니다. 전역 프롬프트 대신 사용됩니다.",
                            // 처음에는 전역 프롬프트에서 시작해 고쳐 쓰게 한다.
                            initial = work?.customPrompt?.ifBlank { currentGlobal } ?: currentGlobal,
                            isGlossary = false,
                            restoreLabel = "전역 프롬프트 불러오기",
                            restoreText = currentGlobal,
                            onBack = ::pop,
                            onSave = { text ->
                                scope.launch { app.works.update(top.workKey) { it.copy(customPrompt = text.trim()) } }
                                pop()
                            },
                        )
                    }

                    is Screen.EditWorkGlossary -> {
                        val work = works.firstOrNull { it.workKey == top.workKey }
                        LongTextEditScreen(
                            title = "작품 용어집",
                            description = "${work?.title.orEmpty()} 에만 쓰는 용어집입니다. 한 줄에 하나씩 原文=번역 형식으로 적습니다.",
                            initial = work?.glossary.orEmpty(),
                            isGlossary = true,
                            onBack = ::pop,
                            onSave = { text ->
                                scope.launch { app.works.update(top.workKey) { it.copy(glossary = text.trim()) } }
                                pop()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun viewerSummary(v: ViewerSettingsData): String {
    val theme = when (v.theme) {
        ViewerSettingsData.THEME_DARK -> "어둡게"
        ViewerSettingsData.THEME_SEPIA -> "세피아"
        else -> "밝게"
    }
    return "글자 ${v.fontSize} · 줄 간격 ${v.lineSpacing / 10}.${v.lineSpacing % 10}배 · $theme" +
        if (v.volumeKeyPaging) " · 볼륨 키 넘김" else ""
}

/** 모델 목록을 처음 불러왔을 때 골라 둘 기본 모델 */
private const val DEFAULT_MODEL_ID = "claude-opus-5-5"

@Composable
private fun MainTabs(
    tab: Tab,
    onTab: (Tab) -> Unit,
    browser: BrowserController,
    browserActive: Boolean,
    workLabel: String,
    extracting: Boolean,
    notice: String?,
    onDismissNotice: () -> Unit,
    onTranslate: () -> Unit,
    onPreview: () -> Unit,
    siteRuleCount: Int,
    aiSummary: String,
    promptSummary: String,
    onOpenSiteRules: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenPrompts: () -> Unit,
    viewerSummary: String,
    onOpenViewerSettings: () -> Unit,
    works: List<WorkEntity>,
    chapterCounts: Map<String, Int>,
    lastChapterSaved: Set<String>,
    onResumeWork: (WorkEntity) -> Unit,
    onOpenWorkChapters: (WorkEntity) -> Unit,
    onOpenWorkSettings: (WorkEntity) -> Unit,
    onOpenWorkInBrowser: (WorkEntity) -> Unit,
    onDeleteWork: (WorkEntity) -> Unit,
) {
    Scaffold(
        containerColor = AppColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                HorizontalDivider(color = AppColors.Outline)
                NavigationBar(containerColor = AppColors.Background, tonalElevation = 0.dp) {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { onTab(item) },
                            icon = { Icon(painterResource(item.iconRes), contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.Blue,
                                selectedTextColor = AppColors.Blue,
                                unselectedIconColor = AppColors.TextSecondary,
                                unselectedTextColor = AppColors.TextSecondary,
                                indicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
        Box {
            // 브라우저는 항상 붙여 두고, 다른 탭은 그 위에 덮어 그린다.
            BrowserScreen(
                browser = browser,
                active = browserActive,
                workLabel = workLabel,
                extracting = extracting,
                notice = notice,
                onDismissNotice = onDismissNotice,
                onTranslate = onTranslate,
                onPreview = onPreview,
                modifier = content,
            )
            val cover = content.background(AppColors.Background).pointerInput(Unit) {}
            when (tab) {
                Tab.Translate -> Unit
                Tab.Library -> LibraryScreen(
                    works = works,
                    chapterCounts = chapterCounts,
                    lastChapterSaved = lastChapterSaved,
                    onResume = onResumeWork,
                    onOpenChapters = onOpenWorkChapters,
                    onOpenSettings = onOpenWorkSettings,
                    onOpenInBrowser = onOpenWorkInBrowser,
                    onDelete = onDeleteWork,
                    modifier = cover,
                )
                Tab.Settings -> SettingsScreen(
                    siteRuleCount = siteRuleCount,
                    aiSummary = aiSummary,
                    promptSummary = promptSummary,
                    viewerSummary = viewerSummary,
                    onOpenSiteRules = onOpenSiteRules,
                    onOpenAiSettings = onOpenAiSettings,
                    onOpenPrompts = onOpenPrompts,
                    onOpenViewerSettings = onOpenViewerSettings,
                    modifier = cover,
                )
            }
        }
    }
}

