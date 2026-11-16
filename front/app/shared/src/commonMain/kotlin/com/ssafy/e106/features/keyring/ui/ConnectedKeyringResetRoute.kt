package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import com.ssafy.e106.core.ble.BleFailure
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.*
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import org.koin.compose.viewmodel.koinViewModel

/** 키링 초기화(운영 · 개발 앱 같음). 시작 → 진행 중 → 완료 · 실패만 보여주고, 중간에 끊긴 작업은 자동으로 마저 한다. */
@Composable fun ConnectedKeyringResetRoute(deviceId: Long, onBack: () -> Unit, onConnect: () -> Unit,
    model: KeyringResetViewModel = koinViewModel(), ble: BleProbeViewModel = keyringBleModel()) {
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
    // 결과 대기: 같은 일련번호의 키링에 다시 연결되면 결과(완료 증명)를 읽고, 아니면 그 키링을 다시 찾는다.
    // 일반 연결 화면으로 보내지 않는다(근처 다른 키링을 차례로 붙였다 끊지 않게). 키링이 아직 지우는 중이면 잠시 뒤 다시 읽는다
    val serial = state.resumeSerial
    val waiting = state.displayStage == KeyringResetStage.Waiting
    // 기다려도 낫지 않는 연결 실패(블루투스 꺼짐 · 권한 없음 · 다른 계정 키링)는 바로 원인과 "다시 시도"를 보여 준다
    val blockedMessage = when (link.error) {
        BleFailure.BluetoothOff, BleFailure.LocationOff -> stringResource(Res.string.ble_bluetooth_off)
        BleFailure.PermissionRequired -> stringResource(Res.string.ble_permission)
        BleFailure.NotOwned -> stringResource(Res.string.ble_not_owned)
        BleFailure.Unsupported -> stringResource(Res.string.ble_failed, BleFailure.Unsupported.name)
        else -> null
    }
    // 이번 대기에서 직접 다시 찾아본 뒤의 오류만 원인으로 본다. 대기 전부터 남아 있던 오류(다른 화면 · 지난 시도)로 바로 멈추지 않는다
    var attempted by remember(waiting) { mutableStateOf(false) }
    // 이 화면이 재연결을 시작한 적이 있는지(대기를 멈춘 뒤에도 그 시도가 남아 있을 수 있다)
    var startedReconnect by remember(deviceId) { mutableStateOf(false) }
    val currentLink by rememberUpdatedState(link)
    // 대기를 멈출 때 지금 연결 상태를 "이미 자동 복구함"으로 표시한다. 그래야 실패 화면이 곧바로 다시 대기로 돌아가지 않는다
    fun stopWaiting(message: String?) { autoFor = currentLink.connected to currentLink.info?.serial; model.stopWaiting(message) }
    LaunchedEffect(waiting, attempted, blockedMessage) {
        if (waiting && attempted && blockedMessage != null && !link.busy && !link.scanning) stopWaiting(blockedMessage)
    }
    // 정해진 시간 안에 같은 키링을 다시 찾지 못하면 실패로 보여 준다(무한 대기 · 반복 탐색을 하지 않는다)
    LaunchedEffect(waiting) { if (waiting) { delay(WAIT_LIMIT_MS); stopWaiting(null) } }
    LaunchedEffect(state.displayStage, state.waitTick, serial, connection, link.busy, link.scanning) {
        if (!waiting || serial == null || link.busy || link.scanning || (attempted && blockedMessage != null)) return@LaunchedEffect
        delay(WAIT_RETRY_MS)
        if (link.connected && link.info?.serial == serial) model.recover(BleKeyringResetPort(ble))
        else { attempted = true; startedReconnect = true; ble.reconnectForReset(serial) }   // 새 시도는 남아 있던 오류를 지운다
    }
    // 이 화면이 시작한 재연결이 남은 채 화면을 나가면 멈춘다(대기를 시간 초과로 멈춘 뒤라도. 다른 화면에서 키링에 몰래 붙지 않게)
    val currentStarted by rememberUpdatedState(startedReconnect)
    DisposableEffect(Unit) { onDispose {
        if (currentStarted && !currentLink.connected && (currentLink.scanning || currentLink.busy)) ble.leave()
    } }
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
        KeyringResetAction.CheckResult, KeyringResetAction.Retry -> {
            // 키링 증명이 이미 있으면 서버만 마저 한다. 실행이 있으면 그 키링(일련번호)만 다시 찾아 결과부터 읽는다
            // (지금 다른 키링에 연결돼 있어도 그 키링으로 조회하지 않는다)
            if (state.displayStage == KeyringResetStage.Partial) model.recover(null)
            else {
                model.waitForDevice()
                // 메모리에 실행이 없으면(대기를 멈춘 뒤 · 시작하지 못함) 저장소에서 다시 판정한다: 증명 있으면 서버만, 실행 있으면 대기, 없으면 확인 화면
                // 다시 판정한 결과가 증명 있음(Partial)이면 서버 확인까지 자동으로 이어 가도록 자동 복구 표시를 풀어 둔다
                if (model.state.value.stage != KeyringResetStage.Waiting) {
                    autoFor = null; model.recover(if (link.connected) BleKeyringResetPort(ble) else null)
                }
            }
        }
        KeyringResetAction.Refresh -> model.load(deviceId, link.connected && link.info != null, link.info?.serial)
        KeyringResetAction.StartReset -> if (link.connected) model.start(BleKeyringResetPort(ble))
        // 미지원 선업로드는 별도 동의로도 실행 가능해지지 않는다.
        KeyringResetAction.UploadRecords -> Unit
    } }, onRecordChoice = model::recordChoice, onRecordDeletionAccepted = model::recordDeletionAccepted)
}

/** 결과 대기 중 다시 조회 · 다시 찾기 사이 간격. 키링이 지우고 다시 켜지는 데 몇 초 걸린다 */
private const val WAIT_RETRY_MS = 1_500L
/** 결과 대기 최대 시간. 넘으면 실패 + "다시 시도"(같은 실행을 다시 기다린다) */
private const val WAIT_LIMIT_MS = 90_000L
