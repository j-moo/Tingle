package com.ssafy.e106.features.home.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.keyring.ui.OwnedKeyringLiveStatus
import com.ssafy.e106.features.keyring.ui.keyringBatteryText
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val WarnBackground = Color(0xFFFFF6E5)   // 키링 손볼 일(반영 대기 · 실패 · 미등록 등) 노란 바탕(시안)
private val WarnInk = Color(0xFF7A4B00)

/**
 * 가입 완료 회원의 홈(시안 02 큰 매칭 버튼). 가운데 큰 원 하나가 새 매칭 상태, 그 문구 바로 아래 내 키링 요약 한 장(모두 화면 가운데).
 * 받은 제안 · 대화방은 아래 탭(제안 · 채팅, 틀이 그린다)으로 보고 홈에는 두지 않는다. 저장/통신/실제 화면 이동은 호출부에서 처리한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, onMatchingChange: (Boolean) -> Unit, onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier, onOpenMatchingSettings: (() -> Unit)? = null,
    onSaveKeyringAlias: (suspend (Long, String) -> Boolean)? = null,
    keyringStatus: @Composable () -> OwnedKeyringLiveStatus? = { null },
    connectedKeyringId: Long? = null,
    keyringPublicName: (@Composable (Long, String) -> Unit)? = null, error: AppError? = null,
    /** 안 읽은 알림이 있으면 종 모양에 점 */
    unreadNotifications: Boolean = false,
    /** 조회 중(조용한 갱신 포함). 이 동안 원을 누르면 예전 설정 버전으로 저장하게 되므로 막는다 */
    busy: Boolean = false) {
    // 위쪽 로딩은 사용자가 당겼을 때만 돈다. 키링 배터리 확인 · 조용한 갱신으로는 돌지 않는다
    val refreshing = state.refreshing
    val refreshLabel = stringResource(Res.string.home_refresh)
    val refresh = {
        if (state.load != HomeLoadState.Loading && !refreshing && !state.savingMatching) onAction(HomeAction.Refresh)
    }
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        // 아래쪽 인셋은 틀(SignedInServiceHost)의 하단 바가 맡는다
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            // 오른쪽 위 종 모양 = 받은 알림 모음(키링 관리는 마이 → 내 키링)
            AppTopBar(stringResource(Res.string.home_brand), trailing = {
                NotificationBell(unreadNotifications) { onAction(HomeAction.Notifications) }
            })
            // 가운데: 큰 매칭 원 · 문구 · 내 키링 한 장을 한 덩어리로(길어지면 스크롤)
            // 내용이 짧아도 화면 가운데에서 당길 수 있게 스크롤 영역을 채운다.
            PullToRefreshBox(refreshing, refresh, Modifier.weight(1f).fillMaxWidth()
                .semantics { customActions = listOf(CustomAccessibilityAction(refreshLabel) { refresh(); true }) },
                contentAlignment = Alignment.TopCenter) {
                BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = screenGutter())) {
                    // 위 40dp: 켜진 원의 빛이 바깥으로 번질 자리. 내용이 길어 원이 스크롤 맨 위에 닿아도 잘리지 않고, 켜고 꺼도 자리가 그대로다
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = PowerGlowRoom).heightIn(min = maxHeight - PowerGlowRoom),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Large, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        when (state.load) {
                            // 뼈대: 큰 원 · 문구 한 줄 · 내 키링 한 장 자리
                            HomeLoadState.Loading -> SkeletonGroup {
                                SkeletonBone(Modifier.align(Alignment.CenterHorizontally), width = 180.dp, height = 180.dp, shape = CircleShape)
                                SkeletonBone(Modifier.align(Alignment.CenterHorizontally), width = 200.dp, height = 28.dp)
                                SkeletonBone(height = AppSpacing.InputMinHeight, shape = RoundedCornerShape(16.dp))
                            }
                            HomeLoadState.Failed -> StateView(error.stateKind(), error.stateTitle(), onRetry = refresh)
                            HomeLoadState.Ready -> {
                                MatchingPower(state, busy, onMatchingChange, onOpenMatchingSettings)
                                KeyringSummary(state, connectedKeyringId, keyringStatus, onSaveKeyringAlias, keyringPublicName)
                                // 등록된 키링이 없으면(모두 초기화) 새 매칭을 켤 수 없다. 여기서 바로 키링을 등록하러 간다
                                if (state.keyring == HomeKeyringState.Missing) AppButton(stringResource(Res.string.home_keyring_connect),
                                    Modifier.fillMaxWidth(), onClick = { onAction(HomeAction.RegisterKeyring) })
                            }
                        }
                    }
                }
            }
        }
    }
}

