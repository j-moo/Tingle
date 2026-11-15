package com.ssafy.e106.features.survey.ui

import com.ssafy.e106.core.ui.theme.AppSizes
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 문항 유형(11-09 시안): 척도 ①E · 문장형 5단계 ①E+문장 · 3단계 ④C · 하나 고르기 ②B · 여러 개 ③A */
internal enum class SurveyAnswerStyle { Scale, Ordinal, Level, Single, Multi }

internal val STANDARD_SCALE = listOf("전혀 그렇지 않다", "그렇지 않다", "보통이다", "그렇다", "매우 그렇다")
private val FIVE_KEYS = (1..5).map { "$it" }
private val LEVEL_KEYS = listOf("low", "mid", "high")

/** 질문지 보기 값 · 문구로 화면 모양을 고른다. 질문지에 유형 필드가 없어 값 모양으로 판별한다 */
internal fun SurveyQuestionState.answerStyle(): SurveyAnswerStyle {
    val keys = options.map { it.key }
    return when {
        maxChoices > 1 -> SurveyAnswerStyle.Multi
        keys == LEVEL_KEYS -> SurveyAnswerStyle.Level
        keys == FIVE_KEYS && options.map { it.label } == STANDARD_SCALE -> SurveyAnswerStyle.Scale
        keys == FIVE_KEYS -> SurveyAnswerStyle.Ordinal
        else -> SurveyAnswerStyle.Single
    }
}

// ①E 테 색: 왼쪽(아니다) 연한 회색 → 오른쪽(그렇다) 검정으로 다섯 단계 그라데이션. 색(빨강 · 초록) 대신 명암만 쓴다
private val ScaleLight = Color(0xFFC4C4C4)
private val ScaleDark = Color(0xFF171717)
private fun scaleTint(i: Int, count: Int) = lerp(ScaleLight, ScaleDark, if (count <= 1) 1f else i / (count - 1f))

/** ①E 아니다(회색) ↔ 그렇다(검정) 원 다섯 개, 테 색이 명암 그라데이션 · 가운데로 갈수록 작게. [ordinal] 이면 양 끝에 실제 보기 문장, 아래에 고른 단계 문장 */
@Composable
internal fun ScaleAnswer(options: List<SurveyOption>, chosen: Set<String>, enabled: Boolean, ordinal: Boolean, onSelect: (String) -> Unit) {
    val sizes = listOf(44.dp, 36.dp, 28.dp, 36.dp, 44.dp)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, option ->
                val tint = scaleTint(i, options.size)
                val on = option.key in chosen
                Box(Modifier.size(52.dp).selectable(on, enabled, Role.RadioButton) { onSelect(option.key) }
                    .semantics { contentDescription = option.label }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(sizes.getOrElse(i) { 36.dp }).border(2.dp, tint, CircleShape)
                        .background(if (on) tint else AppColors.Background, CircleShape))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
            Text(if (ordinal) options.first().label else stringResource(Res.string.survey_scale_no), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
            Text(if (ordinal) options.last().label else stringResource(Res.string.survey_scale_yes), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, textAlign = TextAlign.End)
        }
        options.firstOrNull { it.key in chosen }?.let {
            Text(it.label, Modifier.fillMaxWidth().padding(top = AppSpacing.Small), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        }
    }
}

/** ②B 2열 타일(A~E 번호표). 홀수면 마지막 타일은 한 줄 전체 */
@Composable
internal fun SingleTiles(options: List<SurveyOption>, chosen: Set<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        options.withIndex().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                row.forEach { (i, option) ->
                    val on = option.key in chosen
                    Column(Modifier.weight(1f).fillMaxHeight().clipTile(on)
                        .selectable(on, enabled, Role.RadioButton) { onSelect(option.key) }.padding(AppSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Text(('A' + i).toString(), Modifier.background(if (on) AppColors.SelectionBorder else AppColors.SurfaceMuted, RoundedCornerShape(7.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = if (on) AppColors.OnPrimary else AppColors.TextSecondary)
                        // 고르면 굵기를 바꾸지 않는다(글자 폭이 넓어져 줄이 바뀐다). 배경 · 테두리 · 글자색으로만 표시
                        Text(option.label, style = MaterialTheme.typography.bodyMedium,
                            color = if (on) AppColors.OnSelection else AppColors.TextPrimary)
                    }
                }
            }
        }
    }
}

/** ③A 알약 칩 + 고른 개수. 최대 개수를 채우면 고르지 않은 칩은 막는다 */
@Composable
internal fun MultiChips(options: List<SurveyOption>, chosen: Set<String>, maxChoices: Int, enabled: Boolean, onToggle: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(stringResource(Res.string.survey_multi_count, chosen.size, maxChoices), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold, color = AppColors.OnSelection)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            options.forEach { option ->
                val on = option.key in chosen
                val canToggle = enabled && (on || chosen.size < maxChoices)
                // 고르면 굵기 · 체크 글자를 더하지 않는다(칩이 넓어져 다음 줄로 밀린다). 배경 · 테두리 · 글자색으로만 표시
                Text(option.label,
                    Modifier.heightIn(min = 44.dp).clipChip(on, canToggle).toggleable(on, canToggle, Role.Checkbox) { onToggle(option.key, it) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when { on -> AppColors.OnSelection; canToggle -> AppColors.TextPrimary; else -> AppColors.TextDisabled })
            }
        }
    }
}

