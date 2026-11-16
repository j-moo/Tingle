package com.ssafy.e106.features.home.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.push.*
import com.ssafy.e106.features.chat.ui.ConnectedChatRoute
import com.ssafy.e106.features.safety.ui.*
import com.ssafy.e106.features.report.ui.ConnectedReportRoute
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.onboarding.ui.*
import com.ssafy.e106.features.profile.ui.*
import com.ssafy.e106.features.proposals.ProposalAccess
import com.ssafy.e106.features.proposals.ui.ConnectedProposalRoute
import com.ssafy.e106.features.settings.ui.ConnectedSettingsRoute
import com.ssafy.e106.features.survey.ui.SurveyRoute
import com.ssafy.e106.features.keyring.ui.ConnectedOwnedKeyringsRoute
import com.ssafy.e106.features.keyring.ui.ConnectedOwnedKeyringSyncRoute
import com.ssafy.e106.features.keyring.ui.ConnectedKeyringSetupRoute
import com.ssafy.e106.features.keyring.ui.ConnectedKeyringEncounterRoute
import com.ssafy.e106.features.keyring.ui.KeyringForegroundSync
import com.ssafy.e106.features.keyring.ui.ConnectedKeyringResetRoute
import com.ssafy.e106.features.keyring.ui.keyringBleModel
import com.ssafy.e106.features.keyring.ui.KeyringPublicNameControls
import com.ssafy.e106.features.keyring.ui.observeOwnedKeyringConnection
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// Keyrings는 서버의 등록 목록을 확인하는 경로이며 BLE 등록/초기화 경로와 구분한다.
internal enum class ServicePage { Home, Proposals, Menu, Chats, Safety, Report, AiConsent, Profile, Survey, Settings, Help, HelpHub, Keyrings, BleProbe, KeyringRegistration, KeyringReset, KeyringSync, Preparing, Notifications }

/** 하단 바 탭 순서. 이 페이지들의 루트 화면에서만 바를 보인다 */
private val TabPages = listOf(ServicePage.Home, ServicePage.Proposals, ServicePage.Chats, ServicePage.Menu)

