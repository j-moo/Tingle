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
    var developmentProfile by rememberSaveable(session.userId, session.status, session.isReady) { mutableStateOf(false) }
    val canSkip = profileAccess.canSkipKeyring(session)
    // 등록 전 계정도 개발 앱에서 실물 통신을 확인한다. 실제 귀속은 별도 확인창 수락과 서버 검증 뒤에만 진행한다.
    // 계정/가입 단계 변경 시 진입 상태를 버린다. 연결은 서비스 호스트가 있으면 그 생명주기를 따른다.
    val canSync = profileAccess.canSyncKeyring(session)
    // 가입 입력이 끝나 SYNC_PENDING이 되면 연결/동기화 안내를 연다. 실제 탐색은 OS 권한 이후에 진행한다.
    var openBleProbe by rememberSaveable(session.userId, session.status) { mutableStateOf(canSync) }
    val canManage = profileAccess.canManageProfile(session)
    var manageProfile by rememberSaveable(session.userId, session.status, session.isReady) { mutableStateOf(false) }
    var openSurvey by rememberSaveable(session.userId, session.status, session.isReady) { mutableStateOf(false) }
    val canSurvey = surveyAccess.allowed(session)
    // 개발 메뉴에서도 실제 API 권한을 사용하며 가입 상태를 바꾸지 않는다.
    var openSettings by rememberSaveable(session) { mutableStateOf(false) }
    val canSettings = settingsAccess.allowed(session)
    when {
        state.session != session -> OnboardingPanel(stringResource(Res.string.onboarding_title)) { Text(stringResource(Res.string.onboarding_loading)) }
        (canSkip || canSync) && openBleProbe -> ConnectedKeyringSetupRoute({ openBleProbe = false }, managedConnection = managedKeyringConnection)
        canSettings && openSettings -> ConnectedSettingsRoute(session, { openSettings = false }, showKeyringSync = managedKeyringConnection)
        canSurvey && openSurvey -> SurveyRoute(session, { openSurvey = false })
        canManage && manageProfile -> ProfileManagementRoute(session, { manageProfile = false })
        !session.isReady && session.status == OnboardingStatus.PROFILE_REQUIRED -> ProfileRoute(session)
        canSkip && developmentProfile -> Column(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().padding(horizontal = AppSpacing.Large)) {
                Text(stringResource(Res.string.profile_development_keyring_notice), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { developmentProfile = false }) {
                    Text(stringResource(Res.string.profile_return_keyring))
                }
            }
            Box(Modifier.weight(1f)) { ProfileRoute(session) }
        }
        else -> OnboardingScreen(state, model::check, model::submit, model::load, model::refresh, model::logout,
            canSkipKeyring = canSkip, onSkipKeyring = { if (canSkip) developmentProfile = true },
            canManageProfile = canManage, onManageProfile = { if (canManage) manageProfile = true },
            canOpenSurvey = canSurvey, onOpenSurvey = { if (canSurvey) openSurvey = true },
            canOpenSettings = canSettings, onOpenSettings = { if (canSettings) openSettings = true },
            onBleProbe = if (canSkip || canSync) ({ openBleProbe = true }) else null)
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
    canSkipKeyring: Boolean = false,
    onSkipKeyring: () -> Unit = {},
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
    fun open(url: String) { linkFailed = runCatching { uri.openUri(url) }.isFailure }
    OnboardingPanel(stringResource(when {
        agreements -> Res.string.onboarding_agreements_title
        keyring -> Res.string.onboarding_keyring_title
        else -> Res.string.onboarding_title
    })) {
        when {
            state.submitted -> Text(stringResource(Res.string.onboarding_saved))
            agreements -> {
                Text(stringResource(Res.string.onboarding_agreements_body))
                if (state.agreements.any { !it.required }) {
                    Text(stringResource(Res.string.onboarding_optional_separate), style = MaterialTheme.typography.bodySmall)
                }
                if (state.allowUnpublishedAgreements && state.requiredAgreements.any { !it.canReview }) {
                    Text(developmentNotice ?: stringResource(Res.string.onboarding_development_agreements), style = MaterialTheme.typography.bodySmall)
                }
                state.requiredAgreements.forEach { item ->
                    AppCheckbox(item.type in state.checked, { onCheck(item.type, it) },
                        stringResource(Res.string.onboarding_required, item.title),
                        enabled = !state.busy && state.canCheck(item))
                    if (item.reviewUrl != null) TextButton(onClick = { open(item.reviewUrl!!) }) {
                        Text(stringResource(Res.string.onboarding_read_terms))
                    } else if (!item.canReview && !state.allowUnpublishedAgreements) {
                        Text(stringResource(Res.string.onboarding_terms_missing), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!state.busy && state.requiredAgreements.isEmpty()) Text(stringResource(Res.string.onboarding_terms_empty))
                AppButton(stringResource(Res.string.onboarding_agree), enabled = state.canSubmit, loading = state.busy,
                    modifier = Modifier.fillMaxWidth(), onClick = onSubmit)
                AppButton(stringResource(Res.string.onboarding_retry), variant = AppButtonVariant.Secondary,
                    enabled = !state.busy, onClick = onReload)
            }
            keyring -> {
                // 진입만으로 등록하지 않는다. 운영 앱에는 이 개발 시험 경로의 콜백을 전달하지 않는다.
                onBleProbe?.let { AppButton(stringResource(Res.string.ble_probe_entry), enabled = !state.busy, onClick = it) }
                Text(stringResource(Res.string.onboarding_keyring_wait))
                if (canSkipKeyring) {
                    Text(stringResource(Res.string.profile_development_keyring_entry), style = MaterialTheme.typography.bodySmall)
                    AppButton(stringResource(Res.string.profile_skip_keyring), enabled = !state.busy, onClick = onSkipKeyring)
                }
                AppButton(stringResource(Res.string.onboarding_keyring_search), enabled = false, onClick = {})
                state.purchaseUrl?.let { url -> AppButton(stringResource(Res.string.onboarding_purchase),
                    variant = AppButtonVariant.Secondary, onClick = { open(url) }) }
                    ?: Text(stringResource(Res.string.onboarding_purchase_wait))
                AppButton(stringResource(Res.string.onboarding_retry), variant = AppButtonVariant.Secondary,
                    enabled = !state.busy, onClick = onReload)
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
        if (!keyring && onBleProbe != null) AppButton(stringResource(Res.string.sync_live_open), enabled = !state.busy, onClick = onBleProbe)
        if (canOpenSettings) AppButton(stringResource(Res.string.settings_api_entry), enabled = !state.busy, onClick = onOpenSettings)
        if (canOpenSurvey) AppButton(stringResource(if (keyring) Res.string.survey_api_dev_entry else Res.string.survey_api_entry),
            enabled = !state.busy, onClick = onOpenSurvey)
        if (canManageProfile) AppButton(stringResource(if (session.isReady) Res.string.profile_manage_entry else Res.string.profile_manage_dev_entry),
            enabled = !state.busy, onClick = onManageProfile)
        if (linkFailed) OnboardingError(stringResource(Res.string.onboarding_link_failed))
        if (!agreements || state.submitted) AppButton(stringResource(Res.string.onboarding_refresh),
            loading = state.busy, enabled = !state.busy, onClick = onRefresh)
        TextButton(onClick = onLogout, enabled = !state.busy) { Text(stringResource(Res.string.onboarding_logout)) }
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
