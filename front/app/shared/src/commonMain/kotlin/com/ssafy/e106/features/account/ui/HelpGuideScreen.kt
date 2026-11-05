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
fun HelpGuideScreen(state: HelpGuideState, onAction: (HelpGuideAction) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(when (state.kind) {
        HelpGuideKind.Terms -> Res.string.account_terms
        HelpGuideKind.PrivacyPolicy -> Res.string.account_privacy
        HelpGuideKind.Support -> Res.string.account_support
        HelpGuideKind.Correction -> Res.string.account_correction
    })
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = { onAction(HelpGuideAction.Back) })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    Text(stringResource(when (state.kind) {
                        HelpGuideKind.Terms -> Res.string.help_terms_heading
                        HelpGuideKind.PrivacyPolicy -> Res.string.help_privacy_heading
                        HelpGuideKind.Support -> Res.string.help_support_heading
                        HelpGuideKind.Correction -> Res.string.help_correction_heading
                    }), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(when (state.kind) {
                        HelpGuideKind.Terms -> Res.string.help_terms_body
                        HelpGuideKind.PrivacyPolicy -> Res.string.help_privacy_body
                        HelpGuideKind.Support -> Res.string.help_support_body
                        HelpGuideKind.Correction -> Res.string.help_correction_body
                    }))
                    if (state.isInquiry) {
                        StatusCard(stringResource(Res.string.help_prepare_title), stringResource(
                            if (state.kind == HelpGuideKind.Correction) Res.string.help_correction_prepare else Res.string.help_support_prepare))
                    }
                    when {
                        state.stage == HelpGuideStage.Loading -> {
                            CircularProgressIndicator()
                            Text(stringResource(Res.string.help_loading))
                        }
                        state.stage == HelpGuideStage.LoadFailed -> {
                            StatusCard(stringResource(Res.string.help_failed), stringResource(Res.string.help_failed_body))
                            AppButton(stringResource(Res.string.help_reload), Modifier.fillMaxWidth(), onClick = { onAction(HelpGuideAction.Reload) })
                        }
                        state.openUrl == null -> {
                            StatusCard(stringResource(if (state.isInquiry) Res.string.help_channel_missing else Res.string.help_document_missing),
                                stringResource(Res.string.help_missing_body))
                        }
                        else -> {
                            state.documentTitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            state.version?.takeIf { it.isNotBlank() }?.let { Text(stringResource(Res.string.help_version, it), style = MaterialTheme.typography.bodySmall) }
                            Text(state.openUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            if (state.stage == HelpGuideStage.OpenFailed) {
                                StatusCard(stringResource(Res.string.help_open_failed), stringResource(Res.string.help_open_failed_body))
                            }
                            AppButton(stringResource(if (state.isInquiry) Res.string.help_open_channel else Res.string.help_open_document),
                                Modifier.fillMaxWidth(), onClick = { if (state.openUrl != null) onAction(HelpGuideAction.Open) })
                            Text(stringResource(if (state.isInquiry) Res.string.help_external_inquiry else Res.string.help_external_document),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                    }
                    AppButton(stringResource(Res.string.help_back), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                        onClick = { onAction(HelpGuideAction.Back) })
                }
            }
        }
    }
}
