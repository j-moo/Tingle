package com.ssafy.e106.features.keyring.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 서버 저장 확인과 미인증 기기 적용 상태를 별도로 보여준다. 이 화면이 READY를 만들어 내지 않는다. */
@Composable internal fun KeyringSyncPanel(model: KeyringSyncViewModel, ble: BleProbeViewModel,
    bleState: BleProbeState, registrationBusy: Boolean, owned: OwnedKeyringsViewModel = koinViewModel(),
    managedConnection: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    val list by owned.state.collectAsStateWithLifecycle()
    val serial = bleState.info?.serial?.takeIf { bleState.connected }
    var selected by remember(serial, state.owner) { mutableStateOf<Long?>(null) }
    // 연결이 바뀌면 전송 경로도 제거한다. busy 변화에는 효과를 재실행하지 않아 자기 전송을 취소하지 않는다.
    // 서비스 호스트와 화면이 동시에 attach/detach하지 않도록 소유자를 하나로 제한한다.
    if (!managedConnection) DisposableEffect(serial, state.owner) {
        // 진단 화면에서 수동 동기화할 때도 서비스 호스트와 동일한 형식 선택을 사용한다.
        if (serial != null) model.attachRequest(serial, { ble.syncIssuanceRequest(serial) }) { run -> ble.syncSettings(run, serial) }
        onDispose { model.detach() }
    }
    Text(stringResource(Res.string.sync_live_title), style = MaterialTheme.typography.titleMedium)
    KeyringSyncProgressList(state, model::retry, model::latest)
    if (state.recoveryFailed) AppButton(stringResource(Res.string.sync_live_retry), enabled = !state.busy, onClick = model::retryRecovery)
    if (serial != null) {
        Text(stringResource(Res.string.sync_live_existing))
        AppButton(stringResource(Res.string.owned_keyrings_refresh), enabled = !state.busy && !registrationBusy && !list.loading,
            variant = AppButtonVariant.Secondary, onClick = owned::load)
        list.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        if (list.owner?.userId == state.owner) list.items.orEmpty().forEach { item ->
            AppButton(stringResource(Res.string.sync_live_choose, item.deviceId.toString()),
                enabled = !state.busy && !registrationBusy && !bleState.busy,
                variant = AppButtonVariant.Secondary, onClick = { selected = item.deviceId })
        }
    }
    if (selected != null && serial != null) AlertDialog(onDismissRequest = { selected = null },
        title = { Text(stringResource(Res.string.sync_live_title)) },
        text = { Text(stringResource(Res.string.sync_live_bind_confirm, serial, selected.toString())) },
        confirmButton = { TextButton(enabled = !state.busy && !registrationBusy && !bleState.busy,
            onClick = { val id = selected!!; selected = null; model.useExisting(id, serial) }) { Text(stringResource(Res.string.sync_live_open)) } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(Res.string.registration_live_cancel)) } })
}

/** Preview에서는 이 순수 함수에 상태만 넣는다. 가짜 Repository/서버 완료 상태는 연결하지 않는다. */
@Composable internal fun KeyringSyncProgressList(state: KeyringSyncState, retry: (Long) -> Unit, latest: (Long) -> Unit,
    showActions: Boolean = true) {
    if (state.recoveryFailed) Text(stringResource(Res.string.sync_live_storage), color = MaterialTheme.colorScheme.error)
    if (state.items.isEmpty()) Text(stringResource(Res.string.sync_live_empty))
    state.items.forEach { item ->
        Text(stringResource(Res.string.sync_live_device, item.deviceId.toString()), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(when (item.phase) {
            SyncPhase.WaitingStage -> Res.string.sync_live_stage
            SyncPhase.Issuing -> Res.string.sync_live_issuing
            SyncPhase.WaitingConnection -> Res.string.sync_live_connect
            SyncPhase.Sending -> Res.string.sync_live_sending
            SyncPhase.Stored -> Res.string.sync_live_stored
            SyncPhase.Confirming -> Res.string.sync_live_confirming
            SyncPhase.Confirmed -> Res.string.sync_live_confirmed
            SyncPhase.PolicyWaiting -> Res.string.sync_live_policy
            SyncPhase.Stale -> Res.string.sync_live_stale
            SyncPhase.Failed -> Res.string.sync_live_failed
        }))
        if (item.phase == SyncPhase.Confirmed) {
            Text(stringResource(Res.string.sync_live_physical))
            if (item.applied == false || item.timeReady == false) Text(stringResource(Res.string.sync_live_not_applied))
        }
        if (item.phase == SyncPhase.Failed) item.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        if (!showActions) return@forEach
        if (item.phase == SyncPhase.Stale) AppButton(stringResource(Res.string.sync_live_latest), enabled = !state.busy, onClick = { latest(item.deviceId) })
        else if (item.phase == SyncPhase.Confirmed) AppButton(stringResource(Res.string.sync_live_resend), enabled = !state.busy, onClick = { latest(item.deviceId) })
        else if (item.phase in setOf(SyncPhase.Failed, SyncPhase.PolicyWaiting, SyncPhase.WaitingConnection))
            AppButton(stringResource(Res.string.sync_live_retry), enabled = !state.busy, onClick = { retry(item.deviceId) })
    }
}
