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
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// Keyrings는 서버의 등록 목록을 확인하는 경로이며 BLE 등록/초기화 경로와 구분한다.
internal enum class ServicePage { Home, Proposals, Menu, Chats, Safety, Report, AiConsent, Profile, Survey, Settings, Help, Permissions, Keyrings, Preparing }

/** 서버의 가입 완료 여부와 개발 화면 진입을 분리한다. 개발 진입으로 Session을 READY로 바꾸지 않는다. */
@Composable fun SignedInServiceHost(session: Session.SignedIn, access: ProposalAccess = koinInject(),
    model: HomeSessionViewModel = koinViewModel(), pushes: PushEvents = koinInject()) {
    var opened by rememberSaveable(session.userId, session.isReady, session.status) { mutableStateOf(session.isReady) }
    var page by rememberSaveable(session.userId) { mutableStateOf(ServicePage.Home) }
    var roomId by rememberSaveable(session.userId) { mutableStateOf<Long?>(null) }
    var proposalId by rememberSaveable(session.userId) { mutableStateOf<Long?>(null) }
    var safetyOperation by rememberSaveable(session.userId) { mutableStateOf(SafetyOperation.Block) }
    var consentFromChat by rememberSaveable(session.userId) { mutableStateOf(false) }
    val pendingPush by pushes.pending.collectAsStateWithLifecycle()
    var help by rememberSaveable { mutableStateOf(HelpGuideKind.Terms) }
    // 키링 정보를 홈/마이 어느 쪽에서 열었는지 보존해 같은 메뉴로 돌아간다.
    var keyringFromMenu by rememberSaveable(session.userId) { mutableStateOf(false) }
    var logoutPrompt by rememberSaveable { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    fun go(destination: ServicePage) { model.leave(); page = destination }
    fun chats(room: Long? = null) { roomId = room; go(ServicePage.Chats) }
    fun proposals() { proposalId = null; go(ServicePage.Proposals) }
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
    fun load() { if (opened) when (page) {
        ServicePage.Home -> model.home()
        ServicePage.Menu -> model.account()
        ServicePage.Help -> model.guide(help)
        else -> Unit
    } }
    LaunchedEffect(opened, page, session, state.session) { if (state.session == session) load() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { load() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.leave() }

    if (!opened || !access.allowed(session)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { OnboardingRoute(session) }
            if (access.development && access.allowed(session)) AppButton(stringResource(Res.string.proposals_development_entry),
                Modifier.fillMaxWidth().navigationBarsPadding().padding(AppSpacing.Medium), onClick = { page = ServicePage.Home; opened = true })
        }
        return
    }
    if (state.session != session) return
    if (page in listOf(ServicePage.Home, ServicePage.Menu, ServicePage.Help, ServicePage.Permissions, ServicePage.Preparing))
        ProfileBackHandler(page != ServicePage.Home || !session.isReady) {
            if (page == ServicePage.Home) { model.leave(); opened = false } else go(ServicePage.Home)
        }
    Column(Modifier.fillMaxSize()) {
        if (!session.isReady) TextButton(onClick = { model.leave(); opened = false }, modifier = Modifier.statusBarsPadding()) {
            Text(stringResource(Res.string.proposals_development_notice))
        }
        if (page in listOf(ServicePage.Home, ServicePage.Menu)) state.error?.let {
            Text(it.message, Modifier.padding(AppSpacing.Medium), color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                ServicePage.Home -> HomeScreen(state.home, { go(ServicePage.Settings) }, onAction = { action -> when (action) {
                    HomeAction.Refresh -> model.home()
                    HomeAction.Proposals -> proposals()
                    HomeAction.Profile -> go(ServicePage.Menu)
                    HomeAction.Preferences -> go(ServicePage.Settings)
                    HomeAction.Chats -> chats()
                    HomeAction.Keyring -> { keyringFromMenu = false; go(ServicePage.Keyrings) }
                } }, onOpenMatchingSettings = { go(ServicePage.Settings) })
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
                ServicePage.Report -> roomId?.let { id -> ConnectedReportRoute(session, id, onBack = { chats(id) }) }
                ServicePage.AiConsent -> ConnectedAiConsentRoute(session, onBack = {
                    if(consentFromChat) chats() else go(ServicePage.Menu)
                })
                ServicePage.Profile -> ProfileManagementRoute(session, { go(ServicePage.Menu) })
                ServicePage.Survey -> SurveyRoute(session, { go(ServicePage.Menu) })
                ServicePage.Settings -> ConnectedSettingsRoute(session, { go(ServicePage.Menu) })
                // 소유 정보 확인만 수행하므로 메뉴를 열었다고 등록/동기화를 요청하지 않는다.
                ServicePage.Keyrings -> ConnectedOwnedKeyringsRoute(session, { go(if (keyringFromMenu) ServicePage.Menu else ServicePage.Home) })
                ServicePage.Menu -> AccountMenuScreen(state.account, onAction = { action -> when (action) {
                    AccountMenuAction.Refresh -> model.account()
                    AccountMenuAction.Home -> go(ServicePage.Home)
                    AccountMenuAction.Proposals -> proposals()
                    AccountMenuAction.Chats -> chats()
                    AccountMenuAction.Consents -> { consentFromChat = false; go(ServicePage.AiConsent) }
                    AccountMenuAction.Profile -> go(ServicePage.Profile)
                    AccountMenuAction.Survey -> go(ServicePage.Survey)
                    AccountMenuAction.Matching -> go(ServicePage.Settings)
                    AccountMenuAction.Permissions -> go(ServicePage.Permissions)
                    AccountMenuAction.Keyring -> { keyringFromMenu = true; go(ServicePage.Keyrings) }
                    AccountMenuAction.Logout -> logoutPrompt = true
                    AccountMenuAction.Terms, AccountMenuAction.Privacy, AccountMenuAction.Support, AccountMenuAction.Correction -> {
                        help = when (action) {
                            AccountMenuAction.Terms -> HelpGuideKind.Terms
                            AccountMenuAction.Privacy -> HelpGuideKind.PrivacyPolicy
                            AccountMenuAction.Support -> HelpGuideKind.Support
                            else -> HelpGuideKind.Correction
                        }; go(ServicePage.Help)
                    }
                    else -> go(ServicePage.Preparing)
                } })
                ServicePage.Help -> HelpGuideScreen(state.guide ?: HelpGuideState(help, HelpGuideStage.Loading), onAction = { action -> when (action) {
                    HelpGuideAction.Back -> go(ServicePage.Menu)
                    HelpGuideAction.Reload -> model.guide(help)
                    HelpGuideAction.Open -> state.guide?.openUrl?.let { if (runCatching { uri.openUri(it) }.isFailure) model.guideOpenFailed() }
                } })
                // 알림은 실제 OS 기반으로 관찰한다. 아직 없는 BLE 관찰 결과는 화면에서 준비 중으로 구분한다.
                ServicePage.Permissions -> ConnectedDevicePermissionsRoute(onBack = { go(ServicePage.Menu) })
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
