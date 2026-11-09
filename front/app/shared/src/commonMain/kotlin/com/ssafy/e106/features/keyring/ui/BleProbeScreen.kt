package com.ssafy.e106.features.keyring.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * 키링 연결 화면. [registration] 을 받으면(가입 키링 단계) 아래에 등록 패널을 붙여 실제 귀속까지 진행한다.
 * [autoScan] 이면 들어오자마자(블루투스가 켜지면 그때) 주변 키링을 찾는다. 탐색은 등록이 아니다.
 */
@Composable fun ConnectedBleProbeRoute(onBack: () -> Unit, model: BleProbeViewModel = koinViewModel(),
    registration: KeyringRegistrationViewModel? = null, sync: KeyringSyncViewModel? = null,
    managedConnection: Boolean = false, autoScan: Boolean = false, allowEncounterTransfer: Boolean = false) {
    val permission = rememberBlePermission()
    val state by model.state.collectAsStateWithLifecycle()
    val registrationState = registration?.state?.collectAsStateWithLifecycle()?.value
    val registrationBusy = registrationState?.busy == true
    val syncBusy = sync?.state?.collectAsStateWithLifecycle()?.value?.busy == true
    val registering = registrationBusy || syncBusy
    // 이번 진입에서 찾아봤는지(문구용). 찾은 적 없거나 사용자가 그만뒀으면 "찾기 시작", 찾았는데 없으면 "찾지 못했어요"
    var searched by rememberSaveable { mutableStateOf(false) }
    // 자동 탐색을 이미 했는지. 사용자가 취소한 뒤 다시 저절로 찾지 않게 문구용 searched 와 나눈다
    var autoTried by rememberSaveable { mutableStateOf(false) }
    fun scan() { if (model.scan()) searched = true }               // 실제로 탐색을 시작했을 때만 "찾았다"로 친다
    // 서비스 호스트가 있으면 내부 화면 이동은 연결 종료가 아니다. 단독 연결 화면은 기존 종료 정책을 유지한다.
    // 연결을 놓으면 찾은 결과도 사라지므로 "찾아봤음"도 지운다(돌아왔을 때 "찾지 못했어요"로 보이지 않게)
    fun release() { registration?.pause(); if (!managedConnection) { sync?.detach(); model.leave(); searched = false } }
    // 호스트가 초기화 복구/권한 상태를 보고 일시 중지한 경우 내부 화면 진입으로 다시 켜지 않는다.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        registration?.restore(); if (!managedConnection) sync?.resume()
        if (autoScan && !state.connected) autoTried = false             // 돌아오면 다시 자동으로 찾는다
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { release() }
    DisposableEffect(model, registration, managedConnection) { onDispose { release() } }
    // 블루투스가 꺼지면 연결 · 결과를 놓고, 다시 켜지면 처음처럼 자동으로 찾는다
    LaunchedEffect(permission.availability) {
        // 호스트가 관리하는 연결은 호스트가 정리한다(release 와 같은 정책)
        if (permission.availability != BleAvailability.Ready) { if (!managedConnection) model.leave(); searched = false; autoTried = false }
    }
    LaunchedEffect(autoScan, permission.availability, autoTried, state.busy, state.scanning, registering) {
        if (autoScan && !autoTried && permission.availability == BleAvailability.Ready &&
            !state.connected && !state.busy && !state.scanning && !registering) { autoTried = true; scan() }
    }
    ProfileBackHandler(true) { release(); onBack() }
    // 이미 등록된 키링(BOUND)을 연결했고 내 등록 요청이 진행 중이 아니면 "연결됐어요" 대신 내 키링 목록에서 고르게 한다
    val pickExisting = sync != null && state.connected && state.info?.ownerState == "BOUND" &&
        (registration == null || registrationState?.loaded == true) &&       // 저장된 등록 요청을 다 읽은 뒤에만 판단한다
        registrationState?.pending == null && registrationState?.confirmed == null
    // 내 키링 목록은 동기화가 있을 때만 쓴다(패널과 같은 인스턴스를 넘긴다)
    val ownedModel = sync?.let { koinViewModel<OwnedKeyringsViewModel>() }
    val ownedState = ownedModel?.state?.collectAsStateWithLifecycle()?.value
    // 내 키링 목록을 불러오는 동안(아직 안 불러온 첫 화면 포함)에도 연결 끊기를 숨긴다
    val listLoading = pickExisting && ownedState?.owner != null && (ownedState.loading || (ownedState.items == null && ownedState.error == null))
    BleProbeScreen(permission.availability, state, searched = searched, registrationMode = registration != null, pickExisting = pickExisting,
        syncBusy = syncBusy, registering = registering || listLoading, onBack = { release(); onBack() },
        onScan = ::scan, onStopScan = { model.stopScan(); searched = false }, onConnect = model::connect,
        // 사용자가 그만둔 건 "못 찾음"이 아니다 — 찾기 전 화면으로
        onDisconnect = { model.leave(); searched = false }) {
        // 동기화 중 다른 명령을 막고 등록 결과와 설정 상태를 같은 화면에 표시한다.
        // 저장된 등록 요청 복구 · 미전송 동기화도 연결 전에 보여야 해서 단계와 상관없이 아래에 둔다.
        if (!pickExisting) registration?.let { KeyringRegistrationPanel(it, model, state, syncBusy) }
        sync?.let { KeyringSyncPanel(it, model, state, registrationBusy, ownedModel!!, managedConnection = managedConnection, pickExisting = pickExisting) }
        // 소유 확인은 화면에 보인 이름이 아니라 서버가 확인한 동기화 기록으로 수행한다.
        val info = state.info
        if (allowEncounterTransfer && sync != null && state.connected && info != null) {
            // 목록의 서버 버전과 현재 보드가 보내온 버전은 다를 수 있다.
            Text(stringResource(Res.string.keyring_encounter_connected_firmware, info.firmware),
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            when {
                !info.supportsStatus || !info.supportedFunctions.containsAll(listOf(
                    "GET_OWNER_CHALLENGE", "AUTHORIZE_OWNER", "GET_RECORD", "READ_RECORD_CHUNK", "ACK_RECORD",
                )) -> Body(Res.string.keyring_encounter_unsupported)
                info.ownerState != "BOUND" -> Body(Res.string.keyring_encounter_unbound)
                else -> KeyringEncounterTransfer(info.serial, model, enabled = !registering && !state.busy)
            }
        }
    }
}

/** 기존 소유 키링의 기록 화면에는 새 키링을 등록하는 패널을 붙이지 않는다. */
@Composable internal fun ConnectedKeyringEncounterRoute(onBack: () -> Unit) {
    ConnectedBleProbeRoute(onBack, sync = koinViewModel<KeyringSyncViewModel>(),
        managedConnection = true, autoScan = true, allowEncounterTransfer = true)
}

/** 이 화면을 떠나면 전송만 중단하고, 응답을 기다리는 접수증은 보호 저장소에 남긴다. */
@Composable private fun KeyringEncounterTransfer(serial: String, ble: BleProbeViewModel, enabled: Boolean,
    model: KeyringEncounterViewModel = koinViewModel()) {
    val transfer by model.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.cancel() }
    DisposableEffect(serial, model) { onDispose { model.cancel() } }
    KeyringEncounterTransferContent(transfer, enabled, onSend = { model.send(serial, ble) })
}

