package com.clauder.reader

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.clauder.reader.ai.ApiException
import com.clauder.reader.ai.KeyRotator
import com.clauder.reader.browser.BrowserController
import com.clauder.reader.browser.BrowserScreen
import com.clauder.reader.data.SettingsStore
import com.clauder.reader.data.SiteRule
import com.clauder.reader.data.db.WorkEntity
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import com.clauder.reader.translate.Glossary
import com.clauder.reader.translate.ReaderController
import com.clauder.reader.ui.common.BackTopBar
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
import com.clauder.reader.ui.viewer.ViewerScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var browser: BrowserController
    private lateinit var reader: ReaderController

    /** 브라우저 화면 아래쪽에 보여 줄 안내(추출 실패 등). 값이 생기면 브라우저 화면으로 돌아간다. */
    private val browserNotice = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱 화면이 항상 밝은 배경이므로, 폰이 다크 모드여도 상태 표시줄 아이콘을 어둡게 고정한다.
        val lightBars = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)

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
                AppRoot(app, browser, reader, browserNotice)
            }
        }
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

    // 프롬프트와 용어집
    val globalPrompt by settings.globalPrompt.collectAsState(initial = "")
    val globalGlossary by settings.globalGlossary.collectAsState(initial = "")
    val works by app.works.all.collectAsState(initial = emptyList())
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
        )

        val top = stack.lastOrNull()
        if (top != null) {
            // 아래에 깔린 화면으로 터치가 새지 않게 막는다.
            Box(Modifier.fillMaxSize().background(AppColors.Background).pointerInput(Unit) {}) {
                when (top) {
                    Screen.Viewer -> ViewerScreen(
                        state = reader.state,
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
                Tab.Library -> LibraryPlaceholder(cover)
                Tab.Settings -> SettingsScreen(
                    siteRuleCount, aiSummary, promptSummary, onOpenSiteRules, onOpenAiSettings, onOpenPrompts, cover,
                )
            }
        }
    }
}

@Composable
private fun LibraryPlaceholder(modifier: Modifier) {
    Column(modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("서재", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("번역해서 읽은 작품이 여기에 모입니다 (M4에서 구현)", color = AppColors.TextSecondary)
        }
    }
}