// 켜짐 색은 연두 초안(11-17). 실기기에서 조정한다
private val PowerFill = Color(0xB3C8F5A0)    // 켜짐 원: 연두 70%
private val PowerShine = Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color(0x00FFFFFF), Color(0x268BD650)))   // 유리 반사광: 위는 희게, 아래는 연두가 고인다
private val PowerGlow = Color(0x99A8E878)    // 원 둘레로 번지는 빛
private val PowerRingTop = Color(0xF2FFFFFF)   // 밝게 빛나는 테(위 → 아래 그러데이션)
private val PowerRingBottom = Color(0xB3D4F5B8)
private val PowerInk = AppColors.FreshInk     // 연두 위 ON 글자(대비 4.5:1 이상)
private val PowerGlowRoom = 40.dp            // 원(180dp) 둘레 빛이 원 바깥으로 번지는 거리

/**
 * 가운데 큰 원: 켜짐 = 연두 반투명 유리 원 · 빛나는 밝은 테, 꺼짐 = 회색. 누르면 바꾼다(호출부가 설정 화면을 주면 그리로 연다).
 * 원 아래 묶음(제목 · 문구 한 줄)은 높이가 늘 같아서 눌러도 원이 위아래로 움직이지 않는다
 */
@Composable
private fun MatchingPower(state: HomeState, busy: Boolean, onMatchingChange: (Boolean) -> Unit, onOpenSettings: (() -> Unit)?) {
    // 등록된 키링이 없으면 저장된 설정과 상관없이 새 매칭이 돌지 않는다: 원은 눌리지 않는 흐린 OFF 로 두고 키링 연결을 안내한다
    val missing = state.keyring == HomeKeyringState.Missing
    val on = if (missing) false else state.matchingEnabled
    val label = stringResource(when (on) { true -> Res.string.home_matching_on; false -> Res.string.home_matching_off; null -> Res.string.home_matching_title })
    val enabled = on != null && !state.savingMatching && !missing && !busy
    // 켜고 끌 때 색만 약 200ms 동안 바뀐다
    val fade = tween<Color>(200)
    val glow by animateColorAsState(if (on == true) PowerGlow else PowerGlow.copy(alpha = 0f), fade)
    val ringTop by animateColorAsState(if (on == true) PowerRingTop else AppColors.SurfaceMuted, fade)
    val ringBottom by animateColorAsState(if (on == true) PowerRingBottom else AppColors.SurfaceMuted, fade)
    val fill by animateColorAsState(if (on == true) PowerFill else AppColors.BorderSubtle, fade)
    val ink by animateColorAsState(if (on == true) PowerInk else AppColors.TextSecondary, fade)
    Box(Modifier.size(180.dp).alpha(if (missing) 0.55f else 1f)
        // 켜짐: 원 둘레로 연두 빛이 번진다
        .drawBehind {
            val reach = size.minDimension * 0.72f
            drawCircle(Brush.radialGradient(listOf(glow, glow.copy(alpha = 0f)), radius = reach), reach)
        }
        .border(14.dp, Brush.verticalGradient(listOf(ringTop, ringBottom)), CircleShape)
        .padding(14.dp).frost(CircleShape).background(fill)
        .then(if (on == true) Modifier.background(PowerShine).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape) else Modifier)
        .clickable(enabled, role = Role.Switch, onClickLabel = label) { if (onOpenSettings != null) onOpenSettings() else onMatchingChange(on != true) }
        .semantics { stateDescription = label; if (on != null) toggleableState = androidx.compose.ui.state.ToggleableState(on) },
        contentAlignment = Alignment.Center) {
        Text(stringResource(when (on) { true -> Res.string.home_power_on; false -> Res.string.home_power_off; null -> Res.string.home_unknown }),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
            color = ink)
    }
    Text(stringResource(when { missing -> Res.string.home_keyring_connect_title; on == true -> Res.string.home_power_on_title
        on == false -> Res.string.home_power_off_title; else -> Res.string.home_matching_unknown }),
        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
    // 실패 문구 자리: 비어 있어도 늘 한 줄 높이를 차지한다
    Text(if (state.matchingSaveFailed) stringResource(Res.string.home_matching_failed) else "", color = AppColors.Error,
        textAlign = TextAlign.Center, maxLines = 1, minLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * 내 키링을 펼치면 이 휴대폰의 별명과 해당 기기에서 직접 읽은 배터리 상태가 보인다.
 * 손볼 일이 있으면 노란 바탕과 할 일 한 줄은 접혀 있어도 보인다. 키링 관리 화면은 마이 → 내 키링으로 간다
 */
@Composable
private fun KeyringSummary(state: HomeState, connectedKeyringId: Long?, keyringStatus: @Composable () -> OwnedKeyringLiveStatus?,
    onSaveKeyringAlias: (suspend (Long, String) -> Boolean)?,
    keyringPublicName: (@Composable (Long, String) -> Unit)?) {
    var open by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    val todo = keyringTodo(state.keyring)
    val ink = if (todo == null) AppColors.TextPrimary else WarnInk
    val sub = if (todo == null) AppColors.TextSecondary else WarnInk
    val turn by animateFloatAsState(if (open) 90f else -90f)
    val expanded = stringResource(Res.string.profile_section_expanded)
    val collapsed = stringResource(Res.string.profile_section_collapsed)
    // 등록 목록에 포함된 현재 블루투스 연결만 색과 문구로 알린다.
    val connected = state.keyrings.any { it.deviceId == connectedKeyringId }
    val connectedLabel = stringResource(Res.string.home_keyring_connected)
    val disconnectedLabel = stringResource(Res.string.home_keyring_disconnected)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .then(if (todo == null) Modifier.glass(RoundedCornerShape(16.dp)) else Modifier.background(WarnBackground))) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(AppSpacing.Medium)
            .semantics(mergeDescendants = true) { stateDescription = if (open) expanded else collapsed },
            verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Box(Modifier.size(8.dp).background(if (connected) AppColors.SelectionBorder else AppColors.Error, CircleShape))
                Text(stringResource(Res.string.home_keyring_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = ink)
                Text(if (connected) connectedLabel else disconnectedLabel, style = MaterialTheme.typography.bodySmall, color = sub)
                Icon(painterResource(Res.drawable.ic_arrow_back), null, Modifier.size(16.dp).rotate(turn), tint = sub)
            }
            todo?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = sub) }
        }
        AnimatedVisibility(open, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.fillMaxWidth().padding(start = AppSpacing.Medium, end = AppSpacing.Medium, bottom = AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                HorizontalDivider(color = if (todo == null) AppColors.BorderSubtle else WarnInk.copy(alpha = 0.2f))
                when {
                    state.keyrings.isNotEmpty() -> {
                        val status = if (open) keyringStatus() else null
                        state.keyrings.forEachIndexed { index, device ->
                            // 번호는 표시용 순서지만 별명과 배터리 연결은 고정된 등록 기기 ID를 사용한다.
                            val label = state.keyringAliases[device.deviceId]
                                ?: stringResource(Res.string.sync_live_device, (index + 1).toString())
                            val battery = keyringBatteryText(status?.takeIf { it.deviceId == device.deviceId }?.metrics)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold, color = ink)
                                    Text(stringResource(Res.string.owned_keyrings_battery, battery),
                                        style = MaterialTheme.typography.bodySmall, color = sub)
                                    Text(if (device.deviceId == connectedKeyringId) connectedLabel else disconnectedLabel,
                                        style = MaterialTheme.typography.bodySmall, color = sub)
                                }
                                if (onSaveKeyringAlias != null) TextButton(onClick = { editing = device.deviceId }) {
                                    val action = stringResource(Res.string.home_keyring_alias_edit)
                                    val description = stringResource(Res.string.home_keyring_alias_edit_for, label)
                                    Text(action, Modifier.clearAndSetSemantics { contentDescription = description })
                                }
                            }
                        }
                    }
                    state.keyring == HomeKeyringState.Missing -> Text(stringResource(Res.string.home_keyring_none), style = MaterialTheme.typography.bodySmall, color = sub)
                    state.keyring == HomeKeyringState.Checking -> Text(stringResource(Res.string.home_keyring_checking), style = MaterialTheme.typography.bodySmall, color = sub)
                    else -> Text(stringResource(Res.string.home_keyring_unknown), style = MaterialTheme.typography.bodySmall, color = sub)
                }
            }
        }
    }
    val target = editing
    if (target != null && onSaveKeyringAlias != null && state.keyrings.any { it.deviceId == target }) {
        key(target) {
            val savedAlias = state.keyringAliases[target].orEmpty()
            KeyringAliasDialog(savedAlias,
                save = { onSaveKeyringAlias(target, it) }, close = { editing = null },
                publicName = { draft ->
                    // 공개 명령은 저장을 마친 별명만 키링에 보낸다.
                    keyringPublicName?.invoke(target, savedAlias.takeIf { it == draft.trim() }.orEmpty())
                })
        }
    }
}

