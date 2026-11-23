package com.clauder.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.BuildConfig
import com.clauder.reader.R
import com.clauder.reader.ui.common.OutlineCard
import com.clauder.reader.ui.common.SectionTitle
import com.clauder.reader.ui.theme.AppColors

/** 설정 탭 첫 화면. 아직 만들지 않은 항목은 "준비 중"으로 표시한다. */
@Composable
fun SettingsScreen(
    siteRuleCount: Int,
    aiSummary: String,
    promptSummary: String,
    viewerSummary: String,
    onOpenSiteRules: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenViewerSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text("설정", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))

        SectionTitle("본문 추출")
        OutlineCard {
            SettingsRow(
                title = "사이트별 추출 규칙",
                subtitle = if (siteRuleCount == 0) "규칙 없음 · 자동 추출만 사용" else "규칙 ${siteRuleCount}개",
                onClick = onOpenSiteRules,
            )
        }

        SectionTitle("번역")
        OutlineCard {
            SettingsRow("AI 설정", aiSummary, onOpenAiSettings)
            HorizontalDivider(color = AppColors.Outline)
            SettingsRow("프롬프트와 용어집", promptSummary, onOpenPrompts)
        }

        SectionTitle("읽기")
        OutlineCard {
            SettingsRow("뷰어 설정", viewerSummary, onOpenViewerSettings)
        }

        Text(
            "Clauder v${BuildConfig.VERSION_NAME}",
            color = AppColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (onClick != null) AppColors.TextPrimary else AppColors.TextSecondary,
            )
            Text(subtitle, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        if (onClick != null) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = AppColors.TextSecondary)
        }
    }
}
