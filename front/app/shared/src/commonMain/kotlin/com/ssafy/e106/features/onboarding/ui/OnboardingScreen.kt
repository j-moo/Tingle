package com.ssafy.e106.features.onboarding.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingRoute(session: Session.SignedIn, model: OnboardingViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    when {
        state.session != session -> OnboardingPanel(stringResource(Res.string.onboarding_title)) { Text(stringResource(Res.string.onboarding_loading)) }
        else -> OnboardingScreen(state, model::check, model::submit, model::load, model::refresh, model::logout)
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
                if (state.allowUnpublishedAgreements && state.agreements.any { !it.canReview }) {
                    Text(stringResource(Res.string.onboarding_development_agreements), style = MaterialTheme.typography.bodySmall)
                }
                state.agreements.forEach { item ->
                    AppCheckbox(item.type in state.checked, { onCheck(item.type, it) },
                        stringResource(if (item.required) Res.string.onboarding_required else Res.string.onboarding_optional, item.title),
                        enabled = !state.busy && state.canCheck(item))
                    if (item.reviewUrl != null) TextButton(onClick = { open(item.reviewUrl!!) }) {
                        Text(stringResource(Res.string.onboarding_read_terms))
                    } else if (!item.canReview && !state.allowUnpublishedAgreements) {
                        Text(stringResource(Res.string.onboarding_terms_missing), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!state.busy && state.agreements.isEmpty()) Text(stringResource(Res.string.onboarding_terms_empty))
                AppButton(stringResource(Res.string.onboarding_agree), enabled = state.canSubmit, loading = state.busy,
                    modifier = Modifier.fillMaxWidth(), onClick = onSubmit)
                AppButton(stringResource(Res.string.onboarding_retry), variant = AppButtonVariant.Secondary,
                    enabled = !state.busy, onClick = onReload)
            }
            keyring -> {
                Text(stringResource(Res.string.onboarding_keyring_wait))
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
