package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.ble.BleAvailability
import com.ssafy.e106.core.ble.rememberBlePermission
import org.koin.compose.viewmodel.koinViewModel

/**
 * 로그인한 서비스 전체가 연결을 소유한다. BLE 화면 → 설정 화면 이동으로 전송을 끊지 않는다.
 * 앱 중지·회전·로그아웃에서는 닫고, 동기화 모델의 내구 ACK 기록만 남긴다.
 * 운영 앱의 백그라운드 서비스/다중 기기 연결을 대신하는 구현은 아니다.
 */
@Composable internal fun KeyringForegroundSync(ble: BleProbeViewModel = koinViewModel(),
    sync: KeyringSyncViewModel = koinViewModel()) {
    val permission = rememberBlePermission()
    val link by ble.state.collectAsStateWithLifecycle()
    val state by sync.state.collectAsStateWithLifecycle()
    var foreground by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { foreground = true; sync.resume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { foreground = false; sync.pause(); ble.setForeground(false) }
    DisposableEffect(ble, sync) { onDispose { sync.pause(); ble.setForeground(false) } }
    val allowed = foreground && permission.availability == BleAvailability.Ready
    LaunchedEffect(allowed) { ble.setForeground(allowed) }
    val serial = link.info?.serial?.takeIf { allowed && link.connected }
    // busy 변화는 키에 넣지 않는다. 자기 전송이 시작됐다는 이유로 연결을 분리하면 안 된다.
    DisposableEffect(serial, state.owner) {
        if (serial != null) sync.attach(serial, { ble.syncChallenge(serial) }) { run -> ble.syncSettings(run, serial) }
        onDispose { sync.detach() }
    }
}
