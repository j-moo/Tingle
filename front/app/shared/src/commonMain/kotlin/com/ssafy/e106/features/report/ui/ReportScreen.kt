package com.ssafy.e106.features.report.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 입력/선택 상태만 표시한다. 네트워크 요청·가상 접수·자동 차단은 수행하지 않는다.
 * 사유 선택과 결과는 아래 시트 한 장에서 끝낸다(사유만 골라 바로 접수). 증거 메시지를 고를 때만 전체 화면 목록으로 넘어간다.
 */
@Composable
fun ReportScreen(state: ReportState, onReason: (ReportReason) -> Unit, onDetail: (String) -> Unit,
    onEvidence: (Long, Boolean) -> Unit, onAction: (ReportAction) -> Unit) {
    // 방이 종료돼도 이미 보낸 신고의 결과 화면을 '신고 불가' 안내로 덮지 않는다.
    val unavailable = !state.available && !state.showsSubmissionStatus
    if (!unavailable && (state.step == ReportStep.Evidence || state.step == ReportStep.Confirm)) {
        EvidencePage(state, onEvidence, onAction); return
    }
    ActionSheet(onDismiss = if (unavailable || state.step == ReportStep.Form) ({ onAction(ReportAction.Back) }) else null) {
        when {
            unavailable -> {
                SheetResult(false, stringResource(if (state.accessRetryAvailable) Res.string.report_evidence_failed else Res.string.report_unavailable),
                    stringResource(if (state.accessRetryAvailable) Res.string.report_access_retry_help else Res.string.report_unavailable_help))
                // 일시 실패에서도 현재 화면의 버튼으로 권한을 다시 확인할 수 있어야 한다. 재조회가 성공하기 전에는 신규 신고나 증거 본문을 공개하지 않는다.
                if (state.accessRetryAvailable) AppButton(stringResource(Res.string.report_reload), Modifier.fillMaxWidth(),
                    enabled = state.evidenceLoad != EvidenceLoad.Loading, onClick = { onAction(ReportAction.ReloadEvidence) })
                AppButton(stringResource(Res.string.report_back), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, onClick = { onAction(ReportAction.Close) })
            }
            state.step == ReportStep.Form -> {
                Text(stringResource(Res.string.report_reason_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Column(Modifier.selectableGroup()) {
                    ReportReason.entries.forEach { reason -> AppRadio(state.reason == reason, { onReason(reason) }, reasonLabel(reason)) }
                }
                OutlinedTextField(state.detail, onDetail, Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    label = { Text(stringResource(Res.string.report_detail_optional)) },
                    shape = AppShapes.Control, isError = state.detailCount > state.rules!!.maxDetailUnits)
                Text(stringResource(Res.string.report_detail_count, state.detailCount, state.rules.maxDetailUnits), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                if (state.detailCount > state.rules.maxDetailUnits) Text(stringResource(Res.string.report_detail_limit), color = AppColors.Error)
                if (state.selectedIds.isNotEmpty()) Text(stringResource(Res.string.report_evidence_count, state.selectedIds.size), style = MaterialTheme.typography.bodyMedium)
                // 확인 단계를 건너뛰고 바로 접수하므로 보관 안내를 접수 버튼 위에 둔다
                Text(stringResource(Res.string.report_retention), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                AppButton(stringResource(Res.string.report_submit), Modifier.fillMaxWidth(), enabled = state.validSubmission,
                    onClick = { if (state.validSubmission) onAction(ReportAction.Submit) })
                AppButton(stringResource(Res.string.report_choose_evidence), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                    enabled = state.validForm, onClick = { if (state.validForm) onAction(ReportAction.NextEvidence) })
                // 시트가 화면을 다 채우면 바탕을 누를 수 없고 iOS 에는 뒤로가기가 없어 닫는 버튼을 둔다
                TextButton(onClick = { onAction(ReportAction.Back) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.report_back)) }
            }
            state.step == ReportStep.Sending -> {
                Text(stringResource(Res.string.report_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                AppButton(stringResource(Res.string.report_submit), Modifier.fillMaxWidth(), enabled = false, loading = true,
                    loadingText = stringResource(Res.string.report_sending), onClick = {})
            }
            else -> {
                SheetResult(state.step == ReportStep.Received, stringResource(when (state.step) {
                    ReportStep.Received -> Res.string.report_received
                    ReportStep.Conflict -> Res.string.report_conflict
                    ReportStep.Unknown -> Res.string.report_unknown
                    else -> Res.string.report_failed
                }), stringResource(when (state.step) {
                    ReportStep.Received -> Res.string.report_result_help
                    ReportStep.Conflict -> Res.string.report_conflict_help
                    ReportStep.Unknown -> Res.string.report_unknown_help
                    else -> Res.string.report_input_kept
                }))
                when (state.step) {
                    // 신고만으로는 차단되지 않으므로 접수 직후 차단으로 바로 갈 수 있게 한다(차단 화면에서 한 번 더 확인한다)
                    ReportStep.Received -> AppButton(stringResource(Res.string.report_block), Modifier.fillMaxWidth(),
                        variant = AppButtonVariant.Danger, onClick = { onAction(ReportAction.Block) })
                    ReportStep.Failed -> {
                        AppButton(stringResource(Res.string.report_retry), Modifier.fillMaxWidth(), enabled = state.validSubmission,
                            onClick = { if (state.validSubmission) onAction(ReportAction.Retry) })
                        AppButton(stringResource(Res.string.report_edit), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, onClick = { onAction(ReportAction.Edit) })
                    }
                    ReportStep.Unknown -> AppButton(stringResource(Res.string.report_check_result), Modifier.fillMaxWidth(), onClick = { onAction(ReportAction.CheckResult) })
                    else -> Unit
                }
                AppButton(stringResource(Res.string.report_back), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, onClick = { onAction(ReportAction.Close) })
            }
        }
    }
}

/** 증거 메시지 선택 · 신고 내용 확인. 메시지 목록이 길어 전체 화면으로 둔다. */
@Composable
private fun EvidencePage(state: ReportState, onEvidence: (Long, Boolean) -> Unit, onAction: (ReportAction) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // 가로 화면처럼 짧은 창에서는 고정 제목/버튼이 본문 높이를 모두 차지하므로 목록 안으로 옮긴다.
            val compactHeight = maxHeight < 320.dp
            Column(Modifier.fillMaxSize()) {
                if (!compactHeight) AppTopBar(stringResource(Res.string.report_title), onBack = { onAction(ReportAction.Back) })
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = screenPaddingValues(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                    if (compactHeight) item(key = "header") { AppTopBar(stringResource(Res.string.report_title), onBack = { onAction(ReportAction.Back) }) }
                    if (state.step == ReportStep.Evidence) {
                        item { Text(stringResource(Res.string.report_evidence_help))
                            Text(stringResource(Res.string.report_evidence_count, state.selectedIds.size)) }
                        when (state.evidenceLoad) {
                            EvidenceLoad.Loading -> item { Text(stringResource(Res.string.report_evidence_loading)) }
                            EvidenceLoad.Failed -> item { StatusCard(stringResource(Res.string.report_evidence_failed), stringResource(Res.string.report_input_kept))
                                AppButton(stringResource(Res.string.report_reload), onClick = { onAction(ReportAction.ReloadEvidence) }) }
                            EvidenceLoad.Ready -> {
                                if (state.visibleEvidence.isEmpty()) item { Text(stringResource(Res.string.report_evidence_empty)) }
                                items(state.visibleEvidence, key = { it.id }) { message ->
                                    AppCheckbox(message.id in state.selectedIds, { checked -> onEvidence(message.id, checked) },
                                        stringResource(Res.string.report_evidence_label,
                                            stringResource(if (message.mine) Res.string.report_me else Res.string.report_other), message.timeLabel, message.text),
                                        enabled = true)
                                }
                                if (state.hasMoreEvidence) item { AppButton(stringResource(Res.string.report_more), variant = AppButtonVariant.Secondary,
                                    onClick = { onAction(ReportAction.LoadMoreEvidence) }) }
                            }
                        }
                        if (state.selectedIds.isNotEmpty()) item {
                            if (!state.validEvidence && state.evidenceLoad == EvidenceLoad.Ready) Text(stringResource(Res.string.report_selection_invalid), color = AppColors.Error)
                            TextButton(onClick = { onAction(ReportAction.ClearEvidence) }) { Text(stringResource(Res.string.report_clear)) }
                        }
                    } else {
                        item { Text(stringResource(Res.string.report_review), style = MaterialTheme.typography.titleLarge)
                            state.reason?.let { Text(reasonLabel(it)) }
                            if (state.detail.isNotBlank()) Text(state.detail)
                            Text(stringResource(Res.string.report_evidence_count, state.selectedIds.size)) }
                        items(state.visibleEvidence.filter { it.id in state.selectedIds }, key = { it.id }) { Text(it.text) }
                        item { Text(stringResource(Res.string.report_retention))
                            if (!state.validSubmission) Text(stringResource(Res.string.report_selection_invalid), color = AppColors.Error) }
                    }
                    if (compactHeight) item(key = "actions") { EvidenceActions(state, onAction) }
                }
                if (!compactHeight) EvidenceActions(state, onAction)
            }
        }
    }
}

/** 고정/스크롤 위치에서 동일한 전송 가능 조건을 사용한다. 배치 변경으로 자동 신고하지 않는다. */
@Composable
private fun EvidenceActions(state: ReportState, onAction: (ReportAction) -> Unit) {
    val review = state.step == ReportStep.Evidence
    Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        AppButton(stringResource(if (review) Res.string.report_review else Res.string.report_submit), Modifier.fillMaxWidth(), enabled = state.validSubmission,
            onClick = { if (state.validSubmission) onAction(if (review) ReportAction.Review else ReportAction.Submit) })
        TextButton(onClick = { onAction(ReportAction.Edit) }) { Text(stringResource(Res.string.report_edit)) }
    }
}

@Composable
private fun reasonLabel(reason: ReportReason) = stringResource(when (reason) {
    ReportReason.ABUSE -> Res.string.report_abuse
    ReportReason.SEXUAL -> Res.string.report_sexual
    ReportReason.SPAM -> Res.string.report_spam
    ReportReason.ETC -> Res.string.report_etc
})
