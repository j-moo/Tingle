package com.ssafy.e106.features.onboarding.ui

import com.ssafy.e106.features.settings.ui.HubAgePreference
import com.ssafy.e106.features.settings.ui.HubSyncStart
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import com.ssafy.e106.features.onboarding.data.AgreementItem
import com.ssafy.e106.features.profile.ui.ProfileRoute
import com.ssafy.e106.features.profile.ui.ProfileManagementRoute
import com.ssafy.e106.features.profile.DevelopmentProfileAccess
import com.ssafy.e106.features.survey.ui.SurveyAccess
import com.ssafy.e106.features.survey.ui.SurveyRoute
import com.ssafy.e106.features.settings.SettingsAccess
import com.ssafy.e106.features.settings.ui.ConnectedSettingsRoute
import com.ssafy.e106.features.keyring.ui.ConnectedKeyringSetupRoute
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject

@Composable
fun OnboardingRoute(
    session: Session.SignedIn,
    model: OnboardingViewModel = koinViewModel(),
    profileAccess: DevelopmentProfileAccess = koinInject(),
    surveyAccess: SurveyAccess = koinInject(),
    settingsAccess: SettingsAccess = koinInject(),
    // 서비스 호스트가 키링 연결 · 설정 전송을 맡으면 true(실제 앱). 호스트 없이 이 화면만 쓰면 화면이 연결을 정리한다
    managedKeyringConnection: Boolean = false,
    /** 회원탈퇴 열기. 가입을 마친 계정이 키링 단계로 돌아왔을 때만 키링 화면에 보인다 */
    onWithdraw: (() -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    // 키링 등록(KEYRING_REQUIRED) · 첫 동기화(SYNC_PENDING) 단계의 키링 연결 화면은 운영 앱 · 개발 앱이 같다.
    // 실제 귀속은 등록 확인창 수락과 서버 검증 뒤에만 진행한다. 계정/가입 단계가 바뀌면 진입 상태를 버린다.
    val keyringStep = !session.isReady &&
        (session.status == OnboardingStatus.KEYRING_REQUIRED || session.status == OnboardingStatus.SYNC_PENDING)
    // 가입 입력이 끝나 SYNC_PENDING이 되면 연결/동기화 화면을 바로 연다. 권한은 로그인 직후 권한 시트에서 받았다.
    var openBleProbe by rememberSaveable(session.userId, session.status) {
        mutableStateOf(!session.isReady && session.status == OnboardingStatus.SYNC_PENDING)
    }
    val canManage = profileAccess.canManageProfile(session)
    var manageProfile by rememberSaveable(session.userId, session.status, session.isReady) { mutableStateOf(false) }
    var openSurvey by rememberSaveable(session.userId, session.status, session.isReady) { mutableStateOf(false) }
    val canSurvey = surveyAccess.allowed(session)
    // 개발 메뉴에서도 실제 API 권한을 사용하며 가입 상태를 바꾸지 않는다.
    var openSettings by rememberSaveable(session) { mutableStateOf(false) }
    val canSettings = settingsAccess.allowed(session)
    // 프로필은 허브의 "프로필 작성 시작"으로 연다. 허브가 마지막으로 펼쳐 둔 줄은 단계가 바뀌어도 남겨 접힘 · 펼침 연출에 쓴다
    var openProfile by rememberSaveable(session.userId, session.status) { mutableStateOf(false) }
    var hubSeen by rememberSaveable(session.userId) { mutableStateOf(-1) }
    when {
        // 연결 화면을 로딩 분기보다 먼저 둔다. 등록 뒤 가입 단계가 바뀌는 순간 로딩 화면이 끼면 연결 화면이 빠지면서 BLE 연결이 끊긴다
        keyringStep && openBleProbe -> ConnectedKeyringSetupRoute({ openBleProbe = false },
            managedConnection = managedKeyringConnection, allowRegistration = session.status == OnboardingStatus.KEYRING_REQUIRED)
        // 가입 정보 확인 중: 다음에 나올 화면의 틀을 그대로 두고 값 자리만 뼈대로
        state.session != session -> when {
            session.status == OnboardingStatus.AGREEMENT_REQUIRED -> OnboardingPanel(stringResource(Res.string.onboarding_agreements_title)) { AgreementSkeleton() }
            !session.isReady && session.status == OnboardingStatus.KEYRING_REQUIRED -> KeyringIntro(busy = true, error = null, linkFailed = false,
                purchaseUrl = null, onFind = null, onOpen = {}, onReload = {}, onRefresh = {}, onLogout = model::logout,
                onWithdraw = onWithdraw.takeIf { session.signupCompleted })
            else -> OnboardingPanel(stringResource(Res.string.onboarding_title)) { HubSkeleton() }
        }
        canSettings && openSettings -> ConnectedSettingsRoute(session, { openSettings = false })
        canSurvey && openSurvey -> SurveyRoute(session, { openSurvey = false })
        canManage && manageProfile -> ProfileManagementRoute(session, { manageProfile = false })
        openProfile && !session.isReady && session.status == OnboardingStatus.PROFILE_REQUIRED -> ProfileRoute(session, onBack = { openProfile = false })
        else -> OnboardingScreen(state, model::check, model::submit, model::load, model::refresh, model::logout,
            onWithdraw = onWithdraw.takeIf { session.signupCompleted },
            canManageProfile = canManage, onManageProfile = { if (canManage) manageProfile = true },
            canOpenSurvey = canSurvey, onOpenSurvey = { if (canSurvey) openSurvey = true },
            canOpenSettings = canSettings, onOpenSettings = { if (canSettings) openSettings = true },
            onBleProbe = if (keyringStep) ({ openBleProbe = true }) else null,
            onOpenProfile = { openProfile = true }, hubSeen = hubSeen, onHubSettled = { hubSeen = it },
            hubPreferences = if (canSettings && !session.isReady && session.status == OnboardingStatus.PREFERENCES_REQUIRED)
                ({ HubAgePreference(session, onOpenFull = { openSettings = true }) }) else null,
            hubSync = if (canSettings && !session.isReady && session.status == OnboardingStatus.PREFERENCES_REQUIRED)
                ({ HubSyncStart(session) }) else null,
            // 완료한 희망 나이는 그 줄 안에서 고친다(키링 동기화 단계)
            hubAgeEdit = if (canSettings && !session.isReady && session.status == OnboardingStatus.SYNC_PENDING)
                ({ HubAgePreference(session, onOpenFull = { openSettings = true }) }) else null)
    }
}

/** Pure screen: Preview passes data directly, without a Repository or a fake login. */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onCheck: (String, Boolean) -> Unit,
    onSubmit: () -> Unit,
    onReload: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    developmentNotice: String? = null,
    canManageProfile: Boolean = false,
    onManageProfile: () -> Unit = {},
    canOpenSurvey: Boolean = false,
    onOpenSurvey: () -> Unit = {},
    canOpenSettings: Boolean = false,
    onOpenSettings: () -> Unit = {},
    onBleProbe: (() -> Unit)? = null,
    onOpenProfile: () -> Unit = {},
    hubSeen: Int = -1,
    onHubSettled: (Int) -> Unit = {},
    /** 희망 나이 단계: 가입 준비 화면의 줄 안에 펼쳐 넣는 입력(새 화면으로 들어가지 않는다) */
    hubPreferences: (@Composable ColumnScope.() -> Unit)? = null,
    /** 희망 나이 단계에서 미리 열어 두는 키링 동기화 줄의 버튼 */
    hubSync: (@Composable ColumnScope.() -> Unit)? = null,
    /** 완료한 희망 나이 줄을 펼쳤을 때 그 자리에서 고치는 입력 */
    hubAgeEdit: (@Composable ColumnScope.() -> Unit)? = null,
    onWithdraw: (() -> Unit)? = null,
) {
    val session = state.session ?: return
    val agreements = session.status == OnboardingStatus.AGREEMENT_REQUIRED
    val keyring = !session.isReady && session.status == OnboardingStatus.KEYRING_REQUIRED
    val uri = LocalUriHandler.current
    var linkFailed by remember(session.userId, session.status) { mutableStateOf(false) }
    // 약관 "내용 보기" — 브라우저로 나가지 않고 이 화면 위에 전문을 띄운다(체크 상태 유지)
    var reading by remember(session.userId, session.status) { mutableStateOf<AgreementItem?>(null) }
    reading?.let { item -> item.reviewUrl?.let { url -> AgreementDocument(item.signupTitle, url, onClose = { reading = null }); return } }
    fun open(url: String) { linkFailed = runCatching { uri.openUri(url) }.isFailure }
    // 키링 단계: 가운데 키링 그림 · 키링 찾기 · 구매 안내(시안 02). 개발용 진입(설정 · 성향테스트 · 프로필 수정)은 두지 않는다
    if (keyring && !state.submitted) {
        KeyringIntro(state.busy, state.error?.let { it.stateTitle() }, linkFailed, state.purchaseUrl, onFind = onBleProbe, onOpen = ::open,
            onReload = onReload, onRefresh = onRefresh, onLogout = onLogout, onWithdraw = onWithdraw)
        return
    }
    // 프로필 · 성향테스트 · 희망 나이 · 동기화: 가입 준비 허브(아코디언). 단계별 시작 버튼만 두고 개발용 진입 · 다시 확인 버튼은 두지 않는다
    val hub = if (session.isReady || state.submitted) -1 else hubStep(session.status)
    if (hub > 0) {
        val start = when (session.status) {
            OnboardingStatus.PROFILE_REQUIRED -> onOpenProfile
            OnboardingStatus.SURVEY_REQUIRED -> onOpenSurvey.takeIf { canOpenSurvey }
            OnboardingStatus.PREFERENCES_REQUIRED -> onOpenSettings.takeIf { canOpenSettings }
            else -> onBleProbe
        }
        SignupHub(hub, hubSeen, onHubSettled, start, state.busy, state.error?.let { it.stateTitle() }, onLogout, onRefresh,
            inline = hubPreferences.takeIf { session.status == OnboardingStatus.PREFERENCES_REQUIRED },
            nextInline = hubSync.takeIf { session.status == OnboardingStatus.PREFERENCES_REQUIRED },
            // 완료한 단계 고치기: 프로필 = 프로필 관리 화면, 성향테스트 = 성향테스트 화면("답 수정하기"), 희망 나이 = 그 줄 안에서
            onEdit = { row -> when (row) {
                1 -> onManageProfile.takeIf { canManageProfile }
                2 -> onOpenSurvey.takeIf { canOpenSurvey }
                else -> null
            } },
            editInline = { row -> hubAgeEdit.takeIf { row == 3 } })
        return
    }
    // 약관 항목이 보이는 동안은 당겨도 다시 받지 않는다(다시 받으면 체크가 지워진다). 못 받았거나 저장 뒤 확인이 남았을 때만 당긴다
    val listed = agreements && state.requiredAgreements.isNotEmpty() && !(state.submitted && !state.busy)
    OnboardingPanel(stringResource(when {
        agreements -> Res.string.onboarding_agreements_title
        else -> Res.string.onboarding_title
    }), onRefresh = when { listed -> null; agreements && !state.submitted -> onReload; else -> onRefresh }) {
        when {
            // 동의는 저장했는데 다음 단계 확인이 남았다(가입 상태 조회 실패 등). 다시 저장하지 않고 상태만 다시 확인한다
            agreements && state.submitted && !state.busy -> if (state.error == null) StateView(StateKind.Done, stringResource(Res.string.onboarding_agreed),
                onRetry = onRefresh, retryText = stringResource(Res.string.onboarding_refresh))
            else StateView(state.error.stateKind(), state.error.stateTitle(), onRetry = onRefresh)
            // 항목을 받는 중(처음 · 다시 시도)
            agreements && state.requiredAgreements.isEmpty() && state.busy -> AgreementSkeleton()
            // 항목을 못 받음: 짧은 제목 한 줄 + 작은 "다시 시도"
            agreements && state.requiredAgreements.isEmpty() -> StateView(state.error.stateKind(), state.error.stateTitle(), onRetry = onReload)
            agreements -> {
                // 저장 실패 뒤 체크를 바꾸면 빨간 줄과 "다시 시도" 이름을 거둔다
                var edited by remember(state.error) { mutableStateOf(false) }
                val saveFailed = state.error != null && !edited
                val check = { type: String, on: Boolean -> edited = true; onCheck(type, on) }
                // 전체동의 · 구분선 · 필수 항목(오른쪽 내용 보기) · 다음. 프로필 사진 · 자기소개 동의도 여기서 필수로, 종교는 맨 아래 선택으로 받는다
                if (state.allowUnpublishedAgreements && state.requiredAgreements.any { !it.canReview }) {
                    Text(developmentNotice ?: stringResource(Res.string.onboarding_development_agreements), style = MaterialTheme.typography.bodySmall)
                }
                // 전체동의는 선택 항목(종교)까지 함께 체크한다
                val checkable = (state.requiredAgreements + state.optionalAgreements).filter { state.canCheck(it) }
                if (state.requiredAgreements.isNotEmpty()) {
                    AgreementRow(stringResource(Res.string.onboarding_agree_all), strong = true,
                        checked = checkable.isNotEmpty() && checkable.all { it.type in state.checked },
                        enabled = !state.busy && checkable.isNotEmpty(),
                        onToggle = { on -> checkable.forEach { check(it.type, on) } })
                    HorizontalDivider(color = AppColors.BorderSubtle)
                    AgreementGroupTitle(Res.string.onboarding_group_required)
                }
                state.requiredAgreements.forEach { item ->
                    AgreementRow(stringResource(Res.string.onboarding_required, item.signupTitle), item.type in state.checked,
                        enabled = !state.busy && state.canCheck(item), onToggle = { check(item.type, it) },
                        onRead = item.reviewUrl?.let { { reading = item } })
                    if (!item.canReview && !state.allowUnpublishedAgreements) {
                        Text(stringResource(Res.string.onboarding_terms_missing), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                }
                if (state.optionalAgreements.isNotEmpty()) AgreementGroupTitle(Res.string.onboarding_group_optional)
                state.optionalAgreements.forEach { item ->
                    AgreementRow(stringResource(Res.string.onboarding_optional, item.signupTitle), item.type in state.checked,
                        enabled = !state.busy && state.canCheck(item), onToggle = { check(item.type, it) },
                        onRead = item.reviewUrl?.let { { reading = item } })
                }
                // 저장 실패: 체크는 그대로 두고 버튼 바로 위에 빨간 한 줄, 버튼 이름은 "다시 시도"
                if (saveFailed) state.error?.let { InlineError(it.message, Modifier.padding(top = AppSpacing.Large)) }
                AppButton(stringResource(if (saveFailed) Res.string.state_retry else Res.string.onboarding_agree), enabled = state.canSubmit, loading = state.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = if (saveFailed) 0.dp else AppSpacing.Large), onClick = onSubmit)
            }
            else -> Text(stringResource(Res.string.onboarding_ready_wait))
        }
        if (!agreements) state.error?.let { OnboardingError(it.message) }
        if (onBleProbe != null) AppButton(stringResource(Res.string.sync_live_open), enabled = !state.busy, onClick = onBleProbe)
        if (canOpenSettings) AppButton(stringResource(Res.string.settings_api_entry), enabled = !state.busy, onClick = onOpenSettings)
        if (canOpenSurvey) AppButton(stringResource(Res.string.survey_api_entry),
            enabled = !state.busy, onClick = onOpenSurvey)
        if (canManageProfile) AppButton(stringResource(Res.string.profile_manage_entry),
            enabled = !state.busy, onClick = onManageProfile)
        if (linkFailed) OnboardingError(stringResource(Res.string.onboarding_link_failed))
        if (!agreements) AppButton(stringResource(Res.string.onboarding_refresh),
            loading = state.busy, enabled = !state.busy, onClick = onRefresh)
        // 약관 동의는 시트의 ✕ 가 로그아웃이라 따로 두지 않는다
        if (!agreements) TextButton(onClick = onLogout, enabled = !state.busy) { Text(stringResource(Res.string.onboarding_logout)) }
    }
}

/**
 * 가입 단계 공용 틀: 큰 제목 + 스크롤 내용. [onRefresh] 를 주면 당겨서 새로고침이 되고(고리는 [refreshing] 일 때만 돈다),
 * [bottom] 은 스크롤 밖 맨 아래에 고정된다(아래 띠 · 고정 버튼). 시스템 버튼 영역 위에서 끝난다.
 */
@Composable
internal fun OnboardingPanel(title: String, refreshing: Boolean = false, onRefresh: (() -> Unit)? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            val body: @Composable BoxScope.() -> Unit = {
                // 내용이 짧아도 빈 곳을 당길 수 있게 화면 높이를 다 쓴다
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(screenPaddingValues()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    content()
                }
            }
            if (onRefresh != null) PullDownRefreshBox(refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth(), content = body)
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter, content = body)
            if (bottom != null) Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), content = bottom)
        }
    }
}