/** 전송 결과만 그린다. 서버 접수증이 없는 상태에서는 키링 정리 완료를 표시하지 않는다. */
@Composable internal fun KeyringEncounterTransferContent(transfer: EncounterTransferState, enabled: Boolean, onSend: () -> Unit) {
    AppButton(stringResource(Res.string.keyring_encounter_send), Modifier.fillMaxWidth(), AppButtonVariant.Secondary,
        enabled = enabled && transfer != EncounterTransferState.Sending, onClick = onSend)
    val message = when (transfer) {
        EncounterTransferState.Idle -> null
        EncounterTransferState.Sending -> Res.string.keyring_encounter_sending
        EncounterTransferState.Empty -> Res.string.keyring_encounter_empty
        EncounterTransferState.LegacyOnly -> Res.string.keyring_encounter_legacy_only
        EncounterTransferState.ConfirmedStored, EncounterTransferState.Stored -> Res.string.keyring_encounter_stored
        EncounterTransferState.ReviewStored -> Res.string.keyring_encounter_review_stored
        EncounterTransferState.NoReceipt -> Res.string.keyring_encounter_no_receipt
        EncounterTransferState.Failed -> Res.string.keyring_encounter_failed
    }
    message?.let { Body(it) }
}

/** 화면 단계: ① 찾기 전(Idle) · 찾는 중 · 못 찾음 · 블루투스 꺼짐 등(Unavailable) → ② 키링 고르기 → 연결 중 → ③ 연결됨
 * (이미 등록된 키링이면 ③ 대신 내 키링 고르기: PickExisting) */
private enum class ProbeStep { Unavailable, Idle, Searching, NotFound, Picking, Connecting, Connected, PickExisting }