/** ④C 3단계 슬라이더(낮음 · 보통 · 높음). 칸을 누르거나 끌어서 고른다. 각 칸은 화면 읽기용 선택지로도 노출 */
@Composable
internal fun LevelSlider(options: List<SurveyOption>, chosen: Set<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    val names = listOf(stringResource(Res.string.survey_level_low), stringResource(Res.string.survey_level_mid), stringResource(Res.string.survey_level_high))
    val index = options.indexOfFirst { it.key in chosen }
    val select by rememberUpdatedState(onSelect)   // pointerInput 은 키가 바뀔 때만 다시 시작하므로 최신 콜백을 읽게 한다
    val line = AppColors.BorderSubtle
    val fill = AppColors.SelectionBorder
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Row(Modifier.fillMaxWidth()) {                                                    // 말풍선(고른 칸 위)
            names.forEachIndexed { i, name ->
                Box(Modifier.weight(1f), contentAlignment = stopAlign(i)) {
                    if (i == index) Text(name, Modifier.background(fill, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AppColors.OnPrimary)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).pointerInput(enabled, options) {
            if (!enabled) return@pointerInput
            fun pick(x: Float) = select(options[(x / size.width * 2 + 0.5f).toInt().coerceIn(0, 2)].key)
            detectTapGestures { pick(it.x) }
        }.pointerInput(enabled, options) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures { change, _ -> select(options[(change.position.x / size.width * 2 + 0.5f).toInt().coerceIn(0, 2)].key) }
        }) {
            val r = 12.dp
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2; val left = r.toPx(); val right = size.width - r.toPx()
                val at = { i: Int -> left + (right - left) * i / 2f }
                drawLine(line, Offset(left, y), Offset(right, y), strokeWidth = 4.dp.toPx())
                if (index >= 0) drawLine(fill, Offset(left, y), Offset(at(index), y), strokeWidth = 4.dp.toPx())
                repeat(3) { drawCircle(if (it <= index) fill else line, 5.dp.toPx(), Offset(at(it), y)) }
                if (index >= 0) {
                    drawCircle(Color.White, r.toPx(), Offset(at(index), y))
                    drawCircle(fill, r.toPx(), Offset(at(index), y), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxSize().selectableGroup()) {                               // 화면 읽기 · 키보드용 칸(보이지 않음)
                names.forEachIndexed { i, name ->
                    Box(Modifier.weight(1f).fillMaxHeight().selectable(i == index, enabled, Role.RadioButton) { onSelect(options[i].key) }
                        .semantics { contentDescription = "$name · ${options[i].label}" })
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            names.forEachIndexed { i, name ->
                Text(name, Modifier.weight(1f), textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End },
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
    }
}

/** 상관없어요 — 보기와 섞지 않고 아래에 알약 모양 버튼으로. "모르겠어요"는 버튼을 두지 않는다 */
@Composable
internal fun SpecialAnswers(state: SurveyQuestionState, enabled: Boolean, onAnswerChange: (SurveyAnswer) -> Unit) {
    if (!state.allowAny) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small, Alignment.CenterHorizontally)) {
        if (state.allowAny) SpecialButton(stringResource(Res.string.survey_any), state.answer == SurveyAnswer.Any, enabled) { onAnswerChange(SurveyAnswer.Any) }
    }
}

@Composable
private fun SpecialButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // 고르면 검정으로 채운다. 터치 영역은 48dp
    Box(Modifier.heightIn(min = AppSizes.TouchTarget).clip(RoundedCornerShape(50))
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) AppColors.OnPrimary else AppColors.TextPrimary,
            modifier = Modifier.background(if (selected) AppColors.ActionPrimary else AppColors.Background, RoundedCornerShape(50))
                .border(1.dp, if (selected) AppColors.ActionPrimary else AppColors.BorderControl, RoundedCornerShape(50))
                .padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Small))
    }
}

private fun stopAlign(i: Int) = when (i) { 0 -> Alignment.CenterStart; 1 -> Alignment.Center; else -> Alignment.CenterEnd }

private fun Modifier.clipTile(on: Boolean) = this
    .background(if (on) AppColors.SelectionContainer else AppColors.Background, RoundedCornerShape(14.dp))
    .border(1.5.dp, if (on) AppColors.SelectionBorder else AppColors.BorderSubtle, RoundedCornerShape(14.dp))

private fun Modifier.clipChip(on: Boolean, enabled: Boolean) = this
    .background(if (on) AppColors.SelectionContainer else AppColors.Background, RoundedCornerShape(50))
    .border(1.5.dp, when { on -> AppColors.SelectionBorder; enabled -> AppColors.BorderSubtle; else -> AppColors.ActionDisabled }, RoundedCornerShape(50))