/** 가입 중에는 가입 화면을 유지하되, 탈퇴 제한을 해소할 소유 키링 관리 경로만 허용한다. */
@Composable fun SignedInServiceHost(session: Session.SignedIn, access: ProposalAccess = koinInject(),
    model: HomeSessionViewModel = koinViewModel(), pushes: PushEvents = koinInject(), inbox: NotificationInbox = koinInject(),
    // 기기 시험에서 BLE 부작용만 분리한다. 제품에서는 기존 Activity 범위의 자동 동기화를 사용한다.
    foregroundSync: @Composable (Boolean) -> Unit = { KeyringForegroundSync(suspendSync = it, allowEncounter = session.isReady) }) {
    val withdrawal: WithdrawalCoordinator = koinInject()
    // 탈퇴 루트가 닫힌 첫 프레임부터 목적지를 선택한다. 가입 화면을 먼저 구성해 이동을 다시 막지 않는다.
    // 회전은 저장된 화면을 복원하고, 다른 계정의 이동 요청은 초기값으로 사용하지 않는다.
    val pendingKeyring = withdrawal.state.value.keyringOwner == session.userId
    var opened by rememberSaveable(session.userId, session.isReady, session.status) { mutableStateOf(session.isReady || pendingKeyring) }
    var page by rememberSaveable(session.userId) { mutableStateOf(if (pendingKeyring) ServicePage.Keyrings else ServicePage.Home) }
    var roomId by rememberSaveable(session.userId) { mutableStateOf<Long?>(null) }
    var safetyOperation by rememberSaveable(session.userId) { mutableStateOf(SafetyOperation.Block) }
    var consentFromChat by rememberSaveable(session.userId) { mutableStateOf(false) }
    val pendingPush by pushes.pending.collectAsStateWithLifecycle()
    var help by rememberSaveable { mutableStateOf(HelpGuideKind.Terms) }
    val openNotificationSettings = rememberOpenNotificationSettings()
    var resetDeviceId by rememberSaveable(session.userId) { mutableStateOf<Long?>(null) }
    // 초기화하려다 키링 연결 화면으로 왔으면, 연결되는 대로 초기화 화면으로 돌려보낸다
    var connectForReset by rememberSaveable(session.userId) { mutableStateOf(false) }
    var logoutPrompt by rememberSaveable { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    // 같은 Activity의 동기화 모델을 BLE 화면에서도 공유한다. 가입 화면이 바뀌어도 대상 기록은 유지하며,
    // 앱이 화면에 떠 있는 동안 자동 발급한다(운영 · 개발 앱 같음). 회전/백그라운드에서는 취소 후 내구 기록으로 복구한다.
    // 새 키링의 등록 증명이 끝날 때까지 자동 동기화와 기록 전송이 같은 BLE 연결을 사용하지 않게 한다.
    foregroundSync(page == ServicePage.KeyringReset || page == ServicePage.KeyringRegistration || connectForReset)
    // 바의 선택 표시. 바가 사라지는 동안에도 마지막 탭에 머문다
    var tab by rememberSaveable(session.userId) { mutableIntStateOf(TabPages.indexOf(page).coerceAtLeast(0)) }
    fun go(destination: ServicePage) {
        model.leave(); page = destination
        TabPages.indexOf(destination).takeIf { it >= 0 }?.let { tab = it }
    }
    fun chats(room: Long? = null) { roomId = room; go(ServicePage.Chats) }
    fun proposals() { go(ServicePage.Proposals) }
    WithdrawalKeyringNavigation(session.userId, withdrawal) {
        // 탈퇴 제한 안내의 버튼도 메뉴와 같은 키링 관리 화면으로 이동한다. 다른 계정의 늦은 이동은 소비하지 않는다.
        opened = true
        go(ServicePage.Keyrings)
    }
    // 알림은 이 화면에서만 소비한다. 입력/저장 중 화면을 빼앗지 않고 홈·마이 복귀 후 이동한다.
    LaunchedEffect(pendingPush, session, page, opened, logoutPrompt) {
        val target = pendingPush ?: return@LaunchedEffect
        if (!opened || !session.isReady || logoutPrompt || !canOpenServicePush(page)) return@LaunchedEffect
        when (target) {
            is PushTarget.Chat -> if(target.roomId > 0) chats(target.roomId) else go(ServicePage.Home)
            is PushTarget.Proposal -> if (target.proposalId > 0) proposals() else go(ServicePage.Home)
            PushTarget.Home -> go(ServicePage.Home)
        }
        // 이동 후 상세 화면이 서버의 최신 참여/만료 권한을 다시 검사한다.
        pushes.consume(target)
    }
    fun load() { if (opened && access.allowed(session)) when (page) {
        ServicePage.Home -> model.home()
        ServicePage.Menu -> model.account()
        ServicePage.Help -> model.guide(help)
        else -> Unit
    } }
    LaunchedEffect(opened, page, session, state.session) { if (state.session == session) load() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { load() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.leave() }

    // 가입 상태를 READY로 바꾸거나 홈·제안·채팅을 열지 않는다. 소유 해제에 필요한 화면만 예외다.
    if (!opened || (!access.allowed(session) && !canManageOwnedKeyring(session, page))) {
        // 가입 중 화면에는 회원탈퇴를 두지 않는다(가입을 마친 뒤 내 정보에서 탈퇴한다).
        // 단, 가입을 마친 뒤 키링을 모두 초기화해 키링 화면에 갇힌 계정은 탈퇴 조건(키링 없음)을 갖췄으니 여기서 연다
        OnboardingRoute(session, managedKeyringConnection = true, onWithdraw = { withdrawal.open(session.userId) })
        return
    }
    if (state.session != session) return
    if (page in listOf(ServicePage.Home, ServicePage.Menu, ServicePage.Help, ServicePage.HelpHub, ServicePage.Preparing))
        ProfileBackHandler(page != ServicePage.Home || !session.isReady) { when (page) {
            ServicePage.Home -> { model.leave(); opened = false }
            ServicePage.Help -> go(ServicePage.HelpHub)
            ServicePage.HelpHub -> go(ServicePage.Menu)
            else -> go(ServicePage.Home)
        } }
    // 제안은 목록일 때만 루트다(Route 가 알린다). 페이지가 바뀌면 기본값으로 다시 정한다
    var tabRoot by remember(page) { mutableStateOf(true) }
    var tabEnabled by remember(page) { mutableStateOf(true) }
    // 채팅은 틀이 들고 있는 방 번호로 바로 판단한다(Route 의 알림을 기다리면 한 프레임 바가 남는다)
    val showBar = page in TabPages && tabRoot && !(page == ServicePage.Chats && roomId != null)
    // 틀은 내용에 인셋 · 배경을 주지 않는다. 탭 화면은 위 · 좌우 인셋만, 아래 인셋은 바가 맡는다
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (page) {
                ServicePage.Home -> {
                    // 카드가 접혀 있어도 기기 연결이 끊기거나 복구되면 바로 표시한다.
                    val connection = observeOwnedKeyringConnection(session, state.home.keyrings, keyringBleModel())
                    val unread by inbox.items.collectAsStateWithLifecycle(emptyList())
                    HomeScreen(state.home, model::setMatching, unreadNotifications = unread.any { !it.read }, onAction = { action -> when (action) {
                        HomeAction.Refresh -> {
                            connection.refresh?.invoke()
                            model.refreshHome()
                        }
                        HomeAction.Notifications -> go(ServicePage.Notifications)
                        HomeAction.RegisterKeyring -> go(ServicePage.KeyringRegistration)
                    } }, onSaveKeyringAlias = model::saveKeyringAlias, keyringStatus = { connection.liveStatus },
                        connectedKeyringId = connection.deviceId,
                        keyringPublicName = { id, name ->
                            state.home.keyrings.singleOrNull { it.deviceId == id }?.let { device ->
                                KeyringPublicNameControls(session.userId, device, name)
                            }
                        }, error = state.error, busy = state.busy)
                }
                ServicePage.Proposals -> ConnectedProposalRoute(session, { go(ServicePage.Home) }, { chats(it) }, pushes = pushes,
                    onTabBar = { root, enabled -> tabRoot = root; tabEnabled = enabled })
                ServicePage.Chats -> ConnectedChatRoute(session, roomId, { go(ServicePage.Home) }, { proposals() }, { go(ServicePage.Menu) },
                    onRoom = { roomId = it },
                    onSafety = { id, operation ->
                        roomId = id
                        if(operation == "REPORT") go(ServicePage.Report) else {
                            safetyOperation = if(operation == "BLOCK") SafetyOperation.Block else SafetyOperation.Leave
                            go(ServicePage.Safety)
                        }
                    }, onConsent = { consentFromChat = true; go(ServicePage.AiConsent) }, onViewing = pushes::viewing,
                    // 실제 초안을 전달해야 추천 선택 시 기존 입력의 교체 확인을 받을 수 있다.
                    support = { id, readable, editable, draft, insert, block, latest, used -> ConnectedChatExtras(id, readable, editable, insert, draft = draft, onBlock = block, latestMessage = latest, used = used) })
                ServicePage.Safety -> roomId?.let { id -> ConnectedSafetyRoute(session, id, safetyOperation,
                    onBack = { chats(id) }, onClosed = { chats() }) }
                ServicePage.Report -> roomId?.let { id -> ConnectedReportRoute(session, id, onBack = { chats(id) },
                    onBlock = { safetyOperation = SafetyOperation.Block; go(ServicePage.Safety) }) }
                ServicePage.AiConsent -> ConnectedAiConsentRoute(session, onBack = {
                    if(consentFromChat) chats() else go(ServicePage.Menu)
                })
                ServicePage.Profile -> ProfileManagementRoute(session, { go(ServicePage.Menu) })
                // 알림을 누르면 알림이 왔을 때와 같은 곳으로 간다(상세 화면이 최신 권한을 다시 확인한다)
                ServicePage.Notifications -> NotificationsRoute(session, { go(ServicePage.Home) }, onOpen = { target ->
                    when (target) {
                        is PushTarget.Chat -> chats(target.roomId)
                        is PushTarget.Proposal -> proposals()
                        PushTarget.Home -> go(ServicePage.Home)
                    }
                })
                ServicePage.Survey -> SurveyRoute(session, { go(ServicePage.Menu) })
                ServicePage.Settings -> ConnectedSettingsRoute(session, { go(ServicePage.Menu) })
                // 소유 정보 확인만 수행하므로 메뉴를 열었다고 등록/동기화를 요청하지 않는다.
                ServicePage.Keyrings -> ConnectedOwnedKeyringsRoute(session, { go(ServicePage.Menu) },
                    onReset = { id -> resetDeviceId = id; connectForReset = false; go(ServicePage.KeyringReset) },
                    onConnectForRecords = { connectForReset = false; go(ServicePage.BleProbe) }, ble = keyringBleModel(),
                    onRegister = if (session.isReady) ({ go(ServicePage.KeyringRegistration) }) else null,
                    onSync = if (session.isReady) ({ go(ServicePage.KeyringSync) }) else null)
                ServicePage.KeyringSync -> if (session.isReady) {
                    ConnectedOwnedKeyringSyncRoute(session, model::readKeyringAliases, onBack = { go(ServicePage.Keyrings) })
                } else LaunchedEffect(session) { go(ServicePage.Keyrings) }
                ServicePage.KeyringRegistration -> if (session.isReady) {
                    // 서버 소유 목록으로 등록을 확인한 뒤 관리 화면으로 복귀해 새 목록을 읽는다.
                    ConnectedKeyringSetupRoute({ go(ServicePage.Keyrings) }, managedConnection = true,
                        allowRegistration = true, onRegistrationDone = { go(ServicePage.Keyrings) })
                } else {
                    LaunchedEffect(session) { go(ServicePage.Keyrings) }
                }
                // 키링 초기화는 운영 · 개발 앱 같다. 초기화하려면 키링 연결이 필요해 연결 화면으로 보낸다
                ServicePage.KeyringReset -> resetDeviceId?.let { id ->
                    ConnectedKeyringResetRoute(id, { go(ServicePage.Keyrings) }, { connectForReset = true; go(ServicePage.BleProbe) })
                }
                // 연결 목적을 분리해 기록 메뉴에서 초기화 화면으로 이동하거나 등록 요청을 시작하지 않는다.
                ServicePage.BleProbe -> {
                    if (connectForReset) {
                        val link by keyringBleModel().state.collectAsStateWithLifecycle()
                        LaunchedEffect(link.connected && link.info != null) {
                            if (link.connected && link.info != null) { connectForReset = false; go(ServicePage.KeyringReset) }
                        }
                        ConnectedKeyringSetupRoute({ connectForReset = false; go(ServicePage.Keyrings) }, managedConnection = true)
                    } else ConnectedKeyringEncounterRoute { go(ServicePage.Keyrings) }
                }
                ServicePage.Menu -> AccountMenuScreen(state.account, onAction = { action -> when (action) {
                    AccountMenuAction.Refresh -> model.account()
                    AccountMenuAction.Consents -> { consentFromChat = false; go(ServicePage.AiConsent) }
                    AccountMenuAction.Profile -> go(ServicePage.Profile)
                    AccountMenuAction.Survey -> go(ServicePage.Survey)
                    AccountMenuAction.Matching -> go(ServicePage.Settings)
                    // 한 번 거절된 알림은 앱이 다시 켤 수 없어 휴대폰 설정의 이 앱 알림 화면으로 바로 보낸다
                    AccountMenuAction.Permissions -> openNotificationSettings()
                    AccountMenuAction.Keyring -> go(ServicePage.Keyrings)
                    AccountMenuAction.Logout -> logoutPrompt = true
                    // 확인 화면으로만 진입한다. 메뉴 터치가 준비/탈퇴 POST를 실행하지 않는다.
                    AccountMenuAction.Withdrawal -> withdrawal.open(session.userId)
                    AccountMenuAction.Help -> go(ServicePage.HelpHub)
                } })
                ServicePage.HelpHub -> HelpHubScreen(onOpen = { help = it; go(ServicePage.Help) }, onBack = { go(ServicePage.Menu) })
                ServicePage.Help -> HelpGuideScreen(state.guide ?: HelpGuideState(help, HelpGuideStage.Loading), onAction = { action -> when (action) {
                    HelpGuideAction.Back -> go(ServicePage.HelpHub)
                    HelpGuideAction.Reload -> model.guide(help)
                    HelpGuideAction.Open -> state.guide?.openUrl?.let { if (runCatching { uri.openUri(it) }.isFailure) model.guideOpenFailed() }
                } })
                ServicePage.Preparing -> OnboardingPanel(stringResource(Res.string.proposals_not_connected)) {
                    AppButton(stringResource(Res.string.proposals_return), onClick = { go(ServicePage.Home) })
                }
            }
        }
        // 바는 한 번만 그리고 탭을 바꾸면 위 내용만 바뀐다(내용 교체에는 전환 효과를 넣지 않는다)
        AnimatedVisibility(showBar, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
            AppBottomBar(listOf(stringResource(Res.string.home_tab), stringResource(Res.string.home_proposal_tab),
                stringResource(Res.string.home_chat_tab), stringResource(Res.string.home_profile_tab)), tab,
                // 지금 탭을 다시 누르면 아무것도 하지 않는다(go 가 조회만 취소하고 다시 받지 않는다)
                onSelect = { index -> when (val target = TabPages[index]) {
                    page -> Unit
                    ServicePage.Chats -> chats()
                    else -> go(target)
                } }, modifier = Modifier.navigationBarsPadding(), enabled = page != ServicePage.Proposals || tabEnabled)
        }
    }
    if (logoutPrompt) AppDialog(title = stringResource(Res.string.account_logout),
        description = stringResource(Res.string.account_logout_detail), confirmText = stringResource(Res.string.account_logout),
        dismissText = stringResource(Res.string.proposals_return), onConfirm = { logoutPrompt = false; model.logout() },
        onDismiss = { logoutPrompt = false })
}

/** 편집·전송·신고 확인 화면에서는 알림 이동을 보류한다. */
// 알림 모음은 입력 중인 내용이 없는 읽기 화면이라 바로 이동해도 된다
internal fun canOpenServicePush(page: ServicePage) = page in setOf(ServicePage.Home, ServicePage.Menu, ServicePage.Preparing, ServicePage.Notifications)

/** 약관 동의 이후의 가입 중 계정도 등록된 실물 키링을 해제할 수 있어야 한다. 서버 소유 검증은 각 API가 담당한다. */
internal fun canManageOwnedKeyring(session: Session.SignedIn, page: ServicePage): Boolean =
    session.status != OnboardingStatus.AGREEMENT_REQUIRED &&
        page in setOf(ServicePage.Keyrings, ServicePage.KeyringReset, ServicePage.BleProbe)
