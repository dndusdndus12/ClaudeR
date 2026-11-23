package com.clauder.reader.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.data.ViewerSettings
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.common.OutlineCard
import com.clauder.reader.ui.common.SectionTitle
import com.clauder.reader.ui.theme.AppColors

/** 뷰어 테마별 색 */
data class ViewerPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    /** 위/아래 막대의 바탕 */
    val bar: Color,
    val divider: Color,
    /** 어두운 테마인지(상태 표시줄 아이콘을 밝게 해야 하는지) */
    val dark: Boolean,
) {
    companion object {
        fun of(theme: String): ViewerPalette = when (theme) {
            ViewerSettings.THEME_DARK -> ViewerPalette(
                background = Color(0xFF121212), text = Color(0xFFD0D0D0), secondary = Color(0xFF8A8A8A),
                bar = Color(0xFF1E1E1E), divider = Color(0xFF2C2C2C), dark = true,
            )

            ViewerSettings.THEME_SEPIA -> ViewerPalette(
                background = Color(0xFFF4ECD8), text = Color(0xFF5B4636), secondary = Color(0xFF8C7A64),
                bar = Color(0xFFEFE5CC), divider = Color(0xFFDDD0B4), dark = false,
            )

            else -> ViewerPalette(
                background = Color(0xFFF3F5F9), text = Color(0xFF16181D), secondary = Color(0xFF7A808A),
                bar = Color(0xFFFFFFFF), divider = Color(0xFFD9DDE3), dark = false,
            )
        }
    }
}

private val THEME_LABELS = listOf(
    ViewerSettings.THEME_LIGHT to "밝게",
    ViewerSettings.THEME_DARK to "어둡게",
    ViewerSettings.THEME_SEPIA to "세피아",
)

