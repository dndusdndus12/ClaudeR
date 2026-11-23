package com.clauder.reader.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.data.db.WorkEntity
import com.clauder.reader.translate.Glossary
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.common.OutlineCard
import com.clauder.reader.ui.common.PrimaryButton
import com.clauder.reader.ui.common.SectionTitle
import com.clauder.reader.ui.theme.AppColors

private val WarnText = Color(0xFF9A6700)

/** 설정 > 프롬프트와 용어집: 전역 설정과 작품별 설정 목록 */
@Composable
fun PromptHubScreen(
    promptCustomized: Boolean,
    glossarySummary: String,
    works: List<WorkEntity>,
    onBack: () -> Unit,
    onEditPrompt: () -> Unit,
    onEditGlossary: () -> Unit,
    onOpenWork: (workKey: String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("프롬프트와 용어집", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("전역 설정")
            OutlineCard {
                NavRow("번역 프롬프트", if (promptCustomized) "직접 수정한 프롬프트 사용 중" else "기본 프롬프트 사용 중", onEditPrompt)
                HorizontalDivider(color = AppColors.Outline)
                NavRow("전역 용어집", glossarySummary, onEditGlossary)
            }
            Text(
                "전역 설정은 모든 작품에 적용됩니다. 작품마다 다르게 쓰고 싶으면 아래에서 작품을 골라 설정하세요.",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            SectionTitle("작품별 설정")
            OutlineCard {
                if (works.isEmpty()) {
                    Text(
                        "아직 번역한 작품이 없습니다. 한 번 번역하면 여기에 나타납니다.",
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                works.forEachIndexed { index, work ->
                    if (index > 0) HorizontalDivider(color = AppColors.Outline)
                    NavRow(work.title, workSummary(work), onClick = { onOpenWork(work.workKey) })
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

private fun workSummary(work: WorkEntity): String {
    val prompt = if (work.usesCustomPrompt) "프롬프트: 작품 전용" else "프롬프트: 전역 사용"
    val count = Glossary.parse(work.glossary).entries.size
    val glossary = when {
        count == 0 -> "작품 용어집 없음"
        work.useGlobalGlossary -> "작품 용어집 ${count}개 + 전역"
        else -> "작품 용어집 ${count}개만"
    }
    return "${work.host} · $prompt · $glossary"
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary,
            )
            Text(subtitle, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        if (enabled) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = AppColors.TextSecondary)
        }
    }
}

/** 한 작품의 프롬프트와 용어집 설정 */
@Composable
fun WorkSettingsScreen(
    work: WorkEntity,
    onBack: () -> Unit,
    onSetCustomPrompt: (Boolean) -> Unit,
    onEditPrompt: () -> Unit,
    onSetUseGlobalGlossary: (Boolean) -> Unit,
    onEditGlossary: () -> Unit,
) {
    val custom = work.promptMode == WorkEntity.PROMPT_CUSTOM
    val glossary = remember(work.glossary) { Glossary.parse(work.glossary) }

    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("작품 설정", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(work.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(work.workKey, fontSize = 13.sp, color = AppColors.TextSecondary)

            SectionTitle("번역 프롬프트")
            OutlineCard {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("전역 사용", selected = !custom, onClick = { onSetCustomPrompt(false) })
                    ModeChip("작품 전용", selected = custom, onClick = { onSetCustomPrompt(true) })
                }
                HorizontalDivider(color = AppColors.Outline)
                NavRow(
                    title = "작품 전용 프롬프트 편집",
                    subtitle = when {
                        !custom -> "\"작품 전용\"을 선택하면 편집할 수 있습니다"
                        work.customPrompt.isBlank() -> "아직 비어 있어 전역 프롬프트로 번역합니다"
                        else -> "${work.customPrompt.length}자"
                    },
                    onClick = onEditPrompt,
                    enabled = custom,
                )
            }

            SectionTitle("용어집")
            OutlineCard {
                NavRow("작품 용어집 편집", glossary.summary(), onEditGlossary)
                HorizontalDivider(color = AppColors.Outline)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("전역 용어집도 함께 사용", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "같은 원문이 양쪽에 있으면 작품 용어집을 따릅니다.",
                            fontSize = 14.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                    Switch(
                        checked = work.useGlobalGlossary,
                        onCheckedChange = onSetUseGlobalGlossary,
                        colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Blue),
                    )
                }
            }
            Text(
                "설정을 바꾼 뒤 이미 번역한 회차에 적용하려면 뷰어에서 \"다시 번역\"을 누르세요.",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) AppColors.Blue else AppColors.Background,
        border = BorderStroke(1.dp, if (selected) AppColors.Blue else AppColors.Outline),
    ) {
        Text(
            label,
            color = if (selected) Color.White else AppColors.TextPrimary,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * 긴 글(프롬프트, 용어집)을 고치는 화면.
 * @param isGlossary true 면 입력한 글을 용어집으로 해석해 항목 수, 무시된 줄, 중복 수를 보여 준다.
 * @param restoreLabel 지정하면 [restoreText] 로 되돌리는 버튼을 보여 준다(예: "기본값 복원").
 */
@Composable
fun LongTextEditScreen(
    title: String,
    description: String,
    initial: String,
    isGlossary: Boolean,
    restoreLabel: String? = null,
    restoreText: String = "",
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val parsed = remember(text, isGlossary) { if (isGlossary) Glossary.parse(text) else null }

    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding().imePadding()) {
        BackTopBar(title, onBack)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(description, color = AppColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                shape = RoundedCornerShape(12.dp),
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                placeholder = { if (isGlossary) Text("林远=임원\n守山人=수산인", color = AppColors.TextSecondary) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    parsed?.summary() ?: "${text.length}자",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (restoreLabel != null) {
                    TextButton(onClick = { text = restoreText }) { Text(restoreLabel) }
                }
            }
            // 무시된 줄이 있으면 어느 줄인지 앞의 몇 개만 보여 준다.
            parsed?.ignored?.take(3)?.forEach { line ->
                Text(
                    "${line.lineNumber}번째 줄 무시: ${line.text.take(40)}",
                    fontSize = 13.sp,
                    color = WarnText,
                    maxLines = 1,
                )
            }
            if (parsed != null && parsed.ignored.size > 3) {
                Text("외 ${parsed.ignored.size - 3}줄", fontSize = 13.sp, color = WarnText)
            }
        }
        PrimaryButton("저장하기", onClick = { onSave(text) }, modifier = Modifier.padding(16.dp))
    }
}
