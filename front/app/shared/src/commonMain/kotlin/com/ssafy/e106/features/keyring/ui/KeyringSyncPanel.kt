package com.ssafy.e106.features.keyring.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.Role
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 설정은 연결되면 알아서 보내고, 실패만 하단 시트로 알린다. 이 화면이 READY를 만들어 내지 않는다.
 * [pickExisting] 이면(이미 등록된 키링을 연결함) 내 키링 목록과 보내는 상태를 보여 어떤 키링인지 고르게 한다.
 */
@Composable internal fun KeyringSyncPanel(model: KeyringSyncViewModel, ble: BleProbeViewModel,
    bleState: BleProbeState, registrationBusy: Boolean, owned: OwnedKeyringsViewModel,
    managedConnection: Boolean = false, pickExisting: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    val list by owned.state.collectAsStateWithLifecycle()
    val serial = bleState.info?.serial?.takeIf { bleState.connected }
    // 연결이 바뀌면 전송 경로도 제거한다. busy 변화에는 효과를 재실행하지 않아 자기 전송을 취소하지 않는다.
    // 서비스 호스트와 화면이 동시에 attach/detach하지 않도록 소유자를 하나로 제한한다.
    if (!managedConnection) DisposableEffect(serial, state.owner) {
        // 호스트 없이 이 화면만 쓸 때도 서비스 호스트와 동일한 형식 선택을 사용한다.
        if (serial != null) model.attachRequest(serial, { ble.syncIssuanceRequest(serial) }) { run -> ble.syncSettings(run, serial) }
        onDispose { model.detach() }
    }
    // 고르기 화면에서 목록이 비어 있으면(첫 진입 · 세션 갱신으로 초기화됨) 불러온다
    val needList = pickExisting && list.owner != null && list.items == null && list.error == null && !list.loading
    LaunchedEffect(needList) { if (needList) owned.load() }
    KeyringSyncPanelContent(state, bleState, list, registrationBusy, pickExisting, onRetry = model::retry, onLatest = model::latest,
        onRetryRecovery = model::retryRecovery, onRefreshOwned = owned::load, onUseExisting = model::useExisting)
}

