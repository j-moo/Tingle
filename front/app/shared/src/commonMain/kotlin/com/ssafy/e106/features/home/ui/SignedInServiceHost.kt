package com.ssafy.e106.features.home.ui

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
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.onboarding.ui.*
import com.ssafy.e106.features.profile.ui.*
import com.ssafy.e106.features.proposals.ProposalAccess
import com.ssafy.e106.features.proposals.ui.ConnectedProposalRoute
import com.ssafy.e106.features.settings.ui.ConnectedSettingsRoute
import com.ssafy.e106.features.survey.ui.SurveyRoute
import com.ssafy.e106.features.keyring.ui.ConnectedOwnedKeyringsRoute
import com.ssafy.e106.features.keyring.ui.ConnectedKeyringSetupRoute
import com.ssafy.e106.features.keyring.ui.ConnectedKeyringEncounterRoute
import com.ssafy.e106.features.keyring.ui.KeyringForegroundSync
import com.ssafy.e106.features.keyring.ui.ConnectedKeyringResetRoute
import com.ssafy.e106.features.keyring.ui.BleProbeViewModel
import com.ssafy.e106.features.keyring.ui.observeOwnedKeyringConnection
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// Keyrings는 서버의 등록 목록을 확인하는 경로이며 BLE 등록/초기화 경로와 구분한다.
internal enum class ServicePage { Home, Proposals, Menu, Chats, Safety, Report, AiConsent, Profile, Survey, Settings, Help, HelpHub, Keyrings, BleProbe, KeyringReset, Preparing }

