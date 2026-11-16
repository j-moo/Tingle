package com.ssafy.e106.features.preferences.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import com.ssafy.e106.features.settings.ui.*

/**
 * 희망 나이(시안 03 위쪽 탭). 맨 위 "상관없어요 | 범위 정하기" 탭, 그 아래 범위(큰 숫자 · 막대 · 최소/최대 칸)는 늘 보이고
 * 범위가 아니면 회색으로 잠근다. 최대를 막대 끝(60세+)까지 밀거나 최대 칸을 비우면 제한 없음. 범위 오류면 큰 숫자 대신 두 칸을 빨갛게.
 * 상태를 표시하고 편집 이벤트만 전달한다. 저장·API·화면 이동은 호출부의 책임이다.
 */
@Composable
fun AgePreferenceScreen(
    state: AgePreferenceState,
    onStateChange: (AgePreferenceState) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    onConflictAction: (ConflictAction<ConflictValues.Age>) -> Unit = {},
    onBack: (() -> Unit)? = null,
    submitEnabled: Boolean = true,
    submitLabel: String? = null,
    /** 불러오는 중: 틀(제목 · 아래 버튼)은 그대로 두고 값 자리만 회색 뼈대로 둔다 */
    loading: Boolean = false,
    refreshing: Boolean = false,
    /** 있으면 당겨서 새로고침이 된다 */
    onRefresh: (() -> Unit)? = null,
    /** 버튼 위 알림 자리(새로고침 실패 띠 등). 좌우 여백은 호출부가 정한다 */
    notice: @Composable ColumnScope.() -> Unit = {},
) {
    state.conflict?.let { SettingsConflictScreen(it, onConflictAction); return }
    val editable = !state.saving
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // 제목 없이 왼쪽 위 < 하나. 눌러도 회색 원(물결)이 생기지 않게 표시 없이 누르기만, 터치 영역 48dp
            if (onBack != null) Box(Modifier.fillMaxWidth().heightIn(min = AppSizes.TopBarMinHeight).padding(horizontal = AppSpacing.Small),
                contentAlignment = Alignment.CenterStart) {
                Box(Modifier.size(48.dp).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.button_previous), tint = AppColors.TextPrimary)
                }
            }
            // 빈 곳을 당겨도 새로고침이 되게 화면 높이를 다 쓴다
            val body: @Composable BoxScope.() -> Unit = {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    Text(stringResource(Res.string.age_preference_heading),
                        style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    // 불러오는 중: 탭 · 큰 숫자 · 막대 · 최소/최대 칸 자리만 회색으로
                    if (loading) SkeletonGroup {
                        SkeletonBone(height = AppSpacing.ControlMinHeight, shape = AppShapes.Control)
                        SkeletonBone(width = 160.dp, height = 28.dp)
                        SkeletonBone(height = 24.dp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                            SkeletonField(Modifier.weight(1f)); SkeletonField(Modifier.weight(1f))
                        }
                    } else {
                        ModeTabs(state.mode, editable) { onStateChange(state.selectMode(it)) }
                        AgeRangeEditor(state, onStateChange, enabled = editable && state.mode == AgePreferenceMode.Range)
                        if (state.mode == null) Text(stringResource(Res.string.age_preference_select_help),
                            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                }
            }
            if (onRefresh != null) PullDownRefreshBox(refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth(), content = body)
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter, content = body)
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                notice()
                Column(Modifier.fillMaxWidth().padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                    // 저장 실패: 입력은 그대로 두고 버튼 위 빨간 한 줄 + 버튼 이름 "다시 시도"(고치거나 성공하면 saveFailed 가 풀린다)
                    if (state.saveFailed) InlineError(stringResource(Res.string.age_preference_save_failed))
                    AppButton(if (state.saveFailed) stringResource(Res.string.state_retry) else submitLabel ?: stringResource(Res.string.age_preference_continue),
                        modifier = Modifier.fillMaxWidth(), enabled = !loading && state.canContinue && submitEnabled, loading = state.saving,
                        loadingText = stringResource(Res.string.age_preference_saving), onClick = onContinue)
                }
            }
        }
    }
}

