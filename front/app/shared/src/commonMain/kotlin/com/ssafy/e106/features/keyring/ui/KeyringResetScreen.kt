package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
// 화면은 상태와 사용자 의도만 전달한다. 서버/BLE 호출 및 복구 자료 저장은 아직 연결하지 않는다.
fun KeyringResetScreen(state: KeyringResetState, onAccepted: (Boolean) -> Unit, onAction: (KeyringResetAction) -> Unit,
    onRecordChoice: (ResetRecordChoice) -> Unit = {}, onRecordDeletionAccepted: (Boolean) -> Unit = {},
    showPreviewNotice: Boolean = false) {
    val phase = state.displayStage
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // 이 배너는 debug @Preview에서만 켠다. 제품 화면의 동작 상태와 혼동하지 않는다.
            if (showPreviewNotice) Text(stringResource(Res.string.reset_preview_notice), Modifier.padding(AppSpacing.Small))
            AppTopBar(stringResource(Res.string.reset_title), onBack = if (state.busy) null else ({ onAction(KeyringResetAction.Back) }))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                if (phase == KeyringResetStage.Ready || phase == KeyringResetStage.Confirm) {
                    Text(stringResource(Res.string.reset_question), style = MaterialTheme.typography.headlineSmall)
                    StatusCard(stringResource(Res.string.reset_target), state.deviceLabel.orEmpty())
                    Text(stringResource(Res.string.reset_effect))
                    Text(stringResource(Res.string.reset_both), color = AppColors.Error)
                    Text(stringResource(Res.string.reset_existing))
                    if (phase == KeyringResetStage.Confirm) {
                        ResetRecordsSection(state, onRecordChoice, onRecordDeletionAccepted,
                            onRefresh = { onAction(KeyringResetAction.Refresh) })
                        // 초기화 자체의 동의와 아직 업로드하지 않은 기록의 삭제 동의를 묶지 않는다.
                        AppCheckbox(state.accepted, onAccepted, stringResource(Res.string.reset_accept))
                    }
                } else {
                    val copy = when (phase) {
                        KeyringResetStage.Loading -> Res.string.reset_loading to Res.string.reset_loading_help
                        KeyringResetStage.Disconnected -> Res.string.reset_disconnected to Res.string.reset_disconnected_help
                        KeyringResetStage.Preparing -> Res.string.reset_preparing to Res.string.reset_preparing_help
                        KeyringResetStage.Resetting -> Res.string.reset_device_processing to Res.string.reset_processing_help
                        KeyringResetStage.Unlinking -> Res.string.reset_server_processing to Res.string.reset_processing_help
                        KeyringResetStage.Partial -> Res.string.reset_partial to Res.string.reset_partial_help
                        KeyringResetStage.Recovering -> Res.string.reset_recovering to Res.string.reset_recovering_help
                        KeyringResetStage.Failed -> Res.string.reset_failed to Res.string.reset_failed_help
                        KeyringResetStage.Complete -> Res.string.reset_complete to Res.string.reset_complete_help
                        KeyringResetStage.Unavailable -> Res.string.reset_unavailable to Res.string.reset_unavailable_help
                        else -> Res.string.reset_unknown to Res.string.reset_unknown_help
                    }
                    StatusCard(stringResource(copy.first), stringResource(copy.second))
                    if (!state.deviceLabel.isNullOrBlank()) StatusCard(stringResource(Res.string.reset_target), state.deviceLabel)
                    if (phase in listOf(KeyringResetStage.Preparing, KeyringResetStage.Resetting, KeyringResetStage.Unlinking,
                            KeyringResetStage.Partial, KeyringResetStage.Recovering, KeyringResetStage.Unknown)) {
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
                    KeyringResetStage.Confirm -> if (state.pendingRecordCount != null && state.pendingRecordCount > 0 &&
                        state.recordChoice == ResetRecordChoice.UploadFirst) KeyringResetAction.UploadRecords else KeyringResetAction.StartReset
                    KeyringResetStage.Disconnected -> KeyringResetAction.CheckConnection
                    KeyringResetStage.Partial, KeyringResetStage.Unknown -> KeyringResetAction.CheckResult
                    KeyringResetStage.Failed -> KeyringResetAction.Retry
                    KeyringResetStage.Unavailable -> KeyringResetAction.Refresh
                    else -> KeyringResetAction.Close
                }
                val label = when (action) {
                    KeyringResetAction.OpenConfirm -> Res.string.reset_continue
                    KeyringResetAction.StartReset -> Res.string.reset_start
                    KeyringResetAction.UploadRecords -> Res.string.reset_records_upload
                    KeyringResetAction.CheckConnection -> Res.string.reset_connect
                    KeyringResetAction.CheckResult -> Res.string.reset_check
                    KeyringResetAction.Retry -> Res.string.reset_retry
                    KeyringResetAction.Refresh -> Res.string.reset_refresh
                    else -> Res.string.reset_close
                }
                // 업로드는 삭제 작업이 아니며, 초기화 시작만 두 동의와 기록 조회 결과로 제한한다.
                val enabled = !state.busy && (action != KeyringResetAction.StartReset || state.canConfirm)
                AppButton(stringResource(label), Modifier.fillMaxWidth(),
                    variant = if (action == KeyringResetAction.StartReset) AppButtonVariant.Danger else AppButtonVariant.Primary,
                    enabled = enabled,
                    loading = state.busy, loadingText = stringResource(Res.string.reset_wait),
                    onClick = { if (enabled) onAction(action) })
                if (phase == KeyringResetStage.Confirm) AppButton(stringResource(Res.string.reset_cancel), Modifier.fillMaxWidth(),
                    variant = AppButtonVariant.Secondary, onClick = { onAction(KeyringResetAction.Cancel) })
            }
        }
    }
}

/** 미전송 기록은 실제 조회 전까지 미확인으로 남긴다. Preview의 개수는 서버 결과가 아니다. */
@Composable
private fun ResetRecordsSection(state: KeyringResetState, onChoice: (ResetRecordChoice) -> Unit,
    onDeletionAccepted: (Boolean) -> Unit, onRefresh: () -> Unit) {
    val count = state.pendingRecordCount
    when {
        count == null || count < 0 -> {
            StatusCard(stringResource(Res.string.reset_records_unknown), stringResource(Res.string.reset_records_unknown_help))
            AppButton(stringResource(Res.string.reset_records_refresh), onClick = onRefresh, variant = AppButtonVariant.Secondary)
        }
        count == 0 -> StatusCard(stringResource(Res.string.reset_records_empty), stringResource(Res.string.reset_records_empty_help))
        else -> {
            StatusCard(stringResource(Res.string.reset_records_pending, count), stringResource(Res.string.reset_records_help))
            Column(Modifier.selectableGroup()) {
                AppRadio(state.recordChoice == ResetRecordChoice.UploadFirst, { onChoice(ResetRecordChoice.UploadFirst) },
                    stringResource(Res.string.reset_records_upload_choice))
                AppRadio(state.recordChoice == ResetRecordChoice.Delete, { onChoice(ResetRecordChoice.Delete) },
                    stringResource(Res.string.reset_records_delete_choice))
            }
            if (state.recordChoice == ResetRecordChoice.Delete) {
                Text(stringResource(Res.string.reset_records_delete_help), color = AppColors.Error)
                AppCheckbox(state.recordDeletionAccepted, onDeletionAccepted, stringResource(Res.string.reset_records_delete_accept))
            }
        }
    }
}