/** 뷰어 설정 항목들. 뷰어의 아래 시트와 설정 탭의 화면에서 함께 쓴다. 바꾸는 즉시 저장된다. */
@Composable
fun ViewerSettingsPanel(settings: ViewerSettings, onChange: (ViewerSettings) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Stepper(
            label = "글자 크기",
            value = "${settings.fontSize}",
            onMinus = { onChange(settings.copy(fontSize = settings.fontSize - 1)) },
            onPlus = { onChange(settings.copy(fontSize = settings.fontSize + 1)) },
            canMinus = settings.fontSize > ViewerSettings.FONT_MIN,
            canPlus = settings.fontSize < ViewerSettings.FONT_MAX,
        )
        Stepper(
            label = "줄 간격",
            value = "${settings.lineSpacing / 10}.${settings.lineSpacing % 10}배",
            onMinus = { onChange(settings.copy(lineSpacing = settings.lineSpacing - 1)) },
            onPlus = { onChange(settings.copy(lineSpacing = settings.lineSpacing + 1)) },
            canMinus = settings.lineSpacing > ViewerSettings.SPACING_MIN,
            canPlus = settings.lineSpacing < ViewerSettings.SPACING_MAX,
        )
        Stepper(
            label = "좌우 여백",
            value = "${settings.margin}",
            onMinus = { onChange(settings.copy(margin = settings.margin - 2)) },
            onPlus = { onChange(settings.copy(margin = settings.margin + 2)) },
            canMinus = settings.margin > ViewerSettings.MARGIN_MIN,
            canPlus = settings.margin < ViewerSettings.MARGIN_MAX,
        )

        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("테마", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                THEME_LABELS.forEach { (value, label) ->
                    val palette = ViewerPalette.of(value)
                    val selected = settings.theme == value
                    Surface(
                        onClick = { onChange(settings.copy(theme = value)) },
                        shape = RoundedCornerShape(20.dp),
                        color = palette.background,
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) AppColors.Blue else AppColors.Outline),
                    ) {
                        Text(
                            label,
                            color = palette.text,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = AppColors.Outline, modifier = Modifier.padding(vertical = 6.dp))

        Toggle(
            title = "페이지 넘김 전용 모드",
            detail = "스크롤 없이 한 장씩만 넘깁니다. 한 장에는 온전한 줄만 넣어 화면 끝에서 글자가 잘리지 않습니다. " +
                "화면을 좌우나 위아래로 밀어도 넘어갑니다.",
            checked = settings.pagedMode,
            onChange = { onChange(settings.copy(pagedMode = it)) },
        )
        Toggle(
            title = "상태 표시줄과 시스템 버튼 숨기기",
            detail = "화면을 탭해 위아래 막대를 숨기면 시계, 와이파이 표시와 메뉴/홈/뒤로 버튼도 함께 숨깁니다. " +
                "다시 탭하면 모두 나타납니다.",
            checked = settings.hideSystemBars,
            onChange = { onChange(settings.copy(hideSystemBars = it)) },
        )
        Stepper(
            label = "넘길 때 겹치는 줄 수",
            value = "${settings.pageOverlapLines}줄",
            onMinus = { onChange(settings.copy(pageOverlapLines = settings.pageOverlapLines - 1)) },
            onPlus = { onChange(settings.copy(pageOverlapLines = settings.pageOverlapLines + 1)) },
            canMinus = settings.pageOverlapLines > ViewerSettings.OVERLAP_MIN,
            canPlus = settings.pageOverlapLines < ViewerSettings.OVERLAP_MAX,
        )
        Text(
            "볼륨 키, 리모컨, 탭으로 한 화면을 넘길 때 앞 화면의 마지막 몇 줄을 다음 화면에 다시 보여 줍니다.",
            fontSize = 13.sp,
            color = AppColors.TextSecondary,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Toggle(
            title = "페이지 넘김 애니메이션",
            detail = "한 화면을 넘길 때 기존 화면이 옆으로 밀려나며 넘어갔음을 보여 줍니다.",
            checked = settings.pageAnimation,
            onChange = { onChange(settings.copy(pageAnimation = it)) },
        )
        Toggle(
            title = "볼륨 키로 페이지 넘김",
            detail = "볼륨 아래는 다음 화면, 위는 이전 화면. 블루투스 리모컨도 같은 방식으로 동작합니다.",
            checked = settings.volumeKeyPaging,
            onChange = { onChange(settings.copy(volumeKeyPaging = it)) },
        )
        Toggle(
            title = "끝에서 한 번 더 누르면 다음 화",
            detail = "회차 끝에서 볼륨 아래나 리모컨의 다음 버튼을 누르면 다음 화로 넘어가 번역을 시작합니다. 번역마다 API 요금이 듭니다.",
            checked = settings.volumeKeyNextChapter,
            enabled = settings.volumeKeyPaging,
            onChange = { onChange(settings.copy(volumeKeyNextChapter = it)) },
        )
        Toggle(
            title = "리모컨 밀기 동작으로 페이지 넘김",
            detail = "버튼을 누르면 화면을 미는 동작을 보내는 블루투스 리모컨(R1 등)으로 한 화면씩 넘깁니다.",
            checked = settings.remoteSwipePaging,
            onChange = { onChange(settings.copy(remoteSwipePaging = it)) },
        )
        Toggle(
            title = "리모컨 방향 반대로",
            detail = "리모컨의 다음 버튼이 이전 화면으로 넘어가면 켜세요.",
            checked = settings.remoteSwipeReversed,
            enabled = settings.remoteSwipePaging,
            onChange = { onChange(settings.copy(remoteSwipeReversed = it)) },
        )
        Toggle(
            title = "탭으로 페이지 넘김",
            detail = "화면 위쪽 1/4을 누르면 이전 화면, 아래쪽 1/4을 누르면 다음 화면. 가운데는 위아래 막대를 숨기거나 보입니다.",
            checked = settings.tapPaging,
            onChange = { onChange(settings.copy(tapPaging = it)) },
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    canMinus: Boolean,
    canPlus: Boolean,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        RoundButton("−", onMinus, canMinus)
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        RoundButton("+", onPlus, canPlus)
    }
}

@Composable
private fun RoundButton(symbol: String, onClick: () -> Unit, enabled: Boolean) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = AppColors.Background,
        border = BorderStroke(1.dp, AppColors.Outline),
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = 20.sp, color = if (enabled) AppColors.TextPrimary else AppColors.Outline)
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp, color = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary)
            Text(detail, fontSize = 13.sp, color = AppColors.TextSecondary, lineHeight = 18.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Blue),
        )
    }
}

/** 설정 탭에서 여는 뷰어 설정 화면. 아래에 미리보기를 보여 준다. */
@Composable
fun ViewerSettingsScreen(settings: ViewerSettings, onChange: (ViewerSettings) -> Unit, onBack: () -> Unit) {
    val palette = ViewerPalette.of(settings.theme)
    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("뷰어 설정", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlineCard { ViewerSettingsPanel(settings, onChange, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

            SectionTitle("미리보기")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(palette.background, RoundedCornerShape(14.dp))
                    .padding(horizontal = settings.margin.dp, vertical = 18.dp),
            ) {
                listOf(
                    "이것은 번역문이 어떻게 보이는지 확인하기 위한 예시 문단입니다. 글자 크기와 줄 간격을 바꾸면 바로 반영됩니다.",
                    "두 번째 문단입니다. 좌우 여백과 테마도 여기에서 확인할 수 있습니다.",
                ).forEach { paragraph ->
                    Text(
                        paragraph,
                        color = palette.text,
                        fontSize = settings.fontSize.sp,
                        lineHeight = settings.lineHeight.sp,
                        modifier = Modifier.padding(bottom = (settings.fontSize * 0.9f).dp),
                    )
                }
            }
            Text(
                "뷰어 화면 아래의 \"뷰어 설정\"에서도 같은 설정을 바꿀 수 있습니다.",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, bottom = 24.dp),
            )
        }
    }
}