private fun step(availability: BleAvailability, state: BleProbeState, searched: Boolean, pickExisting: Boolean) = when {
    state.connected && pickExisting -> ProbeStep.PickExisting
    state.connected -> ProbeStep.Connected
    availability != BleAvailability.Ready -> ProbeStep.Unavailable
    state.busy -> ProbeStep.Connecting          // 고른 키링 연결 · 끊긴 키링 자동 재연결(이때 후보는 비어 있다)
    state.candidates.isNotEmpty() -> ProbeStep.Picking
    state.scanning -> ProbeStep.Searching
    searched -> ProbeStep.NotFound
    else -> ProbeStep.Idle
}

/**
 * 순수 화면(Preview 용). 권한 · 연결 상태만 받아 그리고, 등록 · 동기화 패널은 [extra] 로 붙인다(③ 연결됨 · 내 키링 고르기에서는 가운데 묶음 안).
 * 권한은 로그인 직후 권한 시트에서 받으므로 여기서 요청하지 않는다(권한이 없으면 시트가 다시 앱을 막는다).
 * 블루투스 · 위치 서비스는 권한이 아니라 언제든 꺼질 수 있어 준비되지 않았을 때만 안내 문구를 띄운다.
 */
@Composable fun BleProbeScreen(
    availability: BleAvailability,
    state: BleProbeState,
    searched: Boolean = false,
    registrationMode: Boolean = false,
    pickExisting: Boolean = false,
    syncBusy: Boolean = false,
    registering: Boolean = false,
    onBack: () -> Unit = {},
    onScan: () -> Unit = {},
    onStopScan: () -> Unit = {},
    onConnect: (String) -> Unit = {},
    onDisconnect: () -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    val step = step(availability, state, searched, pickExisting)
    val canAct = !state.busy && !registering
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BackArrowBar(onBack)
            // 화면 높이를 최소 높이로 잡아 ① 단계의 제목 · 레이더 · 버튼 묶음을 남은 공간 가운데에 둔다(넘치면 스크롤)
            BoxWithConstraints(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)
                .padding(screenPaddingValues()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                // 오류 안내와 등록 · 동기화 패널. ③ 연결됨에서는 위 묶음과 함께 화면 가운데에, 나머지 단계에선 아래에 둔다
                val below = @Composable {
                    state.error?.let { Banner(stringResource(Res.string.ble_failed, it.name)) }
                    state.commandError?.let { Banner(stringResource(Res.string.ble_command_failed, it)) }
                    extra()
                }
                when (step) {
                    ProbeStep.Connected -> ConnectedHero(state, registrationMode, syncBusy, below)
                    // 내 키링 고르기는 아래 동기화 패널이 그린다. 묶음을 화면 가운데에
                    ProbeStep.PickExisting -> { Spacer(Modifier.weight(1f)); below(); Spacer(Modifier.weight(1f)) }
                    ProbeStep.Picking -> { PickingHero(state, canAct, onConnect); below() }
                    else -> { SearchHero(step, availability, canAct, onScan, onStopScan, onDisconnect); below() }
                }
            }
            }
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = screenGutter()).padding(bottom = AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                val full = Modifier.fillMaxWidth()
                // ① 단계 버튼은 레이더 아래 가운데에 있다(SearchHero)
                when (step) {
                    ProbeStep.Picking -> if (state.scanning) AppButton(stringResource(Res.string.probe_stop), full, AppButtonVariant.Secondary, onClick = onStopScan)
                        else AppButton(stringResource(Res.string.probe_retry), full, AppButtonVariant.Secondary, enabled = canAct, onClick = onScan)
                    // 등록 · 설정을 보내는 동안에는 연결 끊기를 아예 숨긴다
                    ProbeStep.Connected, ProbeStep.PickExisting -> if (!registering)
                        AppButton(stringResource(Res.string.ble_disconnect), full, AppButtonVariant.Secondary, onClick = onDisconnect)
                    else -> Unit
                }
            }
        }
    }
}

