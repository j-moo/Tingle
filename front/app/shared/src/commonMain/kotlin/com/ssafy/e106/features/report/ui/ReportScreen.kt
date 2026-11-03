package com.ssafy.e106.features.report.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 입력/선택 상태만 표시한다. 네트워크 요청·가상 접수·자동 차단은 수행하지 않는다. */
@Composable
fun ReportScreen(state: ReportState, onReason: (ReportReason) -> Unit, onDetail: (String) -> Unit,
    onEvidence: (Long, Boolean) -> Unit, onAction: (ReportAction) -> Unit) {
    val busy = state.step == ReportStep.Sending
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            AppTopBar(stringResource(Res.string.report_title), onBack = if (busy) null else ({ onAction(ReportAction.Back) }))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = screenPaddingValues(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                // 방이 종료돼도 이미 보낸 신고의 결과 화면을 '신고 불가' 안내로 덮지 않는다.
                if (!state.available && !state.showsSubmissionStatus) {
                    item { StatusCard(stringResource(Res.string.report_unavailable), stringResource(Res.string.report_unavailable_help)) }
                } else when (state.step) {
                    ReportStep.Form -> {
                        item {
                            Text(stringResource(Res.string.report_reason_title), style = MaterialTheme.typography.titleMedium)
                            Column(Modifier.selectableGroup()) {
                                ReportReason.entries.forEach { reason ->
                                    AppRadio(state.reason == reason, { onReason(reason) }, reasonLabel(reason))
                                }
                            }
                        }
                        item {
                            OutlinedTextField(state.detail, onDetail, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                                label = { Text(stringResource(Res.string.report_detail_optional)) },
                                shape = AppShapes.Control, isError = state.detailCount > state.rules!!.maxDetailUnits)
                            Text(stringResource(Res.string.report_detail_count, state.detailCount, state.rules.maxDetailUnits), style = MaterialTheme.typography.bodySmall)
                            if (state.detailCount > state.rules.maxDetailUnits) Text(stringResource(Res.string.report_detail_limit), color = AppColors.Error)
                        }
                    }
                    ReportStep.Evidence -> {
                        item { Text(stringResource(Res.string.report_evidence_help))
                            Text(stringResource(Res.string.report_evidence_count, state.selectedIds.size)) }
                        when (state.evidenceLoad) {
                            EvidenceLoad.Loading -> item { Text(stringResource(Res.string.report_evidence_loading)) }
                            EvidenceLoad.Failed -> item { StatusCard(stringResource(Res.string.report_evidence_failed), stringResource(Res.string.report_input_kept))
                                AppButton(stringResource(Res.string.report_reload), onClick = { onAction(ReportAction.ReloadEvidence) }) }
                            EvidenceLoad.Ready -> {
                                if (state.visibleEvidence.isEmpty()) item { Text(stringResource(Res.string.report_evidence_empty)) }
                                items(state.visibleEvidence, key = { it.id }) { message ->
                                    val selected = message.id in state.selectedIds
                                    AppCheckbox(selected, { checked ->
                                        onEvidence(message.id, checked)
                                    }, stringResource(Res.string.report_evidence_label,
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
                    }
                    ReportStep.Confirm, ReportStep.Sending -> {
                        item { Text(stringResource(Res.string.report_review), style = MaterialTheme.typography.titleLarge)
                            state.reason?.let { Text(reasonLabel(it)) }
                            if (state.detail.isNotBlank()) Text(state.detail)
                            Text(stringResource(Res.string.report_evidence_count, state.selectedIds.size)) }
                        items(state.visibleEvidence.filter { it.id in state.selectedIds }, key = { it.id }) { Text(it.text) }
                        item { Text(stringResource(Res.string.report_retention))
                            if (!state.validSubmission) Text(stringResource(Res.string.report_selection_invalid), color = AppColors.Error) }
                    }
                    else -> item {
                        StatusCard(stringResource(when (state.step) {
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
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                // 같은 요청을 다시 보내 영수증을 확인하는 버튼은 새 신고 입력 권한에 종속시키지 않는다.
                if (!state.available && !state.showsSubmissionStatus) AppButton(stringResource(Res.string.report_back), Modifier.fillMaxWidth(), onClick = { onAction(ReportAction.Close) })
                else {
                    val action = when (state.step) {
                        ReportStep.Form -> ReportAction.NextEvidence
                        ReportStep.Evidence -> ReportAction.Review
                        ReportStep.Confirm, ReportStep.Sending -> ReportAction.Submit
                        ReportStep.Failed -> ReportAction.Retry
                        ReportStep.Unknown -> ReportAction.CheckResult
                        else -> ReportAction.Close
                    }
                    val enabled = when (state.step) {
                        ReportStep.Form -> state.validForm
                        ReportStep.Evidence, ReportStep.Confirm, ReportStep.Failed -> state.validSubmission
                        ReportStep.Sending -> false
                        else -> true
                    }
                    AppButton(stringResource(when (action) {
                        ReportAction.NextEvidence -> Res.string.report_choose_evidence
                        ReportAction.Review -> Res.string.report_review
                        ReportAction.Submit -> Res.string.report_submit
                        ReportAction.Retry -> Res.string.report_retry
                        ReportAction.CheckResult -> Res.string.report_check_result
                        else -> Res.string.report_back
                    }), Modifier.fillMaxWidth(), enabled = enabled, loading = busy, loadingText = stringResource(Res.string.report_sending),
                        onClick = { if (enabled) onAction(action) })
                    if (state.step == ReportStep.Confirm || state.step == ReportStep.Failed)
                        TextButton(onClick = { onAction(ReportAction.Edit) }) { Text(stringResource(Res.string.report_edit)) }
                }
            }
        }
    }
}

@Composable
private fun reasonLabel(reason: ReportReason) = stringResource(when (reason) {
    ReportReason.ABUSE -> Res.string.report_abuse
    ReportReason.SEXUAL -> Res.string.report_sexual
    ReportReason.SPAM -> Res.string.report_spam
    ReportReason.ETC -> Res.string.report_etc
})
