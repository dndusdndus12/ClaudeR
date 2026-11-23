package com.clauder.reader

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.clauder.reader.browser.BrowserController
import com.clauder.reader.browser.BrowserScreen
import com.clauder.reader.data.SettingsStore
import com.clauder.reader.data.SiteRule
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import com.clauder.reader.ui.preview.ExtractionPreviewScreen
import com.clauder.reader.ui.settings.SettingsScreen
import com.clauder.reader.ui.settings.SiteRuleEditScreen
import com.clauder.reader.ui.settings.SiteRulesScreen
import com.clauder.reader.ui.theme.AppColors
import com.clauder.reader.ui.theme.ClauderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var browser: BrowserController

    /** 개발용: adb 로 주소와 함께 실행하면 페이지가 열린 뒤 자동으로 추출까지 해 본다. */
    private val debugAutoExtract = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱 화면이 항상 밝은 배경이므로, 폰이 다크 모드여도 상태 표시줄 아이콘을 어둡게 고정한다.
        val lightBars = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)

        val settings = (application as ClauderApp).settings
        browser = BrowserController(this) { url ->
            if (url.startsWith("http")) lifecycleScope.launch { settings.setLastUrl(url) }
        }

        // 마지막으로 보던 페이지를 다시 연다.
        lifecycleScope.launch {
            val start = debugUrl(intent) ?: settings.lastUrl.first()
            if (start.isNotBlank()) browser.load(start)
        }

        setContent {
            ClauderTheme {
                AppRoot(browser, settings, debugAutoExtract)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugUrl(intent)?.let(browser::load)
    }

    private fun debugUrl(intent: Intent?): String? {
        if (!BuildConfig.DEBUG) return null
        val url = intent?.getStringExtra("url")?.takeIf { it.isNotBlank() } ?: return null
        debugAutoExtract.value = intent.getBooleanExtra("extract", false)
        return url
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
    data class Preview(val chapter: ExtractedChapter) : Screen
    data object SiteRules : Screen
    data class SiteRuleEdit(val index: Int?) : Screen
}

@Composable
private fun AppRoot(browser: BrowserController, settings: SettingsStore, debugAutoExtract: MutableState<Boolean>) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.Translate) }
    val stack = remember { mutableStateListOf<Screen>() }
    val rules by settings.siteRules.collectAsState(initial = emptyList())
    var extracting by remember { mutableStateOf(false) }

    // 마지막으로 추출에 성공한 작품명과 그 사이트(다른 사이트로 가면 표시하지 않음)
    var lastWork by remember { mutableStateOf("") }
    var lastWorkHost by remember { mutableStateOf("") }
    val currentHost = remember(browser.url) { Uri.parse(browser.url).host.orEmpty() }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun extract() {
        if (extracting) return
        scope.launch {
            extracting = true
            try {
                val chapter = browser.extract(SiteRule.match(rules, currentHost))
                if (chapter.status == ExtractionStatus.OK && chapter.workTitle.isNotBlank()) {
                    lastWork = chapter.workTitle
                    lastWorkHost = currentHost
                }
                stack.add(Screen.Preview(chapter))
            } finally {
                extracting = false
            }
        }
    }

    LaunchedEffect(debugAutoExtract.value, browser.isLoading) {
        if (debugAutoExtract.value && browser.hasPage && !browser.isLoading) {
            delay(2000)
            debugAutoExtract.value = false
            extract()
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    when (val top = stack.lastOrNull()) {
        null -> MainTabs(
            tab = tab,
            onTab = { tab = it },
            browser = browser,
            workLabel = if (lastWorkHost == currentHost) lastWork else "",
            extracting = extracting,
            onExtract = ::extract,
            siteRuleCount = rules.size,
            onOpenSiteRules = { stack.add(Screen.SiteRules) },
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
    }
}

@Composable
private fun MainTabs(
    tab: Tab,
    onTab: (Tab) -> Unit,
    browser: BrowserController,
    workLabel: String,
    extracting: Boolean,
    onExtract: () -> Unit,
    siteRuleCount: Int,
    onOpenSiteRules: () -> Unit,
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
        when (tab) {
            Tab.Translate -> BrowserScreen(browser, workLabel, extracting, onExtract, content)
            Tab.Library -> LibraryPlaceholder(content)
            Tab.Settings -> SettingsScreen(siteRuleCount, onOpenSiteRules, content)
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
