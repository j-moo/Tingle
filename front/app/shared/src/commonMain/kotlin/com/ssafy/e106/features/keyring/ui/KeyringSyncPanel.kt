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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ble.BleAvailability
import com.ssafy.e106.core.ble.BleFailure
import com.ssafy.e106.core.ble.rememberBlePermission
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.features.keyring.data.OwnedKeyring
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 목록부터 보여주고 선택한 실물만 연결한다. 화면이 바뀌어도 계정의 목록을 새로 확인한다. */
@Composable internal fun ConnectedOwnedKeyringSyncRoute(session: Session.SignedIn,
    readAliases: suspend (Long, List<OwnedKeyring>) -> Map<Long, String>, onBack: () -> Unit,
    owned: OwnedKeyringsViewModel = koinViewModel(), ble: BleProbeViewModel = keyringBleModel(),
    sync: KeyringSyncViewModel = koinViewModel()) {
    val list by owned.state.collectAsStateWithLifecycle()
    val link by ble.state.collectAsStateWithLifecycle()
    val state by sync.state.collectAsStateWithLifecycle()
    val availability = rememberBlePermission().availability
    var selectedId by rememberSaveable(session.userId) { mutableStateOf<Long?>(null) }
    val items = list.items.takeIf { list.owner == session }
    val selected = items?.singleOrNull { it.deviceId == selectedId }
    val aliases by produceState(emptyMap<Long, String>(), session.userId, items) {
        value = readAliases(session.userId, items.orEmpty())
    }
    LifecycleResumeEffect(session, list.owner) {
        if (list.owner == session) owned.load()
        onPauseOrDispose { owned.leave() }
    }
    DisposableEffect(ble) {
        onDispose {
            // 선택 화면에서 시작한 탐색은 이탈 시 취소한다. 연결된 기기는 전경 호스트가 관리한다.
            if (selectedId != null && !ble.state.value.connected) ble.leave()
        }
    }
    ProfileBackHandler(true, onBack)
    OwnedKeyringSyncScreen(list.takeIf { it.owner == session } ?: OwnedKeyringsState(loading = true),
        selected?.deviceId, aliases, availability, link, state.busy, onBack = onBack, onRefresh = owned::load,
        onSelect = { item ->
            selectedId = item.deviceId
            if (availability == BleAvailability.Ready) ble.connectOwned(item)
        }) {
        KeyringAutoConnectionControl(session, selected, link)
        if (selected != null && link.connected && link.info?.serial == selected.deviceSerial) {
            KeyringSyncPanel(sync, ble, link, registrationBusy = false, owned = owned,
                managedConnection = true, pickExisting = true, manualSettings = true)
        }
    }
}

