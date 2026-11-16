package com.ssafy.e106.features.survey.ui

import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
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

/**
 * 문항 화면. 진행 표시는 위에, 문항과 선택지는 화면 가운데에 두어 한 문항이 스크롤 없이 보이게 한다.
 * 답을 고르면 선택지 바로 아래의 "다음"이 켜진다(저장은 뒤에서 하고, 못 보낸 답 수만 위에 작게 알린다). 그 아래에 밑줄 글자 "이전 문항"이 있다.
 */
@Composable
fun SurveyQuestionScreen(
    state: SurveyQuestionState,
    onAnswerChange: (SurveyAnswer) -> Unit,
    onPrevious: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    notice: String? = null,
    pendingCount: Int = 0,
    /** 답 고치기 중에만 준다: 남은 문항을 넘기지 않고 제출 단계로 가는 "수정 마치기" */
    onFinish: (() -> Unit)? = null,
) {
    val locked = busy
    SurveyFrame(modifier, scrollKey = state.questionId, centered = true, header = {
        Column(Modifier.fillMaxWidth().padding(horizontal = screenGutter()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.survey_progress, state.ordinal, state.total), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = AppColors.OnSelection)
                // 못 보낸 답은 뒤에서 계속 다시 보낸다. 화면을 막지 않고 개수만 알린다
                if (pendingCount > 0) Text(stringResource(Res.string.survey_pending, pendingCount), style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            LinearProgressIndicator(
                progress = { (state.ordinal.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = AppColors.SelectionBorder, trackColor = AppColors.SelectionContainer,
            )
        }
    }, footer = {
        // 아래에 고정한 버튼은 없다("다음" · "이전 문항" 모두 손이 닿기 쉽게 선택지 아래에 있다)
    }) {
        notice?.let { Text(it, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Text(state.text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() })
        val chosen = (state.answer as? SurveyAnswer.Choices)?.keys.orEmpty()
        val select: (String) -> Unit = { onAnswerChange(SurveyAnswer.Choices(setOf(it))) }
        // 유형별 화면(11-09 시안 ①E · ②B · ③A · ④C)
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
        // "다음"은 선택지("상관없어요") 아래: 답을 고르면 켜진다. 특수 답 버튼과 붙어 보이지 않게 사이를 띄운다
        AppButton(stringResource(if (state.ordinal == state.total) Res.string.survey_review_submit else Res.string.survey_next),
            Modifier.fillMaxWidth().padding(top = AppSpacing.Section), enabled = state.canContinue && !locked, onClick = onContinue)
        // "이전 문항"은 "다음" 아래에 테두리 없이 밑줄 글자로
        if (state.ordinal > 1) Box(Modifier.clip(AppShapes.Small).clickable(enabled = !locked, role = Role.Button, onClick = onPrevious)
            .heightIn(min = AppSizes.TouchTarget).padding(horizontal = AppSpacing.Large), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.survey_previous), style = MaterialTheme.typography.labelLarge, textDecoration = TextDecoration.Underline,
                color = if (locked) AppColors.TextDisabled else AppColors.TextPrimary)
        }
        // 답 고치기: 고칠 문항만 고친 뒤 끝까지 넘기지 않고 제출 단계로
        if (onFinish != null) Box(Modifier.clip(AppShapes.Small).clickable(enabled = !locked, role = Role.Button, onClick = onFinish)
            .heightIn(min = AppSizes.TouchTarget).padding(horizontal = AppSpacing.Large), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.survey_edit_finish), style = MaterialTheme.typography.labelLarge, textDecoration = TextDecoration.Underline,
                color = if (locked) AppColors.TextDisabled else AppColors.TextPrimary)
        }
    }
}

@Composable
internal fun SurveyFrame(
    modifier: Modifier = Modifier,
    scrollKey: Any? = null,
    onBack: (() -> Unit)? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,   // 주면 당겨서 새로고침이 붙는다(다시 불러올 수 있는 단계만)
    centered: Boolean = false,   // 문구 · 요소를 화면 가운데에(가로 · 세로). 길어지면 위에서부터 스크롤
    header: (@Composable () -> Unit)? = null,   // 위쪽 줄 바로 아래에 고정하는 줄(문항 화면의 진행 표시)
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 같은 문항의 회전은 위치를 복원하고 다른 문항으로 이동하면 맨 위에서 시작한다.
    val scroll = key(scrollKey) { rememberScrollState() }
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
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
            header?.invoke()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val height = maxHeight
                val body: @Composable BoxScope.() -> Unit = {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                        .verticalScroll(scroll).then(if (centered) Modifier.heightIn(min = height) else Modifier).padding(screenPaddingValues()),
                        verticalArrangement = if (centered) Arrangement.spacedBy(AppSpacing.Large, Alignment.CenterVertically) else Arrangement.spacedBy(AppSpacing.Large),
                        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start, content = content)
                }
                if (onRefresh != null) PullDownRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize(), content = body) else body(this)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large), content = { footer() })
            }
        }
    }
}
