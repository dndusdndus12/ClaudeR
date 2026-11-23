package com.clauder.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.data.db.ChapterSummary
import com.clauder.reader.data.db.WorkEntity
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DangerText = Color(0xFFC62828)
private val PendingText = Color(0xFF9A6700)

/**
 * 서재 탭: 번역해서 읽은 작품 목록. 작품을 누르면 마지막으로 보던 회차를 이어서 본다.
 * @param chapterCounts 작품 열쇠 → 저장된 회차 수
 * @param lastChapterSaved 마지막으로 진입한 회차의 번역이 저장되어 있는 작품들의 열쇠
 */
@Composable
fun LibraryScreen(
    works: List<WorkEntity>,
    chapterCounts: Map<String, Int>,
    lastChapterSaved: Set<String>,
    onResume: (WorkEntity) -> Unit,
    onOpenChapters: (WorkEntity) -> Unit,
    onOpenSettings: (WorkEntity) -> Unit,
    onOpenInBrowser: (WorkEntity) -> Unit,
    onDelete: (WorkEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<WorkEntity?>(null) }
    val shown = remember(works, query) {
        val q = query.trim()
        if (q.isEmpty()) works else works.filter { it.title.contains(q, ignoreCase = true) || it.host.contains(q, ignoreCase = true) }
    }

    Column(modifier.statusBarsPadding()) {
        Text(
            "서재",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
        )
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(46.dp)
                .background(AppColors.SurfaceGray, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) Text("제목·사이트 검색", color = AppColors.TextSecondary, fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = AppColors.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (works.isEmpty()) "번역해서 읽은 작품이 여기에 모입니다.\n번역 탭에서 회차를 번역해 보세요." else "검색 결과가 없습니다.",
                    color = AppColors.TextSecondary,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(shown, key = { it.workKey }) { work ->
                    WorkRow(
                        work = work,
                        savedCount = chapterCounts[work.workKey] ?: 0,
                        lastSaved = work.workKey in lastChapterSaved,
                        onResume = { onResume(work) },
                        onOpenChapters = { onOpenChapters(work) },
                        onOpenSettings = { onOpenSettings(work) },
                        onOpenInBrowser = { onOpenInBrowser(work) },
                        onDelete = { pendingDelete = work },
                    )
                    HorizontalDivider(color = AppColors.Outline)
                }
            }
        }
    }

    pendingDelete?.let { work ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("서재에서 삭제") },
            text = {
                Text(
                    "${work.title}\n\n저장된 번역 ${chapterCounts[work.workKey] ?: 0}화와 이 작품의 전용 프롬프트, 용어집이 모두 삭제됩니다. " +
                        "되돌릴 수 없습니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(work)
                    pendingDelete = null
                }) { Text("삭제", color = DangerText) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun WorkRow(
    work: WorkEntity,
    savedCount: Int,
    lastSaved: Boolean,
    onResume: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onResume).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 46.dp, height = 62.dp).background(AppColors.SurfaceGray, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_tab_library), contentDescription = null, tint = AppColors.Outline)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(AppColors.Green, CircleShape))
                Text(
                    work.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
            Text(
                work.lastChapterTitle.ifBlank { "마지막으로 본 회차 없음" },
                fontSize = 14.sp,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${work.host} · 저장 ${savedCount}화",
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 마지막 회차에 진입만 하고 번역이 끝나지 않은 경우: 누르면 사이트에서 그 회차를 연다.
            if (!lastSaved && work.lastChapterUrl.isNotBlank()) {
                Text(
                    "이 회차는 아직 번역 전 · 누르면 사이트에서 엽니다",
                    fontSize = 13.sp,
                    color = PendingText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "더 보기", tint = AppColors.TextSecondary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                fun item(label: String, color: Color = Color.Unspecified, action: () -> Unit) = Triple(label, color, action)
                listOf(
                    item("이어보기", action = onResume),
                    item("저장된 회차 목록", action = onOpenChapters),
                    item("브라우저에서 열기", action = onOpenInBrowser),
                    item("작품 설정 (프롬프트, 용어집)", action = onOpenSettings),
                    item("서재에서 삭제", DangerText, onDelete),
                ).forEach { (label, color, action) ->
                    DropdownMenuItem(
                        text = { Text(label, color = color) },
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

/** 한 작품의 저장된 회차 목록. 누르면 저장된 번역을 바로 연다. */
@Composable
fun ChapterListScreen(
    work: WorkEntity?,
    chapters: List<ChapterSummary>,
    onBack: () -> Unit,
    onOpen: (url: String) -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("저장된 회차", onBack)
        Text(
            work?.title.orEmpty(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "번역해 둔 ${chapters.size}화 · 누르면 API 를 다시 부르지 않고 바로 엽니다",
            fontSize = 13.sp,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        )
        HorizontalDivider(color = AppColors.Outline)
        if (chapters.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("저장된 번역이 없습니다.", color = AppColors.TextSecondary)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(chapters, key = { it.url }) { chapter ->
                    val isLast = chapter.url == work?.lastChapterUrl?.substringBefore('#')
                    Column(Modifier.fillMaxWidth().clickable { onOpen(chapter.url) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            chapter.translatedTitle.ifBlank { chapter.title },
                            fontSize = 16.sp,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            color = if (isLast) AppColors.Blue else AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            chapter.title + " · " + dateFormat.format(Date(chapter.translatedAt)) + if (isLast) " · 마지막으로 본 회차" else "",
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider(color = AppColors.Outline)
                }
            }
        }
    }
}