/** 실물 탐색 후보 대신 서버 소유 목록을 그린다. 재시도도 같은 카드에서 시작한다. */
@Composable internal fun OwnedKeyringSyncScreen(list: OwnedKeyringsState, selectedId: Long?, aliases: Map<Long, String>,
    availability: BleAvailability, link: BleProbeState, syncing: Boolean, onBack: () -> Unit, onRefresh: () -> Unit,
    onSelect: (OwnedKeyring) -> Unit, result: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BackArrowBar(onBack)
            // 키링 연결 화면처럼 제목 · 목록 · 버튼 묶음을 남은 공간 가운데에 둔다(넘치면 스크롤)
            BoxWithConstraints(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)
                .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                Spacer(Modifier.weight(1f))
                Text(stringResource(Res.string.sync_pick_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
                when {
                    // 불러오는 중: 카드 자리만 회색으로. 실패: 짧은 제목 한 줄 + 작은 "다시 시도"(다른 화면과 같은 상태 표시)
                    list.loading || (list.items == null && list.error == null) -> SkeletonGroup(spacing = AppSpacing.Medium) {
                        repeat(2) { SkeletonBone(height = 72.dp, shape = RoundedCornerShape(16.dp)) }
                    }
                    list.error != null -> StateView(list.error.stateKind(), list.error.stateTitle(), onRetry = onRefresh)
                    list.items.isNullOrEmpty() -> Text(stringResource(Res.string.owned_keyrings_empty), style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary, textAlign = TextAlign.Center)
                    else -> list.items.orEmpty().forEachIndexed { index, item ->
                        KeyringCard(aliases[item.deviceId]?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.sync_live_device, (index + 1).toString()),
                            selected = item.deviceId == selectedId,
                            enabled = !syncing && !link.busy && !link.scanning && !item.deviceSerial.isNullOrBlank(),
                            onClick = { onSelect(item) }) {
                            Text(if (item.deviceSerial.isNullOrBlank()) stringResource(Res.string.sync_manual_missing_serial)
                                else stringResource(Res.string.sync_manual_device_number, item.deviceId.toString()),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                    }
                }
                if (selectedId != null) {
                    val hint = when {
                        availability != BleAvailability.Ready -> when (availability) {
                            BleAvailability.Unsupported -> Res.string.ble_unsupported
                            BleAvailability.PermissionRequired -> Res.string.ble_permission
                            BleAvailability.LocationOff -> Res.string.ble_location_off
                            else -> Res.string.ble_bluetooth_off
                        }
                        link.scanning -> Res.string.sync_manual_searching
                        link.busy && !link.connected -> Res.string.probe_connecting
                        link.error == BleFailure.NotFound -> Res.string.sync_manual_not_found
                        link.error == BleFailure.NotOwned -> Res.string.ble_not_owned
                        link.error != null -> Res.string.sync_manual_connection_failed
                        else -> null
                    }
                    if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary, textAlign = TextAlign.Center)
                }
                result()
                Spacer(Modifier.weight(1f))
            }
            }
        }
    }
}

/**
 * 설정은 연결되면 알아서 보내고, 실패만 하단 시트로 알린다. 이 화면이 READY를 만들어 내지 않는다.
 * [pickExisting] 이면(이미 등록된 키링을 연결함) 내 키링 목록과 보내는 상태를 보여 어떤 키링인지 고르게 한다.
 */
