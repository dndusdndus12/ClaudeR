package com.clauder.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.data.SiteRule
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.common.OutlineCard
import com.clauder.reader.ui.common.PrimaryButton
import com.clauder.reader.ui.theme.AppColors

/** 등록된 사이트 규칙 목록 */
@Composable
fun SiteRulesScreen(
    rules: List<SiteRule>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (index: Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("사이트별 추출 규칙", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "자동 추출이 잘 안 되는 사이트에만 등록하면 됩니다. 규칙이 있는 사이트에서는 자동 추출보다 규칙을 먼저 적용합니다.",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (rules.isEmpty()) {
                OutlineCard {
                    Text("등록된 규칙이 없습니다.", color = AppColors.TextSecondary, modifier = Modifier.padding(16.dp))
                }
            } else {
                OutlineCard {
                    rules.forEachIndexed { index, rule ->
                        if (index > 0) HorizontalDivider(color = AppColors.Outline)
                        Row(
                            Modifier.fillMaxWidth().clickable { onEdit(index) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.domain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(summary(rule), fontSize = 14.sp, color = AppColors.TextSecondary)
                            }
                            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = AppColors.TextSecondary)
                        }
                    }
                }
            }
        }
        PrimaryButton("규칙 추가", onAdd, Modifier.padding(16.dp))
    }
}

private fun summary(rule: SiteRule): String {
    val parts = buildList {
        if (rule.contentSelector.isNotBlank()) add("본문")
        if (rule.titleSelector.isNotBlank()) add("제목")
        if (rule.nextSelector.isNotBlank()) add("다음 화")
        if (rule.prevSelector.isNotBlank()) add("이전 화")
        if (rule.workTitleSelector.isNotBlank()) add("작품명")
    }
    return if (parts.isEmpty()) "지정한 선택자 없음" else parts.joinToString(" · ") + " 지정"
}

/**
 * 규칙 하나를 추가하거나 고치는 화면.
 * @param initial 고칠 규칙(새로 만들 때는 도메인만 채워진 규칙)
 * @param isNew 새 규칙이면 삭제 버튼을 숨긴다
 */
@Composable
fun SiteRuleEditScreen(
    initial: SiteRule,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (SiteRule) -> Unit,
    onDelete: () -> Unit,
) {
    var domain by rememberSaveable { mutableStateOf(initial.domain) }
    var content by rememberSaveable { mutableStateOf(initial.contentSelector) }
    var title by rememberSaveable { mutableStateOf(initial.titleSelector) }
    var next by rememberSaveable { mutableStateOf(initial.nextSelector) }
    var prev by rememberSaveable { mutableStateOf(initial.prevSelector) }
    var work by rememberSaveable { mutableStateOf(initial.workTitleSelector) }

    val normalized = SiteRule.normalizeDomain(domain)

    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding().imePadding()) {
        BackTopBar(if (isNew) "규칙 추가" else "규칙 수정", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "CSS 선택자를 입력합니다. 예: #content, div.txtnav, a.next\n비워 둔 칸은 자동으로 인식합니다.",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Field("도메인", domain, { domain = it }, "예: 69shuba.com", KeyboardType.Uri, mono = false)
            Field("본문 선택자", content, { content = it }, "본문 글이 들어 있는 요소")
            Field("회차 제목 선택자", title, { title = it }, "회차 제목 요소")
            Field("다음 화 링크 선택자", next, { next = it }, "다음 화로 가는 링크나 버튼")
            Field("이전 화 링크 선택자 (선택)", prev, { prev = it }, "이전 화로 가는 링크나 버튼")
            Field("작품명 선택자 (선택)", work, { work = it }, "작품명이 적힌 요소")
            if (!isNew) {
                TextButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                    Text("이 규칙 삭제", color = Color(0xFFC62828))
                }
            }
        }
        PrimaryButton(
            text = "저장하기",
            enabled = normalized.isNotEmpty(),
            onClick = {
                onSave(
                    SiteRule(
                        domain = normalized,
                        contentSelector = content.trim(),
                        titleSelector = title.trim(),
                        nextSelector = next.trim(),
                        prevSelector = prev.trim(),
                        workTitleSelector = work.trim(),
                    ),
                )
            },
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    mono: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint, color = AppColors.TextSecondary) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 16.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
