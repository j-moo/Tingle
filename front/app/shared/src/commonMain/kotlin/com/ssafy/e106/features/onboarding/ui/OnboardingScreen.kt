package com.ssafy.e106.features.onboarding.ui

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
    managedKeyringConnection: Boolean = false,
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
    when {
        // 연결 화면을 로딩 분기보다 먼저 둔다. 등록 뒤 가입 단계가 바뀌는 순간 로딩 화면이 끼면 연결 화면이 빠지면서 BLE 연결이 끊긴다
        // managedKeyringConnection 은 개발 앱 서비스 호스트에서만 true — 기기 진단도 그때만 보인다
        keyringStep && openBleProbe -> ConnectedKeyringSetupRoute({ openBleProbe = false }, managedConnection = managedKeyringConnection,
            diagnostics = managedKeyringConnection)
        state.session != session -> OnboardingPanel(stringResource(Res.string.onboarding_title)) { Text(stringResource(Res.string.onboarding_loading)) }
        canSettings && openSettings -> ConnectedSettingsRoute(session, { openSettings = false }, showKeyringSync = managedKeyringConnection)
        canSurvey && openSurvey -> SurveyRoute(session, { openSurvey = false })
        canManage && manageProfile -> ProfileManagementRoute(session, { manageProfile = false })
        !session.isReady && session.status == OnboardingStatus.PROFILE_REQUIRED -> ProfileRoute(session)
        else -> OnboardingScreen(state, model::check, model::submit, model::load, model::refresh, model::logout,
            canManageProfile = canManage, onManageProfile = { if (canManage) manageProfile = true },
            canOpenSurvey = canSurvey, onOpenSurvey = { if (canSurvey) openSurvey = true },
            canOpenSettings = canSettings, onOpenSettings = { if (canSettings) openSettings = true },
            onBleProbe = if (keyringStep) ({ openBleProbe = true }) else null)
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
) {
    val session = state.session ?: return
    val agreements = session.status == OnboardingStatus.AGREEMENT_REQUIRED
    val keyring = !session.isReady && session.status == OnboardingStatus.KEYRING_REQUIRED
    val uri = LocalUriHandler.current
    var linkFailed by remember(session.userId, session.status) { mutableStateOf(false) }
    // 약관 "내용 보기" — 브라우저로 나가지 않고 이 화면 위에 전문을 띄운다(체크 상태 유지)
    var reading by remember(session.userId, session.status) { mutableStateOf<AgreementItem?>(null) }
    reading?.let { item -> item.reviewUrl?.let { url -> AgreementDocument(item.title, url, onClose = { reading = null }); return } }
    fun open(url: String) { linkFailed = runCatching { uri.openUri(url) }.isFailure }
    // 키링 단계: 가운데 키링 그림 · 키링 찾기 · 구매 안내(시안 02). 개발용 진입(설정 · 성향테스트 · 프로필 수정)은 두지 않는다
    if (keyring && !state.submitted) {
        KeyringIntro(state.busy, state.error?.message, linkFailed, state.purchaseUrl, onFind = onBleProbe, onOpen = ::open,
            onReload = onReload, onRefresh = onRefresh, onLogout = onLogout)
        return
    }
    OnboardingPanel(stringResource(when {
        agreements -> Res.string.onboarding_agreements_title
        else -> Res.string.onboarding_title
    })) {
        when {
            state.submitted -> Text(stringResource(Res.string.onboarding_saved))
            agreements -> {
                // 전체동의 · 구분선 · 필수 항목(오른쪽 내용 보기) · 다음. 선택 동의(사진 · 자기소개 · 종교)는 프로필에서 따로 받는다
                if (state.allowUnpublishedAgreements && state.requiredAgreements.any { !it.canReview }) {
                    Text(developmentNotice ?: stringResource(Res.string.onboarding_development_agreements), style = MaterialTheme.typography.bodySmall)
                }
                val checkable = state.requiredAgreements.filter { state.canCheck(it) }
                if (state.requiredAgreements.isNotEmpty()) {
                    AgreementRow(stringResource(Res.string.onboarding_agree_all), strong = true,
                        checked = checkable.isNotEmpty() && checkable.all { it.type in state.checked },
                        enabled = !state.busy && checkable.isNotEmpty(),
                        onToggle = { on -> checkable.forEach { onCheck(it.type, on) } })
                    HorizontalDivider(color = AppColors.BorderSubtle)
                }
                state.requiredAgreements.forEach { item ->
                    AgreementRow(stringResource(Res.string.onboarding_required, item.title), item.type in state.checked,
                        enabled = !state.busy && state.canCheck(item), onToggle = { onCheck(item.type, it) },
                        onRead = item.reviewUrl?.let { { reading = item } })
                    if (!item.canReview && !state.allowUnpublishedAgreements) {
                        Text(stringResource(Res.string.onboarding_terms_missing), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                }
                if (!state.busy && state.requiredAgreements.isEmpty()) Text(stringResource(Res.string.onboarding_terms_empty))
                AppButton(stringResource(Res.string.onboarding_agree), enabled = state.canSubmit, loading = state.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.Large), onClick = onSubmit)
                // 항목을 못 받았거나 저장에 실패했을 때만 다시 불러오기
                if (state.requiredAgreements.isEmpty() || state.error != null) AppButton(stringResource(Res.string.onboarding_retry),
                    variant = AppButtonVariant.Secondary, enabled = !state.busy, onClick = onReload)
            }
            else -> Text(stringResource(when {
                session.isReady -> Res.string.onboarding_ready_wait
                session.status == OnboardingStatus.PROFILE_REQUIRED -> Res.string.onboarding_profile_wait
                session.status == OnboardingStatus.SURVEY_REQUIRED -> Res.string.onboarding_survey_wait
                session.status == OnboardingStatus.PREFERENCES_REQUIRED -> Res.string.onboarding_preferences_wait
                else -> Res.string.onboarding_sync_wait
            }))
        }
        state.error?.let { OnboardingError(it.message) }
        if (onBleProbe != null) AppButton(stringResource(Res.string.sync_live_open), enabled = !state.busy, onClick = onBleProbe)
        if (canOpenSettings) AppButton(stringResource(Res.string.settings_api_entry), enabled = !state.busy, onClick = onOpenSettings)
        if (canOpenSurvey) AppButton(stringResource(Res.string.survey_api_entry),
            enabled = !state.busy, onClick = onOpenSurvey)
        if (canManageProfile) AppButton(stringResource(Res.string.profile_manage_entry),
            enabled = !state.busy, onClick = onManageProfile)
        if (linkFailed) OnboardingError(stringResource(Res.string.onboarding_link_failed))
        if (!agreements || state.submitted) AppButton(stringResource(Res.string.onboarding_refresh),
            loading = state.busy, enabled = !state.busy, onClick = onRefresh)
        // 약관 동의는 시트의 ✕ 가 로그아웃이라 따로 두지 않는다
        if (!agreements) TextButton(onClick = onLogout, enabled = !state.busy) { Text(stringResource(Res.string.onboarding_logout)) }
    }
}

@Composable
internal fun OnboardingPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(screenPaddingValues()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}
@Composable internal fun OnboardingError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
