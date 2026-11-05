package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 순수 화면. AI 요청·표시 ACK·자동 전송을 실행하지 않는다. */
@Composable
fun ChatAiConversationScreen(
    state: ChatConversationState,
    ai: ChatAiState,
    onDraftChange: (String) -> Unit,
    onAction: (ConversationAction) -> Unit,
    onAiAction: (String, ChatAiAction) -> Unit,
    onMessageRecovery: (String, MessageRecoveryAction) -> Unit = { _, _ -> },
) {
    var pendingQuestion by remember(ai.scope, ai.icebreakers, state.load, state.inputEnabled) { mutableStateOf<String?>(null) }
    ChatConversationScreen(state, onDraftChange, onAction, onMessageRecovery = onMessageRecovery, conversationSupport = {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            if (ai.showNotice) PersonalNotice(ai.notice, onAiAction)
            if (ai.scope != null && ai.icebreakers.scope == ai.scope) {
                IcebreakerPanel(ai.icebreakers, state.inputEnabled) { question ->
                    if (state.inputEnabled) {
                        if (state.draft.isEmpty()) onDraftChange(question) else pendingQuestion = question
                    }
                }
            }
        }
    })
    pendingQuestion?.let { question ->
        AlertDialog(onDismissRequest = { pendingQuestion = null },
            title = { Text(stringResource(Res.string.chat_ai_replace_title)) },
            text = { Text(stringResource(Res.string.chat_ai_replace_help)) },
            confirmButton = { TextButton(onClick = {
                if (state.inputEnabled && ai.icebreakers.status == IcebreakerStatus.Ready &&
                    ai.icebreakers.validQuestions && question in ai.icebreakers.questions) onDraftChange(question)
                pendingQuestion = null
            }) { Text(stringResource(Res.string.chat_ai_replace)) } },
            dismissButton = { TextButton(onClick = { pendingQuestion = null }) { Text(stringResource(Res.string.chat_ai_keep)) } })
    }
}

@Composable
private fun IcebreakerPanel(state: IcebreakerUi, enabled: Boolean, onPick: (String) -> Unit) {
    if (state.status == IcebreakerStatus.Disabled) return
    Surface(color = AppColors.SurfaceMuted, shape = AppShapes.Control) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(stringResource(Res.string.chat_ai_questions_title), style = MaterialTheme.typography.titleMedium)
            when {
                state.status == IcebreakerStatus.Pending -> Text(stringResource(Res.string.chat_ai_questions_pending))
                state.status == IcebreakerStatus.Ready && state.validQuestions -> {
                    Text(stringResource(if (state.generalQuestions) Res.string.chat_ai_general else Res.string.chat_ai_pick_help),
                        style = MaterialTheme.typography.bodySmall)
                    state.questions.forEach { question ->
                        AppButton(question, Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                            enabled = enabled, onClick = { if (enabled) onPick(question) })
                    }
                }
                else -> Text(stringResource(Res.string.chat_ai_questions_failed))
            }
        }
    }
}

@Composable
private fun PersonalNotice(notice: RiskNoticeUi, onAction: (String, ChatAiAction) -> Unit) {
    val key = notice.noticeKey ?: return
    Surface(color = AppColors.SurfaceMuted, shape = AppShapes.Control) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(stringResource(if (notice.audience == RiskAudience.Recipient) Res.string.chat_ai_protect else Res.string.chat_ai_respect),
                style = MaterialTheme.typography.titleMedium)
            Text(notice.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.chat_ai_notice_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            if (notice.audience == RiskAudience.Recipient) TextButton(onClick = { onAction(key, ChatAiAction.OpenBlock) }) {
                Text(stringResource(Res.string.chat_ai_block))
            }
            TextButton(onClick = { onAction(key, ChatAiAction.DismissNotice) }) { Text(stringResource(Res.string.chat_ai_close)) }
        }
    }
}
