package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.*
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import org.koin.compose.viewmodel.koinViewModel

/** 키링 초기화(운영 · 개발 앱 같음). 시작 → 진행 중 → 완료 · 실패만 보여주고, 중간에 끊긴 작업은 자동으로 마저 한다. */
@Composable fun ConnectedKeyringResetRoute(deviceId: Long, onBack: () -> Unit, onConnect: () -> Unit,
    model: KeyringResetViewModel = koinViewModel(), ble: BleProbeViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val link by ble.state.collectAsStateWithLifecycle()
    // 중간에 끊긴 초기화는 결과 확인 화면 없이 연결 상태마다 한 번 자동으로 마저 한다. 그래도 안 되면 실패 + 다시 시도
    // 화면을 떠났다 오면(pause 가 작업을 취소한다) 다시 한 번 자동으로 시도한다
    var autoFor by remember(deviceId) { mutableStateOf<Any?>(null) }
    LifecycleResumeEffect(deviceId) {
        model.load(deviceId, link.connected && link.info != null, link.info?.serial)
        onPauseOrDispose { model.pause(); autoFor = null }
    }
    // 화면을 떠나 연결을 바꾸거나 BLE가 끊기면 이전 기기에 대한 동의를 재사용하지 않는다.
    LaunchedEffect(link.connected, link.info?.serial) { model.connectionChanged(link.connected && link.info != null, link.info?.serial) }
    // 키링이 연결돼 있지 않으면 따로 안내하지 않고 키링 연결 화면으로 보낸다(연결되면 호스트가 다시 이 화면으로 돌려보낸다)
    // 시작 전(확인 화면)만 바로 보낸다. 진행하다 실패하고 끊긴 경우는 실패를 먼저 보여준다(버튼으로 연결)
    val needConnection = state.displayStage == KeyringResetStage.Disconnected && state.stage != KeyringResetStage.Failed
    LaunchedEffect(needConnection) { if (needConnection) onConnect() }
    val connection = link.connected to link.info?.serial
    LaunchedEffect(state.displayStage, connection) {
        if (state.displayStage in listOf(KeyringResetStage.Partial, KeyringResetStage.Unknown) && autoFor != connection) {
            autoFor = connection; model.recover(if (link.connected) BleKeyringResetPort(ble) else null)
        }
    }
    ProfileBackHandler(!state.busy, onBack)
    KeyringResetScreen(state, model::accepted, onAction = { action -> when (action) {
        KeyringResetAction.Back, KeyringResetAction.Close -> onBack()
        KeyringResetAction.Cancel -> { model.cancel(); onBack() }
        KeyringResetAction.CheckConnection -> onConnect()
        // 키링 증명이 이미 있는(Partial) 작업은 서버만 마저 하면 된다. 그 밖에 키링이 필요하면 연결 화면으로 보낸다
        KeyringResetAction.CheckResult, KeyringResetAction.Retry -> when {
            link.connected && link.info != null -> model.recover(BleKeyringResetPort(ble))
            state.displayStage == KeyringResetStage.Partial -> model.recover(null)
            else -> onConnect()
        }
        KeyringResetAction.Refresh -> model.load(deviceId, link.connected && link.info != null, link.info?.serial)
        KeyringResetAction.StartReset -> if (link.connected) model.start(BleKeyringResetPort(ble))
        // 미지원 선업로드는 별도 동의로도 실행 가능해지지 않는다.
        KeyringResetAction.UploadRecords -> Unit
    } }, onRecordChoice = model::recordChoice, onRecordDeletionAccepted = model::recordDeletionAccepted)
}