/** 가입 중에는 가입 화면을 유지하되, 탈퇴 제한을 해소할 소유 키링 관리 경로만 허용한다. */
@Composable fun SignedInServiceHost(session: Session.SignedIn, access: ProposalAccess = koinInject(),
    model: HomeSessionViewModel = koinViewModel(), pushes: PushEvents = koinInject(),
    // 기기 시험에서 BLE 부작용만 분리한다. 제품에서는 기존 Activity 범위의 자동 동기화를 사용한다.
    foregroundSync: @Composable (Boolean) -> Unit = { KeyringForegroundSync(suspendSync = it, allowEncounter = session.isReady) }) {
    val withdrawal: WithdrawalCoordinator = koinInject()
    // 탈퇴 루트가 닫힌 첫 프레임부터 목적지를 선택한다. 가입 화면을 먼저 구성해 이동을 다시 막지 않는다.
    // 회전은 저장된 화면을 복원하고, 다른 계정의 이동 요청은 초기값으로 사용하지 않는다.
    val pendingKeyring = withdrawal.state.value.keyringOwner == session.userId
    var opened by rememberSaveable(session.userId, session.isReady, session.status) { mutableStateOf(session.isReady || pendingKeyring) }
    var page by rememberSaveable(session.userId) { mutableStateOf(if (pendingKeyring) ServicePage.Keyrings else ServicePage.Home) }
    var roomId by rememberSaveable(session.userId) { mutableStateOf<Long?>(null) }
    var proposalId by rememberSaveable(session.userId) { mutableStateOf<Long?>(null) }
    var safetyOperation by rememberSaveable(session.userId) { mutableStateOf(SafetyOperation.Block) }
    var consentFromChat by rememberSaveable(session.userId) { mutableStateOf(false) }
    val pendingPush by pushes.pending.collectAsStateWithLifecycle()
    var help by rememberSaveable { mutableStateOf(HelpGuideKind.Terms) }
    val openNotificationSettings = rememberOpenNotificationSettings()
    // 키링 정보를 홈/마이 어느 쪽에서 열었는지 보존해 같은 메뉴로 돌아간다.
    var keyringFromMenu by rememberSaveable(session.userId) { mutableStateOf(false) }
    var resetDeviceId by rememberSaveable(session.userId) { mutableStateOf<Long?>(null) }
    // 초기화하려다 키링 연결 화면으로 왔으면, 연결되는 대로 초기화 화면으로 돌려보낸다
    var connectForReset by rememberSaveable(session.userId) { mutableStateOf(false) }
    var logoutPrompt by rememberSaveable { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    // 같은 Activity의 동기화 모델을 BLE 화면에서도 공유한다. 가입 화면이 바뀌어도 대상 기록은 유지하며,
    // 앱이 화면에 떠 있는 동안 자동 발급한다(운영 · 개발 앱 같음). 회전/백그라운드에서는 취소 후 내구 기록으로 복구한다.
    foregroundSync(page == ServicePage.KeyringReset || connectForReset)
    fun go(destination: ServicePage) { model.leave(); page = destination }
    fun chats(room: Long? = null) { roomId = room; go(ServicePage.Chats) }
    fun proposals() { proposalId = null; go(ServicePage.Proposals) }
    WithdrawalKeyringNavigation(session.userId, withdrawal) {
        // 탈퇴 제한 안내의 버튼도 메뉴와 같은 키링 관리 화면으로 이동한다. 다른 계정의 늦은 이동은 소비하지 않는다.
        opened = true
        keyringFromMenu = true
        go(ServicePage.Keyrings)
    }
    // 알림은 이 화면에서만 소비한다. 입력/저장 중 화면을 빼앗지 않고 홈·마이 복귀 후 이동한다.
    LaunchedEffect(pendingPush, session, page, opened, logoutPrompt) {
        val target = pendingPush ?: return@LaunchedEffect
        if (!opened || !session.isReady || logoutPrompt || !canOpenServicePush(page)) return@LaunchedEffect
        when (target) {
            is PushTarget.Chat -> if(target.roomId > 0) chats(target.roomId) else go(ServicePage.Home)
            is PushTarget.Proposal -> if(target.proposalId > 0) { proposalId = target.proposalId; go(ServicePage.Proposals) } else go(ServicePage.Home)
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
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { OnboardingRoute(session, managedKeyringConnection = true) }
            // BE 탈퇴 API는 가입 중에도 허용한다. 키링 미등록 계정이 홈에 도달할 때까지 기다리지 않는다.
            TextButton(onClick = { withdrawal.open(session.userId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.account_withdrawal))
            }
        }
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
    Column(Modifier.fillMaxSize()) {
        if (page in listOf(ServicePage.Home, ServicePage.Menu)) state.error?.let {
            Text(it.message, Modifier.padding(AppSpacing.Medium), color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                ServicePage.Home -> HomeScreen(state.home, model::setMatching, onAction = { action -> when (action) {
                    HomeAction.Refresh -> model.home()
                    HomeAction.Proposals -> proposals()
                    HomeAction.Profile -> go(ServicePage.Menu)
                    HomeAction.Chats -> chats()
                    HomeAction.Keyring -> { keyringFromMenu = false; go(ServicePage.Keyrings) }
                } }, keyringStatus = {
                    observeOwnedKeyringConnection(session, state.home.keyrings, koinViewModel<BleProbeViewModel>()).liveStatus
                })
                ServicePage.Proposals -> ConnectedProposalRoute(session, { go(ServicePage.Home) }, { go(ServicePage.Menu) },
                    { chats() }, { chats(it) }, initialProposalId = proposalId)
                ServicePage.Chats -> ConnectedChatRoute(session, roomId, { go(ServicePage.Home) }, { proposals() }, { go(ServicePage.Menu) },
                    onSafety = { id, operation ->
                        roomId = id
                        if(operation == "REPORT") go(ServicePage.Report) else {
                            safetyOperation = if(operation == "BLOCK") SafetyOperation.Block else SafetyOperation.Leave
                            go(ServicePage.Safety)
                        }
                    }, onConsent = { consentFromChat = true; go(ServicePage.AiConsent) },
                    // 실제 초안을 전달해야 추천 선택 시 기존 입력의 교체 확인을 받을 수 있다.
                    support = { id, readable, editable, draft, insert -> ConnectedChatExtras(id, readable, editable, insert, draft = draft) })
                ServicePage.Safety -> roomId?.let { id -> ConnectedSafetyRoute(session, id, safetyOperation,
                    onBack = { chats(id) }, onClosed = { chats() }) }
                ServicePage.Report -> roomId?.let { id -> ConnectedReportRoute(session, id, onBack = { chats(id) },
                    onBlock = { safetyOperation = SafetyOperation.Block; go(ServicePage.Safety) }) }
                ServicePage.AiConsent -> ConnectedAiConsentRoute(session, onBack = {
                    if(consentFromChat) chats() else go(ServicePage.Menu)
                })
                ServicePage.Profile -> ProfileManagementRoute(session, { go(ServicePage.Menu) })
                ServicePage.Survey -> SurveyRoute(session, { go(ServicePage.Menu) })
                ServicePage.Settings -> ConnectedSettingsRoute(session, { go(ServicePage.Menu) })
                // 소유 정보 확인만 수행하므로 메뉴를 열었다고 등록/동기화를 요청하지 않는다.
                ServicePage.Keyrings -> ConnectedOwnedKeyringsRoute(session, { go(if (keyringFromMenu) ServicePage.Menu else ServicePage.Home) },
                    onReset = { id -> resetDeviceId = id; connectForReset = false; go(ServicePage.KeyringReset) },
                    onConnectForRecords = { connectForReset = false; go(ServicePage.BleProbe) }, ble = koinViewModel())
                // 키링 초기화는 운영 · 개발 앱 같다. 초기화하려면 키링 연결이 필요해 연결 화면으로 보낸다
                ServicePage.KeyringReset -> resetDeviceId?.let { id ->
                    ConnectedKeyringResetRoute(id, { go(ServicePage.Keyrings) }, { connectForReset = true; go(ServicePage.BleProbe) })
                }
                // 연결 목적을 분리해 기록 메뉴에서 초기화 화면으로 이동하거나 등록 요청을 시작하지 않는다.
                ServicePage.BleProbe -> {
                    if (connectForReset) {
                        val link by koinViewModel<BleProbeViewModel>().state.collectAsStateWithLifecycle()
                        LaunchedEffect(link.connected && link.info != null) {
                            if (link.connected && link.info != null) { connectForReset = false; go(ServicePage.KeyringReset) }
                        }
                        ConnectedKeyringSetupRoute({ connectForReset = false; go(ServicePage.Keyrings) }, managedConnection = true)
                    } else ConnectedKeyringEncounterRoute { go(ServicePage.Keyrings) }
                }
                ServicePage.Menu -> AccountMenuScreen(state.account, onAction = { action -> when (action) {
                    AccountMenuAction.Refresh -> model.account()
                    AccountMenuAction.Home -> go(ServicePage.Home)
                    AccountMenuAction.Proposals -> proposals()
                    AccountMenuAction.Chats -> chats()
                    AccountMenuAction.Consents -> { consentFromChat = false; go(ServicePage.AiConsent) }
                    AccountMenuAction.Profile -> go(ServicePage.Profile)
                    AccountMenuAction.Survey -> go(ServicePage.Survey)
                    AccountMenuAction.Matching -> go(ServicePage.Settings)
                    // 한 번 거절된 알림은 앱이 다시 켤 수 없어 휴대폰 설정의 이 앱 알림 화면으로 바로 보낸다
                    AccountMenuAction.Permissions -> openNotificationSettings()
                    AccountMenuAction.Keyring -> { keyringFromMenu = true; go(ServicePage.Keyrings) }
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
    }
    if (logoutPrompt) AppDialog(title = stringResource(Res.string.account_logout),
        description = stringResource(Res.string.account_logout_detail), confirmText = stringResource(Res.string.account_logout),
        dismissText = stringResource(Res.string.proposals_return), onConfirm = { logoutPrompt = false; model.logout() },
        onDismiss = { logoutPrompt = false })
}

/** 편집·전송·신고 확인 화면에서는 알림 이동을 보류한다. */
internal fun canOpenServicePush(page: ServicePage) = page in setOf(ServicePage.Home, ServicePage.Menu, ServicePage.Preparing)

/** 약관 동의 이후의 가입 중 계정도 등록된 실물 키링을 해제할 수 있어야 한다. 서버 소유 검증은 각 API가 담당한다. */
internal fun canManageOwnedKeyring(session: Session.SignedIn, page: ServicePage): Boolean =
    session.status != OnboardingStatus.AGREEMENT_REQUIRED &&
        page in setOf(ServicePage.Keyrings, ServicePage.KeyringReset, ServicePage.BleProbe)
