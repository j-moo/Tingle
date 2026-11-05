package com.ssafy.e106.features.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 비교·명시적 선택만 제공한다. 서버 저장·화면 이동·기기 동기화를 수행하지 않는다. */
@Composable
fun <T : ConflictValues> SettingsConflictScreen(state: SettingsConflictState<T>, onAction: (ConflictAction<T>) -> Unit) {
    val headingText = when (state.stage) {
        ConflictStage.Changed -> Res.string.conflict_changed
        ConflictStage.VersionMissing -> Res.string.conflict_version_missing
        ConflictStage.Loading -> Res.string.conflict_loading
        ConflictStage.LoadFailed -> Res.string.conflict_load_failed
        ConflictStage.Review -> Res.string.conflict_review
        ConflictStage.Saving -> Res.string.conflict_saving
        ConflictStage.SaveFailed -> Res.string.conflict_save_failed
        ConflictStage.OutcomeUnknown -> Res.string.conflict_unknown
    }
    Surface(color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            AppTopBar(stringResource(if (state.intended is ConflictValues.Age) Res.string.age_preference_title else Res.string.settings_matching_title))
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                Text(stringResource(headingText), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                Text(stringResource(when (state.stage) {
                    ConflictStage.Changed -> Res.string.conflict_changed_help
                    ConflictStage.OutcomeUnknown -> Res.string.conflict_unknown_help
                    ConflictStage.Saving -> Res.string.conflict_saving_help
                    ConflictStage.Review, ConflictStage.SaveFailed -> Res.string.conflict_review_help
                    else -> Res.string.conflict_reload_help
                }), color = AppColors.TextSecondary)
                Text(stringResource(Res.string.conflict_intended), style = MaterialTheme.typography.titleMedium)
                ConflictValueText(state.intended)
                val latest = state.reviewed
                if (latest != null) {
                    Text(stringResource(Res.string.conflict_latest), style = MaterialTheme.typography.titleMedium)
                    ConflictValueText(latest.values)
                    if (latest.values == state.intended) Text(stringResource(Res.string.conflict_equal), color = AppColors.TextSecondary)
                    Text(stringResource(Res.string.conflict_sync_help), color = AppColors.TextSecondary)
                }
                if (!state.busy) {
                    if (latest != null) AppButton(stringResource(Res.string.conflict_use_latest), Modifier.fillMaxWidth(),
                        variant = AppButtonVariant.Secondary, onClick = { onAction(ConflictAction.UseLatest(latest)) })
                    AppButton(stringResource(Res.string.conflict_back), Modifier.fillMaxWidth(),
                        variant = AppButtonVariant.Secondary, onClick = { onAction(ConflictAction.Back) })
                }
            }
            val review = state.reviewed
            val save = state.saveAction
            AppButton(stringResource(if (review == null) Res.string.conflict_reload else Res.string.conflict_save_mine),
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                enabled = !state.busy && (review == null || save != null), loading = state.busy,
                loadingText = stringResource(headingText), onClick = {
                    if (!state.busy) {
                        if (review == null) onAction(ConflictAction.Reload) else if (save != null) onAction(save)
                    }
                })
        }
    }
}

@Composable
private fun ConflictValueText(value: ConflictValues) {
    when (value) {
        is ConflictValues.Age -> Text(when {
            value.unselected -> stringResource(Res.string.conflict_unselected)
            !value.valid -> stringResource(Res.string.conflict_invalid)
            value.any -> stringResource(Res.string.age_preference_any)
            value.maximum == null -> stringResource(Res.string.age_preference_at_least, value.minimum!!)
            value.minimum == value.maximum -> stringResource(Res.string.age_preference_exact, value.minimum!!)
            else -> stringResource(Res.string.age_preference_selected, value.minimum!!, value.maximum)
        })
        is ConflictValues.Matching -> {
            Text(stringResource(Res.string.conflict_matching_value, stringResource(if (value.enabled) Res.string.conflict_on else Res.string.conflict_off)))
            Text(stringResource(Res.string.conflict_photo_value, stringResource(if (value.photoRequired) Res.string.conflict_on else Res.string.conflict_off)))
        }
    }
}
