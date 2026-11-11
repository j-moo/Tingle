package com.ssafy.e106.features.account.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AiConsentScreen(state: AiConsentState, onChecked: (Boolean) -> Unit, onAction: (AiConsentAction) -> Unit, modifier: Modifier = Modifier) {
    ConsentFrame(stringResource(Res.string.ai_consent_title), { if (!state.busy) onAction(AiConsentAction.Back) }, modifier) {
        if (state.stage in listOf(AiConsentStage.Ready, AiConsentStage.ConfirmWithdrawal) && state.snapshot != null) {
            val value = state.snapshot
            Text(stringResource(Res.string.ai_consent_heading), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(Res.string.ai_consent_summary))
            // 켜져 있으면 끌 때 철회 확인창을 띄우고, 꺼져 있으면 안내 전문을 확인한 뒤에만 켤 수 있다(저장은 아래 버튼으로)
            AppSwitch(value.enabled || state.checked, { on -> if (value.enabled) { if (!on) onAction(AiConsentAction.Withdraw) } else onChecked(on) },
                stringResource(Res.string.ai_consent_switch),
                enabled = if (value.enabled) state.canWithdraw else state.noticeAvailable && state.reviewedVersion == value.noticeVersion)
            // 스위치를 켠 것만으로는 저장되지 않는다. 왜 못 켜는지와 저장 버튼을 스위치 바로 아래에 둔다
            if (!value.enabled) {
                if (state.reviewedVersion != value.noticeVersion) Text(stringResource(Res.string.ai_consent_read_first), style = MaterialTheme.typography.bodySmall)
                AppButton(stringResource(Res.string.ai_consent_grant), Modifier.fillMaxWidth(), enabled = state.canGrant,
                    onClick = { if (state.canGrant) onAction(AiConsentAction.Grant) })
            }
            StatusCard(stringResource(if (value.enabled) Res.string.ai_consent_enabled else Res.string.ai_consent_disabled),
                stringResource(if (value.enabled) Res.string.ai_consent_separate else Res.string.ai_consent_disabled_help))
            state.savedEnabled?.let { Text(stringResource(if (it) Res.string.ai_consent_saved else Res.string.ai_consent_withdrawn), color = AppColors.SelectionBorder) }
            Text(stringResource(Res.string.ai_consent_version, value.noticeVersion), style = MaterialTheme.typography.bodySmall)
            if (value.enabled && value.grantedAtLabel != null) Text(stringResource(Res.string.ai_consent_date, value.grantedAtLabel), style = MaterialTheme.typography.bodySmall)
            ServiceRow(stringResource(Res.string.ai_consent_open_notice), onClick = { onAction(AiConsentAction.OpenNotice) })
            if (!state.noticeAvailable) Text(stringResource(Res.string.ai_consent_notice_missing), color = AppColors.TextSecondary)
            Text(stringResource(Res.string.ai_consent_no_restore), style = MaterialTheme.typography.bodySmall)
        } else {
            val title = when (state.stage) {
                AiConsentStage.Loading -> Res.string.ai_consent_loading
                AiConsentStage.Saving -> Res.string.ai_consent_saving
                AiConsentStage.Unknown -> Res.string.ai_consent_unknown
                AiConsentStage.VersionChanged -> Res.string.ai_consent_changed
                AiConsentStage.LoginRequired -> Res.string.ai_consent_login
                AiConsentStage.Forbidden -> Res.string.ai_consent_forbidden
                else -> Res.string.ai_consent_failed
            }
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            when (state.stage) {
                AiConsentStage.Loading, AiConsentStage.Saving -> CircularProgressIndicator(color = AppColors.SelectionBorder)
                AiConsentStage.Forbidden -> AppButton(stringResource(Res.string.ai_consent_back), onClick = { onAction(AiConsentAction.Back) })
                else -> {
                    if (state.stage == AiConsentStage.Unknown) Text(stringResource(Res.string.ai_consent_unknown_help))
                    if (state.stage == AiConsentStage.VersionChanged) Text(stringResource(Res.string.ai_consent_changed_help))
                    AppButton(stringResource(if (state.stage == AiConsentStage.Unknown) Res.string.ai_consent_check_result else Res.string.ai_consent_retry),
                        Modifier.fillMaxWidth(), onClick = { onAction(when(state.stage) {
                            AiConsentStage.Unknown -> AiConsentAction.CheckResult
                            AiConsentStage.LoginRequired -> AiConsentAction.Login
                            else -> AiConsentAction.Reload
                        }) })
                }
            }
        }
    }
    if (state.stage == AiConsentStage.ConfirmWithdrawal) AlertDialog(onDismissRequest = { onAction(AiConsentAction.CancelWithdrawal) },
        title = { Text(stringResource(Res.string.ai_consent_withdraw_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(stringResource(Res.string.ai_consent_withdraw_impact)); Text(stringResource(Res.string.ai_consent_no_restore))
        } }, confirmButton = { TextButton(onClick = { onAction(AiConsentAction.ConfirmWithdrawal) }) { Text(stringResource(Res.string.ai_consent_confirm)) } },
        dismissButton = { TextButton(onClick = { onAction(AiConsentAction.CancelWithdrawal) }) { Text(stringResource(Res.string.ai_consent_cancel)) } })
}

@Composable
fun AiConsentNoticeScreen(state: AiConsentState, onBack: () -> Unit, onReviewed: () -> Unit, modifier: Modifier = Modifier) {
    ConsentFrame(stringResource(Res.string.ai_consent_notice_title), onBack, modifier) {
        val document = state.notice
        if (state.noticeAvailable && document != null) {
            Text(stringResource(Res.string.ai_consent_version, document.version), style = MaterialTheme.typography.bodySmall)
            Text(document.body)
            AppButton(stringResource(Res.string.ai_consent_notice_read), Modifier.fillMaxWidth(), onClick = onReviewed)
        } else {
            Text(stringResource(Res.string.ai_consent_notice_missing))
            AppButton(stringResource(Res.string.ai_consent_back), onClick = onBack)
        }
    }
}
@Composable private fun ConsentFrame(title: String, back: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = back)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large), content = content)
            }
        }
    }
}
