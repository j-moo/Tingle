package com.ssafy.e106.features.survey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** Stateless question UI. Preview callbacks do not save answers or navigate. */
@Composable
fun SurveyQuestionScreen(
    state: SurveyQuestionState,
    onAnswerChange: (SurveyAnswer) -> Unit,
    onPrevious: () -> Unit,
    onContinue: () -> Unit,
    onRetrySave: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckVersion: (() -> Unit)? = null,
    onPreviousSession: (() -> Unit)? = null,
    busy: Boolean = false,
    notice: String? = null,
) {
    val saving = state.saveState == SurveySaveState.Saving
    val locked = saving || busy
    SurveyFrame(modifier, scrollKey = state.questionId, footer = {
        AppButton(
            text = stringResource(if (state.ordinal == state.total) Res.string.survey_review_submit else Res.string.survey_next),
            modifier = Modifier.fillMaxWidth(), enabled = state.canContinue && !locked,
            loading = saving, loadingText = stringResource(Res.string.survey_saving), onClick = onContinue,
        )
    }) {
        notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Text(stringResource(Res.string.survey_progress, state.ordinal, state.total),
            style = MaterialTheme.typography.labelLarge, color = AppColors.OnSelection)
        LinearProgressIndicator(
            progress = { (state.ordinal.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(), color = AppColors.SelectionBorder,
            trackColor = AppColors.SelectionContainer,
        )
        Text(state.text, style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(if (state.maxChoices == 1) Res.string.survey_choose_one else Res.string.survey_choose_many, state.maxChoices),
            style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        val chosen = (state.answer as? SurveyAnswer.Choices)?.keys.orEmpty()
        Column(
            if (state.maxChoices == 1) Modifier.selectableGroup() else Modifier,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            state.options.forEach { option ->
                val selected = option.key in chosen
                val optionModifier = Modifier.background(
                    if (selected) AppColors.SelectionContainer else AppColors.SurfaceMuted, AppShapes.Control,
                )
                if (state.maxChoices == 1) {
                    AppRadio(selected, { onAnswerChange(SurveyAnswer.Choices(setOf(option.key))) },
                        option.label, optionModifier, enabled = !locked)
                } else {
                    AppCheckbox(selected, { checked ->
                        onAnswerChange(SurveyAnswer.Choices(if (checked) chosen + option.key else chosen - option.key))
                    }, option.label, optionModifier,
                        enabled = !locked && (selected || chosen.size < state.maxChoices))
                }
            }
            if (state.allowUnknown) SpecialAnswer(
                stringResource(Res.string.survey_unknown), state.answer == SurveyAnswer.Unknown, !locked,
            ) { onAnswerChange(SurveyAnswer.Unknown) }
            if (state.allowAny) SpecialAnswer(
                stringResource(Res.string.survey_any), state.answer == SurveyAnswer.Any, !locked,
            ) { onAnswerChange(SurveyAnswer.Any) }
        }
        if (state.allowAny) Text(stringResource(Res.string.survey_special_help),
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        Text(stringResource(when (state.saveState) {
            SurveySaveState.Unanswered -> Res.string.survey_autosave
            SurveySaveState.Saving -> Res.string.survey_saving
            SurveySaveState.Saved -> Res.string.survey_saved
            SurveySaveState.Failed -> Res.string.survey_save_failed
        }), color = if (state.saveState == SurveySaveState.Failed) AppColors.Error else AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.saveState == SurveySaveState.Failed) AppButton(
            stringResource(Res.string.survey_retry_save), variant = AppButtonVariant.Secondary,
            onClick = onRetrySave, enabled = !locked, modifier = Modifier.fillMaxWidth(),
        )
        onCheckVersion?.let { AppButton(stringResource(Res.string.survey_change_version), variant = AppButtonVariant.Secondary, enabled = !locked, onClick = it) }
        onPreviousSession?.let { AppButton(stringResource(Res.string.survey_change_previous), variant = AppButtonVariant.Secondary, enabled = !locked, onClick = it) }
        if (state.ordinal > 1) AppButton(stringResource(Res.string.survey_previous),
            variant = AppButtonVariant.Secondary, enabled = !locked, onClick = onPrevious)
    }
}

@Composable
private fun SpecialAnswer(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    AppRadio(selected, onSelect, label,
        Modifier.background(if (selected) AppColors.SelectionContainer else AppColors.SurfaceMuted, AppShapes.Control), enabled)
}

@Composable
internal fun SurveyFrame(
    modifier: Modifier = Modifier,
    scrollKey: Any? = null,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 같은 문항의 회전은 위치를 복원하고 다른 문항으로 이동하면 맨 위에서 시작한다.
    val scroll = key(scrollKey) { rememberScrollState() }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.survey_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(scroll).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large), content = content)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large), content = { footer() })
            }
        }
    }
}
