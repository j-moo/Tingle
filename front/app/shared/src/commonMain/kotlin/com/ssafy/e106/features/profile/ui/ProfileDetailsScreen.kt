package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import com.ssafy.e106.features.profile.data.OptionalConsentType
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** 상태와 이벤트만 받는 UI. 사진 선택기·자르기·동의 저장·업로드는 호출부에서 연결한다. */
@Composable
fun ProfileDetailsScreen(
    state: ProfileDetailsState,
    onReligionChange: (ProfileReligion) -> Unit,
    onReligionConsentChange: (Boolean) -> Unit,
    onIntroductionChange: (String) -> Unit,
    onIntroductionConsentChange: (Boolean) -> Unit,
    onPhotoConsentChange: (Boolean) -> Unit,
    onChoosePhoto: () -> Unit,
    onDeletePhoto: () -> Unit,
    onContinue: () -> Unit,
    photoContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    consentInfo: @Composable (OptionalConsentType) -> Unit = {},
    canChangeConsent: (OptionalConsentType) -> Boolean = { true },
    footer: @Composable () -> Unit = {},
    continueEnabled: Boolean = state.canContinue,
    continueLabel: String? = null,
) {
    val introductionIntoView = remember { BringIntoViewRequester() }
    var introductionFocused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // 여러 줄 입력은 AppTextField의 자동 스크롤을 사용하지 않는다. 키보드로 창이 줄어들면
    // 작성 중인 입력칸을 다시 보여 준다. IME 표시 전에는 기본 포커스 이동에 맡긴다.
    LaunchedEffect(introductionFocused, state.editable, state.introductionConsent, density) {
        if (introductionFocused && state.editable && state.introductionConsent) {
            snapshotFlow { ime.getBottom(density) }.collect { bottom ->
                if (bottom > 0) introductionIntoView.bringIntoView()
            }
        }
    }
    val introductionError = state.introductionError ?: when {
        state.introductionTooLong -> stringResource(Res.string.profile_intro_too_long)
        state.introductionTooManyLines -> stringResource(Res.string.profile_intro_too_many_lines)
        else -> null
    }
    // 저장 동작은 동일하게 유지하고 배치만 바꾼다. 키보드로 짧아진 창에서는 본문 끝까지 스크롤한다.
    val submit: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AppButton(continueLabel ?: stringResource(Res.string.profile_continue),
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                enabled = continueEnabled, loading = state.saving,
                loadingText = stringResource(Res.string.profile_saving), onClick = onContinue)
        }
    }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val compactHeight = maxHeight < 320.dp
            Column(Modifier.fillMaxSize()) {
                if (!compactHeight) AppTopBar(stringResource(Res.string.profile_details_title), onBack = onBack)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Section),
                    ) {
                        // 입력 필드를 다른 조건 분기로 옮기지 않아 IME 개폐 중 포커스와 입력 상태를 보존한다.
                        if (compactHeight) AppTopBar(stringResource(Res.string.profile_details_title), onBack = onBack)
                        header()
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            Text(stringResource(Res.string.profile_details_heading),
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.semantics { heading() })
                            DetailHelp(Res.string.profile_details_intro)
                            DetailHelp(Res.string.profile_details_public)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            DetailTitle(Res.string.profile_photo_title)
                            Box(Modifier.size(144.dp).align(Alignment.CenterHorizontally).clip(AppShapes.Card),
                                contentAlignment = Alignment.Center) {
                                if (state.hasPhoto) photoContent()
                                else ProfilePlaceholder(stringResource(Res.string.profile_photo_empty))
                            }
                            AppCheckbox(state.photoConsent,
                                onCheckedChange = { if (state.editable) onPhotoConsentChange(it) },
                                label = stringResource(Res.string.profile_photo_consent), enabled = state.editable && canChangeConsent(OptionalConsentType.PROFILE_PHOTO))
                            consentInfo(OptionalConsentType.PROFILE_PHOTO)
                            AppButton(
                                stringResource(if (state.hasPhoto) Res.string.profile_photo_replace else Res.string.profile_photo_add),
                                modifier = Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                                enabled = state.editable && state.photoConsent,
                                loading = state.photoBusy,
                                loadingText = stringResource(Res.string.profile_photo_uploading),
                                onClick = onChoosePhoto,
                            )
                            if (state.hasPhoto) AppButton(
                                stringResource(Res.string.profile_photo_delete),
                                modifier = Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                                enabled = state.editable, onClick = onDeletePhoto,
                            )
                            if (state.photoStatus == ProfilePhotoStatus.Replacing) DetailHelp(Res.string.profile_photo_replacing)
                            state.photoError?.let { DetailError(it) }
    //                        DetailHelp(Res.string.profile_photo_rule)
                            DetailHelp(Res.string.profile_photo_matching)
                        }
                        HorizontalDivider(color = AppColors.BorderSubtle)
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            DetailTitle(Res.string.profile_intro_title)
                            AppCheckbox(state.introductionConsent,
                                onCheckedChange = { if (state.editable) onIntroductionConsentChange(it) },
                                label = stringResource(Res.string.profile_intro_consent), enabled = state.editable && canChangeConsent(OptionalConsentType.PROFILE_INTRODUCTION))
                            consentInfo(OptionalConsentType.PROFILE_INTRODUCTION)
                            // AppTextField는 한 줄 전용이므로 이 화면의 여러 줄 입력과 스크롤 복구를 연결한다.
                            OutlinedTextField(
                                value = state.introduction,
                                onValueChange = { if (state.editable && state.introductionConsent) onIntroductionChange(it) },
                                enabled = state.editable && state.introductionConsent,
                                modifier = Modifier.fillMaxWidth()
                                    .bringIntoViewRequester(introductionIntoView)
                                    .onFocusChanged { introductionFocused = it.isFocused },
                                label = { Text(stringResource(Res.string.profile_intro_title)) },
                                placeholder = { Text(stringResource(Res.string.profile_intro_placeholder)) },
                                minLines = 4, maxLines = 6, shape = AppShapes.Control,
                                isError = introductionError != null,
                                textStyle = MaterialTheme.typography.bodyLarge,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppColors.TextPrimary, unfocusedBorderColor = AppColors.BorderControl,
                                    disabledContainerColor = AppColors.SurfaceMuted, errorBorderColor = AppColors.Error,
                                ),
                            )
                            Text(stringResource(Res.string.profile_intro_count, state.introductionLength),
                                modifier = Modifier.align(Alignment.End), style = MaterialTheme.typography.bodySmall,
                                color = if (state.introductionLength > 200) AppColors.Error else AppColors.TextSecondary)
                            introductionError?.let { DetailError(it) }
                            DetailHelp(Res.string.profile_intro_rule)
                        }
                        HorizontalDivider(color = AppColors.BorderSubtle)
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            DetailTitle(Res.string.profile_religion_title)
                            DetailHelp(Res.string.profile_religion_private_help)
                            AppCheckbox(state.religionConsent,
                                onCheckedChange = { if (state.editable) onReligionConsentChange(it) },
                                label = stringResource(Res.string.profile_religion_consent), enabled = state.editable && canChangeConsent(OptionalConsentType.PROFILE_RELIGION))
                            consentInfo(OptionalConsentType.PROFILE_RELIGION)
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                                ProfileReligion.entries.forEach { option ->
                                    val selected = state.religion == option
                                    AppRadio(selected = selected, onSelect = { onReligionChange(option) },
                                        label = stringResource(option.label()),
                                        enabled = state.editable && state.religionConsent,
                                        modifier = Modifier.background(
                                            if (selected) AppColors.SelectionContainer else AppColors.SurfaceMuted, AppShapes.Control))
                                }
                            }
                        }
                        footer()
                        if (compactHeight) submit()
                    }
                }
                if (!compactHeight) submit()
            }
        }
    }
}

@Composable private fun DetailTitle(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
}
@Composable private fun DetailHelp(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
}
@Composable private fun DetailError(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.Error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
internal fun ProfileReligion.label() = when (this) {
    ProfileReligion.None -> Res.string.profile_religion_none
    ProfileReligion.Protestant -> Res.string.profile_religion_protestant
    ProfileReligion.Catholic -> Res.string.profile_religion_catholic
    ProfileReligion.Buddhist -> Res.string.profile_religion_buddhist
    ProfileReligion.Other -> Res.string.profile_religion_other
    ProfileReligion.Private -> Res.string.profile_religion_private
}

