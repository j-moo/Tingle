package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppButtonVariant
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.components.InlineError
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** 본인만 보는 등록 전 확인 화면. 저장·화면 이동은 호출부에서 연결한다. */
@Composable
fun ProfileConfirmationScreen(
    state: ProfileConfirmationState,
    onEditBasics: () -> Unit,
    onEditLifestyle: () -> Unit,
    onEditDetails: () -> Unit,
    onRegister: () -> Unit,
    photoContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.profile_confirmation_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Text(stringResource(Res.string.profile_confirmation_heading),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                        SummaryHelp(Res.string.profile_confirmation_intro)
                    }
                    SummarySection(Res.string.profile_confirmation_basics,
                        Res.string.profile_confirmation_edit_basics, !state.registering, onEditBasics) {
                        SummaryValue(Res.string.profile_nickname, state.nickname)
                        SummaryValue(Res.string.profile_birth_date, state.birthDateText)
                        SummaryHelp(Res.string.profile_confirmation_birth_help)
                        SummaryValue(Res.string.profile_confirmation_gender,
                            stringResource(if (state.gender == ProfileGender.Male) Res.string.profile_male else Res.string.profile_female))
                    }
                    SummarySection(Res.string.profile_confirmation_lifestyle,
                        Res.string.profile_confirmation_edit_lifestyle, !state.registering, onEditLifestyle) {
                        SummaryValue(Res.string.profile_confirmation_smoking, stringResource(state.smoking.label()))
                        SummaryValue(Res.string.profile_confirmation_drinking, stringResource(state.drinking.label()))
                    }
                    SummarySection(Res.string.profile_confirmation_details,
                        Res.string.profile_confirmation_edit_details, !state.registering, onEditDetails) {
                        Box(Modifier.size(144.dp).clip(AppShapes.Card).background(AppColors.SurfaceMuted),
                            contentAlignment = Alignment.Center) {
                            if (state.hasPhoto) photoContent()
                            else Text(stringResource(Res.string.profile_confirmation_no_photo),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                                modifier = Modifier.padding(AppSpacing.Large))
                        }
                        SummaryValue(Res.string.profile_confirmation_religion,
                            stringResource(if (state.religion == null || state.religion == ProfileReligion.Private)
                                Res.string.profile_confirmation_private else state.religion.label()))
                        SummaryValue(Res.string.profile_confirmation_introduction,
                            state.introduction.ifBlank { stringResource(Res.string.profile_confirmation_no_intro) })
                        SummaryHelp(Res.string.profile_details_public)
                    }
                    Column(Modifier.fillMaxWidth().background(AppColors.SelectionContainer, AppShapes.Card)
                        .padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Text(stringResource(Res.string.profile_notice_title),
                            style = MaterialTheme.typography.titleMedium, color = AppColors.OnSelection,
                            modifier = Modifier.semantics { heading() })
                        Text(stringResource(Res.string.profile_notice_body),
                            style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSelection)
                    }
                    SummaryHelp(Res.string.profile_confirmation_next)
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                    // 등록 실패: 확인한 내용은 그대로 두고 버튼 바로 위에 한 줄로 알린다. 버튼은 "다시 시도"가 된다
                    val failure = (state.registrationError ?: error).takeIf { !state.registering }
                    failure?.let { InlineError(it) }
                    AppButton(text = stringResource(if (failure != null) Res.string.state_retry else Res.string.profile_confirmation_submit),
                        modifier = Modifier.fillMaxWidth(), enabled = state.canRegister && !state.registering,
                        loading = state.registering, loadingText = stringResource(Res.string.profile_confirmation_registering),
                        onClick = onRegister)
                }
            }
        }
    }
}

@Composable
private fun SummarySection(title: StringResource, editLabel: StringResource, editable: Boolean,
    onEdit: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glass(AppShapes.Card, AppColors.SurfaceMuted).padding(AppSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        content()
        AppButton(stringResource(editLabel), variant = AppButtonVariant.Secondary,
            enabled = editable, onClick = onEdit)
    }
}

@Composable
private fun SummaryValue(label: StringResource, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
    }
}

@Composable
private fun SummaryHelp(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
}
