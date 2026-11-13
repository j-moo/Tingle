package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.features.keyring.data.BleDeviceStatus
import com.ssafy.e106.features.keyring.data.KeyringOwnerAuthorizer
import com.ssafy.e106.features.keyring.data.OwnedKeyring
import com.ssafy.e106.features.keyring.data.KeyringSyncStore
import com.ssafy.e106.features.keyring.data.unsentCount
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** 실행 화면은 실제 API만 사용한다. 회전/복귀 시 다시 읽고 화면 이탈 시 민감 목록을 제거한다. */
@Composable fun ConnectedOwnedKeyringsRoute(session: Session.SignedIn, onBack: () -> Unit,
    model: OwnedKeyringsViewModel = koinViewModel(), onReset: (Long) -> Unit,
    // 서버 소유 해제 뒤에도 원 계정의 로컬 기록으로 중단된 초기화를 다시 찾는다
    recoveryLinks: (@Composable () -> Unit)? = null, onConnectForRecords: (() -> Unit)? = null,
    ble: BleProbeViewModel? = null, onRegister: (() -> Unit)? = null, onSync: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    // 진입과 복귀를 한 곳에서 처리해 첫 화면에서 GET 두 개가 동시에 시작되지 않게 한다.
    LifecycleResumeEffect(session, state.owner) {
        if (state.owner == session) model.load()
        onPauseOrDispose { model.leave() }
    }
    ProfileBackHandler(true, onBack)
    if (state.owner != session) return
    val connection = if (ble != null) observeOwnedKeyringConnection(session, state.items.orEmpty(), ble) else null
    OwnedKeyringsScreen(state, model::load, onBack, onReset, onConnectForRecords,
        connection?.deviceId, connection?.liveStatus, connection?.refresh, onRegister = onRegister, onSync = onSync,
        connectedFirmwareVersion = connection?.firmwareVersion) {
        if (recoveryLinks != null) recoveryLinks()
        else {
            // 목록이 아직 없으면(불러오는 중 · 실패) 중복 여부를 모르므로 띄우지 않는다
            val items = state.items
            if (items != null) KeyringResetRecoveryLinks(session.userId, onReset, skip = items.map { it.deviceId }.toSet())
        }
    }
}

internal data class OwnedKeyringConnection(
    val deviceId: Long?,
    val liveStatus: OwnedKeyringLiveStatus?,
    val refresh: (() -> Unit)?,
    val firmwareVersion: String?,
)

/** 홈과 관리 화면은 같은 BLE 연결의 소유 기기만 읽고, 동기화·기록 전송이 끝난 뒤 조회한다. */
@Composable internal fun observeOwnedKeyringConnection(
    session: Session.SignedIn, devices: List<OwnedKeyring>, ble: BleProbeViewModel,
): OwnedKeyringConnection {
    val link by ble.state.collectAsStateWithLifecycle()
    val transfer by keyringEncounterModel().state.collectAsStateWithLifecycle()
    val syncing by koinViewModel<KeyringSyncViewModel>().state.collectAsStateWithLifecycle()
    val store = koinInject<KeyringSyncStore>()
    val authorizer = koinInject<KeyringOwnerAuthorizer>()
    // 현재 연결에서 읽은 기기 정보가 서버 소유 목록의 일련번호와 일치할 때만 버전을 표시한다.
    val connectedInfo = link.info?.takeIf { link.connected && link.connectionGeneration != null }
    val device = connectedInfo?.let { info -> devices.singleOrNull { it.deviceSerial == info.serial } }
    val working = transfer == EncounterTransferState.Sending || syncing.busy
    val liveStatus = link.ownedStatus?.takeIf { !working && it.owner == session.userId &&
        it.deviceId == device?.deviceId && it.connectionGeneration == link.connectionGeneration }
    val refresh: (() -> Unit)? = if (device != null && !working && liveStatus?.loading != true) {
        { ble.readOwnedStatus(session.userId, device, store, authorizer) }
    } else null
    // 입장·재연결·동기화/전송 종료 시 현 기기 상태를 다시 읽는다.
    LaunchedEffect(session.userId, device?.deviceId, link.connectionGeneration, working) {
        if (device != null && !working) ble.readOwnedStatus(session.userId, device, store, authorizer)
    }
    return OwnedKeyringConnection(device?.deviceId, liveStatus, refresh, connectedInfo?.firmware?.takeIf { device != null })
}

/**
 * 순수 표시 함수(Preview 용). 키링 고르기 화면과 같은 카드("내 키링 N" · 펌웨어)에 오른쪽 "초기화" 버튼을 둔다. 서버 번호는 보여주지 않는다.
 * 목록은 들어올 때 · 돌아올 때 자동으로 불러오고, 당겨서 새로고침할 수 있다. 불러오는 중은 카드 자리 뼈대, 실패 · 비어 있음은 상태 화면(작은 "다시 시도").
 */
