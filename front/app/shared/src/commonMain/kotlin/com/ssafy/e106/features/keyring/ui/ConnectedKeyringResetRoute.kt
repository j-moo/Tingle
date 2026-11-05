package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.*
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import org.koin.compose.viewmodel.koinViewModel

/** 개발 앱에서 별도 삭제 동의를 받은 초기화와 기존 실행 복구를 제공한다. 운영 메뉴는 계속 분리한다. */
@Composable fun ConnectedKeyringResetRoute(deviceId: Long, onBack: () -> Unit, onConnect: () -> Unit,
    model: KeyringResetViewModel = koinViewModel(), ble: BleProbeViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val link by ble.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(deviceId) {
        model.load(deviceId, link.connected && link.info != null, link.info?.serial)
        onPauseOrDispose { model.pause() }
    }
    // 화면을 떠나 연결을 바꾸거나 BLE가 끊기면 이전 기기에 대한 동의를 재사용하지 않는다.
    LaunchedEffect(link.connected, link.info?.serial) { model.connectionChanged(link.connected && link.info != null, link.info?.serial) }
    ProfileBackHandler(!state.busy, onBack)
    KeyringResetScreen(state, model::accepted, onAction = { action -> when (action) {
        KeyringResetAction.Back, KeyringResetAction.Close -> onBack()
        KeyringResetAction.OpenConfirm -> model.confirm()
        KeyringResetAction.Cancel -> model.cancel()
        KeyringResetAction.CheckConnection -> onConnect()
        KeyringResetAction.CheckResult, KeyringResetAction.Retry -> model.recover(if (link.connected) BleKeyringResetPort(ble) else null)
        KeyringResetAction.Refresh -> model.load(deviceId, link.connected && link.info != null, link.info?.serial)
        KeyringResetAction.StartReset -> if (link.connected) model.start(BleKeyringResetPort(ble))
        // 미지원 선업로드는 별도 동의로도 실행 가능해지지 않는다.
        KeyringResetAction.UploadRecords -> Unit
    } }, onRecordChoice = model::recordChoice, onRecordDeletionAccepted = model::recordDeletionAccepted)
}
