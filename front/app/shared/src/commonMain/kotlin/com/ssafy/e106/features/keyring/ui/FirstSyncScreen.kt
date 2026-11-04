package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** 타이머/임의 진행률/자동 성공 전환 없이 확인된 상태만 표시한다. 실제 작업은 호출부가 담당한다. */
@Composable
fun FirstSyncScreen(state: FirstSyncState, onAction: (FirstSyncAction) -> Unit, modifier: Modifier = Modifier) {
    val copy = syncCopy(state.step)
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.first_sync_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)) {
                    Text(stringResource(copy.title), style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(copy.body), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
                    if (copy.busy) CircularProgressIndicator(color = AppColors.SelectionBorder,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(AppSpacing.Large))
                    if (state.step == FirstSyncStep.Complete) {
                        CompletionCard(state.matchingEnabled)
                    } else {
                        val active = when (state.step) {
                            FirstSyncStep.Connecting -> 0
                            FirstSyncStep.Preparing, FirstSyncStep.Sending -> 1
                            FirstSyncStep.AwaitingApply -> 2
                            FirstSyncStep.CheckingAccount -> 3
                            else -> null
                        }
                        if (active != null) SyncSteps(active)
                        StatusCard(stringResource(copy.helpTitle), stringResource(copy.helpBody))
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppButton(stringResource(copy.button), Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    enabled = !copy.busy, loading = copy.busy, loadingText = stringResource(copy.button),
                    onClick = { if (!copy.busy) onAction(copy.action) })
            }
        }
    }
}

@Composable
private fun SyncSteps(active: Int) {
    val labels = listOf(Res.string.first_sync_step_connect, Res.string.first_sync_step_send,
        Res.string.first_sync_step_apply, Res.string.first_sync_step_account)
    Surface(shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
            labels.forEachIndexed { index, label ->
                // 세로 배치로 큰 글자에서도 단계 이름과 상태가 겹치지 않게 한다.
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(when {
                        index < active -> Res.string.first_sync_step_done
                        index == active -> Res.string.first_sync_step_current
                        else -> Res.string.first_sync_step_wait
                    }), style = MaterialTheme.typography.bodySmall,
                        color = if (index <= active) AppColors.OnSelection else AppColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun CompletionCard(matchingEnabled: Boolean?) {
    Surface(shape = AppShapes.Card, color = AppColors.SelectionContainer) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Section), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(stringResource(Res.string.first_sync_complete_badge), style = MaterialTheme.typography.titleLarge,
                color = AppColors.OnSelection)
            Text(stringResource(when (matchingEnabled) {
                true -> Res.string.first_sync_matching_on
                false -> Res.string.first_sync_matching_off
                null -> Res.string.first_sync_matching_unknown
            }), style = MaterialTheme.typography.bodyLarge, color = AppColors.OnSelection)
        }
    }
    Text(stringResource(Res.string.first_sync_complete_help), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
}

private data class SyncCopy(
    val title: StringResource,
    val body: StringResource,
    val button: StringResource,
    val action: FirstSyncAction,
    val busy: Boolean = false,
    val helpTitle: StringResource = Res.string.first_sync_help_title,
    val helpBody: StringResource = Res.string.first_sync_help_body,
)

private fun syncCopy(step: FirstSyncStep): SyncCopy = when (step) {
    FirstSyncStep.Disconnected -> SyncCopy(Res.string.first_sync_disconnected_title, Res.string.first_sync_disconnected_body,
        Res.string.first_sync_connect, FirstSyncAction.Connect)
    FirstSyncStep.Connecting -> SyncCopy(Res.string.first_sync_connecting_title, Res.string.first_sync_connecting_body,
        Res.string.first_sync_connecting, FirstSyncAction.Connect, busy = true)
    FirstSyncStep.Preparing -> SyncCopy(Res.string.first_sync_preparing_title, Res.string.first_sync_preparing_body,
        Res.string.first_sync_preparing, FirstSyncAction.RetrySync, busy = true)
    FirstSyncStep.Sending -> SyncCopy(Res.string.first_sync_sending_title, Res.string.first_sync_sending_body,
        Res.string.first_sync_sending, FirstSyncAction.RetrySync, busy = true)
    FirstSyncStep.AwaitingApply -> SyncCopy(Res.string.first_sync_applying_title, Res.string.first_sync_applying_body,
        Res.string.first_sync_applying, FirstSyncAction.CheckDeviceStatus, busy = true)
    FirstSyncStep.CheckingAccount -> SyncCopy(Res.string.first_sync_account_title, Res.string.first_sync_account_body,
        Res.string.first_sync_checking, FirstSyncAction.CheckAccountStatus, busy = true)
    FirstSyncStep.Interrupted -> SyncCopy(Res.string.first_sync_interrupted_title, Res.string.first_sync_interrupted_body,
        Res.string.first_sync_reconnect, FirstSyncAction.Connect)
    FirstSyncStep.Failed -> SyncCopy(Res.string.first_sync_failed_title, Res.string.first_sync_failed_body,
        Res.string.first_sync_retry, FirstSyncAction.RetrySync)
    FirstSyncStep.ResultUnknown -> SyncCopy(Res.string.first_sync_unknown_title, Res.string.first_sync_unknown_body,
        Res.string.first_sync_check_device, FirstSyncAction.CheckDeviceStatus)
    FirstSyncStep.AccountCheckFailed -> SyncCopy(Res.string.first_sync_account_failed_title, Res.string.first_sync_account_failed_body,
        Res.string.first_sync_check_account, FirstSyncAction.CheckAccountStatus,
        helpTitle = Res.string.first_sync_applied_title, helpBody = Res.string.first_sync_applied_body)
    FirstSyncStep.SettingsChanged -> SyncCopy(Res.string.first_sync_changed_title, Res.string.first_sync_changed_body,
        Res.string.first_sync_retry_latest, FirstSyncAction.RetrySync)
    FirstSyncStep.Complete -> SyncCopy(Res.string.first_sync_complete_title, Res.string.first_sync_complete_body,
        Res.string.first_sync_home, FirstSyncAction.OpenHome)
}
