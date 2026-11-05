package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.*
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import org.koin.compose.viewmodel.koinViewModel

/** 개발 앱에서 현재 소유 조회·미전송 미확인 차단·기존 실행 복구를 제공한다. 새 초기화는 아직 실행하지 않는다. */
@Composable fun ConnectedKeyringResetRoute(deviceId: Long, onBack: () -> Unit, onConnect: () -> Unit,
    model: KeyringResetViewModel = koinViewModel(), ble: BleProbeViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val link by ble.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(deviceId) {
        model.load(deviceId, link.connected)
        onPauseOrDispose { model.pause() }
    }
    ProfileBackHandler(!state.busy, onBack)
    KeyringResetScreen(state, model::accepted, onAction = { action -> when (action) {
        KeyringResetAction.Back, KeyringResetAction.Close -> onBack()
        KeyringResetAction.OpenConfirm -> model.confirm()
        KeyringResetAction.Cancel -> model.cancel()
        KeyringResetAction.CheckConnection -> onConnect()
        KeyringResetAction.CheckResult, KeyringResetAction.Retry -> model.recover(if (link.connected) BleKeyringResetPort(ble) else null)
        KeyringResetAction.Refresh -> model.load(deviceId, link.connected)
        // 개수 미확인 정책은 UI와 모델 양쪽에서 유지한다. 미지원 업로드/초기화를 흉내 내지 않는다.
        KeyringResetAction.StartReset, KeyringResetAction.UploadRecords -> Unit
    } })
}
