package com.clauder.reader.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.ai.TokenUsage
import com.clauder.reader.data.TapZone
import com.clauder.reader.data.ViewerSettings
import com.clauder.reader.data.ViewerTaps
import com.clauder.reader.translate.PromptBuilder
import com.clauder.reader.translate.ReaderPhase
import com.clauder.reader.translate.ReaderState
import com.clauder.reader.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * 뷰어가 화면 밖(액티비티)에서 받아야 하는 페이지 넘김 입력.
 * 볼륨 키와 리모컨의 터치는 화면 요소가 아니라 액티비티가 먼저 받기 때문에 이렇게 등록해 둔다.
 */
class PageInput(
    /** 볼륨 키 등 키 입력으로 넘길지 */
    val keys: Boolean,
    /** 리모컨의 밀기 터치로 넘길지 */
    val remoteSwipe: Boolean,
    val remoteSwipeReversed: Boolean,
    /** 실제로 넘기는 동작. 인자는 "앞으로 넘기는지". */
    val turn: (forward: Boolean) -> Unit,
)

private val ErrorBg = Color(0xFFFDECEC)
private val ErrorText = Color(0xFFC62828)
private val TopBarHeight = 52.dp
private val BottomBarHeight = 62.dp

/** 페이지 넘김 애니메이션 시간(ms). 빠르게 연달아 넘겨도 답답하지 않을 만큼 짧게 둔다. */
private const val PageSlideMillis = 220

