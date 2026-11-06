package com.ssafy.e106.features.keyring.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 가입 키링 단계의 실제 서버 등록 경로(운영 앱 · 개발 앱 같음). [diagnostics] 는 개발 앱에서만 켠다. */
@Composable fun ConnectedKeyringSetupRoute(onBack: () -> Unit, managedConnection: Boolean = false, diagnostics: Boolean = false) {
    // 호스트가 연결을 관리하는 경우 화면을 나가도 설정 저장/가입 단계 전환까지 같은 연결을 사용한다.
    ConnectedBleProbeRoute(onBack, koinViewModel(), koinViewModel<KeyringRegistrationViewModel>(), koinViewModel<KeyringSyncViewModel>(), managedConnection,
        autoScan = true, diagnostics = diagnostics)
}

/** 재시도는 새 등록이 아니라 보관한 동일 요청의 결과 확인이다. 자동으로 서버 등록을 시작하지 않는다. */
@Composable internal fun KeyringRegistrationPanel(model: KeyringRegistrationViewModel, ble: BleProbeViewModel,
    bleState: BleProbeState, syncBusy: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    val serial = bleState.info?.serial
    // 기기에서 BOUND를 읽어도 누구의 계정인지는 추정하지 않는다. 서버 등록 결과가 유실된
    // 요청은 이미 저장한 본문으로 복구할 수 있으므로 신규 BLE 증명 경로에만 이 제한을 적용한다.
    val blocked = registrationBlock(bleState.info)
    // 실제 기기/회원이 바뀌면 이전 확인창의 동의를 재사용하지 않는다. 회전 후 확인창도 다시 연다.
    var confirm by remember(serial, state.owner) { mutableStateOf(false) }
    Text(stringResource(Res.string.registration_live_title), style = MaterialTheme.typography.titleMedium)
    state.owner?.let { Text(stringResource(Res.string.registration_live_owner, it.toString())) }
    if (state.busy) { CircularProgressIndicator(); Text(stringResource(Res.string.registration_live_busy)) }
    if (!state.loaded && !state.busy) AppButton(stringResource(Res.string.registration_live_restore), enabled = !syncBusy, onClick = model::restore)
    state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    val visibleIssue = if (state.pending?.body == null && state.confirmed == null) blocked ?: state.issue else state.issue
    visibleIssue?.let { issue -> Text(stringResource(when (issue) {
        RegistrationIssue.Storage -> Res.string.registration_live_storage
        RegistrationIssue.ExpiredChallenge -> Res.string.registration_live_expired
        RegistrationIssue.Device -> Res.string.registration_live_device
        RegistrationIssue.NotInOwnedList -> Res.string.registration_live_unconfirmed
        RegistrationIssue.InvalidResponse -> Res.string.registration_live_invalid
        RegistrationIssue.ResetPending -> Res.string.registration_reset_pending
        RegistrationIssue.DeviceBound -> Res.string.registration_live_bound
    }), color = MaterialTheme.colorScheme.error) }
    when {
        state.confirmed != null -> {
            Text(stringResource(Res.string.registration_live_success, state.confirmed!!.deviceId.toString()))
            AppButton(stringResource(Res.string.registration_live_continue), enabled = !state.busy && !syncBusy, onClick = model::continueOnboarding)
        }
        state.pending != null -> {
            val pending = state.pending!!
            Text(stringResource(Res.string.registration_live_pending, pending.serial))
            val needsBoard = pending.body == null
            AppButton(stringResource(Res.string.registration_live_retry), enabled = state.loaded && !state.busy && !syncBusy &&
                (!needsBoard || (blocked == null && bleState.connected && !bleState.busy && serial == pending.serial)),
                onClick = { model.retry { challenge -> ble.registrationProof(challenge, pending.serial) } })
            if (state.canRestart) AppButton(stringResource(Res.string.registration_live_restart), enabled = !state.busy && !syncBusy,
                variant = AppButtonVariant.Secondary, onClick = model::restart)
        }
        serial != null && bleState.info.supportsRegistration && blocked == null -> {
            AppButton(stringResource(Res.string.registration_live_review), enabled = state.loaded && !state.busy &&
                !bleState.busy && bleState.connected && !syncBusy, onClick = { confirm = true })
        }
    }
    if (confirm && serial != null && blocked == null && state.pending == null && state.confirmed == null) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text(stringResource(Res.string.registration_live_confirm_title)) },
        text = { Text(stringResource(Res.string.registration_live_confirm_body, serial)) },
        confirmButton = { TextButton(enabled = state.loaded && !state.busy && bleState.connected && !bleState.busy && !syncBusy,
            onClick = { confirm = false; model.start(serial) { challenge -> ble.registrationProof(challenge, serial) } }) {
            Text(stringResource(Res.string.registration_live_accept)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.registration_live_cancel)) } })
}