@Composable internal fun KeyringSyncPanel(model: KeyringSyncViewModel, ble: BleProbeViewModel,
    bleState: BleProbeState, registrationBusy: Boolean, owned: OwnedKeyringsViewModel,
    managedConnection: Boolean = false, pickExisting: Boolean = false, manualSettings: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    val list by owned.state.collectAsStateWithLifecycle()
    val serial = bleState.info?.serial?.takeIf { bleState.connected }
    // 연결이 바뀌면 전송 경로도 제거한다. busy 변화에는 효과를 재실행하지 않아 자기 전송을 취소하지 않는다.
    // 서비스 호스트와 화면이 동시에 attach/detach하지 않도록 소유자를 하나로 제한한다.
    if (!managedConnection) DisposableEffect(serial, state.owner) {
        // 호스트 없이 이 화면만 쓸 때도 서비스 호스트와 동일한 형식 선택을 사용한다.
        if (serial != null) model.attachRequest(serial, { ble.syncIssuanceRequest(serial) },
            getApplied = { ble.readSettingsApplied(it, serial) },
            getDeviceInfo = { ble.readSyncDeviceInfo(serial) }) { run -> ble.syncSettings(run, serial) }
        onDispose { model.detach() }
    }
    // 고르기 화면에서 목록이 비어 있으면(첫 진입 · 세션 갱신으로 초기화됨) 불러온다
    val needList = pickExisting && list.owner != null && list.items == null && list.error == null && !list.loading
    LaunchedEffect(needList) { if (needList) owned.load() }
    KeyringSyncPanelContent(state, bleState, list, registrationBusy, pickExisting, onRetry = model::retry, onLatest = model::latest,
        onRetryRecovery = model::retryRecovery, onRefreshOwned = owned::load,
        onUseExisting = { deviceId, serial -> model.useExisting(deviceId, serial) },
        manualSettings = manualSettings,
        onCheckSettings = { deviceId, serial -> model.useExisting(deviceId, serial, checkLatest = true) })
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
    manualSettings: Boolean = false,
    onCheckSettings: (deviceId: Long, serial: String) -> Unit = { _, _ -> },
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
            confirmValueChange = { it != SheetValue.Hidden || !lockedNow }), containerColor = AppColors.Sheet, dragHandle = null) {
        SyncFailSheetContent(locked, onRetry = { retrying = true; retryAll() }, onClose = { closedFor = failKey })
    }
    if (pickExisting && serial != null) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        // 지금 연결한 보드로 보내는 중 · 보낸 키링만 "보내는 중"으로 친다. 실패했거나 다른 보드용 기록이면
        // 맞추기 · 다시 불러오기를 남겨 이 보드로 다시 맞출 수 있게 한다
        val mine = state.items.filter { it !in stuck && it.serial == serial }
        if (manualSettings) {
            // 연결한 일련번호에 대응하는 소유 기기 하나만 동기화한다.
            val target = list.items?.singleOrNull { it.deviceSerial == serial }
            if (target != null) AppButton(stringResource(Res.string.sync_manual_check), Modifier.fillMaxWidth(),
                enabled = !locked && !bleState.busy && !registrationBusy, onClick = { onCheckSettings(target.deviceId, serial) })
            else OwnedKeyringPicker(list, serial, emptySet(),
                !locked && !bleState.busy && !registrationBusy, onRefreshOwned, onCheckSettings)
        } else OwnedKeyringPicker(list, serial, syncing = mine.map { it.deviceId }.toSet(),
            enabled = !state.busy && !registrationBusy && !bleState.busy, onRefreshOwned, onUseExisting)
        // 이 보드로 보내는 중 · 보냄만 바로 아래에 보여준다(실패는 하단 시트로, 다른 보드용 기록은 숨김)
        // 카드와 같은 "내 키링 N" 번호를 붙인다(목록에 없는 키링은 번호 없이)
        KeyringSyncProgressList(state.copy(items = mine, recoveryFailed = false), onRetry, onLatest,
            numbers = list.items.orEmpty().mapIndexed { i, item -> item.deviceId to i + 1 }.toMap())
    }
    if (!pickExisting) {
        // 새로 등록한 키링이나 연결이 끊긴 경우에도 저장 이후의 적용 확인을 가입 화면에서 이어간다.
        val application = state.items.filter { it.phase in setOf(SyncPhase.ApplyChecking, SyncPhase.ApplyWaitingConnection,
            SyncPhase.ApplyConfirming, SyncPhase.ApplyRetry, SyncPhase.ApplyFailed, SyncPhase.ApplyFirmwareRequired,
            SyncPhase.ApplyConfirmed) && (serial == null || it.serial == serial) }
        if (application.isNotEmpty()) KeyringSyncProgressList(
            state.copy(items = application, recoveryFailed = false), onRetry, onLatest)
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
        // 불러오는 중(아직 안 불러온 첫 화면 포함): 문구 대신 카드 자리만 회색으로
        list.loading || (list.items == null && list.error == null) -> SkeletonGroup(spacing = AppSpacing.Medium) {
            repeat(2) { SkeletonBone(height = 72.dp, shape = RoundedCornerShape(16.dp)) }
        }
        // 실패 · 비어 있음: 짧은 제목 한 줄 + 작은 "다시 시도"(아래 큰 다시 불러오기 버튼을 대신한다)
        list.error != null -> StateView(list.error.stateKind(), list.error.stateTitle(), onRetry = onRefresh, retryEnabled = enabled)
        items.isEmpty() -> {
            StateView(StateKind.Empty, stringResource(Res.string.sync_pick_empty), onRetry = onRefresh, retryEnabled = enabled)
            // 남의 키링일 수 있다는 안내는 다음 행동에 필요해 남긴다
            Text(stringResource(Res.string.sync_pick_empty_help), color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        }
    }
    items.forEachIndexed { index, item ->
        // 누르면 고른 카드만 초록 테두리 · 체크. 확정은 아래 버튼으로
        // 다른 소유 기기의 목록 항목과 지금 연결한 실물을 서로 대응시키지 않는다.
        KeyringCard(stringResource(Res.string.sync_live_device, (index + 1).toString()), selected = item.deviceId == tapped,
            enabled = enabled && item.deviceSerial == serial,
            onClick = { tapped = item.deviceId }) {
            Text(stringResource(Res.string.sync_pick_firmware, item.firmwareVersion), style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary)
        }
    }
    // 받아 둔 목록에 지금 연결한 키링이 없으면 모든 카드가 꺼진다. 목록이 낡았을 수 있어 다시 불러올 길을 남긴다
    if (items.isNotEmpty() && items.none { it.deviceSerial == serial } && !list.loading)
        TextAction(stringResource(Res.string.state_retry), onRefresh, enabled = enabled)
    val chosen = items.firstOrNull { it.deviceId == tapped && it.deviceSerial == serial }
    // 고른 키링으로 이미 보내는 중(보냄)이면 확정 버튼은 숨기고 아래 진행 상태만 둔다
    if (chosen != null && chosen.deviceId in syncing) return
    if (items.isNotEmpty()) AppButton(stringResource(Res.string.sync_pick_confirm), Modifier.fillMaxWidth(),
        enabled = enabled && chosen != null, onClick = { chosen?.let { onUseExisting(it.deviceId, serial) } })
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
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(stringResource(when (item.phase) {
            SyncPhase.WaitingStage -> Res.string.sync_live_stage
            SyncPhase.Issuing -> Res.string.sync_live_issuing
            SyncPhase.WaitingConnection -> Res.string.sync_live_connect
            SyncPhase.Sending -> Res.string.sync_live_sending
            SyncPhase.Stored -> Res.string.sync_live_stored
            SyncPhase.Confirming -> Res.string.sync_live_confirming
            SyncPhase.Confirmed -> when {
                item.alreadyLatest -> Res.string.sync_manual_already_latest
                item.confirmedTsv3Version != null -> Res.string.sync_live_full_confirmed
                else -> Res.string.sync_live_confirmed
            }
            SyncPhase.PolicyWaiting -> Res.string.sync_live_policy
            SyncPhase.Stale -> Res.string.sync_live_stale
            SyncPhase.Failed -> Res.string.sync_live_failed
            SyncPhase.ApplyChecking -> Res.string.sync_apply_checking
            SyncPhase.ApplyWaitingConnection -> Res.string.sync_apply_connect
            SyncPhase.ApplyConfirming -> Res.string.sync_apply_confirming
            SyncPhase.ApplyRetry -> Res.string.sync_apply_retry_message
            SyncPhase.ApplyFailed -> Res.string.sync_apply_failed
            SyncPhase.ApplyFirmwareRequired -> Res.string.sync_apply_firmware
            SyncPhase.ApplyConfirmed -> Res.string.sync_apply_confirmed
        }), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (item.phase == SyncPhase.Confirmed && item.confirmedTsv3Version == null && (item.applied == false || item.timeReady == false))
            Text(stringResource(Res.string.sync_live_not_applied), color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        if (item.phase == SyncPhase.Failed) item.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (item.phase == SyncPhase.ApplyRetry) item.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (item.phase in setOf(SyncPhase.ApplyRetry, SyncPhase.ApplyFailed, SyncPhase.ApplyConfirmed))
            AppButton(stringResource(Res.string.sync_apply_retry_button), full, enabled = !state.busy, onClick = { retry(item.deviceId) })
        if (item.phase == SyncPhase.Stale) AppButton(stringResource(Res.string.sync_live_latest), full, enabled = !state.busy, onClick = { latest(item.deviceId) })
        else if (item.phase in setOf(SyncPhase.Failed, SyncPhase.PolicyWaiting, SyncPhase.WaitingConnection))
            AppButton(stringResource(Res.string.sync_live_retry), full, enabled = !state.busy, onClick = { retry(item.deviceId) })
    }
}
