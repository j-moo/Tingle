package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 실제 공개 프로필 조회·응답 요청·이동을 수행하지 않는 순수 화면. 시안 08: 동그란 사진 · 이름 · 항목 표 · 소개 · 매칭 이유, 아래 고정 거절 | 수락 */
@Composable
fun ProposalProfileScreen(
    state: ProposalProfileState,
    onAction: (ProposalProfileAction) -> Unit,
    modifier: Modifier = Modifier,
    error: AppError? = null,   // 불러오기 실패 화면의 종류(연결 · 서버)를 고르는 데만 쓴다
) {
    val ready = state.load == ProposalProfileLoad.Ready && state.profile != null && state.profile.photoState != ProposalPhotoState.Unavailable
    val loading = state.load == ProposalProfileLoad.Loading
    val unavailable = !loading && (state.load == ProposalProfileLoad.Unavailable || state.profile?.photoState == ProposalPhotoState.Unavailable)
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.fillMaxWidth().heightIn(min = AppSizes.TopBarMinHeight).padding(horizontal = AppSpacing.Small),
                contentAlignment = Alignment.CenterStart) {
                // 제목 없이 < 하나. 눌러도 회색 원이 생기지 않게 표시 없이, 터치 영역은 48dp
                Box(Modifier.size(48.dp).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                    onClick = { onAction(ProposalProfileAction.Back) }), contentAlignment = Alignment.Center) {
                    Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.proposal_profile_back), tint = AppColors.TextPrimary)
                }
            }
            // 당기면 프로필을 다시 받는다(받는 중 · 볼 수 없는 제안에서는 받지 않는다)
            val reload = { if (!loading && !unavailable) onAction(ProposalProfileAction.Reload) }
            PullDownRefreshBox(false, reload, Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = if (ready || loading) Arrangement.spacedBy(AppSpacing.Large) else Arrangement.Center,
                ) {
                    when {
                        loading -> ProfileSkeleton()
                        unavailable -> StateView(StateKind.Locked, stringResource(Res.string.proposal_profile_unavailable))
                        !ready -> StateView(error.stateKind(), error.stateTitle(), onRetry = reload)
                        else -> ProfileContent(state, state.profile!!, onAction)
                    }
                }
            }
            when {
                ready -> DecisionBar(state, onAction)
                // 받는 동안에도 아래 버튼 자리는 실제와 같게 둔다(누를 수 없음)
                loading -> DecisionBar(ProposalProfileState(), onAction, hint = false)
                unavailable -> AppButton(stringResource(Res.string.proposal_profile_back), Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .align(Alignment.CenterHorizontally).padding(horizontal = AppSpacing.Large).padding(top = AppSpacing.Small, bottom = AppSpacing.Large),
                    onClick = { onAction(ProposalProfileAction.Back) })
            }
        }
    }
}

/** 뼈대: 사진 · 이름 · 항목 표(항목 이름은 실제와 같게) · 소개 · 매칭 이유 자리 */
@Composable
private fun ProfileSkeleton() {
    SkeletonGroup {
        SkeletonBone(Modifier.align(Alignment.CenterHorizontally), width = 120.dp, height = 120.dp, shape = CircleShape)
        SkeletonBone(Modifier.align(Alignment.CenterHorizontally), width = 160.dp, height = 28.dp)
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            listOf(Res.string.proposal_profile_gender, Res.string.proposal_profile_smoking, Res.string.proposal_profile_drinking,
                Res.string.proposal_profile_religion).forEach { label ->
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    SkeletonBone(width = 96.dp)
                }
            }
        }
        HorizontalDivider(color = AppColors.BorderSubtle)
        repeat(2) { SkeletonBone(height = 96.dp, shape = AppShapes.Card) }
    }
}

@Composable
private fun ColumnScope.ProfileContent(state: ProposalProfileState, profile: PublicProposalProfile,
    onAction: (ProposalProfileAction) -> Unit) {
    ProposalAvatar(profile.photoState, profile.photoRequest, profile.photoImage, profile.nickname, 120.dp,
        Modifier.align(Alignment.CenterHorizontally), onRetry = { onAction(ProposalProfileAction.RetryPhoto) })
    Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(profile.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.proposal_profile_unknown_name),
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f, false).semantics { heading() })
        Text(if (profile.age != null && profile.age >= 19) stringResource(Res.string.proposal_profile_age, profile.age)
            else stringResource(Res.string.proposal_profile_unknown_age),
            style = MaterialTheme.typography.titleMedium, color = AppColors.TextSecondary)
    }
    // 왼쪽 항목 · 오른쪽 값 표
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        ProfileValue(stringResource(Res.string.proposal_profile_gender), profile.genderLabel)
        ProfileValue(stringResource(Res.string.proposal_profile_smoking), profile.smokingLabel)
        ProfileValue(stringResource(Res.string.proposal_profile_drinking), profile.drinkingLabel)
        ProfileValue(stringResource(Res.string.proposal_profile_religion), profile.religionLabel)
    }
    HorizontalDivider(color = AppColors.BorderSubtle)
    ProfileSection(stringResource(Res.string.proposal_profile_intro), AppColors.SurfaceMuted) {
        Text(profile.introduction?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.proposal_profile_no_intro),
            style = MaterialTheme.typography.bodyMedium)
    }
    ProfileSection(stringResource(Res.string.proposal_profile_reason), AppColors.SelectionContainer) {
        MatchReasonContent(state.reason, onAction)
    }
}

/** 아래 고정: 응답 전엔 거절 | 수락, 수락했으면 기다림 안내(글자만) */
@Composable
private fun DecisionBar(state: ProposalProfileState, onAction: (ProposalProfileAction) -> Unit, hint: Boolean = true) {
    Column(Modifier.fillMaxWidth()
        .drawBehind { drawLine(AppColors.BorderSubtle, Offset(0f, 0f), Offset(size.width, 0f), AppSpacing.Stroke.toPx()) }
        .padding(horizontal = AppSpacing.Section, vertical = AppSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        if (state.myDecision == MyProposalDecision.AcceptedWaiting) {
            // 바탕 상자 없이 글자만 가운데
            Column(Modifier.fillMaxWidth().padding(vertical = AppSpacing.Small), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
                Text(stringResource(Res.string.proposal_profile_waiting), style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
                Text(stringResource(Res.string.proposal_profile_waiting_help), style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary, textAlign = TextAlign.Center)
            }
        } else {
            if (!state.canRespond && hint) Text(stringResource(Res.string.proposal_profile_permission_unknown),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                AppButton(stringResource(Res.string.proposal_profile_reject), Modifier.weight(1f),
                    enabled = state.canRespond, variant = AppButtonVariant.Secondary,
                    onClick = { onAction(ProposalProfileAction.Reject) })
                AppButton(stringResource(Res.string.proposal_profile_accept), Modifier.weight(1f),
                    enabled = state.canRespond, onClick = { onAction(ProposalProfileAction.Accept) })
            }
        }
    }
}

@Composable
private fun ProfileSection(title: String, color: androidx.compose.ui.graphics.Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = AppShapes.Card, color = color) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun ProfileValue(label: String, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        Text(value?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.proposal_profile_not_public),
            style = MaterialTheme.typography.bodyMedium)
    }
}