/** 희망 나이 입력 칸만(탭 · 범위 · 안내). 가입 준비 화면의 줄 안에 펼쳐 넣을 때 쓴다 */
@Composable
fun AgePreferenceFields(state: AgePreferenceState, onStateChange: (AgePreferenceState) -> Unit) {
    val editable = !state.saving
    ModeTabs(state.mode, editable) { onStateChange(state.selectMode(it)) }
    AgeRangeEditor(state, onStateChange, enabled = editable && state.mode == AgePreferenceMode.Range)
    if (state.mode == null) Text(stringResource(Res.string.age_preference_select_help),
        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
}

/** "상관없어요 | 범위 정하기" 탭 */
@Composable
private fun ModeTabs(mode: AgePreferenceMode?, enabled: Boolean, onSelect: (AgePreferenceMode) -> Unit) =
    AppSegmentedTabs(AgePreferenceMode.entries.map { stringResource(if (it == AgePreferenceMode.Any) Res.string.age_preference_tab_any else Res.string.age_preference_tab_range) },
        mode?.ordinal, { onSelect(AgePreferenceMode.entries[it]) }, enabled = enabled)

@OptIn(ExperimentalMaterial3Api::class)   // 손잡이 · 줄 모양을 바꾸는 RangeSlider(thumb · track) 판이 실험 API
@Composable
private fun AgeRangeEditor(state: AgePreferenceState, onStateChange: (AgePreferenceState) -> Unit, enabled: Boolean) {
    val low = (state.minimumValue ?: MIN_AGE).coerceIn(MIN_AGE, AGE_SLIDER_TOP)
    val high = if (state.noUpperLimit) AGE_SLIDER_TOP else (state.maximumValue ?: AGE_SLIDER_TOP).coerceIn(low, AGE_SLIDER_TOP)
    val invalid = enabled && (state.minimumInvalid || state.maximumInvalid || state.reversed)
    // 큰 숫자는 막대가 아니라 입력값 그대로(숫자 칸에 60보다 큰 나이를 써도 그 나이로 보인다. 막대 손잡이만 끝에 붙음)
    val top = state.maximumValue.takeIf { !state.noUpperLimit && state.maximumAge.isNotEmpty() }
    val bottom = state.minimumValue ?: MIN_AGE
    val summary = when {
        top == null -> stringResource(Res.string.age_preference_at_least, bottom)
        top == bottom -> stringResource(Res.string.age_preference_exact, bottom)
        else -> stringResource(Res.string.age_preference_selected, bottom, top)
    }
    // 범위가 아니면(상관없어요 · 아직 안 고름) 전부 회색으로 잠근다. 고른 값은 그대로 남겨 다시 범위를 고르면 이어 쓴다
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        if (!invalid) Text(summary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = if (enabled) AppColors.OnSelection else AppColors.TextSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        // 시안 막대: 연한 초록 얇은 줄 + 고른 구간만 진한 초록, 손잡이는 초록 원 · 흰 테 · 초록 바깥 테. 손잡이 옆 틈 · 끝 점 없음
        val colors = SliderDefaults.colors(activeTrackColor = AppColors.SelectionBorder, inactiveTrackColor = AppColors.SelectionContainer,
            disabledActiveTrackColor = AppColors.SelectionBorder, disabledInactiveTrackColor = AppColors.SelectionContainer)
        RangeSlider(value = low.toFloat()..high.toFloat(),
            onValueChange = { onStateChange(state.changeSlider(it.start.roundToInt(), it.endInclusive.roundToInt())) },
            valueRange = MIN_AGE.toFloat()..AGE_SLIDER_TOP.toFloat(), enabled = enabled, colors = colors,
            startThumb = { AgeThumb() }, endThumb = { AgeThumb() },
            track = { SliderDefaults.Track(it, Modifier.height(4.dp), colors = colors, enabled = enabled,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = summary })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Res.string.age_preference_years, MIN_AGE), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Text(stringResource(Res.string.age_preference_top, AGE_SLIDER_TOP), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            AppTextField(state.minimumAge, { onStateChange(state.changeMinimum(it)) },
                stringResource(Res.string.age_preference_minimum), modifier = Modifier.weight(1f),
                placeholder = "$MIN_AGE", enabled = enabled, isError = invalid && (state.minimumInvalid || state.reversed),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
            // 비우면 제한 없음(막대 끝 60세+ 와 같음)
            AppTextField(if (state.noUpperLimit) "" else state.maximumAge, { onStateChange(state.changeMaximum(it)) },
                stringResource(Res.string.age_preference_maximum), modifier = Modifier.weight(1f),
                placeholder = stringResource(Res.string.age_preference_no_limit), enabled = enabled,
                isError = invalid && (state.maximumInvalid || state.reversed),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
        }
        // 두 칸에 한 문구: 범위가 뒤집히면 둘 다 빨간 테두리
        if (invalid) Text(stringResource(if (state.reversed) Res.string.age_preference_order_error else Res.string.age_preference_integer_error),
            style = MaterialTheme.typography.bodySmall, color = AppColors.Error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** 막대 손잡이: 초록 원 · 흰 테 · 초록 바깥 테(시안) */
@Composable
private fun AgeThumb() {
    Box(Modifier.size(24.dp).border(1.5.dp, AppColors.SelectionBorder, CircleShape).padding(4.dp)
        .background(AppColors.SelectionBorder, CircleShape))
}
