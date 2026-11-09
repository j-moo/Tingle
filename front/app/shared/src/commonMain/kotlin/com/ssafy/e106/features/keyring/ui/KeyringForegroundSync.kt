package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.ble.BleAvailability
import com.ssafy.e106.core.ble.rememberBlePermission
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import com.ssafy.e106.features.keyring.data.KeyringResetStore
import kotlinx.coroutines.CancellationException

/**
 * 로그인한 서비스 전체가 연결을 소유한다. BLE 화면 → 설정 화면 이동으로 전송을 끊지 않는다.
 * 앱 중지·회전·로그아웃에서는 닫고, 동기화 모델의 내구 ACK 기록만 남긴다.
 * 운영 앱의 백그라운드 서비스/다중 기기 연결을 대신하는 구현은 아니다.
 */
@Composable internal fun KeyringForegroundSync(ble: BleProbeViewModel = koinViewModel(),
    sync: KeyringSyncViewModel = koinViewModel(), suspendSync: Boolean = false,
    resets: KeyringResetStore = koinInject()) {
    val permission = rememberBlePermission()
    val link by ble.state.collectAsStateWithLifecycle()
    val state by sync.state.collectAsStateWithLifecycle()
    var foreground by remember { mutableStateOf(false) }
    val revision by resets.revision.collectAsStateWithLifecycle()
    var resetBlocked by remember { mutableStateOf(true) }
    var checkedOwner by remember { mutableStateOf<Long?>(null) }
    var checkedRevision by remember { mutableStateOf(-1L) }
    // 재시작 직후 내구 초기화 기록을 확인하기 전에는 설정을 자동 전송하지 않는다. 손상도 허용으로 바꾸지 않는다.
    LaunchedEffect(state.owner, revision, foreground) {
        resetBlocked = true
        val owner = state.owner
        if (owner != null && foreground) {
            try {
                resetBlocked = resets.read(owner).any { it.run?.state != "COMPLETED" }
                checkedOwner = owner; checkedRevision = revision
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { resetBlocked = true }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { foreground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { foreground = false; sync.pause(); ble.setForeground(false) }
    DisposableEffect(ble, sync) { onDispose { sync.pause(); ble.setForeground(false) } }
    val allowed = foreground && permission.availability == BleAvailability.Ready
    LaunchedEffect(allowed) { ble.setForeground(allowed) }
    // 계정/저장 기록이 바뀐 첫 프레임에도 이전 계정의 허용 결과를 재사용하지 않는다.
    val canSync = allowed && !suspendSync && !resetBlocked && checkedOwner == state.owner && checkedRevision == revision
    LaunchedEffect(canSync) { if (canSync) sync.resume() else sync.pause() }
    val serial = link.info?.serial?.takeIf { canSync && link.connected }
    // busy 변화는 키에 넣지 않는다. 자기 전송이 시작됐다는 이유로 연결을 분리하면 안 된다.
    DisposableEffect(serial, state.owner) {
        // 최초 발급에서만 기기 지원에 맞는 본문을 생성하며 이후 HTTP 재시도는 내구 본문을 사용한다.
        if (serial != null) sync.attachRequest(serial, { ble.syncIssuanceRequest(serial) },
            getApplied = { ble.readSettingsApplied(it, serial) }) { run -> ble.syncSettings(run, serial) }
        onDispose { sync.detach() }
    }
}
