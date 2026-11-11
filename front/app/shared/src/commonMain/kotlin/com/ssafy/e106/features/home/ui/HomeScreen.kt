package com.ssafy.e106.features.home.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
 * 받은 제안 · 대화방은 아래 탭(제안 · 채팅)으로 보고 홈에는 두지 않는다. 저장/통신/실제 화면 이동은 호출부에서 처리한다.
 */
@Composable
fun HomeScreen(state: HomeState, onMatchingChange: (Boolean) -> Unit, onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier, onOpenMatchingSettings: (() -> Unit)? = null,
    onSaveKeyringAlias: (suspend (Long, String) -> Boolean)? = null,
    keyringStatus: @Composable () -> OwnedKeyringLiveStatus? = { null }) {
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // 오른쪽 위 "키링 설정" = 키링 화면(내 키링 목록 · 초기화)
            AppTopBar(stringResource(Res.string.home_brand), actionText = stringResource(Res.string.home_keyring_settings),
                onAction = { onAction(HomeAction.Keyring) })
            // 가운데: 큰 매칭 원 · 문구 · 내 키링 한 장을 한 덩어리로(길어지면 스크롤)
            Column(Modifier.weight(1f).fillMaxWidth().widthIn(max = 640.dp).align(Alignment.CenterHorizontally)
                .padding(horizontal = screenGutter())) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Large, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        when (state.load) {
                            HomeLoadState.Loading -> {
                                CircularProgressIndicator(color = AppColors.SelectionBorder)
                                Text(stringResource(Res.string.home_loading), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            }
                            HomeLoadState.Failed -> {
                                Text(stringResource(Res.string.home_failed), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                                Text(stringResource(Res.string.home_failed_help), style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextSecondary, textAlign = TextAlign.Center)
                                AppButton(stringResource(Res.string.home_retry), Modifier.fillMaxWidth(), onClick = { onAction(HomeAction.Refresh) })
                            }
                            HomeLoadState.Ready -> {
                                MatchingPower(state, onMatchingChange, onOpenMatchingSettings)
                                KeyringSummary(state, keyringStatus, onSaveKeyringAlias)
                            }
                        }
                    }
                }
            }
            AppBottomBar(labels = listOf(stringResource(Res.string.home_tab), stringResource(Res.string.home_proposal_tab),
                stringResource(Res.string.home_chat_tab), stringResource(Res.string.home_profile_tab)), selected = 0,
                onSelect = { index ->
                    when (index) {
                        1 -> onAction(HomeAction.Proposals)
                        2 -> onAction(HomeAction.Chats)
                        3 -> onAction(HomeAction.Profile)
                    }
                })
        }
    }
}

/** 가운데 큰 원: 켜짐 = 초록 원 · 연초록 테, 꺼짐 = 회색. 누르면 바꾼다(호출부가 설정 화면을 주면 그리로 연다) */
@Composable
private fun MatchingPower(state: HomeState, onMatchingChange: (Boolean) -> Unit, onOpenSettings: (() -> Unit)?) {
    val on = state.matchingEnabled
    val label = stringResource(when (on) { true -> Res.string.home_matching_on; false -> Res.string.home_matching_off; null -> Res.string.home_matching_title })
    val enabled = on != null && !state.savingMatching
    Box(Modifier.size(180.dp).border(14.dp, if (on == true) AppColors.SelectionContainer else AppColors.SurfaceMuted, CircleShape)
        .padding(14.dp).clip(CircleShape).background(if (on == true) AppColors.SelectionBorder else AppColors.BorderSubtle)
        .clickable(enabled, role = Role.Switch, onClickLabel = label) { if (onOpenSettings != null) onOpenSettings() else onMatchingChange(on != true) }
        .semantics { stateDescription = label; if (on != null) toggleableState = androidx.compose.ui.state.ToggleableState(on) },
        contentAlignment = Alignment.Center) {
        Text(stringResource(when (on) { true -> Res.string.home_power_on; false -> Res.string.home_power_off; null -> Res.string.home_unknown }),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
            color = if (on == true) AppColors.OnPrimary else AppColors.TextSecondary)
    }
    Text(stringResource(when (on) { true -> Res.string.home_power_on_title; false -> Res.string.home_power_off_title; null -> Res.string.home_matching_unknown }),
        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
    if (on == false) Text(stringResource(Res.string.home_power_off_body),
        style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
    if (state.savingMatching) Text(stringResource(Res.string.home_matching_saving), textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    else if (state.matchingSaveFailed) Text(stringResource(Res.string.home_matching_failed), color = AppColors.Error, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * 내 키링을 펼치면 이 휴대폰의 별명과 해당 기기에서 직접 읽은 배터리 상태가 보인다.
 * 손볼 일이 있으면 노란 바탕과 할 일 한 줄은 접혀 있어도 보인다. 키링 설정 화면은 오른쪽 위 "키링 설정"으로 간다
 */
@Composable
private fun KeyringSummary(state: HomeState, keyringStatus: @Composable () -> OwnedKeyringLiveStatus?,
    onSaveKeyringAlias: (suspend (Long, String) -> Boolean)?) {
    var open by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    val todo = keyringTodo(state.keyring)
    val ink = if (todo == null) AppColors.TextPrimary else WarnInk
    val sub = if (todo == null) AppColors.TextSecondary else WarnInk
    val turn by animateFloatAsState(if (open) 90f else -90f)
    val expanded = stringResource(Res.string.profile_section_expanded)
    val collapsed = stringResource(Res.string.profile_section_collapsed)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .then(if (todo == null) Modifier.border(1.5.dp, AppColors.BorderSubtle, RoundedCornerShape(16.dp)) else Modifier.background(WarnBackground))) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(AppSpacing.Medium)
            .semantics(mergeDescendants = true) { stateDescription = if (open) expanded else collapsed },
            verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Box(Modifier.size(8.dp).background(if (todo == null) AppColors.SelectionBorder else Color(0xFFD08A00), CircleShape))
                Text(stringResource(Res.string.home_keyring_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = ink)
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
            KeyringAliasDialog(state.keyringAliases[target].orEmpty(),
                save = { onSaveKeyringAlias(target, it) }, close = { editing = null })
        }
    }
}

/** 입력만 회전에 보존한다. 닫힌 화면에는 저장 결과를 전달하지 않는다. */
@Composable
private fun KeyringAliasDialog(initial: String, save: suspend (String) -> Boolean, close: () -> Unit) {
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

/** 손볼 일이 있는 상태만 한 줄 안내(그 밖의 확인 중 · 등록됨 · 연결됨 · 연결 안 됨 · 미확인은 상태 글자 없이) */
private fun keyringTodo(state: HomeKeyringState): StringResource? = when (state) {
    HomeKeyringState.DisconnectedPending -> Res.string.home_device_pending_help
    HomeKeyringState.SyncFailed -> Res.string.home_device_failed_help
    HomeKeyringState.Missing -> Res.string.home_device_missing_help
    HomeKeyringState.RecheckRequired -> Res.string.home_device_recheck_help
    HomeKeyringState.StorageFull -> Res.string.home_device_full_help
    else -> null
}