/** 순수 화면(Preview 용). 동기화 · 내 키링 목록 · 연결 상태만 받아 그리고, 서버 · 기기 호출은 콜백으로 넘긴다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun KeyringSyncPanelContent(
    state: KeyringSyncState,
    bleState: BleProbeState,
    list: OwnedKeyringsState,
    registrationBusy: Boolean = false,
    pickExisting: Boolean = false,
    onRetry: (deviceId: Long) -> Unit = {},
    onLatest: (deviceId: Long) -> Unit = {},
    onRetryRecovery: () -> Unit = {},
    onRefreshOwned: () -> Unit = {},
    onUseExisting: (deviceId: Long, serial: String) -> Unit = { _, _ -> },
) {
    val serial = bleState.info?.serial?.takeIf { bleState.connected }
    // 사용자가 손대야 하는 실패. 진행 상황은 띄우지 않고(연결되면 알아서 보낸다), 실패는 하단 시트로 알린다
    // 지금 연결한 보드의 실패만 본다(다른 보드 기록은 여기서 다시 시도해도 풀리지 않는다)
    val stuck = state.items.filter { it.phase in setOf(SyncPhase.Failed, SyncPhase.Stale, SyncPhase.PolicyWaiting) &&
        (it.serial == null || it.serial == serial) }
    val failed = serial != null && (stuck.isNotEmpty() || state.recoveryFailed)
    // ✕로 닫은 실패 묶음. 같은 실패면 다시 띄우지 않고, 다른 기기 · 다른 단계로 실패하면 다시 띄운다
    val failKey: Set<Any> = stuck.map { it.deviceId to it.phase }.toSet() + setOfNotNull("recovery".takeIf { state.recoveryFailed })
    var closedFor by remember { mutableStateOf<Set<Any>?>(null) }
    val closed = closedFor == failKey
    // 다시 시도를 누른 뒤 결과가 나올 때까지 시트를 띄운 채 로딩을 보여준다. 성공하면 내려가고, 실패하면 그대로 남는다
    var retrying by remember { mutableStateOf(false) }
    val busy by rememberUpdatedState(state.busy)
    LaunchedEffect(retrying) {
        if (!retrying) return@LaunchedEffect
        // 화면에 "보내는 중"이 늦게 도착해도 바로 끝난 것으로 보지 않는다. 시작되지 않았으면(1초) 그대로 끝낸다
        withTimeoutOrNull(1_000) { snapshotFlow { busy }.first { it } }
        snapshotFlow { busy }.first { !it }
        retrying = false
    }
    val locked = retrying || state.busy
    // 실패한 키링을 모두 다시 보낸다. 구버전 설정(Stale)이 있으면 첫 키링을 새로 발급하고, 이어서 기록을 지워 나머지도 한 번에 다시 돈다
    // (발급 중이면 다시 돌기는 발급이 끝난 뒤로 미뤄진다)
    fun retryAll() { stuck.firstOrNull { it.phase == SyncPhase.Stale }?.let { onLatest(it.deviceId) }; onRetryRecovery() }
    val lockedNow by rememberUpdatedState(locked)
    LaunchedEffect(failed, retrying) { if (!failed && !retrying) closedFor = null }   // 실패가 풀리면 다음 실패에는 다시 띄운다
    if ((failed || retrying) && !closed) ModalBottomSheet(onDismissRequest = { if (!locked) closedFor = failKey },
        // 반만 열린 상태로 멈추지 않고 내용 높이(화면 70%)까지 한 번에 올라오게 한다. 보내는 중엔 끌어내려도 닫히지 않는다
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !lockedNow }), containerColor = AppColors.Background, dragHandle = null) {
        SyncFailSheetContent(locked, onRetry = { retrying = true; retryAll() }, onClose = { closedFor = failKey })
    }
    if (pickExisting && serial != null) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        // 지금 연결한 보드로 보내는 중 · 보낸 키링만 "보내는 중"으로 친다. 실패했거나 다른 보드용 기록이면
        // 맞추기 · 다시 불러오기를 남겨 이 보드로 다시 맞출 수 있게 한다
        val mine = state.items.filter { it !in stuck && it.serial == serial }
        OwnedKeyringPicker(list, serial, syncing = mine.map { it.deviceId }.toSet(),
            enabled = !state.busy && !registrationBusy && !bleState.busy, onRefreshOwned, onUseExisting)
        // 이 보드로 보내는 중 · 보냄만 바로 아래에 보여준다(실패는 하단 시트로, 다른 보드용 기록은 숨김)
        // 카드와 같은 "내 키링 N" 번호를 붙인다(목록에 없는 키링은 번호 없이)
        KeyringSyncProgressList(state.copy(items = mine, recoveryFailed = false), onRetry, onLatest,
            numbers = list.items.orEmpty().mapIndexed { i, item -> item.deviceId to i + 1 }.toMap())
    }
    // ✕로 닫은 뒤에도 같은 실패를 다시 시도할 길을 남긴다. 고르기 화면에서 키링 실패는 "선택한 키링으로 맞추기"가 그 역할
    if (failed && closed && (!pickExisting || stuck.isEmpty())) AppButton(stringResource(Res.string.sync_live_retry), Modifier.fillMaxWidth(),
        enabled = !locked, onClick = { closedFor = null; retrying = true; retryAll() })   // 다시 하면 결과 시트를 다시 띄운다
}

/** 설정 전송 실패 시트 안쪽. 화면 높이의 70%를 채우고 ✕로 닫는다 */
@Composable private fun SyncFailSheetContent(busy: Boolean = false, onRetry: () -> Unit = {}, onClose: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f).navigationBarsPadding().padding(horizontal = screenGutter())
        .padding(top = AppSpacing.Medium, bottom = AppSpacing.Large), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.align(Alignment.End).minimumInteractiveComponentSize().clickable(remember { MutableInteractionSource() }, indication = null, enabled = !busy,
            role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(Res.drawable.ic_close), stringResource(Res.string.sync_fail_close), tint = AppColors.TextPrimary)
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(Res.string.sync_fail_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(AppSpacing.Medium))
        Text(stringResource(Res.string.sync_fail_body), color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(AppSpacing.Section))
        AppButton(stringResource(Res.string.sync_live_retry), Modifier.fillMaxWidth(), loading = busy, onClick = onRetry)
        Spacer(Modifier.weight(1f))
    }
}

