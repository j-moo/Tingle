package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.ssafy.e106.core.ui.theme.AppSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 가입과 내 키링에서 같은 서버 등록 경로를 사용한다. */
@Composable fun ConnectedKeyringSetupRoute(onBack: () -> Unit, managedConnection: Boolean = false,
    allowRegistration: Boolean = false, onRegistrationDone: (() -> Unit)? = null) {
    // 호스트가 연결을 관리하는 경우 화면을 나가도 설정 저장/가입 단계 전환까지 같은 연결을 사용한다.
    // 등록 화면은 등록 증명만 다룬다. 동기화 화면과 초기화 연결에는 소유 목록 확인을 적용한다.
    ConnectedBleProbeRoute(onBack, registration = if (allowRegistration) koinViewModel<KeyringRegistrationViewModel>() else null,
        sync = if (allowRegistration) null else koinViewModel<KeyringSyncViewModel>(),
        managedConnection = managedConnection, autoScan = true, onRegistrationDone = onRegistrationDone)
}

/** 재시도는 새 등록이 아니라 보관한 동일 요청의 결과 확인이다. 자동으로 서버 등록을 시작하지 않는다. */
@Composable internal fun KeyringRegistrationPanel(model: KeyringRegistrationViewModel, ble: BleProbeViewModel,
    bleState: BleProbeState, syncBusy: Boolean = false, onRegistrationDone: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    // 가입 화면은 서버의 다음 단계를 다시 확인하고, 관리 화면은 확인된 등록 결과를 목록에서 보여준다.
    KeyringRegistrationPanelContent(state, bleState, syncBusy, onRestore = model::restore,
        onContinue = onRegistrationDone ?: model::continueOnboarding,
        onRetry = { serial -> model.retry { challenge -> ble.registrationProof(challenge, serial) } }, onRestart = model::restart,
        onStart = { serial -> model.start(serial) { challenge -> ble.registrationProof(challenge, serial) } })
}

/** 순수 화면(Preview 용). 등록 상태 · 연결 상태만 받아 그리고, 서버 · 기기 호출은 콜백으로 넘긴다 */
@Composable fun KeyringRegistrationPanelContent(
    state: LiveRegistrationState,
    bleState: BleProbeState,
    syncBusy: Boolean = false,
    onRestore: () -> Unit = {},
    onContinue: () -> Unit = {},
    onRetry: (serial: String) -> Unit = {},
    onRestart: () -> Unit = {},
    onStart: (serial: String) -> Unit = {},
) {
    val serial = bleState.info?.serial
    val full = Modifier.fillMaxWidth()                           // 하단 "연결 끊기"와 같은 길이
    // 기기에서 BOUND를 읽어도 누구의 계정인지는 추정하지 않는다. 서버 등록 결과가 유실된
    // 요청은 이미 저장한 본문으로 복구할 수 있으므로 신규 BLE 증명 경로에만 이 제한을 적용한다.
    val blocked = registrationBlock(bleState.info)
    // 실제 기기/회원이 바뀌면 이전 확인창의 동의를 재사용하지 않는다. 회전 후 확인창도 다시 연다.
    var confirm by remember(serial, state.owner) { mutableStateOf(false) }
    // 위쪽 키링 선택 묶음과 맞춰 패널 글자 · 로딩 원도 가운데 정렬
    val center = TextAlign.Center
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Text(stringResource(Res.string.registration_live_title), style = MaterialTheme.typography.titleMedium, textAlign = center)
        if (state.busy) { CircularProgressIndicator(); Text(stringResource(Res.string.registration_live_busy), textAlign = center) }
        if (!state.loaded && !state.busy) AppButton(stringResource(Res.string.registration_live_restore), full, enabled = !syncBusy, onClick = onRestore)
        state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, textAlign = center) }
        val visibleIssue = if (state.pending?.body == null && state.confirmed == null) blocked ?: state.issue else state.issue
        visibleIssue?.let { issue -> Text(stringResource(when (issue) {
            RegistrationIssue.Storage -> Res.string.registration_live_storage
            RegistrationIssue.ExpiredChallenge -> Res.string.registration_live_expired
            RegistrationIssue.Device -> Res.string.registration_live_device
            RegistrationIssue.NotInOwnedList -> Res.string.registration_live_unconfirmed
            RegistrationIssue.InvalidResponse -> Res.string.registration_live_invalid
            RegistrationIssue.ResetPending -> Res.string.registration_reset_pending
            RegistrationIssue.DeviceBound -> Res.string.registration_live_bound
            RegistrationIssue.WindowClosed -> Res.string.registration_live_window
        }), color = MaterialTheme.colorScheme.error, textAlign = center) }
        when {
            state.confirmed != null -> {
                Text(stringResource(Res.string.registration_live_success), textAlign = center)
                AppButton(stringResource(Res.string.registration_live_continue), full, enabled = !state.busy && !syncBusy, onClick = onContinue)
            }
            state.pending != null -> {
                val pending = state.pending
                Text(stringResource(Res.string.registration_live_pending), textAlign = center)
                val needsBoard = pending.body == null
                AppButton(stringResource(Res.string.registration_live_retry), full, enabled = state.loaded && !state.busy && !syncBusy &&
                    (!needsBoard || (blocked == null && bleState.connected && !bleState.busy && serial == pending.serial)),
                    onClick = { onRetry(pending.serial) })
                if (state.canRestart) AppButton(stringResource(Res.string.registration_live_restart), full, enabled = !state.busy && !syncBusy,
                    variant = AppButtonVariant.Secondary, onClick = onRestart)
            }
            serial != null && bleState.info.supportsRegistration && blocked == null -> {
                AppButton(stringResource(Res.string.registration_live_review), full, enabled = state.loaded && !state.busy &&
                    !bleState.busy && bleState.connected && !syncBusy, onClick = { confirm = true })
            }
        }
    }
    if (confirm && serial != null && blocked == null && state.pending == null && state.confirmed == null) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text(stringResource(Res.string.registration_live_confirm_title)) },
        text = { Text(stringResource(Res.string.registration_live_confirm_body, serial)) },
        confirmButton = { TextButton(enabled = state.loaded && !state.busy && bleState.connected && !bleState.busy && !syncBusy,
            onClick = { confirm = false; onStart(serial) }) {
            Text(stringResource(Res.string.registration_live_accept)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.registration_live_cancel)) } })
}
