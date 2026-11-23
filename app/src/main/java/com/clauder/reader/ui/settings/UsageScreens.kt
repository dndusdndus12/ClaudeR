package com.clauder.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.data.db.MonthlyUsage
import com.clauder.reader.data.db.UsageLogEntity
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.common.OutlineCard
import com.clauder.reader.ui.common.SectionTitle
import com.clauder.reader.ui.theme.AppColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ErrorText = Color(0xFFC62828)

/** 실제 요금과 사용량을 볼 수 있는 Claude Console 페이지(로그인 필요). 앱에서는 조회할 수 없어 링크만 둔다. */
private const val CONSOLE_USAGE_URL = "https://platform.claude.com/usage"
private const val CONSOLE_COST_URL = "https://platform.claude.com/cost"

private fun tokens(n: Long): String = NumberFormat.getIntegerInstance().format(n)

/** "2026-11" → "2026년 11월" */
private fun monthLabel(month: String): String {
    val parts = month.split('-')
    return if (parts.size == 2) "${parts[0]}년 ${parts[1].trimStart('0')}월" else month
}

fun currentMonthKey(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

/** 설정 화면의 한 줄 요약. 예: "이번 달 번역 12회 · 입력 31,204 · 출력 50,113" */
fun usageSummaryLine(monthly: List<MonthlyUsage>): String {
    val m = monthly.firstOrNull { it.month == currentMonthKey() } ?: return "이번 달 기록 없음"
    return "이번 달 번역 ${m.requests}회 · 입력 ${tokens(m.inputTokens)} · 출력 ${tokens(m.outputTokens)}"
}

/**
 * 설정 > 토큰 사용량: 이번 달 합계, 월별 누적, 최근 번역 기록.
 * API 가 응답에 적어 준 토큰 수만 보여 준다. 금액은 앱이 정확히 알 수 없으므로 계산하지 않는다.
 */
@Composable
fun UsageScreen(
    monthly: List<MonthlyUsage>,
    recent: List<UsageLogEntity>,
    onBack: () -> Unit,
) {
    val thisMonth = monthly.firstOrNull { it.month == currentMonthKey() }
    val dateFormat = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("토큰 사용량", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("이번 달")
            OutlineCard {
                Column(Modifier.padding(16.dp)) {
                    Text("번역 ${thisMonth?.requests ?: 0}회", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (thisMonth == null) {
                        Text(
                            "아직 번역 기록이 없습니다.",
                            fontSize = 14.sp,
                            color = AppColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        TokenRow("입력", thisMonth.inputTokens)
                        TokenRow("출력", thisMonth.outputTokens)
                        TokenRow("캐시 읽기", thisMonth.cacheReadTokens)
                        TokenRow("캐시 쓰기", thisMonth.cacheWriteTokens)
                    }
                }
            }
            Text(
                "이 앱이 보낸 번역 요청에 대해 API 가 응답으로 알려 준 토큰 수입니다. " +
                    "도중에 취소한 번역은 토큰 수를 받지 못해 여기에 들어가지 않습니다.",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            SectionTitle("실제 요금과 남은 크레딧")
            OutlineCard {
                LinkRow("Claude Console 에서 사용량 보기", "platform.claude.com/usage") { runCatching { uriHandler.openUri(CONSOLE_USAGE_URL) } }
                HorizontalDivider(color = AppColors.Outline)
                LinkRow("Claude Console 에서 비용 보기", "platform.claude.com/cost") { runCatching { uriHandler.openUri(CONSOLE_COST_URL) } }
            }
            Text(
                "요금과 남은 크레딧은 앱에서 조회할 수 없어 표시하지 않습니다. " +
                    "API 키를 만든 계정으로 Claude Console 에 로그인하면 정확한 값을 볼 수 있습니다.",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            SectionTitle("월별 누적")
            OutlineCard {
                if (monthly.isEmpty()) {
                    Text("기록이 없습니다.", color = AppColors.TextSecondary, modifier = Modifier.padding(16.dp))
                }
                monthly.forEachIndexed { index, month ->
                    if (index > 0) HorizontalDivider(color = AppColors.Outline)
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(monthLabel(month.month), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(usageLine(month), fontSize = 13.sp, color = AppColors.TextSecondary)
                    }
                }
            }

            SectionTitle("최근 번역")
            OutlineCard {
                if (recent.isEmpty()) {
                    Text("기록이 없습니다.", color = AppColors.TextSecondary, modifier = Modifier.padding(16.dp))
                }
                recent.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = AppColors.Outline)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            entry.chapterTitle.ifBlank { entry.chapterUrl },
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${entry.workTitle} · ${entry.modelName} · ${dateFormat.format(Date(entry.time))}",
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "입력 ${tokens(entry.inputTokens)} · 출력 ${tokens(entry.outputTokens)}" +
                                (if (entry.cacheReadTokens > 0) " · 캐시 읽기 ${tokens(entry.cacheReadTokens)}" else "") +
                                (if (entry.cacheWriteTokens > 0) " · 캐시 쓰기 ${tokens(entry.cacheWriteTokens)}" else "") +
                                (if (!entry.success) " · 실패" else ""),
                            fontSize = 13.sp,
                            color = if (entry.success) AppColors.TextSecondary else ErrorText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

@Composable
private fun TokenRow(label: String, count: Long) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
        Text("${tokens(count)} 토큰", fontSize = 15.sp)
    }
}

@Composable
private fun LinkRow(title: String, address: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(address, fontSize = 13.sp, color = AppColors.TextSecondary)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = AppColors.TextSecondary)
    }
}

private fun usageLine(m: MonthlyUsage): String = buildList {
    add("번역 ${m.requests}회")
    add("입력 ${tokens(m.inputTokens)}")
    add("출력 ${tokens(m.outputTokens)}")
    if (m.cacheReadTokens > 0) add("캐시 읽기 ${tokens(m.cacheReadTokens)}")
    if (m.cacheWriteTokens > 0) add("캐시 쓰기 ${tokens(m.cacheWriteTokens)}")
}.joinToString(" · ")
