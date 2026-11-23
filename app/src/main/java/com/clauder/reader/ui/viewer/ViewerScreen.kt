package com.clauder.reader.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.ai.TokenUsage
import com.clauder.reader.translate.PromptBuilder
import com.clauder.reader.translate.ReaderPhase
import com.clauder.reader.translate.ReaderState
import com.clauder.reader.ui.theme.AppColors
import java.text.NumberFormat

private val ViewerBackground = Color(0xFFF3F5F9)
private val ErrorBg = Color(0xFFFDECEC)
private val ErrorText = Color(0xFFC62828)

/** 번역문을 읽는 화면. 글꼴, 줄 간격, 테마 설정은 M5에서 추가한다. */
@Composable
fun ViewerScreen(
    state: ReaderState,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRetranslate: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenWorkSettings: () -> Unit,
) {
    val chapter = state.chapter
    val translated = remember(state.text) { PromptBuilder.parseTranslation(state.text) }
    val listState = rememberLazyListState()

    // 다른 회차로 바뀌면 맨 위에서부터 읽도록 한다.
    LaunchedEffect(chapter?.url) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(ViewerBackground)) {
        // 위쪽: 뒤로 가기와 작품명
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "브라우저로 돌아가기")
            }
            Text(
                chapter?.workTitle.orEmpty().ifBlank { "번역" },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (state.workKey.isNotBlank()) {
                TextButton(onClick = onOpenWorkSettings) { Text("작품 설정") }
            }
        }
        HorizontalDivider(color = AppColors.Outline)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
        ) {
            if (state.phase != ReaderPhase.LOADING_PAGE) {
                item(key = "title") {
                    Text(
                        translated.title.ifBlank { chapter?.chapterTitle.orEmpty() },
                        fontSize = 22.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                }
                itemsIndexed(translated.paragraphs) { _, paragraph ->
                    Text(
                        paragraph,
                        fontSize = 19.sp,
                        lineHeight = 32.sp,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 18.dp),
                    )
                }
            }
            item(key = "status") {
                StatusFooter(state, onRetranslate, onOpenAiSettings)
            }
        }

        HorizontalDivider(color = AppColors.Outline)
        Row(
            Modifier
                .fillMaxWidth()
                .background(AppColors.Background)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val moving = state.phase == ReaderPhase.LOADING_PAGE
            OutlinedButton(
                onClick = onPrev,
                enabled = chapter?.hasPrev == true && !moving,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) { Text("이전 화") }
            TextButton(onClick = onRetranslate, enabled = chapter != null && !state.busy) {
                Text("다시 번역")
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

@Composable
private fun StatusFooter(state: ReaderState, onRetranslate: () -> Unit, onOpenAiSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
        when (state.phase) {
            ReaderPhase.LOADING_PAGE -> Progress("다음 화를 불러오는 중…")
            ReaderPhase.REQUESTING -> Progress(
                (if (state.retrying) "문제가 있어 자동으로 다시 시도하는 중…" else "번역을 요청하는 중…") + part(state),
            )
            ReaderPhase.THINKING -> Progress("모델이 번역을 준비하는 중… 이 동안은 글자가 나오지 않습니다." + part(state))
            ReaderPhase.STREAMING -> Progress("번역 중…" + part(state))
            ReaderPhase.DONE -> UsageLine(state)

            ReaderPhase.ERROR -> {
                Column(
                    Modifier.fillMaxWidth().background(ErrorBg, RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Text("번역하지 못했습니다", fontWeight = FontWeight.Bold, color = ErrorText)
                    Text(state.error.orEmpty(), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
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
                UsageLine(state)
            }
        }
    }
}

/** 긴 회차를 나눠 번역할 때의 진행 표시. 예: " (2/3)" */
private fun part(state: ReaderState): String =
    if (state.chunkCount > 1 && state.chunkIndex > 0) " (${state.chunkIndex}/${state.chunkCount})" else ""

@Composable
private fun Progress(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Blue)
        Text(label, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun UsageLine(state: ReaderState) {
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
        color = AppColors.TextSecondary,
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