/**
 * 당겨서 시작한 새로고침에만 고리를 돌린다. 화면에 들어오며 도는 불러오기는 뼈대가 알리므로 고리를 겹쳐 보이지 않는다.
 * 돌려주는 값: (고리를 돌릴지, 당겼을 때 부를 함수)
 */
@Composable
internal fun pullRefresh(busy: Boolean, onRefresh: () -> Unit): Pair<Boolean, () -> Unit> {
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (!busy) pulled = false }
    return (pulled && busy) to { pulled = true; onRefresh() }
}

/** 약관 항목을 받는 중: 전체동의 · 항목 줄 자리에 뼈대. "다음"은 실제 자리에 막아 둔다 */
@Composable
private fun AgreementSkeleton() {
    SkeletonGroup(spacing = AppSpacing.Section) { repeat(5) { SkeletonBone(Modifier.fillMaxWidth(if (it == 0) 0.5f else 0.8f), height = 20.dp) } }
    AppButton(stringResource(Res.string.onboarding_agree), Modifier.fillMaxWidth().padding(top = AppSpacing.Large), enabled = false, onClick = {})
}
@Composable internal fun OnboardingError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** 동의 시트의 묶음 이름(필수 동의 · 선택 동의) */
@Composable private fun AgreementGroupTitle(text: org.jetbrains.compose.resources.StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary,
        modifier = Modifier.padding(top = AppSpacing.Small).semantics { heading() })
}
