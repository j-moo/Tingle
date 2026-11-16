package com.ssafy.e106.features.profile.ui

import com.ssafy.e106.core.ui.components.TagLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppRadio
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** 값과 선택 이벤트만 전달하는 화면. 저장·API·화면 이동은 호출부에서 연결한다. */
@Composable
fun ProfileLifestyleScreen(
    state: ProfileLifestyleState,
    onSmokingChange: (ProfileSmoking) -> Unit,
    onDrinkingChange: (ProfileDrinking) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.profile_lifestyle_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Text(stringResource(Res.string.profile_lifestyle_heading),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                        HelpText(Res.string.profile_lifestyle_intro)
                    }
                    ProfileLifestyleFields(state, onSmokingChange, onDrinkingChange)
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppButton(
                    text = stringResource(Res.string.profile_continue),
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                        .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    enabled = state.canContinue, loading = state.saving,
                    loadingText = stringResource(Res.string.profile_saving), onClick = onContinue,
                )
            }
        }
    }
}

/** 흡연 · 음주 선택과 도움말. 한 화면(위)과 가입 아코디언이 같이 쓴다 */
@Composable
internal fun ProfileLifestyleFields(state: ProfileLifestyleState, onSmokingChange: (ProfileSmoking) -> Unit, onDrinkingChange: (ProfileDrinking) -> Unit) {
    val editable = !state.saving
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)) {
        LifestyleChoices(
            title = Res.string.profile_smoking_title,
            options = ProfileSmoking.entries,
            selected = state.smoking,
            label = { it.label() }, onSelect = onSmokingChange,
            enabled = editable, error = state.smokingError,
        )
        HelpText(Res.string.profile_smoking_help)
        LifestyleChoices(
            title = Res.string.profile_drinking_title,
            options = ProfileDrinking.entries,
            selected = state.drinking,
            label = { it.label() }, onSelect = onDrinkingChange,
            enabled = editable, error = state.drinkingError,
        )
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            HelpText(Res.string.profile_drinking_days_help)
            HelpText(Res.string.profile_drinking_special_help)
        }
    }
}

@Composable
private fun <T> LifestyleChoices(
    title: StringResource,
    options: List<T>,
    selected: T?,
    label: (T) -> StringResource,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        TagLabel(stringResource(title), style = MaterialTheme.typography.titleMedium,
            color = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled, heading = true)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            options.forEach { option ->
                val isSelected = selected == option
                AppRadio(
                    selected = isSelected, onSelect = { onSelect(option) },
                    label = stringResource(label(option)), enabled = enabled,
                    modifier = Modifier.background(
                        if (isSelected && enabled) AppColors.SelectionContainer else AppColors.SurfaceMuted,
                        AppShapes.Control,
                    ),
                )
            }
        }
        if (enabled && error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = AppColors.Error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun HelpText(resource: StringResource) {
    Text(stringResource(resource), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
}

internal fun ProfileSmoking.label(): StringResource = when (this) {
    ProfileSmoking.NonSmoker -> Res.string.profile_smoking_none
    ProfileSmoking.Electronic -> Res.string.profile_smoking_electronic
    ProfileSmoking.OccasionallyWhenDrinking -> Res.string.profile_smoking_occasional
    ProfileSmoking.Smoker -> Res.string.profile_smoking_yes
}

internal fun ProfileDrinking.label(): StringResource = when (this) {
    ProfileDrinking.Never -> Res.string.profile_drinking_never
    ProfileDrinking.SpecialOccasions -> Res.string.profile_drinking_special
    ProfileDrinking.MonthlyOrLess -> Res.string.profile_drinking_monthly_less
    ProfileDrinking.MonthlyTwoToFour -> Res.string.profile_drinking_monthly
    ProfileDrinking.WeeklyTwoToThree -> Res.string.profile_drinking_weekly
    ProfileDrinking.WeeklyFourOrMore -> Res.string.profile_drinking_often
}
