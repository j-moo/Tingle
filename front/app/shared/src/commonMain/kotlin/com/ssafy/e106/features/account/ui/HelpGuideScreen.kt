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
    })
    // 처음 불러오기 실패: 화면 전체를 상태 화면으로(당기거나 작은 "다시 시도"). 화면이 오류 종류를 받지 않아 "알 수 없는 오류" 모양이다
    if (state.stage == HelpGuideStage.LoadFailed) {
        StateScreen(StateKind.Unknown, stringResource(Res.string.help_failed), modifier, barTitle = title,
            onBack = { onAction(HelpGuideAction.Back) }, onRefresh = { onAction(HelpGuideAction.Reload) })
        return
    }
    val openLabel = stringResource(if (state.isInquiry) Res.string.help_open_channel else Res.string.help_open_document)
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = { onAction(HelpGuideAction.Back) })
            // 다시 받는 동안은 주소 자리가 뼈대로 바뀌어 알리므로 고리는 따로 돌리지 않는다
            PullDownRefreshBox(false, { onAction(HelpGuideAction.Reload) }, Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    Text(stringResource(when (state.kind) {
                        HelpGuideKind.Terms -> Res.string.help_terms_heading
                        HelpGuideKind.PrivacyPolicy -> Res.string.help_privacy_heading
                        HelpGuideKind.Support -> Res.string.help_support_heading
                    }), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(when (state.kind) {
                        HelpGuideKind.Terms -> Res.string.help_terms_body
                        HelpGuideKind.PrivacyPolicy -> Res.string.help_privacy_body
                        HelpGuideKind.Support -> Res.string.help_support_body
                    }))
                    if (state.isInquiry) {
                        StatusCard(stringResource(Res.string.help_prepare_title), stringResource(Res.string.help_support_prepare))
                    }
                    when {
                        state.stage == HelpGuideStage.Loading -> {
                            // 문서 제목 · 버전 · 주소 자리만 뼈대. 여는 버튼은 실제 자리에 막아 둔다
                            SkeletonGroup(spacing = AppSpacing.Medium) {
                                SkeletonBone(Modifier.fillMaxWidth(0.5f), height = 20.dp)
                                SkeletonBone(Modifier.fillMaxWidth(0.3f), height = 12.dp)
                                SkeletonBone(Modifier.fillMaxWidth(0.8f), height = 12.dp)
                            }
                            AppButton(openLabel, Modifier.fillMaxWidth(), enabled = false, onClick = {})
                        }
                        // 연결할 주소가 아직 없음(내용 없음): 안내 글은 그대로 두고 짧은 제목 한 줄
                        state.openUrl == null -> StateView(StateKind.Empty,
                            stringResource(if (state.isInquiry) Res.string.help_channel_missing else Res.string.help_document_missing))
                        else -> {
                            state.documentTitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            state.version?.takeIf { it.isNotBlank() }?.let { Text(stringResource(Res.string.help_version, it), style = MaterialTheme.typography.bodySmall) }
                            Text(state.openUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            // 열기 실패: 버튼 바로 위에 빨간 한 줄, 버튼 이름은 "다시 시도"
                            val openFailed = state.stage == HelpGuideStage.OpenFailed
                            if (openFailed) InlineError(stringResource(Res.string.help_open_failed))
                            AppButton(if (openFailed) stringResource(Res.string.state_retry) else openLabel,
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

/** 약관 · 개인정보 · 문의를 한 화면에 줄로 모은다. 줄을 누르면 해당 안내 화면으로 간다(주소 확인 · 열기는 그 화면이 한다). */
@Composable
fun HelpHubScreen(onOpen: (HelpGuideKind) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.account_section_help), onBack = onBack)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    Column {
                        ServiceRow(stringResource(Res.string.account_terms), detail = stringResource(Res.string.help_hub_document), onClick = { onOpen(HelpGuideKind.Terms) })
                        ServiceRow(stringResource(Res.string.account_privacy), detail = stringResource(Res.string.help_hub_document), onClick = { onOpen(HelpGuideKind.PrivacyPolicy) })
                        ServiceRow(stringResource(Res.string.account_support), detail = stringResource(Res.string.account_support_detail), onClick = { onOpen(HelpGuideKind.Support) })
                    }
                    StatusCard(stringResource(Res.string.help_prepare_title), stringResource(Res.string.help_hub_caution))
                }
            }
        }
    }
}