/** 입력만 회전에 보존한다. 닫힌 화면에는 저장 결과를 전달하지 않는다. */
@Composable
private fun KeyringAliasDialog(initial: String, save: suspend (String) -> Boolean, close: () -> Unit,
    publicName: @Composable (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit(value: String) {
        if (saving) return
        saving = true
        failed = false
        scope.launch {
            if (save(value)) close()
            else { saving = false; failed = true }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text(stringResource(Res.string.home_keyring_alias_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                AppTextField(draft, { draft = it; failed = false }, stringResource(Res.string.home_keyring_alias_label),
                    supportingText = stringResource(Res.string.home_keyring_alias_help),
                    errorText = when {
                        failed -> stringResource(Res.string.home_keyring_alias_failed)
                        draft.trim().length > KEYRING_ALIAS_LIMIT -> stringResource(Res.string.home_keyring_alias_limit)
                        else -> null
                    }, enabled = !saving)
                if (initial.isNotEmpty()) TextButton(onClick = { submit("") }, enabled = !saving) {
                    Text(stringResource(Res.string.home_keyring_alias_remove))
                }
                // 휴대폰에 저장하는 별명과 검색에 공개하는 작업은 각각 명시적으로 실행한다.
                publicName(draft)
            }
        },
        confirmButton = {
            TextButton(onClick = { submit(draft) }, enabled = !saving && draft.isNotBlank() && draft.trim().length <= KEYRING_ALIAS_LIMIT) {
                Text(stringResource(if (saving) Res.string.home_keyring_alias_saving else Res.string.home_keyring_alias_save))
            }
        },
        dismissButton = {
            TextButton(onClick = close, enabled = !saving) { Text(stringResource(Res.string.home_keyring_alias_cancel)) }
        },
    )
}

/** 현재 연결 표시 아래에 키링에 필요한 조치가 있을 때만 추가 안내를 붙인다. */
private fun keyringTodo(state: HomeKeyringState): StringResource? = when (state) {
    HomeKeyringState.DisconnectedPending -> Res.string.home_device_pending_help
    HomeKeyringState.SyncFailed -> Res.string.home_device_failed_help
    HomeKeyringState.Missing -> Res.string.home_device_missing_help
    HomeKeyringState.RecheckRequired -> Res.string.home_device_recheck_help
    HomeKeyringState.StorageFull -> Res.string.home_device_full_help
    else -> null
}

/** 종 모양 버튼. 안 읽은 알림이 있으면 오른쪽 위에 초록 점(화면 리더엔 "새 알림 있음") */
@Composable private fun NotificationBell(unread: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (unread) Res.string.notifications_open_unread else Res.string.notifications_open)
    // 48dp 칸을 직접 잡아야 점이 종의 오른쪽 위 모서리에 붙는다(최소 터치 크기 수정자는 내용 크기를 24dp로 둔다)
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(Res.drawable.ic_notifications), null, Modifier.size(24.dp), tint = AppColors.TextPrimary)
        if (unread) Box(Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 11.dp).size(8.dp).clip(CircleShape)
            .background(AppColors.SelectionBorder))
    }
}
