package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
// 화면은 상태와 사용자 의도만 전달한다. 실제 조회/복구는 Route와 모델이 수행하며 Preview와 구분한다.
fun KeyringResetScreen(state: KeyringResetState, onAccepted: (Boolean) -> Unit, onAction: (KeyringResetAction) -> Unit,
    onRecordChoice: (ResetRecordChoice) -> Unit = {}, onRecordDeletionAccepted: (Boolean) -> Unit = {},
    showPreviewNotice: Boolean = false) {
    val phase = state.displayStage
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // 이 배너는 debug @Preview에서만 켠다. 제품 화면의 동작 상태와 혼동하지 않는다.
            if (showPreviewNotice) Text(stringResource(Res.string.reset_preview_notice), Modifier.padding(AppSpacing.Small))
            // 다른 키링 화면과 같은 화살표. 진행 중에는 뒤로 갈 수 없어 자리만 둔다
            if (state.busy) Spacer(Modifier.height(48.dp)) else BackArrowBar { onAction(KeyringResetAction.Back) }
            if (phase == KeyringResetStage.Ready || phase == KeyringResetStage.Confirm) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    state.errorMessage?.let { Text(it, color = AppColors.Error) }
                    // 되돌릴 수 없는 작업이라는 점을 붉은 표시 · 경고 상자로 먼저 보여준다
                    Row(Modifier.background(AppColors.ErrorContainer, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(Res.drawable.ic_warning), null, Modifier.size(14.dp), tint = AppColors.Error)
                        Text(stringResource(Res.string.reset_irreversible), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold, color = AppColors.Error)
                    }
                    Text(stringResource(Res.string.reset_question), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(Res.string.reset_effect), color = AppColors.TextSecondary)
                    Text(stringResource(Res.string.reset_both), Modifier.fillMaxWidth().background(AppColors.ErrorContainer, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp), color = AppColors.OnErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(Res.string.reset_existing), color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    ResetRecordsSection(state, onRecordChoice, onRecordDeletionAccepted,
                        onRefresh = { onAction(KeyringResetAction.Refresh) })
                    // 안내와 확인이 한 화면이다. 초기화 자체의 동의와 아직 업로드하지 않은 기록의 삭제 동의를 묶지 않는다.
                    AppCheckbox(state.accepted, onAccepted, stringResource(Res.string.reset_accept))
                }
            } else if (phase == KeyringResetStage.Loading) {
                // 불러오는 중(아직 초기화 전)은 확인 화면과 같은 자리에 회색 뼈대만 — "초기화하고 있어요"로 오해하지 않게
                SkeletonGroup(Modifier.weight(1f).padding(screenPaddingValues()), label = stringResource(Res.string.reset_loading_label)) {
                    SkeletonBone(width = 120.dp, height = 24.dp, shape = RoundedCornerShape(50))
                    SkeletonBone(Modifier.fillMaxWidth(0.7f), height = 28.dp)
                    SkeletonBone(height = 40.dp)
                    SkeletonBone(height = 64.dp, shape = RoundedCornerShape(14.dp))
                    SkeletonBone(height = 40.dp)
                    SkeletonBone(height = AppSpacing.ControlMinHeight)
                }
            } else {
                // 시작 화면 말고는 진행 중 · 완료 · 실패(· 연결 필요) 하나만 가운데에 보여준다
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium, Alignment.CenterVertically)) {
                    when {
                        // 초기화 진행은 기기 작업이라 로딩 원과 안내를 그대로 둔다
                        state.busy -> {
                            val label = stringResource(Res.string.reset_running)
                            CircularProgressIndicator(Modifier.size(48.dp).semantics { contentDescription = label }, color = AppColors.TextPrimary)
                            ResetHeadline(label, stringResource(Res.string.reset_running_help))
                        }
                        // 완료: 상태 화면(제목 한 줄) + 아래 "확인"
                        phase == KeyringResetStage.Complete -> StateView(StateKind.Done, stringResource(Res.string.reset_done))
                        phase == KeyringResetStage.Disconnected && state.stage != KeyringResetStage.Failed -> {
                            KeyringPebble(Modifier.size(96.dp).alpha(0.45f))
                            ResetHeadline(stringResource(Res.string.reset_disconnected), stringResource(Res.string.reset_disconnected_help))
                        }
                        // 확인 불가 · 실패: 상태 화면 모양으로 통일하되, 되돌릴 수 없는 작업이라 다음 행동 안내는 지우지 않는다
                        else -> {
                            val unavailable = phase == KeyringResetStage.Unavailable
                            StateView(StateKind.Unknown, stringResource(if (unavailable) Res.string.reset_unavailable else Res.string.reset_failed))
                            Text(stringResource(if (unavailable) Res.string.reset_unavailable_help else Res.string.reset_failed_help),
                                color = AppColors.TextSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    if (!state.busy) state.errorMessage?.let { Text(it, color = AppColors.Error, textAlign = TextAlign.Center) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                val action = when (phase) {
                    KeyringResetStage.Ready, KeyringResetStage.Confirm -> if (state.pendingRecordCount != null && state.pendingRecordCount > 0 &&
                        state.recordChoice == ResetRecordChoice.UploadFirst) KeyringResetAction.UploadRecords else KeyringResetAction.StartReset
                    KeyringResetStage.Disconnected -> KeyringResetAction.CheckConnection
                    // 결과 확인 화면은 없다. 앱이 자동으로 마저 하고, 그래도 안 되면 "다시 시도"로 같은 확인을 다시 한다
                    KeyringResetStage.Partial, KeyringResetStage.Unknown -> KeyringResetAction.CheckResult
                    KeyringResetStage.Failed -> KeyringResetAction.Retry
                    KeyringResetStage.Unavailable -> KeyringResetAction.Refresh
                    else -> KeyringResetAction.Close
                }
                val label = when (action) {
                    KeyringResetAction.StartReset -> Res.string.reset_start
                    KeyringResetAction.UploadRecords -> if (state.recordUploadAvailable) Res.string.reset_records_upload
                        else Res.string.reset_records_preparing
                    KeyringResetAction.CheckConnection -> Res.string.reset_connect
                    // 다시 하는 동작의 이름은 모두 "다시 시도", 완료는 "확인"
                    KeyringResetAction.CheckResult, KeyringResetAction.Retry, KeyringResetAction.Refresh -> Res.string.reset_retry
                    else -> Res.string.reset_close
                }
                // 선업로드 계약·펌웨어가 준비되기 전에는 클릭 콜백도 실행하지 않는다.
                val enabled = !state.busy && when (action) {
                    KeyringResetAction.StartReset -> state.canConfirm
                    KeyringResetAction.UploadRecords -> state.canUploadRecords
                    else -> true
                }
                // 진행 중에는 버튼 없이 가운데 로딩만 둔다
                if (!state.busy) AppButton(stringResource(label), Modifier.fillMaxWidth(),
                    variant = if (action == KeyringResetAction.StartReset) AppButtonVariant.Danger else AppButtonVariant.Primary,
                    enabled = enabled,
                    onClick = { if (enabled) onAction(action) })
                // 불러오는 중에는 실제 화면의 버튼 자리를 잠근 채로 둔다
                else if (phase == KeyringResetStage.Loading) AppButton(stringResource(Res.string.reset_start), Modifier.fillMaxWidth(), enabled = false, onClick = {})
                if (phase == KeyringResetStage.Ready || phase == KeyringResetStage.Confirm) AppButton(stringResource(Res.string.reset_cancel), Modifier.fillMaxWidth(),
                    variant = AppButtonVariant.Secondary, onClick = { onAction(KeyringResetAction.Cancel) })
            }
        }
    }
}

/** 진행 중 · 연결 필요의 제목 + 안내 한 줄 */
@Composable
private fun ResetHeadline(title: String, help: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
    Text(help, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
}

/** 미전송 기록은 실제 조회 전까지 미확인으로 남긴다. Preview의 개수는 서버 결과가 아니다. */
@Composable
private fun ResetRecordsSection(state: KeyringResetState, onChoice: (ResetRecordChoice) -> Unit,
    onDeletionAccepted: (Boolean) -> Unit, onRefresh: () -> Unit) {
    val count = state.pendingRecordCount
    when {
        count == null || count < 0 -> {
            StatusCard(stringResource(Res.string.reset_records_unknown), stringResource(Res.string.reset_records_unknown_help))
            // 미확인은 기록 0개가 아니다. 미지원 조회를 가능한 재시도로 표시하지 않는다.
            if (!state.recordQueryAvailable) Text(stringResource(Res.string.reset_records_query_unavailable))
            AppButton(stringResource(if (state.recordQueryAvailable) Res.string.reset_records_refresh else Res.string.reset_records_preparing),
                enabled = state.canQueryRecords, onClick = { if (state.canQueryRecords) onRefresh() }, variant = AppButtonVariant.Secondary)
            // 조회가 없어도 기록이 있을 수 있음을 알리고 초기화 자체와 분리된 삭제 동의를 받는다.
            // 음수는 손상된 값이므로 이 경로로 진행시키지 않는다.
            if (count == null) {
                Text(stringResource(Res.string.reset_records_unknown_delete_help), color = AppColors.Error)
                AppCheckbox(state.recordDeletionAccepted, { accepted ->
                    onChoice(ResetRecordChoice.Delete)
                    onDeletionAccepted(accepted)
                }, stringResource(Res.string.reset_records_unknown_delete_accept))
            }
        }
        count == 0 -> Unit                                    // 보낼 기록이 없으면 따로 알리지 않는다
        else -> {
            StatusCard(stringResource(Res.string.reset_records_pending, count), stringResource(Res.string.reset_records_help))
            Column(Modifier.selectableGroup()) {
                AppRadio(state.recordChoice == ResetRecordChoice.UploadFirst, { onChoice(ResetRecordChoice.UploadFirst) },
                    stringResource(Res.string.reset_records_upload_choice))
                AppRadio(state.recordChoice == ResetRecordChoice.Delete, { onChoice(ResetRecordChoice.Delete) },
                    stringResource(Res.string.reset_records_delete_choice))
            }
            if (!state.recordUploadAvailable) Text(stringResource(Res.string.reset_records_upload_unavailable))
            if (state.recordChoice == ResetRecordChoice.Delete) {
                Text(stringResource(Res.string.reset_records_delete_help), color = AppColors.Error)
                AppCheckbox(state.recordDeletionAccepted, onDeletionAccepted, stringResource(Res.string.reset_records_delete_accept))
            }
        }
    }
}