/** ① 찾기 전 · 찾는 중 · 못 찾음 · 블루투스 꺼짐. 제목 · 레이더 · 버튼을 한 묶음으로 화면 가운데에 둔다 */
@Composable private fun ColumnScope.SearchHero(step: ProbeStep, availability: BleAvailability, canAct: Boolean,
    onScan: () -> Unit, onStopScan: () -> Unit, onCancelConnect: () -> Unit) {
    val (title, body) = when (step) {
        ProbeStep.Searching -> Res.string.probe_search_title to Res.string.probe_search_body
        ProbeStep.Connecting -> Res.string.probe_connecting to Res.string.probe_connecting_body
        ProbeStep.NotFound -> Res.string.probe_not_found_title to Res.string.probe_not_found_body
        else -> Res.string.probe_idle_title to Res.string.probe_idle_body
    }
    Spacer(Modifier.weight(1f))
    Title(title)
    if (step == ProbeStep.Unavailable) Banner(stringResource(when (availability) {
        BleAvailability.Unsupported -> Res.string.ble_unsupported
        BleAvailability.PermissionRequired -> Res.string.ble_permission
        BleAvailability.LocationOff -> Res.string.ble_location_off
        else -> Res.string.ble_bluetooth_off
    })) else Body(body)
    Radar(scanning = step == ProbeStep.Searching || step == ProbeStep.Connecting, Modifier.align(Alignment.CenterHorizontally).padding(vertical = AppSpacing.Medium))
    val button = Modifier.fillMaxWidth()
    when (step) {
        ProbeStep.Searching -> AppButton(stringResource(Res.string.probe_stop), button, AppButtonVariant.Secondary, onClick = onStopScan)
        // 연결 · 자동 재연결은 시간이 걸릴 수 있어 언제든 그만둘 수 있게 한다
        ProbeStep.Connecting -> AppButton(stringResource(Res.string.probe_stop), button, AppButtonVariant.Secondary, onClick = onCancelConnect)
        ProbeStep.NotFound -> AppButton(stringResource(Res.string.probe_retry), button, enabled = canAct, onClick = onScan)
        else -> AppButton(stringResource(Res.string.probe_start), button, enabled = canAct && step == ProbeStep.Idle, onClick = onScan)
    }
    Spacer(Modifier.weight(1f))
}

/** ② 찾은 키링 고르기. 신호가 센(가까운) 순서 */
@Composable private fun ColumnScope.PickingHero(state: BleProbeState, canAct: Boolean, onConnect: (String) -> Unit) {
    Spacer(Modifier.weight(1f))                                // ① 단계처럼 묶음을 화면 가운데에
    Title(Res.string.probe_pick_title)
    Body(Res.string.probe_pick_body)
    state.candidates.sortedByDescending { it.rssi }.forEach { device ->
        KeyringCard(device.name ?: stringResource(Res.string.probe_unnamed), selected = false, enabled = canAct,
            onClick = { onConnect(device.id) }) { SignalBars(signalBars(device.rssi), Modifier.padding(top = 4.dp)) }
    }
    Text(stringResource(Res.string.probe_missing_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
        modifier = Modifier.fillMaxWidth().background(AppColors.SurfaceMuted, RoundedCornerShape(14.dp)).padding(14.dp))
    Spacer(Modifier.weight(1f))
}

/** ③ 연결됨. "연결됐어요" 화면 대신 고르던 목록을 그대로 두고, 연결한 키링만 초록 테두리 · 체크로 표시한다.
 * 등록은 아래 등록 패널([below])이 맡는다 */
@Composable private fun ColumnScope.ConnectedHero(state: BleProbeState, registrationMode: Boolean, syncBusy: Boolean,
    below: @Composable () -> Unit) {
    Spacer(Modifier.weight(1f))                                // ① 단계처럼 묶음을 화면 가운데에
    Title(Res.string.probe_pick_title)
    Body(if (registrationMode) Res.string.probe_selected_body else Res.string.probe_selected_body_plain)
    val info = state.info
    val meta = info?.let { stringResource(Res.string.probe_device_meta, it.serial, it.firmware) }
    val list = state.candidates.sortedByDescending { it.rssi }
    if (list.any { it.id == state.selectedId }) list.forEach { device ->
        val selected = device.id == state.selectedId
        // 연결 중에는 다른 키링으로 바꿀 수 없어 나머지는 흐리게만 둔다(바꾸려면 아래 "연결 끊기")
        KeyringCard(device.name ?: stringResource(Res.string.probe_unnamed), selected = selected, enabled = selected, onClick = null) {
            if (selected && meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            else SignalBars(signalBars(device.rssi), Modifier.padding(top = 4.dp))
        }
    } else if (info != null) {
        // 자동 재연결 등으로 후보 목록이 없으면 연결한 키링 하나만
        KeyringCard(info.serial, selected = true, onClick = null) {
            Text(meta.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary)
        }
    }
    if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Spinner()
        Text(stringResource(if (syncBusy) Res.string.sync_live_sending else Res.string.ble_connecting), style = MaterialTheme.typography.bodyMedium)
    }
    below()                                                    // 등록 패널을 묶음 안에 둬 함께 가운데로
    Spacer(Modifier.weight(1f))
}

/** 글자 "이전" 대신 화살표 아이콘(TalkBack은 "이전"으로 읽는다). 눌렀을 때 원(물결) 없이 화살표만, 터치 영역은 48dp 유지 */
@Composable internal fun BackArrowBar(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Small)) {
        Box(Modifier.minimumInteractiveComponentSize().clickable(remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.owned_keyrings_back), tint = AppColors.TextPrimary)
        }
    }
}

