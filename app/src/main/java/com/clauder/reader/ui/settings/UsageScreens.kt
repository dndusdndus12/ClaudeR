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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.ai.ModelInfo
import com.clauder.reader.data.ModelPrice
import com.clauder.reader.data.Pricing
import com.clauder.reader.data.db.MonthlyUsage
import com.clauder.reader.data.db.UsageLogEntity
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.common.OutlineCard
import com.clauder.reader.ui.common.PrimaryButton
import com.clauder.reader.ui.common.SectionTitle
import com.clauder.reader.ui.theme.AppColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WarnText = Color(0xFF9A6700)
private val ErrorText = Color(0xFFC62828)

private fun tokens(n: Long): String = NumberFormat.getIntegerInstance().format(n)

/** "2026-11" → "2026년 11월" */
private fun monthLabel(month: String): String {
    val parts = month.split('-')
    return if (parts.size == 2) "${parts[0]}년 ${parts[1].trimStart('0')}월" else month
}

fun currentMonthKey(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

/** 설정 > 사용량과 비용: 이번 달 합계, 월별 누적, 최근 번역 기록 */
@Composable
fun UsageScreen(
    monthly: List<MonthlyUsage>,
    recent: List<UsageLogEntity>,
    onBack: () -> Unit,
    onOpenPrices: () -> Unit,
) {
    val thisMonth = monthly.firstOrNull { it.month == currentMonthKey() }
    val dateFormat = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }

    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("사용량과 비용", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("이번 달")
            OutlineCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        thisMonth?.costUsd?.let(Pricing::formatUsd) ?: "$0",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (thisMonth == null) "아직 번역 기록이 없습니다." else usageLine(thisMonth),
                        fontSize = 14.sp,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (thisMonth != null && thisMonth.unpriced > 0) {
                        Text(
                            "단가를 모르는 모델의 번역 ${thisMonth.unpriced}회는 금액에 포함되지 않았습니다.",
                            fontSize = 13.sp,
                            color = WarnText,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            Text(
                "금액은 토큰 수에 단가를 곱한 추정값입니다. 실제 청구액은 Anthropic 콘솔에서 확인하세요.",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            SectionTitle("단가")
            OutlineCard {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenPrices).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("모델별 단가", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "기본값은 ${Pricing.DEFAULTS_AS_OF} 공식 가격표 기준 · 직접 고칠 수 있습니다",
                            fontSize = 14.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = AppColors.TextSecondary)
                }
            }

            SectionTitle("월별 누적")
            OutlineCard {
                if (monthly.isEmpty()) {
                    Text("기록이 없습니다.", color = AppColors.TextSecondary, modifier = Modifier.padding(16.dp))
                }
                monthly.forEachIndexed { index, month ->
                    if (index > 0) HorizontalDivider(color = AppColors.Outline)
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(monthLabel(month.month), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(usageLine(month), fontSize = 13.sp, color = AppColors.TextSecondary)
                        }
                        Text(
                            month.costUsd?.let(Pricing::formatUsd) ?: "단가 없음",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
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
                        Text(entry.costUsd?.let(Pricing::formatUsd) ?: "—", fontSize = 15.sp)
                    }
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

private fun usageLine(m: MonthlyUsage): String = buildList {
    add("번역 ${m.requests}회")
    add("입력 ${tokens(m.inputTokens)}")
    add("출력 ${tokens(m.outputTokens)}")
    if (m.cacheReadTokens > 0) add("캐시 읽기 ${tokens(m.cacheReadTokens)}")
    if (m.cacheWriteTokens > 0) add("캐시 쓰기 ${tokens(m.cacheWriteTokens)}")
}.joinToString(" · ")

/** 모델별 단가 목록 */
@Composable
fun PriceListScreen(
    models: List<ModelInfo>,
    custom: Map<String, ModelPrice>,
    onBack: () -> Unit,
    onEdit: (modelId: String) -> Unit,
) {
    // 모델 목록에 없더라도 직접 단가를 넣어 둔 모델은 함께 보여 준다.
    val ids = remember(models, custom) { (models.map { it.id } + custom.keys).distinct() }
    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("모델별 단가", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "토큰 100만 개당 달러입니다. 가격이 바뀌면 모델을 눌러 고치세요. " +
                    "고친 값은 그 뒤의 번역부터 적용되고, 이미 기록된 금액은 바뀌지 않습니다.",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            OutlineCard {
                if (ids.isEmpty()) {
                    Text(
                        "모델 목록이 없습니다. 설정 > AI 설정에서 모델 목록을 먼저 불러오세요.",
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                ids.forEachIndexed { index, id ->
                    if (index > 0) HorizontalDivider(color = AppColors.Outline)
                    val price = Pricing.priceFor(id, custom)
                    val label = models.firstOrNull { it.id == id }?.label ?: id
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    price == null -> "단가 없음 · 눌러서 입력"
                                    else -> "입력 $${Pricing.formatRate(price.input)} · 출력 $${Pricing.formatRate(price.output)}" +
                                        if (id in custom) " · 직접 입력" else " · 기본값"
                                },
                                fontSize = 14.sp,
                                color = if (price == null) WarnText else AppColors.TextSecondary,
                            )
                        }
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = AppColors.TextSecondary)
                    }
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

/**
 * 모델 하나의 단가를 고치는 화면.
 * @param current 지금 적용 중인 단가(직접 입력한 값 또는 기본값). 없으면 빈 칸으로 시작한다.
 * @param isCustom 직접 입력한 값을 쓰고 있는지(그렇다면 기본값으로 되돌리기 버튼을 보여 준다)
 */
@Composable
fun PriceEditScreen(
    modelLabel: String,
    modelId: String,
    current: ModelPrice?,
    isCustom: Boolean,
    onBack: () -> Unit,
    onSave: (ModelPrice) -> Unit,
    onReset: () -> Unit,
) {
    fun initial(value: Double?) = value?.let(Pricing::formatRate).orEmpty()
    var input by remember { mutableStateOf(initial(current?.input)) }
    var output by remember { mutableStateOf(initial(current?.output)) }
    var cacheRead by remember { mutableStateOf(initial(current?.cacheRead)) }
    var write5m by remember { mutableStateOf(initial(current?.cacheWrite5m)) }
    var write1h by remember { mutableStateOf(initial(current?.cacheWrite1h)) }

    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
    val values = listOf(input, output, cacheRead, write5m, write1h).map(::parse)
    val valid = values.all { it != null }

    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding().imePadding()) {
        BackTopBar("단가 수정", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(modelLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(modelId, fontSize = 13.sp, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace)
            Text(
                "토큰 100만 개당 달러로 입력합니다.",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            RateField("입력", input) { input = it }
            RateField("출력", output) { output = it }
            RateField("캐시 읽기", cacheRead) { cacheRead = it }
            RateField("캐시 쓰기 (5분 유지)", write5m) { write5m = it }
            RateField("캐시 쓰기 (1시간 유지)", write1h) { write1h = it }
            if (!valid) {
                Text("모든 칸에 0 이상의 숫자를 입력하세요.", color = ErrorText, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (isCustom) {
                TextButton(onClick = onReset, modifier = Modifier.padding(top = 4.dp)) { Text("기본값으로 되돌리기") }
            }
        }
        PrimaryButton(
            text = "저장하기",
            enabled = valid,
            onClick = { onSave(ModelPrice(values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!)) },
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun RateField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        prefix = { Text("$") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
