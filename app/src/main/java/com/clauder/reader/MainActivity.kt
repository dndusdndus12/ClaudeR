package com.clauder.reader

import android.os.Build
import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.ui.theme.AppColors
import com.clauder.reader.ui.theme.ClauderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱 화면이 항상 밝은 배경이므로, 폰이 다크 모드여도 상태 표시줄 아이콘을 어둡게 고정한다.
        val lightBars = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)
        setContent {
            ClauderTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab(val label: String, val iconRes: Int) {
    Translate("번역", R.drawable.ic_tab_translate),
    Library("서재", R.drawable.ic_tab_library),
    Settings("설정", R.drawable.ic_tab_settings),
}

@Composable
private fun AppRoot() {
    var current by rememberSaveable { mutableStateOf(Tab.Translate) }

    Scaffold(
        containerColor = AppColors.Background,
        bottomBar = {
            Column {
                HorizontalDivider(color = AppColors.Outline)
                NavigationBar(containerColor = AppColors.Background, tonalElevation = 0.dp) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab,
                            onClick = { current = tab },
                            icon = { Icon(painterResource(tab.iconRes), contentDescription = tab.label) },
                            label = { Text(tab.label) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            PlaceholderScreen(current)
        }
    }
}

@Composable
private fun PlaceholderScreen(tab: Tab) {
    val description = when (tab) {
        Tab.Translate -> "여기에 소설 사이트를 여는 브라우저가 들어갑니다 (M1)"
        Tab.Library -> "여기에 번역해서 읽은 작품 목록이 들어갑니다 (M4)"
        Tab.Settings -> "여기에 AI, 프롬프트, 용어집, 뷰어 설정이 들어갑니다 (M2~M5)"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text(tab.label, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "M0 설치 확인용 빌드 · v${BuildConfig.VERSION_NAME}\n" +
                "기기: ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}
