package com.ssafy.e106.features.survey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.painterResource
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
        if (state.maxChoices == 1) Text(stringResource(Res.string.survey_choose_one),
            style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        val chosen = (state.answer as? SurveyAnswer.Choices)?.keys.orEmpty()
        val select: (String) -> Unit = { onAnswerChange(SurveyAnswer.Choices(setOf(it))) }
        // 유형별 화면(11-09 시안 ①E · ②B · ③A · ④C). 저장 · 다음 조건은 그대로
        when (state.answerStyle()) {
            SurveyAnswerStyle.Scale -> ScaleAnswer(state.options, chosen, !locked, ordinal = false, onSelect = select)
            SurveyAnswerStyle.Ordinal -> ScaleAnswer(state.options, chosen, !locked, ordinal = true, onSelect = select)
            SurveyAnswerStyle.Level -> LevelSlider(state.options, chosen, !locked, select)
            SurveyAnswerStyle.Single -> SingleTiles(state.options, chosen, !locked, select)
            SurveyAnswerStyle.Multi -> MultiChips(state.options, chosen, state.maxChoices, !locked) { key, on ->
                onAnswerChange(SurveyAnswer.Choices(if (on) chosen + key else chosen - key))
            }
        }
        SpecialAnswers(state, !locked, onAnswerChange)
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
internal fun SurveyFrame(
    modifier: Modifier = Modifier,
    scrollKey: Any? = null,
    onBack: (() -> Unit)? = null,
    centered: Boolean = false,   // 문항 밖 화면(시안 01): 문구 · 요소를 화면 가운데에(가로 · 세로). 길어지면 위에서부터 스크롤
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 같은 문항의 회전은 위치를 복원하고 다른 문항으로 이동하면 맨 위에서 시작한다.
    val scroll = key(scrollKey) { rememberScrollState() }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // 문항 밖 화면(시안 01)은 제목 없이 왼쪽 위 < 하나로 나간다. 문항 화면은 지금처럼 제목만
            if (onBack != null) Box(Modifier.fillMaxWidth().heightIn(min = AppSizes.TopBarMinHeight).padding(horizontal = AppSpacing.Small),
                contentAlignment = Alignment.CenterStart) {
                // 눌러도 회색 원(물결 · 배경)이 생기지 않게 표시 없이 누르기만. 터치 영역은 48dp
                Box(Modifier.size(48.dp).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.survey_api_back), tint = AppColors.TextPrimary)
                }
            } else AppTopBar(stringResource(Res.string.survey_title))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(scroll).then(if (centered) Modifier.heightIn(min = maxHeight) else Modifier).padding(screenPaddingValues()),
                    verticalArrangement = if (centered) Arrangement.spacedBy(AppSpacing.Large, Alignment.CenterVertically) else Arrangement.spacedBy(AppSpacing.Large),
                    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start, content = content)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large), content = { footer() })
            }
        }
    }
}