@Composable fun OwnedKeyringsScreen(state: OwnedKeyringsState, onRefresh: () -> Unit, onBack: () -> Unit,
    onReset: (Long) -> Unit, onConnectForRecords: (() -> Unit)? = null, connectedDeviceId: Long? = null,
    liveStatus: OwnedKeyringLiveStatus? = null, onRefreshStatus: (() -> Unit)? = null,
    onRegister: (() -> Unit)? = null,
    onSync: (() -> Unit)? = null,
    connectedFirmwareVersion: String? = null,
    recoveryLinks: @Composable () -> Unit = {}) {
    // 당겨서 새로고침한 동안만 고리를 돌린다(처음 들어올 때 · 돌아올 때는 뼈대만 보인다)
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) pulled = false }
    val items = state.items
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BackArrowBar(onBack)
            PullDownRefreshBox(pulled && state.loading, { pulled = true; onRefresh() }, Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState())
                    .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium, Alignment.CenterVertically)) {
                    // 실패 · 비어 있음은 상태 화면이 제목을 대신한다
                    if (state.error == null && items?.isEmpty() != true) Text(stringResource(Res.string.owned_keyrings_title),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() })
                    when {
                        // VM 은 다시 읽는 동안 예전 목록을 지운다(지난 소유 정보를 현재처럼 보이지 않게). 그래서 새로고침 중에도 뼈대다
                        state.loading || (items == null && state.error == null) -> SkeletonGroup(spacing = AppSpacing.Medium) {
                            repeat(2) { SkeletonBone(height = 72.dp, shape = RoundedCornerShape(16.dp)) }
                        }
                        state.error != null -> StateView(state.error.stateKind(), state.error.stateTitle(), onRetry = onRefresh)
                        items.isNullOrEmpty() -> StateView(StateKind.Empty, stringResource(Res.string.owned_keyrings_empty), onRetry = onRefresh)
                        else -> items.orEmpty().forEachIndexed { index, item ->
                            val title = stringResource(Res.string.sync_live_device, (index + 1).toString())
                            // TalkBack 이 어느 키링의 초기화인지 읽게 한다
                            val label = stringResource(Res.string.owned_keyrings_reset_for, title)
                            KeyringCard(title, selected = false, onClick = null,
                                trailing = { AppButton(stringResource(Res.string.owned_keyrings_reset), Modifier.semantics { contentDescription = label },
                                    variant = AppButtonVariant.Secondary, onClick = { onReset(item.deviceId) }) }) {
                                // 서버의 초기 등록 버전은 이후 업데이트를 반영하지 않는다. 끊긴 기기는 현재 버전을 확인할 수 없다.
                                val firmware = connectedFirmwareVersion?.takeIf { item.deviceId == connectedDeviceId }
                                Text(
                                    firmware?.let { stringResource(Res.string.sync_pick_firmware, it) }
                                        ?: stringResource(Res.string.owned_keyrings_firmware_unknown),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextSecondary,
                                )
                                if (item.deviceId == connectedDeviceId) {
                                    OwnedKeyringMetrics(liveStatus?.takeIf { it.deviceId == item.deviceId })
                                } else {
                                    KeyringStatusText(stringResource(Res.string.owned_keyrings_status_disconnected))
                                }
                            }
                        }
                    }
                    if (connectedDeviceId != null) {
                        AppButton(stringResource(Res.string.owned_keyrings_status_refresh), Modifier.fillMaxWidth(),
                            variant = AppButtonVariant.Secondary, enabled = onRefreshStatus != null,
                            onClick = { onRefreshStatus?.invoke() })
                    }
                    // 초기화로 마지막 키링이 사라져도 이 목록에서 다시 등록을 시작할 수 있다.
                    if (!state.loading && state.error == null && state.items != null && onRegister != null) {
                        AppButton(stringResource(Res.string.settings_register), Modifier.fillMaxWidth(),
                            onClick = onRegister)
                    }
                    // 실제 연결은 연결 화면에서 시작한다. 연결된 키링의 기록은 자동으로 처리한다.
                    if (!state.loading && state.error == null && !state.items.isNullOrEmpty() && onSync != null) {
                        AppButton(stringResource(Res.string.sync_manual_open), Modifier.fillMaxWidth(), onClick = onSync)
                    }
                    if (!state.loading && state.error == null && !state.items.isNullOrEmpty() && onConnectForRecords != null) {
                        AppButton(stringResource(Res.string.keyring_encounter_connect), Modifier.fillMaxWidth(),
                            variant = AppButtonVariant.Secondary, onClick = onConnectForRecords)
                    }
                    // 서버 소유 해제 후에도 원 계정의 로컬 실행 기록으로 초기화 결과를 다시 찾을 수 있다
                    recoveryLinks()
                }
            }
            }
        }
    }
}

@Composable private fun OwnedKeyringMetrics(liveStatus: OwnedKeyringLiveStatus?) {
    when {
        liveStatus == null || liveStatus.loading -> KeyringStatusText(stringResource(Res.string.owned_keyrings_status_loading))
        liveStatus.failed -> KeyringStatusText(stringResource(Res.string.owned_keyrings_status_failed))
        else -> {
            val metrics = liveStatus.metrics
            val pending = metrics?.unsentCount
            val pendingText = if (pending == null) stringResource(Res.string.owned_keyrings_status_unknown)
            else stringResource(Res.string.owned_keyrings_records_count, pending.toString())
            KeyringStatusText(stringResource(Res.string.owned_keyrings_battery, keyringBatteryText(metrics)))
            KeyringStatusText(stringResource(Res.string.owned_keyrings_records, pendingText))
        }
    }
}

/** 잔량을 읽지 못하거나 배터리가 없으면 미확인으로 표시하고, 전압 추정값에는 '약'을 붙인다. */
@Composable internal fun keyringBatteryText(metrics: BleDeviceStatus?): String {
    val battery = metrics?.batteryPercent?.takeUnless { metrics.ownerFlow.batteryPresence == "ABSENT" }
        ?: return stringResource(Res.string.owned_keyrings_status_unknown)
    val format = if (metrics.ownerFlow.batteryPercentSource == "VOLTAGE_ESTIMATE")
        Res.string.owned_keyrings_battery_estimate else Res.string.owned_keyrings_battery_percent
    return stringResource(format, battery.toString())
}

@Composable private fun KeyringStatusText(value: String) {
    Text(value, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
}