/** 이미 등록된 키링을 연결했을 때 내 키링 목록에서 어떤 키링인지 고른다. 서버 번호 대신 "내 키링 1, 2…"로 보여준다 */
@Composable private fun OwnedKeyringPicker(list: OwnedKeyringsState, serial: String, syncing: Set<Long>, enabled: Boolean,
    onRefresh: () -> Unit, onUseExisting: (deviceId: Long, serial: String) -> Unit) {
    // 누른 키링(deviceId). 목록 순서가 바뀌거나 화면이 돌아가도 그대로. 다른 키링의 동기화 기록으로 미리 고르지 않는다
    var tapped by rememberSaveable(serial) { mutableStateOf<Long?>(null) }
    val items = list.items.orEmpty()
    Text(stringResource(Res.string.sync_pick_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
    when {
        list.loading -> Text(stringResource(Res.string.sync_pick_loading), color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        list.error != null -> Text(list.error.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        list.items != null && items.isEmpty() -> Text(stringResource(Res.string.sync_pick_empty), color = AppColors.TextSecondary,
            textAlign = TextAlign.Center)
    }
    items.forEachIndexed { index, item ->
        // 누르면 고른 카드만 초록 테두리 · 체크. 확정은 아래 버튼으로
        KeyringCard(stringResource(Res.string.sync_live_device, (index + 1).toString()), selected = item.deviceId == tapped, enabled = enabled,
            onClick = { tapped = item.deviceId }) {
            Text(stringResource(Res.string.sync_pick_firmware, item.firmwareVersion), style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary)
        }
    }
    val chosen = items.firstOrNull { it.deviceId == tapped }
    // 고른 키링으로 이미 보내는 중(보냄)이면 확정 · 다시 불러오기 버튼은 숨기고 아래 진행 상태만 둔다
    if (chosen != null && chosen.deviceId in syncing) return
    if (items.isNotEmpty()) AppButton(stringResource(Res.string.sync_pick_confirm), Modifier.fillMaxWidth(),
        enabled = enabled && chosen != null, onClick = { chosen?.let { onUseExisting(it.deviceId, serial) } })
    if (!list.loading && (list.error != null || list.items != null)) AppButton(stringResource(Res.string.sync_pick_reload), Modifier.fillMaxWidth(),
        AppButtonVariant.Secondary, enabled = enabled, onClick = onRefresh)
}

/** Preview에서는 이 순수 함수에 상태만 넣는다. 가짜 Repository/서버 완료 상태는 연결하지 않는다. */
@Composable internal fun KeyringSyncProgressList(state: KeyringSyncState, retry: (Long) -> Unit, latest: (Long) -> Unit,
    numbers: Map<Long, Int>? = null) {
    val full = Modifier.fillMaxWidth()
    if (state.recoveryFailed) Text(stringResource(Res.string.sync_live_storage), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    state.items.forEachIndexed { index, item ->
        // [numbers] 가 있으면 그 번호(카드와 같은 "내 키링 N"), 없으면 키링이 여럿일 때만 순번을 붙인다(서버 번호는 보여주지 않는다)
        val number = if (numbers != null) numbers[item.deviceId] else (index + 1).takeIf { state.items.size > 1 }
        if (number != null) Text(stringResource(Res.string.sync_live_device, number.toString()),
            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
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
        }), textAlign = TextAlign.Center)
        if (item.phase == SyncPhase.Confirmed && (item.applied == false || item.timeReady == false))
            Text(stringResource(Res.string.sync_live_not_applied), color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        if (item.phase == SyncPhase.Failed) item.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (item.phase == SyncPhase.Stale) AppButton(stringResource(Res.string.sync_live_latest), full, enabled = !state.busy, onClick = { latest(item.deviceId) })
        else if (item.phase in setOf(SyncPhase.Failed, SyncPhase.PolicyWaiting, SyncPhase.WaitingConnection))
            AppButton(stringResource(Res.string.sync_live_retry), full, enabled = !state.busy, onClick = { retry(item.deviceId) })
    }
}