/**
 * 번역문을 읽는 화면.
 * - 화면을 탭하면 위아래 막대가 숨겨지거나 다시 나타난다.
 * - 볼륨 키(블루투스 리모컨)로 한 화면씩 넘긴다.
 *
 * @param registerPageInput 볼륨 키와 리모컨 입력을 받을 곳을 등록한다(null 이면 해제).
 * @param setDarkSystemBars 어두운 테마일 때 상태 표시줄 아이콘을 밝게 바꾼다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    state: ReaderState,
    settings: ViewerSettings,
    onSettingsChange: (ViewerSettings) -> Unit,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRetranslate: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenWorkSettings: () -> Unit,
    registerPageInput: (PageInput?) -> Unit,
    setDarkSystemBars: (Boolean) -> Unit,
    /** 읽던 위치가 바뀌면 알린다(회차 주소, 화면 맨 위 항목 번호, 그 안에서의 어긋남). */
    onPosition: (url: String, index: Int, offset: Int) -> Unit,
) {
    val chapter = state.chapter
    val palette = ViewerPalette.of(settings.theme)
    val translated = remember(state.text) { PromptBuilder.parseTranslation(state.text) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var barsWanted by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 오류가 났을 때는 조치할 수 있도록 막대를 항상 보여 준다.
    val barsVisible = barsWanted || state.phase == ReaderPhase.ERROR

    // 회차를 열 때: 새로 번역하는 회차는 맨 위에서, 저장된 회차는 읽던 위치에서 시작한다.
    LaunchedEffect(state.openCount) { listState.scrollToItem(state.scrollIndex, state.scrollOffset) }

    // 읽던 위치 저장: 스크롤이 잠시 멈추면 저장하고, 화면을 떠날 때도 한 번 더 저장한다.
    val latestPosition by rememberUpdatedState(onPosition)
    val doneUrl = if (state.phase == ReaderPhase.DONE) chapter?.url else null
    LaunchedEffect(state.openCount, doneUrl) {
        val url = doneUrl ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                delay(600)
                latestPosition(url, index, offset)
            }
    }
    DisposableEffect(doneUrl) {
        onDispose {
            if (doneUrl != null) {
                latestPosition(doneUrl, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
    }

    DisposableEffect(palette.dark) {
        setDarkSystemBars(palette.dark)
        onDispose { setDarkSystemBars(false) }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 키 입력은 화면이 다시 그려진 뒤에도 최신 값을 봐야 하므로 최신 값을 가리키는 참조로 넘긴다.
    val latestState by rememberUpdatedState(state)
    val latestSettings by rememberUpdatedState(settings)
    val latestBars by rememberUpdatedState(barsVisible)
    val latestNext by rememberUpdatedState(onNext)

    // 페이지 넘김 애니메이션: 넘기기 직전의 화면을 그림으로 찍어 글 위에 덮고,
    // 그 그림을 옆으로 밀어내면 아래에 이미 넘어가 있는 새 화면이 드러난다.
    val pageLayer = rememberGraphicsLayer()
    var leavingPage by remember { mutableStateOf<ImageBitmap?>(null) }
    val leavingOffset = remember { Animatable(0f) }
    var turnCount by remember { mutableIntStateOf(0) }

    fun turnPage(forward: Boolean) {
        val current = latestState
        if (current.phase == ReaderPhase.LOADING_PAGE) return
        if (!forward && !listState.canScrollBackward) return
        if (forward && !listState.canScrollForward) {
            // 회차 끝: 설정에 따라 다음 화로 넘어간다.
            val canAdvance = current.phase == ReaderPhase.DONE && current.chapter?.hasNext == true
            if (latestSettings.volumeKeyNextChapter && canAdvance) latestNext()
            return
        }
        val distance = with(density) {
            ViewerTaps.pageDistance(
                viewportHeight = listState.layoutInfo.viewportSize.height.toFloat(),
                coveredTop = (statusTop + if (latestBars) TopBarHeight else 0.dp).toPx(),
                coveredBottom = (navBottom + if (latestBars) BottomBarHeight else 0.dp).toPx(),
                lineHeight = latestSettings.lineHeight.sp.toPx(),
                overlapLines = latestSettings.pageOverlapLines,
            )
        }
        val turn = ++turnCount
        scope.launch {
            val snapshot = if (latestSettings.pageAnimation) runCatching { pageLayer.toImageBitmap() }.getOrNull() else null
            if (snapshot != null) {
                leavingOffset.snapTo(0f)
                leavingPage = snapshot
            }
            val moved = listState.scrollBy(if (forward) distance else -distance)
            try {
                // 다음 화면으로 갈 때는 기존 화면이 왼쪽으로, 이전 화면으로 갈 때는 오른쪽으로 밀려난다.
                if (snapshot != null && moved != 0f) {
                    val width = listState.layoutInfo.viewportSize.width.toFloat()
                    leavingOffset.animateTo(if (forward) -width else width, tween(PageSlideMillis, easing = FastOutSlowInEasing))
                }
            } finally {
                // 애니메이션 도중에 또 넘기면 새 넘김이 자기 그림을 올려 두므로, 그때는 지우지 않는다.
                if (turnCount == turn) leavingPage = null
            }
        }
    }

    DisposableEffect(settings.volumeKeyPaging, settings.remoteSwipePaging, settings.remoteSwipeReversed) {
        registerPageInput(
            PageInput(
                keys = settings.volumeKeyPaging,
                remoteSwipe = settings.remoteSwipePaging,
                remoteSwipeReversed = settings.remoteSwipeReversed,
                turn = { forward -> turnPage(forward) },
            ),
        )
        onDispose { registerPageInput(null) }
    }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    // 화면에 그리는 내용을 그대로 기록해 두었다가 넘길 때 그림으로 꺼내 쓴다.
                    pageLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(pageLayer)
                }
                .pointerInput(settings.tapPaging) {
                    detectTapGestures { offset ->
                        when (ViewerTaps.zoneOf(offset.y, size.height.toFloat(), settings.tapPaging)) {
                            TapZone.PREVIOUS_PAGE -> turnPage(forward = false)
                            TapZone.NEXT_PAGE -> turnPage(forward = true)
                            TapZone.TOGGLE_BARS -> barsWanted = !barsWanted
                        }
                    }
                },
            // 막대는 글 위에 떠 있으므로, 막대를 숨기거나 보여도 글의 위치가 움직이지 않는다.
            // 대신 처음과 끝이 막대에 가려지지 않도록 위아래에 여유를 둔다.
            contentPadding = PaddingValues(
                start = settings.margin.dp,
                end = settings.margin.dp,
                top = statusTop + TopBarHeight + 16.dp,
                bottom = navBottom + BottomBarHeight + 20.dp,
            ),
        ) {
            if (state.phase != ReaderPhase.LOADING_PAGE) {
                item(key = "title") {
                    Text(
                        translated.title.ifBlank { chapter?.chapterTitle.orEmpty() },
                        fontSize = (settings.fontSize + 3).sp,
                        lineHeight = ((settings.fontSize + 3) * 1.45f).sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.text,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                }
                itemsIndexed(translated.paragraphs) { _, paragraph ->
                    Text(
                        paragraph,
                        fontSize = settings.fontSize.sp,
                        lineHeight = settings.lineHeight.sp,
                        color = palette.text,
                        modifier = Modifier.padding(bottom = (settings.fontSize * 0.9f).dp),
                    )
                }
            }
            item(key = "status") {
                StatusFooter(state, palette, onRetranslate, onOpenAiSettings)
            }
        }

        // 밀려나는 기존 화면. 바탕색을 깔아 아래의 새 화면과 글자가 겹쳐 보이지 않게 한다.
        leavingPage?.let { page ->
            Image(
                bitmap = page,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(leavingOffset.value.roundToInt(), 0) }
                    .background(palette.background),
            )
        }

        // 막대를 숨겼을 때 글이 상태 표시줄과 시스템 버튼 밑으로 비쳐 보이지 않게 가린다.
        Box(Modifier.fillMaxWidth().height(statusTop).background(palette.background).align(Alignment.TopCenter))
        Box(Modifier.fillMaxWidth().height(navBottom).background(palette.background).align(Alignment.BottomCenter))

        AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(palette.bar).pointerInput(Unit) {}) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(TopBarHeight).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "브라우저로 돌아가기", tint = palette.text)
                    }
                    Text(
                        chapter?.workTitle.orEmpty().ifBlank { "번역" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "더 보기", tint = palette.text)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("다시 번역") },
                                enabled = chapter != null && !state.busy,
                                onClick = {
                                    menuOpen = false
                                    onRetranslate()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("작품 설정 (프롬프트, 용어집)") },
                                enabled = state.workKey.isNotBlank(),
                                onClick = {
                                    menuOpen = false
                                    onOpenWorkSettings()
                                },
                            )
                        }
                    }
                }
                HorizontalDivider(color = palette.divider)
            }
        }

        AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(palette.bar).pointerInput(Unit) {}) {
                HorizontalDivider(color = palette.divider)
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().height(BottomBarHeight).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val moving = state.phase == ReaderPhase.LOADING_PAGE
                    OutlinedButton(
                        onClick = onPrev,
                        enabled = chapter?.hasPrev == true && !moving,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) { Text("이전 화", color = if (chapter?.hasPrev == true && !moving) palette.text else palette.secondary) }
                    TextButton(onClick = { showSettings = true }) {
                        Text("뷰어 설정", color = AppColors.Blue)
                    }
                    Button(
                        onClick = onNext,
                        enabled = chapter?.hasNext == true && !moving,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) { Text("다음 화", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = AppColors.Background) {
            ViewerSettingsPanel(
                settings = settings,
                onChange = onSettingsChange,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun StatusFooter(
    state: ReaderState,
    palette: ViewerPalette,
    onRetranslate: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
        when (state.phase) {
            ReaderPhase.LOADING_PAGE -> Progress("회차를 불러오는 중…", palette)
            ReaderPhase.REQUESTING -> Progress(
                (if (state.retrying) "문제가 있어 자동으로 다시 시도하는 중…" else "번역을 요청하는 중…") + part(state),
                palette,
            )

            ReaderPhase.THINKING -> Progress("모델이 번역을 준비하는 중… 이 동안은 글자가 나오지 않습니다." + part(state), palette)
            ReaderPhase.STREAMING -> Progress("번역 중…" + part(state), palette)
            ReaderPhase.DONE -> UsageLine(state, palette)

            ReaderPhase.ERROR -> {
                Column(
                    Modifier.fillMaxWidth().background(ErrorBg, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Text("번역하지 못했습니다", fontWeight = FontWeight.Bold, color = ErrorText)
                    Text(
                        state.error.orEmpty(),
                        fontSize = 14.sp,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.needsSetup) {
                            Button(
                                onClick = onOpenAiSettings,
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("AI 설정 열기") }
                        }
                        OutlinedButton(onClick = onRetranslate, shape = RoundedCornerShape(10.dp)) {
                            Text("다시 시도")
                        }
                    }
                }
                UsageLine(state, palette)
            }
        }
    }
}

/** 긴 회차를 나눠 번역할 때의 진행 표시. 예: " (2/3)" */
private fun part(state: ReaderState): String =
    if (state.chunkCount > 1 && state.chunkIndex > 0) " (${state.chunkIndex}/${state.chunkCount})" else ""

@Composable
private fun Progress(label: String, palette: ViewerPalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Blue)
        Text(label, fontSize = 14.sp, color = palette.secondary, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun UsageLine(state: ReaderState, palette: ViewerPalette) {
    val usage = state.usage ?: return
    val prefix = if (state.fromCache) "저장된 번역 · " else ""
    val details = buildList {
        if (state.modelName.isNotBlank()) add(state.modelName)
        if (state.glossaryTerms > 0) add("용어집 ${state.glossaryTerms}개 적용")
        if (state.chunkCount > 1) add("${state.chunkCount}부분으로 나눠 번역")
    }.joinToString(" · ")
    Text(
        prefix + usageText(usage) + if (details.isNotBlank()) "\n$details" else "",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = palette.secondary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

private fun usageText(u: TokenUsage): String {
    val n = NumberFormat.getIntegerInstance()
    return buildList {
        add("입력 ${n.format(u.inputTokens)}")
        add("출력 ${n.format(u.outputTokens)}")
        if (u.cacheReadTokens > 0) add("캐시 읽기 ${n.format(u.cacheReadTokens)}")
        if (u.cacheWriteTokens > 0) add("캐시 쓰기 ${n.format(u.cacheWriteTokens)}")
    }.joinToString(" · ") + " 토큰"
}
