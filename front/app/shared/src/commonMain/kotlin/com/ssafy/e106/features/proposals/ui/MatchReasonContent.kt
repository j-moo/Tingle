package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppButtonVariant
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 매칭 이유 영역을 독립적으로 확인할 수 있는 화면. 화면 이동은 연결부에서 담당한다. */
@Composable
fun MatchReasonScreen(state: MatchReasonState, onAction: (ProposalProfileAction) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(AppSpacing.Section),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
            Text(stringResource(Res.string.proposal_profile_reason), style = MaterialTheme.typography.titleLarge)
            Surface(shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
                Box(Modifier.fillMaxWidth().padding(AppSpacing.Large)) { MatchReasonContent(state, onAction) }
            }
        }
    }
}

/** 조회/재생성 동작을 구분한다. 설명 실패가 제안 응답 권한을 바꾸지 않는다. */
@Composable
fun MatchReasonContent(state: MatchReasonState, onAction: (ProposalProfileAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Text(state.visibleText ?: stringResource(when {
            !state.accessible -> Res.string.proposal_reason_unavailable
            state.busy -> Res.string.proposal_reason_busy
            state.status == MatchReasonStatus.Pending -> Res.string.proposal_profile_reason_pending
            state.status == MatchReasonStatus.Failed -> Res.string.proposal_profile_reason_failed
            else -> Res.string.proposal_reason_unknown
        }), style = MaterialTheme.typography.bodyMedium)
        if (state.visibleText != null) Text(stringResource(Res.string.proposal_reason_rule),
            style = MaterialTheme.typography.bodySmall)
        if (state.status == MatchReasonStatus.Failed && state.accessible && !state.retryable)
            Text(stringResource(Res.string.proposal_reason_no_retry), style = MaterialTheme.typography.bodySmall)
        if (state.canRetry) AppButton(stringResource(Res.string.proposal_reason_retry), Modifier.fillMaxWidth(),
            variant = AppButtonVariant.Secondary, onClick = { if (state.canRetry) onAction(ProposalProfileAction.RetryReason) })
        if (state.canCheck) AppButton(stringResource(Res.string.proposal_reason_check), Modifier.fillMaxWidth(),
            variant = AppButtonVariant.Secondary, onClick = { if (state.canCheck) onAction(ProposalProfileAction.CheckReason) })
    }
}
