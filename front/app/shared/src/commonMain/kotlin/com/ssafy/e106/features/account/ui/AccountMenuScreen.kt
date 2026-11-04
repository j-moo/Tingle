package com.ssafy.e106.features.account.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** 순수 메뉴 화면. 데이터 조회·화면 이동·계정 변경은 호출부가 담당한다. */
@Composable
fun AccountMenuScreen(
    state: AccountMenuState,
    onAction: (AccountMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state.load == AccountMenuLoad.Ready
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.account_title),
                actionText = if (state.load == AccountMenuLoad.Loading) null else stringResource(Res.string.account_refresh),
                onAction = { onAction(AccountMenuAction.Refresh) })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)) {
                    when (state.load) {
                        AccountMenuLoad.Loading -> {
                            StatusCard(stringResource(Res.string.account_loading), stringResource(Res.string.account_loading_help))
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = AppColors.SelectionBorder)
                        }
                        AccountMenuLoad.Failed -> {
                            StatusCard(stringResource(Res.string.account_failed), stringResource(Res.string.account_failed_help))
                            AppButton(stringResource(Res.string.account_retry), Modifier.fillMaxWidth(),
                                onClick = { onAction(AccountMenuAction.Refresh) })
                        }
                        AccountMenuLoad.Ready -> {
                            if (state.refreshFailed) StatusCard(stringResource(Res.string.account_refresh_failed),
                                stringResource(Res.string.account_previous_info))
                            Surface(shape = AppShapes.Card, color = AppColors.SelectionContainer) {
                                Column(Modifier.fillMaxWidth().padding(AppSpacing.Section),
                                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                                    Text(stringResource(Res.string.account_profile_label), style = MaterialTheme.typography.labelLarge)
                                    Text(state.nickname?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.account_name_unknown),
                                        style = MaterialTheme.typography.headlineMedium)
                                    Text(stringResource(Res.string.account_profile_help), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    MenuSection(Res.string.account_section_me) {
                        MenuRow(Res.string.account_profile, Res.string.account_profile_detail, AccountMenuAction.Profile, onAction)
                        MenuRow(Res.string.account_survey, surveyDetail(if (ready) state.survey else AccountSurveyStatus.Unknown),
                            AccountMenuAction.Survey, onAction)
                    }
                    MenuSection(Res.string.account_section_matching) {
                        MenuRow(Res.string.account_matching, Res.string.account_matching_detail, AccountMenuAction.Matching, onAction)
                        MenuRow(Res.string.account_keyring, keyringDetail(if (ready) state.keyring else AccountKeyringStatus.Unknown),
                            AccountMenuAction.Keyring, onAction)
                        MenuRow(Res.string.account_permissions, Res.string.account_permissions_detail, AccountMenuAction.Permissions, onAction)
                    }
                    MenuSection(Res.string.account_section_help) {
                        MenuRow(Res.string.account_terms, null, AccountMenuAction.Terms, onAction)
                        MenuRow(Res.string.account_privacy, null, AccountMenuAction.Privacy, onAction)
                        MenuRow(Res.string.account_consents, Res.string.account_consents_detail, AccountMenuAction.Consents, onAction)
                        MenuRow(Res.string.account_correction, Res.string.account_correction_detail, AccountMenuAction.Correction, onAction)
                        MenuRow(Res.string.account_support, Res.string.account_support_detail, AccountMenuAction.Support, onAction)
                    }
                    MenuSection(Res.string.account_section_account) {
                        MenuRow(Res.string.account_logout, Res.string.account_logout_detail, AccountMenuAction.Logout, onAction)
                        MenuRow(Res.string.account_withdrawal, Res.string.account_withdrawal_detail, AccountMenuAction.Withdrawal, onAction)
                    }
                    state.appVersion?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(Res.string.account_version, it), style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary)
                    }
                }
            }
            AppBottomBar(labels = listOf(stringResource(Res.string.home_tab), stringResource(Res.string.home_proposal_tab),
                stringResource(Res.string.home_chat_tab), stringResource(Res.string.home_profile_tab)), selected = 3,
                onSelect = { index -> when (index) {
                    0 -> onAction(AccountMenuAction.Home)
                    1 -> onAction(AccountMenuAction.Proposals)
                    2 -> onAction(AccountMenuAction.Chats)
                } })
        }
    }
}

@Composable
private fun MenuSection(title: StringResource, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun MenuRow(title: StringResource, detail: StringResource?, action: AccountMenuAction, onAction: (AccountMenuAction) -> Unit) {
    ServiceRow(stringResource(title), detail = detail?.let { stringResource(it) }, onClick = { onAction(action) })
}

private fun surveyDetail(status: AccountSurveyStatus): StringResource = when (status) {
    AccountSurveyStatus.Unknown -> Res.string.account_survey_unknown
    AccountSurveyStatus.NotStarted -> Res.string.account_survey_not_started
    AccountSurveyStatus.InProgress -> Res.string.account_survey_in_progress
    AccountSurveyStatus.Completed -> Res.string.account_survey_completed
}

private fun keyringDetail(status: AccountKeyringStatus): StringResource = when (status) {
    AccountKeyringStatus.Unknown -> Res.string.account_keyring_unknown
    AccountKeyringStatus.Registered -> Res.string.account_keyring_registered
    AccountKeyringStatus.NotRegistered -> Res.string.account_keyring_missing
}
