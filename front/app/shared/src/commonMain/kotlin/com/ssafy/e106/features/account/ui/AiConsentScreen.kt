package com.ssafy.e106.features.account.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * AI 분석 동의 화면. [error] 는 불러오기 실패의 종류(연결 끊김 · 서버 오류)를 고르는 데만 쓰고, [noticeOpenFailed] 는 안내 전문을 열지 못했을 때다.
 * 불러오는 중은 뼈대, 저장 중은 보던 화면 그대로 버튼만 처리 중, 그 밖의 실패 · 결과 확인 필요는 상태 화면으로 보인다.
 */
@Composable
fun AiConsentScreen(state: AiConsentState, onChecked: (Boolean) -> Unit, onAction: (AiConsentAction) -> Unit, modifier: Modifier = Modifier,
    error: AppError? = null, noticeOpenFailed: Boolean = false) {
    val barTitle = stringResource(Res.string.ai_consent_title)
    val back = { if (!state.busy) onAction(AiConsentAction.Back) }
    val value = state.snapshot
    when {
        // 안내 글과 항목 이름은 실제와 같게 두고 동의 상태가 들어갈 자리만 뼈대
        state.stage == AiConsentStage.Loading -> ConsentFrame(barTitle, back, modifier) {
            Text(stringResource(Res.string.ai_consent_heading), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(Res.string.ai_consent_summary))
            SkeletonGroup {
                SkeletonBone(height = AppSpacing.ControlMinHeight, shape = AppShapes.Control)
                SkeletonBone(height = 88.dp, shape = AppShapes.Card)
                SkeletonBone(Modifier.fillMaxWidth(0.5f), height = 12.dp)
            }
            ServiceRow(stringResource(Res.string.ai_consent_open_notice))
            Text(stringResource(Res.string.ai_consent_no_restore), style = MaterialTheme.typography.bodySmall)
        }
        // 당겨서 새로고침은 저장 · 철회 확인 중이 아닐 때만 다시 받는다(받으면 전문 확인 · 체크를 새로 거친다)
        value != null && state.stage in listOf(AiConsentStage.Ready, AiConsentStage.ConfirmWithdrawal, AiConsentStage.Saving) -> ConsentFrame(barTitle, back, modifier,
            onRefresh = { if (state.stage == AiConsentStage.Ready) onAction(AiConsentAction.Reload) }) {
            Text(stringResource(Res.string.ai_consent_heading), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(Res.string.ai_consent_summary))
            // 켜져 있으면 끌 때 철회 확인창을 띄우고, 꺼져 있으면 안내 전문을 확인한 뒤에만 켤 수 있다(저장은 아래 버튼으로)
            AppSwitch(value.enabled || state.checked, { on -> if (value.enabled) { if (!on) onAction(AiConsentAction.Withdraw) } else onChecked(on) },
                stringResource(Res.string.ai_consent_switch),
                enabled = if (value.enabled) state.canWithdraw else !state.busy && state.noticeAvailable && state.reviewedVersion == value.noticeVersion)
            // 스위치를 켠 것만으로는 저장되지 않는다. 왜 못 켜는지와 저장 버튼을 스위치 바로 아래에 둔다
            if (!value.enabled && state.reviewedVersion != value.noticeVersion) Text(stringResource(Res.string.ai_consent_read_first), style = MaterialTheme.typography.bodySmall)
            // 저장 중에도 보던 화면을 그대로 두고 버튼만 처리 중으로 바꾼다(철회를 저장하는 중이면 철회 버튼 이름으로)
            if (!value.enabled || state.busy) AppButton(stringResource(if (value.enabled) Res.string.ai_consent_confirm else Res.string.ai_consent_grant),
                Modifier.fillMaxWidth(), enabled = state.canGrant, loading = state.busy, onClick = { if (state.canGrant) onAction(AiConsentAction.Grant) })
            StatusCard(stringResource(if (value.enabled) Res.string.ai_consent_enabled else Res.string.ai_consent_disabled),
                stringResource(if (value.enabled) Res.string.ai_consent_separate else Res.string.ai_consent_disabled_help))
            state.savedEnabled?.let { Text(stringResource(if (it) Res.string.ai_consent_saved else Res.string.ai_consent_withdrawn), color = AppColors.SelectionBorder) }
            Text(stringResource(Res.string.ai_consent_version, value.noticeVersion), style = MaterialTheme.typography.bodySmall)
            if (value.enabled && value.grantedAtLabel != null) Text(stringResource(Res.string.ai_consent_date, value.grantedAtLabel), style = MaterialTheme.typography.bodySmall)
            ServiceRow(stringResource(Res.string.ai_consent_open_notice), onClick = if (state.busy) null else ({ onAction(AiConsentAction.OpenNotice) }))
            if (noticeOpenFailed) InlineError(stringResource(Res.string.chat_extras_document_failed))
            if (!state.noticeAvailable) Text(stringResource(Res.string.ai_consent_notice_missing), color = AppColors.TextSecondary)
            Text(stringResource(Res.string.ai_consent_no_restore), style = MaterialTheme.typography.bodySmall)
        }
        // 저장 결과를 모름: 다시 저장하지 않고 현재 상태부터 확인한다
        state.stage == AiConsentStage.Unknown -> StateScreen(StateKind.Unconfirmed, stringResource(Res.string.ai_consent_unknown), modifier, barTitle, back,
            primary = StateAction(stringResource(Res.string.ai_consent_recheck)) { onAction(AiConsentAction.CheckResult) },
            note = stringResource(Res.string.ai_consent_unknown_help))
        state.stage == AiConsentStage.VersionChanged -> StateScreen(StateKind.Changed, stringResource(Res.string.ai_consent_changed), modifier, barTitle, back,
            primary = StateAction(stringResource(Res.string.ai_consent_view_new)) { onAction(AiConsentAction.Reload) },
            note = stringResource(Res.string.ai_consent_changed_help))
        state.stage == AiConsentStage.LoginRequired -> StateScreen(StateKind.Session, stringResource(Res.string.state_session), modifier, barTitle, back,
            primary = StateAction(stringResource(Res.string.ai_consent_relogin)) { onAction(AiConsentAction.Login) })
        state.stage == AiConsentStage.Forbidden -> StateScreen(StateKind.Locked, stringResource(Res.string.ai_consent_forbidden), modifier, barTitle, back,
            primary = StateAction(stringResource(Res.string.ai_consent_back)) { onAction(AiConsentAction.Back) })
        // 처음 불러오기 실패: 당기거나 작은 "다시 시도"로 다시 받는다
        else -> StateScreen(error.stateKind(), error.stateTitle(), modifier, barTitle, back, onRefresh = { onAction(AiConsentAction.Reload) })
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
@Composable private fun ConsentFrame(title: String, back: () -> Unit, modifier: Modifier, onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = back)
            val body: @Composable BoxScope.() -> Unit = {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large), content = content)
            }
            // 다시 받는 동안은 화면이 뼈대로 바뀌어 알리므로 고리는 따로 돌리지 않는다
            if (onRefresh != null) PullDownRefreshBox(false, onRefresh, Modifier.weight(1f).fillMaxWidth(), content = body)
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter, content = body)
        }
    }
}