/**
 * 키링 카드. 선택되면 초록 테두리와 체크, 아니면 옅은 테두리. [onClick] 이 있으면 카드 전체를 누를 수 있다.
 * 선택 목록에서는 화살표를 두지 않는다(눌러도 다른 화면이 나오지 않는다).
 */
@Composable internal fun KeyringCard(title: String, selected: Boolean, enabled: Boolean = true, onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null, sub: @Composable () -> Unit) {
    val border = if (selected) BorderStroke(2.dp, AppColors.SelectionBorder) else BorderStroke(1.dp, AppColors.BorderSubtle)
    // 막혀 있어도 선택한 카드는 흐리게 하지 않는다(무엇을 골랐는지 계속 보이게)
    val modifier = Modifier.fillMaxWidth().alpha(if (enabled || selected) 1f else 0.4f)
        // 고를 수 있는 카드만 선택 상태를 읽어 준다(목록 카드는 "선택 안 됨"을 읽지 않게)
        .semantics { if (onClick != null || selected) this.selected = selected }
    val content = @Composable {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PebbleThumb()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                sub()
            }
            if (selected) Image(painterResource(Res.drawable.ic_check_done), null, Modifier.size(24.dp))
            else trailing?.invoke()
        }
    }
    if (onClick != null) Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = AppColors.Background,
        border = border, modifier = modifier) { content() }
    else Surface(shape = RoundedCornerShape(16.dp), color = AppColors.Background, border = border, modifier = modifier) { content() }
}

@Composable private fun Title(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        color = AppColors.TextPrimary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading(); liveRegion = LiveRegionMode.Polite })
}

@Composable private fun Body(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable private fun Banner(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.Error,
        modifier = Modifier.fillMaxWidth().background(AppColors.Error.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable private fun PebbleThumb() {
    Box(Modifier.size(44.dp).background(KeyringPebbleColors.Light, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        KeyringPebble(Modifier.size(36.dp))
    }
}

@Composable private fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier.clearAndSetSemantics {}) {
        repeat(4) { i ->
            Box(Modifier.width(3.dp).height((4 + i * 3).dp)
                .background(if (i < level) AppColors.OnSelection else AppColors.BorderSubtle, RoundedCornerShape(1.dp)))
        }
    }
}

/** 작은 로딩 원. 프리뷰에선 멈춘 원(무한 애니메이션이면 프리뷰가 끝나지 않는다) */
@Composable private fun Spinner() {
    if (LocalInspectionMode.current) CircularProgressIndicator({ 0.7f }, Modifier.size(18.dp), strokeWidth = 2.dp)
    else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
}

/** 신호 세기(dBm) → 막대 1~4개. 기기마다 달라 거리감만 보여준다 */
private fun signalBars(rssi: Int) = when {
    rssi >= -55 -> 4
    rssi >= -70 -> 3
    rssi >= -85 -> 2
    else -> 1
}

/** 가운데 '나'와 겹친 원, 찾는 중이면 민트 빛이 돈다(장식이라 읽지 않음) */
@Composable private fun Radar(scanning: Boolean, modifier: Modifier = Modifier) {
    // 프리뷰는 화면이 멈출 때까지 기다렸다 찍으므로 무한 회전을 돌리면 끝나지 않는다(시간 초과). 프리뷰에선 한 장면으로 고정
    val angle = if (LocalInspectionMode.current) null
        else rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)))
    Box(modifier.size(220.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Color(0xFFF7FBF8), radius = r / 3)
            listOf(1f, 2f / 3, 1f / 3).forEach { drawCircle(Color(0xFFDDEBE1), radius = r * it, style = Stroke(1.dp.toPx())) }
        }
        // 회전값은 그리기 단계에서만 읽는다. 컴포지션에서 읽으면 매 프레임 화면 전체(문구 포함)를 다시 계산한다
        if (scanning) Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle?.value ?: 40f }) {
            drawCircle(Brush.sweepGradient(0f to KeyringPebbleColors.Signal.copy(alpha = 0.5f), 0.2f to Color.Transparent,
                1f to Color.Transparent))
        }
        Box(Modifier.size(44.dp).background(AppColors.TextPrimary, CircleShape), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.probe_me), color = AppColors.OnPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

