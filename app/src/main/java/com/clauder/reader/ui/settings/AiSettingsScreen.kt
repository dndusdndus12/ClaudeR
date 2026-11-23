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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauder.reader.R
import com.clauder.reader.ai.AiProvider
import com.clauder.reader.ai.ModelInfo
import com.clauder.reader.data.ApiKeyEntry
import com.clauder.reader.ui.common.BackTopBar
import com.clauder.reader.ui.common.OutlineCard
import com.clauder.reader.ui.common.PrimaryButton
import com.clauder.reader.ui.common.SectionTitle
import com.clauder.reader.ui.theme.AppColors

private val ErrorText = Color(0xFFC62828)

/** 설정 > AI 설정: 공급자별 API 키, 모델 선택, 생각 강도 */
@Composable
fun AiSettingsScreen(
    providers: List<AiProvider>,
    keyCounts: Map<String, Int>,
    models: List<ModelInfo>,
    selectedModelId: String,
    effort: String,
    cacheTtl: String,
    prefetchNext: Boolean,
    loadingModels: Boolean,
    modelError: String?,
    onBack: () -> Unit,
    onOpenKeys: (providerId: String) -> Unit,
    onRefreshModels: () -> Unit,
    onSelectModel: (ModelInfo) -> Unit,
    onSelectEffort: (String) -> Unit,
    onSelectCacheTtl: (String) -> Unit,
    onSetPrefetchNext: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding()) {
        BackTopBar("AI 설정", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionTitle("내 API 키")
            OutlineCard {
                providers.forEachIndexed { index, provider ->
                    if (index > 0) HorizontalDivider(color = AppColors.Outline)
                    val count = keyCounts[provider.id] ?: 0
                    ProviderRow(provider, count, onClick = { onOpenKeys(provider.id) })
                }
            }
            Text(
                "키는 이 기기 안에만 암호화되어 저장되며, 설정 내보내기에 포함되지 않습니다.",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            SectionTitle("번역 모델")
            OutlineCard {
                val selected = models.firstOrNull { it.id == selectedModelId }
                Column(Modifier.padding(16.dp)) {
                    Text(
                        selected?.label ?: if (selectedModelId.isBlank()) "선택한 모델 없음" else selectedModelId,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (selected != null) {
                        Text(selected.id, fontSize = 13.sp, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onRefreshModels, enabled = !loadingModels, shape = RoundedCornerShape(10.dp)) {
                            Text(if (models.isEmpty()) "모델 목록 불러오기" else "모델 목록 새로 고침")
                        }
                        if (loadingModels) {
                            CircularProgressIndicator(
                                Modifier.padding(start = 12.dp).size(18.dp),
                                strokeWidth = 2.dp,
                                color = AppColors.Blue,
                            )
                        }
                    }
                    modelError?.let {
                        Text(it, color = ErrorText, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                models.forEach { model ->
                    HorizontalDivider(color = AppColors.Outline)
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelectModel(model) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = model.id == selectedModelId,
                            onClick = { onSelectModel(model) },
                            colors = RadioButtonDefaults.colors(selectedColor = AppColors.Blue),
                        )
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(model.label, fontSize = 16.sp)
                            Text(
                                model.id + if (model.lifecycle == "deprecated") " · 지원 종료 예정" else "",
                                fontSize = 12.sp,
                                color = AppColors.TextSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            SectionTitle("생각 강도")
            OutlineCard {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EFFORT_CHOICES.forEach { (value, label) ->
                            ChoiceChip(label, selected = effort == value, onClick = { onSelectEffort(value) })
                        }
                    }
                    Text(
                        "모델이 번역 전에 얼마나 깊이 생각할지 정합니다. 낮을수록 빠르고 비용이 적게 들고, " +
                            "높을수록 품질이 좋아질 수 있습니다. 선택한 모델이 지원하지 않는 단계는 보내지 않고 모델 기본값으로 동작합니다.",
                        color = AppColors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            SectionTitle("프롬프트 캐시 유지 시간")
            OutlineCard {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("1시간", selected = cacheTtl == "1h", onClick = { onSelectCacheTtl("1h") })
                        ChoiceChip("5분", selected = cacheTtl == "5m", onClick = { onSelectCacheTtl("5m") })
                    }
                    Text(
                        "매번 똑같이 보내는 프롬프트 부분을 서버가 기억해 두어 입력 요금을 줄입니다. " +
                            "기억시키는 첫 요청은 조금 더 비싸고(1시간은 2배, 5분은 1.25배), 그 뒤로는 훨씬 싸게 읽습니다. " +
                            "회차 하나를 읽는 데 5분 넘게 걸린다면 1시간이 유리합니다. " +
                            "프롬프트가 너무 짧으면(모델에 따라 약 500~4,000토큰 미만) 캐시되지 않습니다.",
                        color = AppColors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            SectionTitle("미리 번역")
            OutlineCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("다음 화 미리 번역", fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = prefetchNext,
                            onCheckedChange = onSetPrefetchNext,
                            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Blue),
                        )
                    }
                    Text(
                        "켜 두면 회차를 읽는 동안 다음 1회차를 미리 번역해 저장합니다. \"다음 화\"를 눌렀을 때 기다리지 않고 바로 읽을 수 있습니다. " +
                            "미리 번역한 회차도 똑같이 요금이 나가며, 읽지 않고 그만두면 그 회차의 요금은 그대로 쓴 것이 됩니다. " +
                            "첫 번역은 지금처럼 직접 번역 버튼을 눌러야 시작하고, 뷰어를 닫으면 미리 번역도 멈춥니다.",
                        color = AppColors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

private val EFFORT_CHOICES = listOf("" to "모델 기본값", "low" to "낮음", "medium" to "중간", "high" to "높음")

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) AppColors.Blue else AppColors.Background,
        border = BorderStroke(1.dp, if (selected) AppColors.Blue else AppColors.Outline),
    ) {
        Text(
            label,
            color = if (selected) Color.White else AppColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ProviderRow(provider: AiProvider, keyCount: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (provider.implemented) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                provider.displayName,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (provider.implemented) AppColors.TextPrimary else AppColors.TextSecondary,
            )
            Text(
                when {
                    !provider.implemented -> "지원 예정"
                    keyCount == 0 -> "키 없음"
                    else -> "키 ${keyCount}개"
                },
                fontSize = 14.sp,
                color = AppColors.TextSecondary,
            )
        }
        if (provider.implemented) {
            if (keyCount == 0) {
                Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, AppColors.Blue), color = AppColors.Background) {
                    Text(
                        "등록",
                        color = AppColors.Blue,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            } else {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = AppColors.TextSecondary)
            }
        }
    }
}

/** 한 공급자의 API 키 목록과 추가/삭제 */
@Composable
fun AiKeysScreen(
    providerName: String,
    keys: List<ApiKeyEntry>,
    /** 키가 실패해서 쉬는 중이면 그 설명, 아니면 null */
    restingStatus: (ApiKeyEntry) -> String?,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ApiKeyEntry?>(null) }

    Column(Modifier.fillMaxSize().background(AppColors.Background).navigationBarsPadding().imePadding()) {
        BackTopBar("$providerName API 키", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "키를 여러 개 등록해 두면, 한 키가 인증 실패, 요청 한도 초과, 크레딧 소진, 서버 과부하로 실패했을 때 " +
                    "자동으로 다음 키로 다시 시도합니다. 실패한 키는 잠시 쉬게 합니다.",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            OutlineCard {
                if (keys.isEmpty()) {
                    Text("등록된 키가 없습니다.", color = AppColors.TextSecondary, modifier = Modifier.padding(16.dp))
                }
                keys.forEachIndexed { index, key ->
                    if (index > 0) HorizontalDivider(color = AppColors.Outline)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", color = AppColors.TextSecondary, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(key.masked(), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                            restingStatus(key)?.let { Text(it, fontSize = 13.sp, color = ErrorText) }
                        }
                        TextButton(onClick = { pendingDelete = key }) { Text("삭제", color = ErrorText) }
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("새 API 키") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            )
        }
        PrimaryButton(
            text = "키 추가",
            enabled = input.isNotBlank(),
            onClick = {
                onAdd(input)
                input = ""
            },
            modifier = Modifier.padding(16.dp),
        )
    }

    pendingDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("키 삭제") },
            text = { Text("${key.masked()}\n이 키를 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(key.id)
                    pendingDelete = null
                }) { Text("삭제", color = ErrorText) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } },
        )
    }
}
