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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.ai.TokenUsage
import com.clauder.reader.data.Pricing
import com.clauder.reader.data.ReadingPosition
import com.clauder.reader.data.TapZone
import com.clauder.reader.data.ViewerSettings
import com.clauder.reader.data.ViewerTaps
import com.clauder.reader.translate.PromptBuilder
import com.clauder.reader.translate.ReaderPhase
import com.clauder.reader.translate.ReaderState
import com.clauder.reader.ui.theme.AppColors
import kotlinx.coroutines.delay
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
 * - 스크롤 모드(기본): 손가락으로 스크롤하고, 볼륨 키나 리모컨으로 한 화면씩 넘길 수도 있다.
 * - 페이지 넘김 전용 모드: 스크롤 없이 한 장씩만 넘기며, 줄이 화면 끝에서 잘리지 않는다.
 * - 화면을 탭하면 위아래 막대가 숨겨지고, 설정에 따라 상태 표시줄과 시스템 버튼도 함께 숨겨진다.
 *
 * @param registerPageInput 볼륨 키와 리모컨 입력을 받을 곳을 등록한다(null 이면 해제).
 * @param setDarkSystemBars 어두운 테마일 때 상태 표시줄 아이콘을 밝게 바꾼다.
 * @param setSystemBarsHidden 상태 표시줄과 시스템 버튼을 숨기거나 다시 보인다.
 * @param onPosition 읽던 위치가 바뀌면 알린다(회차 주소, 항목 번호, 그 안에서의 어긋남).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    setSystemBarsHidden: (Boolean) -> Unit,
    onPosition: (url: String, index: Int, offset: Int) -> Unit,
) {
    val chapter = state.chapter
    val palette = ViewerPalette.of(settings.theme)
    val translated = remember(state.text) { PromptBuilder.parseTranslation(state.text) }
    val listState = rememberLazyListState()
    val paged = remember { PagedState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var barsWanted by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var rootWidth by remember { mutableIntStateOf(0) }
    // 오류가 났을 때는 조치할 수 있도록 막대를 항상 보여 준다.
    val barsVisible = barsWanted || state.phase == ReaderPhase.ERROR

    // ---------- 시스템 막대(상태 표시줄, 시스템 버튼) ----------

    DisposableEffect(palette.dark) {
        setDarkSystemBars(palette.dark)
        onDispose { setDarkSystemBars(false) }
    }

    // 위아래 막대를 숨기면 시스템 막대도 함께 숨기고, 막대를 다시 보이면 함께 보인다.
    val systemBarsHidden = settings.hideSystemBars && !barsVisible
    LaunchedEffect(systemBarsHidden) { setSystemBarsHidden(systemBarsHidden) }
    DisposableEffect(Unit) { onDispose { setSystemBarsHidden(false) } }

    // 시스템 막대가 숨겨져도 값이 변하지 않는 높이를 쓴다. 그래야 숨기고 보일 때 글이 위아래로 움직이지 않는다.
    val statusTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()

    // ---------- 읽던 위치 ----------

    // 지금 읽고 있는 위치(항목 번호, 어긋남). 스크롤 모드와 페이지 모드가 함께 쓰므로 모드를 바꿔도 자리가 이어진다.
    var position by remember { mutableStateOf(state.scrollIndex to state.scrollOffset) }
    var openSeen by remember { mutableIntStateOf(state.openCount) }
    if (openSeen != state.openCount) {
        // 회차를 새로 열었다: 새로 번역하는 회차는 맨 위에서, 저장된 회차는 읽던 위치에서 시작한다.
        position = state.scrollIndex to state.scrollOffset
        openSeen = state.openCount
    }

    LaunchedEffect(state.openCount, settings.pagedMode) {
        if (!settings.pagedMode) {
            listState.scrollToItem(position.first, ReadingPosition.pixels(position.second))
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { position = it }
        }
    }

    // 저장: 위치가 잠시 멈추면 저장하고, 화면을 떠날 때도 한 번 더 저장한다.
    val latestOnPosition by rememberUpdatedState(onPosition)
    val latestPosition by rememberUpdatedState(position)
    val doneUrl = if (state.phase == ReaderPhase.DONE) chapter?.url else null
    LaunchedEffect(position, doneUrl) {
        val url = doneUrl ?: return@LaunchedEffect
        delay(600)
        latestOnPosition(url, position.first, position.second)
    }
    DisposableEffect(doneUrl) {
        onDispose {
            if (doneUrl != null) latestOnPosition(doneUrl, latestPosition.first, latestPosition.second)
        }
    }

    // ---------- 페이지 넘김 ----------

    // 키 입력은 화면이 다시 그려진 뒤에도 최신 값을 봐야 하므로 최신 값을 가리키는 참조로 넘긴다.
    val latestState by rememberUpdatedState(state)
    val latestSettings by rememberUpdatedState(settings)
    val latestBars by rememberUpdatedState(barsVisible)
    val latestSystemHidden by rememberUpdatedState(systemBarsHidden)
    val latestNext by rememberUpdatedState(onNext)

    // 넘김 애니메이션: 넘기기 직전의 화면을 그림으로 찍어 글 위에 덮고,
    // 그 그림을 옆으로 밀어내면 아래에 이미 넘어가 있는 새 화면이 드러난다.
    val pageLayer = rememberGraphicsLayer()
    var leavingPage by remember { mutableStateOf<ImageBitmap?>(null) }
    val leavingOffset = remember { Animatable(0f) }
    var turnCount by remember { mutableIntStateOf(0) }

    fun turnPage(forward: Boolean) {
        val current = latestState
        if (current.phase == ReaderPhase.LOADING_PAGE) return
        val pagedMode = latestSettings.pagedMode
        val canMove = when {
            pagedMode -> if (forward) paged.canGoForward else paged.canGoBackward
            else -> if (forward) listState.canScrollForward else listState.canScrollBackward
        }
        if (!canMove) {
            // 회차 끝에서 앞으로 넘기면, 설정에 따라 다음 화로 넘어간다.
            val canAdvance = forward && current.phase == ReaderPhase.DONE && current.chapter?.hasNext == true
            if (canAdvance && latestSettings.volumeKeyNextChapter) latestNext()
            return
        }
        val turn = ++turnCount
        scope.launch {
            val snapshot = if (latestSettings.pageAnimation) runCatching { pageLayer.toImageBitmap() }.getOrNull() else null
            if (snapshot != null) {
                leavingOffset.snapTo(0f)
                leavingPage = snapshot
            }
            val moved = if (pagedMode) {
                paged.turn(forward)
                true
            } else {
                val distance = with(density) {
                    ViewerTaps.pageDistance(
                        viewportHeight = listState.layoutInfo.viewportSize.height.toFloat(),
                        coveredTop = (statusTop + if (latestBars) TopBarHeight else 0.dp).toPx(),
                        coveredBottom = ((if (latestSystemHidden) 0.dp else navBottom) + if (latestBars) BottomBarHeight else 0.dp).toPx(),
                        lineHeight = latestSettings.lineHeight.sp.toPx(),
                        overlapLines = latestSettings.pageOverlapLines,
                    )
                }
                listState.scrollBy(if (forward) distance else -distance) != 0f
            }
            try {
                // 다음 화면으로 갈 때는 기존 화면이 왼쪽으로, 이전 화면으로 갈 때는 오른쪽으로 밀려난다.
                if (snapshot != null && moved) {
                    val width = rootWidth.toFloat()
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

    // ---------- 화면 ----------

    Box(Modifier.fillMaxSize().background(palette.background).onSizeChanged { rootWidth = it.width }) {
        Box(
            Modifier
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
        ) {
            if (settings.pagedMode) {
                val loading = state.phase == ReaderPhase.LOADING_PAGE
                PagedContent(
                    content = if (loading) PromptBuilder.Translated("", emptyList())
                    else translated.copy(title = translated.title.ifBlank { chapter?.chapterTitle.orEmpty() }),
                    streaming = state.phase == ReaderPhase.STREAMING,
                    settings = settings,
                    palette = palette,
                    topInset = statusTop,
                    // 시스템 버튼을 숨기는 설정이면 그 자리까지 글을 채운다(막대를 잠깐 보일 때만 가려진다).
                    bottomInset = if (settings.hideSystemBars) 0.dp else navBottom,
                    state = paged,
                    restoreKey = state.openCount,
                    startIndex = position.first,
                    startOffset = position.second,
                    statusText = progressLabel(state),
                    onPosition = { index, offset -> position = index to offset },
                    onSwipe = { forward -> turnPage(forward) },
                )
                if (state.phase == ReaderPhase.ERROR) {
                    Box(Modifier.align(Alignment.Center).padding(horizontal = settings.margin.dp)) {
                        StatusFooter(state, palette, onRetranslate, onOpenAiSettings)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
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

        // 글이 상태 표시줄(카메라 구멍 포함)과 시스템 버튼 밑으로 비쳐 보이지 않게 가린다.
        Box(Modifier.fillMaxWidth().height(statusTop).background(palette.background).align(Alignment.TopCenter))
        if (!systemBarsHidden) {
            Box(Modifier.fillMaxWidth().height(navBottom).background(palette.background).align(Alignment.BottomCenter))
        }

        AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(palette.bar).pointerInput(Unit) {}) {
                Row(
                    Modifier.fillMaxWidth().padding(top = statusTop).height(TopBarHeight).padding(horizontal = 4.dp),
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
                // 페이지 모드에는 글 끝에 토큰 수를 적을 자리가 없으므로 막대에 보여 준다.
                if (settings.pagedMode && state.phase == ReaderPhase.DONE) {
                    usageSummary(state)?.let { summary ->
                        Text(
                            summary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = palette.secondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = navBottom).height(BottomBarHeight).padding(horizontal = 12.dp),
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

/** 진행 중일 때의 한 줄 안내. 진행 중이 아니면 null. */
private fun progressLabel(state: ReaderState): String? = when (state.phase) {
    ReaderPhase.LOADING_PAGE -> "회차를 불러오는 중…"
    ReaderPhase.REQUESTING ->
        (if (state.retrying) "문제가 있어 자동으로 다시 시도하는 중…" else "번역을 요청하는 중…") + part(state)

    ReaderPhase.THINKING -> "모델이 번역을 준비하는 중… 이 동안은 글자가 나오지 않습니다." + part(state)
    ReaderPhase.STREAMING -> "번역 중…" + part(state)
    ReaderPhase.DONE, ReaderPhase.ERROR -> null
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

            else -> progressLabel(state)?.let { Progress(it, palette) }
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
    val summary = usageSummary(state) ?: return
    Text(
        summary,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = palette.secondary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** 토큰 수, 모델, 용어집 적용 수를 두 줄로 요약한다. 토큰 기록이 없으면 null. */
private fun usageSummary(state: ReaderState): String? {
    val usage = state.usage ?: return null
    val prefix = if (state.fromCache) "저장된 번역 · " else ""
    val details = buildList {
        if (state.modelName.isNotBlank()) add(state.modelName)
        if (state.glossaryTerms > 0) add("용어집 ${state.glossaryTerms}개 적용")
        if (state.chunkCount > 1) add("${state.chunkCount}부분으로 나눠 번역")
    }.joinToString(" · ")
    val cost = state.costUsd?.let { " · 약 " + Pricing.formatUsd(it) }.orEmpty()
    return prefix + usageText(usage) + cost + if (details.isNotBlank()) "\n$details" else ""
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
