package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun KeyringResetScreen(state: KeyringResetState, onAccepted: (Boolean) -> Unit, onAction: (KeyringResetAction) -> Unit) {
    val phase = state.displayStage
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.reset_title), onBack = if (state.busy) null else ({ onAction(KeyringResetAction.Back) }))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                if (phase == KeyringResetStage.Ready || phase == KeyringResetStage.Confirm) {
                    Text(stringResource(Res.string.reset_question), style = MaterialTheme.typography.headlineSmall)
                    StatusCard(stringResource(Res.string.reset_target), state.deviceLabel.orEmpty())
                    Text(stringResource(Res.string.reset_effect))
                    Text(stringResource(Res.string.reset_both), color = AppColors.Error)
                    Text(stringResource(Res.string.reset_existing))
                    if (phase == KeyringResetStage.Confirm) AppCheckbox(state.accepted, onAccepted, stringResource(Res.string.reset_accept))
                } else {
                    val copy = when (phase) {
                        KeyringResetStage.Loading -> Res.string.reset_loading to Res.string.reset_loading_help
                        KeyringResetStage.Disconnected -> Res.string.reset_disconnected to Res.string.reset_disconnected_help
                        KeyringResetStage.Resetting -> Res.string.reset_device_processing to Res.string.reset_processing_help
                        KeyringResetStage.Unlinking -> Res.string.reset_server_processing to Res.string.reset_processing_help
                        KeyringResetStage.Partial -> Res.string.reset_partial to Res.string.reset_partial_help
                        KeyringResetStage.Failed -> Res.string.reset_failed to Res.string.reset_failed_help
                        KeyringResetStage.Complete -> Res.string.reset_complete to Res.string.reset_complete_help
                        KeyringResetStage.Unavailable -> Res.string.reset_unavailable to Res.string.reset_unavailable_help
                        else -> Res.string.reset_unknown to Res.string.reset_unknown_help
                    }
                    StatusCard(stringResource(copy.first), stringResource(copy.second))
                    if (phase in listOf(KeyringResetStage.Resetting, KeyringResetStage.Unlinking, KeyringResetStage.Partial, KeyringResetStage.Unknown)) {
                        ServiceRow(stringResource(Res.string.reset_device_step), detail = stringResource(if (state.deviceResetConfirmed) Res.string.reset_confirmed else Res.string.reset_unconfirmed))
                        ServiceRow(stringResource(Res.string.reset_server_step), detail = stringResource(if (state.ownershipReleased) Res.string.reset_confirmed else Res.string.reset_unconfirmed))
                        Text(stringResource(Res.string.reset_both), color = AppColors.Error)
                    }
                    if (phase == KeyringResetStage.Complete) Text(stringResource(Res.string.reset_existing))
                }
            }
            Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                val action = when (phase) {
                    KeyringResetStage.Ready -> KeyringResetAction.OpenConfirm
                    KeyringResetStage.Confirm -> KeyringResetAction.StartReset
                    KeyringResetStage.Disconnected -> KeyringResetAction.CheckConnection
                    KeyringResetStage.Partial, KeyringResetStage.Unknown -> KeyringResetAction.CheckResult
                    KeyringResetStage.Failed -> KeyringResetAction.Retry
                    KeyringResetStage.Unavailable -> KeyringResetAction.Refresh
                    else -> KeyringResetAction.Close
                }
                val label = when (action) {
                    KeyringResetAction.OpenConfirm -> Res.string.reset_continue
                    KeyringResetAction.StartReset -> Res.string.reset_start
                    KeyringResetAction.CheckConnection -> Res.string.reset_connect
                    KeyringResetAction.CheckResult -> Res.string.reset_check
                    KeyringResetAction.Retry -> Res.string.reset_retry
                    KeyringResetAction.Refresh -> Res.string.reset_refresh
                    else -> Res.string.reset_close
                }
                AppButton(stringResource(label), Modifier.fillMaxWidth(),
                    variant = if (phase == KeyringResetStage.Confirm) AppButtonVariant.Danger else AppButtonVariant.Primary,
                    enabled = !state.busy && (phase != KeyringResetStage.Confirm || state.canConfirm),
                    loading = state.busy, loadingText = stringResource(Res.string.reset_wait),
                    onClick = { if (!state.busy && (phase != KeyringResetStage.Confirm || state.canConfirm)) onAction(action) })
                if (phase == KeyringResetStage.Confirm) AppButton(stringResource(Res.string.reset_cancel), Modifier.fillMaxWidth(),
                    variant = AppButtonVariant.Secondary, onClick = { onAction(KeyringResetAction.Cancel) })
            }
        }
    }
}
