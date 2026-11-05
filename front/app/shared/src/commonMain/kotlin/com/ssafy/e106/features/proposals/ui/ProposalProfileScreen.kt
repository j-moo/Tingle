package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 실제 공개 프로필 조회·사진 로딩·응답 요청·이동을 수행하지 않는 순수 화면. */
@Composable
fun ProposalProfileScreen(
    state: ProposalProfileState,
    onAction: (ProposalProfileAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.proposal_profile_title),
                onBack = { onAction(ProposalProfileAction.Back) })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section),
                ) {
                    when {
                        state.load == ProposalProfileLoad.Loading -> {
                            StatusCard(stringResource(Res.string.proposal_profile_loading),
                                stringResource(Res.string.proposal_profile_loading_help))
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = AppColors.SelectionBorder)
                        }
                        state.load == ProposalProfileLoad.Unavailable || state.profile?.photoState == ProposalPhotoState.Unavailable -> {
                            StatusCard(stringResource(Res.string.proposal_profile_unavailable),
                                stringResource(Res.string.proposal_profile_unavailable_help))
                            AppButton(stringResource(Res.string.proposal_profile_back), Modifier.fillMaxWidth(),
                                onClick = { onAction(ProposalProfileAction.Back) })
                        }
                        state.load == ProposalProfileLoad.Failed || state.profile == null -> {
                            StatusCard(stringResource(Res.string.proposal_profile_failed),
                                stringResource(Res.string.proposal_profile_failed_help))
                            AppButton(stringResource(Res.string.proposal_profile_retry), Modifier.fillMaxWidth(),
                                onClick = { onAction(ProposalProfileAction.Reload) })
                        }
                        else -> ProfileContent(state, state.profile, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileContent(state: ProposalProfileState, profile: PublicProposalProfile,
    onAction: (ProposalProfileAction) -> Unit) {
    if (state.reviewRequired) StatusCard(stringResource(Res.string.proposal_review_notice),
        stringResource(Res.string.proposal_review_notice_help))
    ProposalPhoto(profile.photoState, profile.photoRequest, profile.photoImage, Modifier.fillMaxWidth(),
        onRetry = { onAction(ProposalProfileAction.RetryPhoto) })
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(profile.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.proposal_profile_unknown_name),
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(if (profile.age != null && profile.age >= 19) stringResource(Res.string.proposal_profile_age, profile.age)
            else stringResource(Res.string.proposal_profile_unknown_age), style = MaterialTheme.typography.titleMedium)
        ProfileValue(stringResource(Res.string.proposal_profile_gender), profile.genderLabel)
    }
    ProfileSection(stringResource(Res.string.proposal_profile_intro)) {
        Text(profile.introduction?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.proposal_profile_no_intro),
            style = MaterialTheme.typography.bodyMedium)
    }
    ProfileSection(stringResource(Res.string.proposal_profile_lifestyle)) {
        ProfileValue(stringResource(Res.string.proposal_profile_smoking), profile.smokingLabel)
        ProfileValue(stringResource(Res.string.proposal_profile_drinking), profile.drinkingLabel)
        ProfileValue(stringResource(Res.string.proposal_profile_religion), profile.religionLabel)
    }
    ProfileSection(stringResource(Res.string.proposal_profile_reason)) {
        MatchReasonContent(state.reason, onAction)
    }
    Text(state.deadlineLabel?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.proposal_profile_deadline, it) }
        ?: stringResource(Res.string.proposal_profile_deadline_unknown),
        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    if (state.myDecision == MyProposalDecision.AcceptedWaiting) {
        StatusCard(stringResource(Res.string.proposal_profile_waiting),
            stringResource(Res.string.proposal_profile_waiting_help))
    } else {
        Text(stringResource(Res.string.proposal_profile_decision_help),
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        if (!state.canRespond) Text(stringResource(Res.string.proposal_profile_permission_unknown),
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        AppButton(stringResource(Res.string.proposal_profile_accept), Modifier.fillMaxWidth(),
            enabled = state.canRespond, onClick = { onAction(ProposalProfileAction.Accept) })
        AppButton(stringResource(Res.string.proposal_profile_reject), Modifier.fillMaxWidth(),
            enabled = state.canRespond, variant = AppButtonVariant.Secondary,
            onClick = { onAction(ProposalProfileAction.Reject) })
    }
}

@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun ProfileValue(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary)
        Text(value?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.proposal_profile_not_public),
            style = MaterialTheme.typography.bodyMedium)
    }
}
