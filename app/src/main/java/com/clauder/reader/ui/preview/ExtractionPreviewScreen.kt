package com.clauder.reader.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.extract.ExtractedChapter
import com.clauder.reader.extract.ExtractionStatus
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.common.OutlineCard
import com.clauder.reader.ui.common.SectionTitle
import com.clauder.reader.ui.theme.AppColors

/** 추출 결과를 그대로 보여주는 확인용 화면. 번역 기능이 붙기 전(M1)의 검증 수단이다. */
@Composable
fun ExtractionPreviewScreen(
    chapter: ExtractedChapter,
    onBack: () -> Unit,
    onOpenNext: () -> Unit,
    onOpenPrev: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("추출 미리보기", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item { StatusBanner(chapter) }
            item {
                SectionTitle("추출 정보")
                OutlineCard {
                    InfoRow("작품명", chapter.workTitle.ifBlank { "(찾지 못함)" })
                    RowDivider()
                    InfoRow("회차 제목", chapter.chapterTitle.ifBlank { "(찾지 못함)" })
                    RowDivider()
                    InfoRow("다음 화", navText(chapter.nextUrl, chapter.nextClickable))
                    RowDivider()
                    InfoRow("이전 화", navText(chapter.prevUrl, chapter.prevClickable))
                    RowDivider()
                    InfoRow("본문", "${chapter.paragraphs.size}문단 · ${chapter.charCount}자 (제거한 줄 ${chapter.removedLines}개)")
                    RowDivider()
                    InfoRow(
                        "추출 방식",
                        when (chapter.contentSource) {
                            "rule" -> "사이트 규칙: ${chapter.contentPath}"
                            "auto" -> "자동 추출: ${chapter.contentPath}"
                            else -> "본문 블록 없음"
                        },
                    )
                }
            }
            if (chapter.warnings.isNotEmpty()) {
                item {
                    SectionTitle("경고")
                    OutlineCard {
                        chapter.warnings.forEach { Text(it, Modifier.padding(14.dp), fontSize = 14.sp, color = WarnText) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onOpenPrev,
                        enabled = chapter.hasPrev,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("이전 화 열기") }
                    OutlinedButton(
                        onClick = onOpenNext,
                        enabled = chapter.hasNext,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("다음 화 열기") }
                }
            }
            item { SectionTitle("본문 (원문)") }
            itemsIndexed(chapter.paragraphs) { _, paragraph ->
                SelectionContainer {
                    Text(paragraph, fontSize = 17.sp, lineHeight = 28.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

private val OkBg = Color(0xFFE7F6EC)
private val OkText = Color(0xFF0A7D32)
private val FailBg = Color(0xFFFDECEC)
private val FailText = Color(0xFFC62828)
private val WarnText = Color(0xFF9A6700)

@Composable
private fun StatusBanner(chapter: ExtractedChapter) {
    val ok = chapter.status == ExtractionStatus.OK
    val (title, detail) = when (chapter.status) {
        ExtractionStatus.OK -> "본문 추출 성공" to "아래 내용이 실제 회차 본문과 같은지 확인하세요."
        ExtractionStatus.BLOCKED -> "보안 확인 페이지로 보입니다" to
            "브라우저 화면으로 돌아가 보안 확인을 직접 통과한 뒤 다시 시도하세요."
        ExtractionStatus.TOO_SHORT -> "본문을 찾지 못했습니다" to
            "회차 페이지가 맞는지 확인하세요. 계속 실패하면 설정 > 사이트별 추출 규칙에서 본문 선택자를 지정할 수 있습니다."
        ExtractionStatus.ERROR -> "추출 실패" to (chapter.errorMessage ?: "알 수 없는 오류")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(if (ok) OkBg else FailBg, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = if (ok) OkText else FailText)
        Text(detail, fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun navText(url: String?, clickable: Boolean) = when {
    url != null -> url
    clickable -> "(주소 없는 버튼 — 눌러서 이동)"
    else -> "(찾지 못함)"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = AppColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.width(76.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, fontSize = 14.sp)
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = AppColors.Outline)
