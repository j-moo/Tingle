package com.ssafy.e106.features.preferences.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import com.ssafy.e106.features.settings.ui.*

/** 상태를 표시하고 편집 이벤트만 전달한다. 저장·API·화면 이동은 호출부의 책임이다. */
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
) {
    state.conflict?.let { SettingsConflictScreen(it, onConflictAction); return }
    val editable = !state.saving
    // 가로+키보드에서는 저장 버튼도 스크롤에 포함한다. 서버 저장 여부·버튼 활성 조건은 바꾸지 않는다.
    val submit: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AppButton(submitLabel ?: stringResource(if (state.saveFailed) Res.string.age_preference_retry else Res.string.age_preference_continue),
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                enabled = state.canContinue && submitEnabled, loading = state.saving,
                loadingText = stringResource(Res.string.age_preference_saving), onClick = onContinue)
        }
    }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val compactHeight = maxHeight < 320.dp
            Column(Modifier.fillMaxSize()) {
                if (!compactHeight) AppTopBar(stringResource(Res.string.age_preference_title), onBack = onBack)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                        // 같은 입력 컴포지션을 유지한다. 키보드가 나타나도 입력값·포커스를 새로 만들지 않는다.
                        if (compactHeight) AppTopBar(stringResource(Res.string.age_preference_title), onBack = onBack)
                        Text(stringResource(Res.string.age_preference_heading),
                            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text(stringResource(Res.string.age_preference_intro),
                            style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            AgePreferenceMode.entries.forEach { mode ->
                                AppRadio(state.mode == mode,
                                    { onStateChange(state.copy(mode = mode, saveFailed = false)) },
                                    stringResource(if (mode == AgePreferenceMode.Any) Res.string.age_preference_any else Res.string.age_preference_range),
                                    Modifier.background(if (state.mode == mode) AppColors.SelectionContainer else AppColors.SurfaceMuted, AppShapes.Control),
                                    enabled = editable)
                            }
                        }
                        when (state.mode) {
                            AgePreferenceMode.Range -> AgeRangeEditor(state, onStateChange)
                            AgePreferenceMode.Any -> Text(stringResource(Res.string.age_preference_any_help),
                                color = AppColors.OnSelection, style = MaterialTheme.typography.bodyLarge)
                            null -> Text(stringResource(Res.string.age_preference_select_help),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                        Text(stringResource(Res.string.age_preference_apply_help),
                            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        if (state.saveFailed) Text(stringResource(Res.string.age_preference_save_failed),
                            style = MaterialTheme.typography.bodyMedium, color = AppColors.Error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (compactHeight) submit()
                    }
                }
                if (!compactHeight) submit()
            }
        }
    }
}

@Composable
private fun AgeRangeEditor(state: AgePreferenceState, onStateChange: (AgePreferenceState) -> Unit) {
    val editable = !state.saving
    val summary = when {
        !state.validRange -> stringResource(Res.string.age_preference_input_help)
        state.noUpperLimit -> stringResource(Res.string.age_preference_at_least, state.minimumValue!!)
        state.minimumValue == state.maximumValue -> stringResource(Res.string.age_preference_exact, state.minimumValue!!)
        else -> stringResource(Res.string.age_preference_selected, state.minimumValue!!, state.maximumValue!!)
    }
    Text(summary, style = MaterialTheme.typography.headlineSmall, color = AppColors.OnSelection,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    val end = state.sliderEnd.toFloat()
    val low = (state.minimumValue ?: 19).toFloat().coerceIn(19f, end)
    val high = (state.maximumValue ?: state.sliderEnd).toFloat().coerceIn(low, end)
    val colors = SliderDefaults.colors(thumbColor = AppColors.SelectionBorder,
        activeTrackColor = AppColors.SelectionBorder, inactiveTrackColor = AppColors.SelectionContainer)
    if (state.noUpperLimit) {
        Slider(value = low, onValueChange = { onStateChange(state.changeMinimum(it.roundToInt().toString())) },
            valueRange = 19f..end, enabled = editable, colors = colors,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = summary })
    } else {
        RangeSlider(value = low..high,
            onValueChange = { onStateChange(state.changeRange(it.start.roundToInt(), it.endInclusive.roundToInt())) },
            valueRange = 19f..end, enabled = editable, colors = colors,
            modifier = Modifier.fillMaxWidth())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(Res.string.age_preference_years, 19), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.age_preference_years, state.sliderEnd), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(Res.string.age_preference_slider_help),
        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        AppTextField(state.minimumAge, { onStateChange(state.changeMinimum(it)) },
            stringResource(Res.string.age_preference_minimum), modifier = Modifier.weight(1f),
            placeholder = "19", enabled = editable,
            errorText = if (state.minimumInvalid) stringResource(Res.string.age_preference_integer_error) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
        if (!state.noUpperLimit) AppTextField(state.maximumAge, { onStateChange(state.changeMaximum(it)) },
            stringResource(Res.string.age_preference_maximum), modifier = Modifier.weight(1f),
            enabled = editable, errorText = when {
                state.maximumInvalid -> stringResource(Res.string.age_preference_integer_error)
                state.reversed -> stringResource(Res.string.age_preference_order_error)
                else -> null
            }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
    }
    AppCheckbox(state.noUpperLimit,
        { onStateChange(state.copy(noUpperLimit = it, saveFailed = false)) },
        stringResource(Res.string.age_preference_no_upper), enabled = editable)
}
