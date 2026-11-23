package com.clauder.reader.browser

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.clauder.reader.R
import com.clauder.reader.ui.theme.AppColors

const val EXAMPLE_URL = "https://www.69shuba.com/txt/21160/12097699"

/**
 * 번역 탭: 위쪽 주소창, 가운데 웹 페이지, 아래쪽 동작 막대.
 * @param workLabel 마지막으로 추출한 작품명(없으면 페이지 제목을 보여준다)
 */
@Composable
fun BrowserScreen(
    browser: BrowserController,
    workLabel: String,
    extracting: Boolean,
    onExtract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = browser.canGoBack) { browser.goBack() }

    Column(modifier.fillMaxSize().background(AppColors.Background)) {
        AddressBar(browser)
        if (browser.isLoading) {
            LinearProgressIndicator(
                progress = { browser.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = AppColors.Blue,
                trackColor = Color.Transparent,
            )
        } else {
            HorizontalDivider(color = AppColors.Outline, thickness = 1.dp)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    // 탭을 다녀와도 같은 WebView 를 다시 붙인다.
                    (browser.webView.parent as? ViewGroup)?.removeView(browser.webView)
                    browser.webView
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!browser.hasPage) StartHint(onOpenExample = { browser.load(EXAMPLE_URL) })
        }

        HorizontalDivider(color = AppColors.Outline)
        ActionBar(browser, workLabel, extracting, onExtract)
    }
}

@Composable
private fun AddressBar(browser: BrowserController) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var focused by remember { mutableStateOf(false) }

    // 입력 중이 아닐 때는 현재 페이지 주소를 보여준다.
    LaunchedEffect(browser.url, focused) {
        if (!focused) text = TextFieldValue(browser.url)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .background(AppColors.SurfaceGray, RoundedCornerShape(12.dp))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = AppColors.TextPrimary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onGo = {
                    browser.load(text.text)
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        if (it.isFocused && !focused) text = text.copy(selection = TextRange(0, text.text.length))
                        focused = it.isFocused
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.text.isEmpty()) {
                            Text("주소 입력 또는 붙여넣기", color = AppColors.TextSecondary, fontSize = 15.sp)
                        }
                        inner()
                    }
                },
            )
            if (focused && text.text.isNotEmpty()) {
                IconButton(onClick = { text = TextFieldValue("") }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = "지우기",
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        IconButton(onClick = { if (browser.isLoading) browser.stop() else browser.reload() }) {
            Icon(
                painterResource(if (browser.isLoading) R.drawable.ic_close else R.drawable.ic_refresh),
                contentDescription = if (browser.isLoading) "중지" else "새로고침",
                tint = AppColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun ActionBar(
    browser: BrowserController,
    workLabel: String,
    extracting: Boolean,
    onExtract: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceGray)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = browser::goBack, enabled = browser.canGoBack, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "뒤로")
        }
        IconButton(onClick = browser::goForward, enabled = browser.canGoForward, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "앞으로")
        }
        Button(
            onClick = onExtract,
            enabled = browser.hasPage && !extracting,
            modifier = Modifier.padding(horizontal = 4.dp).height(44.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
        ) {
            if (extracting) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("추출 미리보기", fontWeight = FontWeight.SemiBold)
            }
        }
        // 작품명(추출 전에는 페이지 제목)
        Surface(
            modifier = Modifier.weight(1f).height(44.dp).padding(end = 8.dp),
            shape = RoundedCornerShape(22.dp),
            color = AppColors.Background,
            border = BorderStroke(1.dp, AppColors.Outline),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val label = workLabel.ifBlank { browser.title }
                if (workLabel.isNotBlank()) {
                    Box(Modifier.size(8.dp).background(AppColors.Green, CircleShape))
                }
                Text(
                    label.ifBlank { "페이지 없음" },
                    modifier = Modifier.padding(start = if (workLabel.isNotBlank()) 8.dp else 0.dp),
                    fontSize = 14.sp,
                    fontWeight = if (workLabel.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (label.isBlank()) AppColors.TextSecondary else AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StartHint(onOpenExample: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("소설 사이트를 열어 보세요", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "위 주소창에 주소를 입력하거나 붙여넣으면 일반 브라우저처럼 열립니다. " +
                "읽을 회차 페이지에서 아래의 파란 버튼을 누르면 본문을 추출합니다.",
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
        )
        OutlinedButton(onClick = onOpenExample, shape = RoundedCornerShape(12.dp)) {
            Text("예시 페이지 열기")
        }
    }
}
