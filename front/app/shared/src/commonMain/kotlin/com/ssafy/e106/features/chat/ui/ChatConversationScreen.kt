package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.distinctUntilChanged

/** 화면 표시만 담당한다. 서버 전송·읽음 ACK·타이머·자동 답장을 실행하지 않는다. */
@Composable
fun ChatConversationScreen(
    state: ChatConversationState,
    onDraftChange: (String) -> Unit,
    onAction: (ConversationAction) -> Unit,
    modifier: Modifier = Modifier,
    onMessageRecovery: (String, MessageRecoveryAction) -> Unit = { _, _ -> },
    conversationSupport: (@Composable () -> Unit)? = null,
    onMessagesDisplayed: (List<Long>) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val latestDisplayed by rememberUpdatedState(onMessagesDisplayed)
    // 미리 구성된 오프스크린 항목은 읽음 처리하지 않는다. 실제 viewport에 들어온 상대 메시지만 전달한다.
    LaunchedEffect(state.readable, state.messages) {
        if (state.readable) snapshotFlow {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            state.messages.filter { !it.mine && it.delivery == MessageDelivery.Sent && "message-${it.id}" in visibleKeys }
                .mapNotNull { it.id.toLongOrNull() }
        }.distinctUntilChanged().collect { ids -> withFrameNanos { }; latestDisplayed(ids) }
    }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            AppTopBar(
                title = if (state.readable) state.nickname?.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.chat_conversation_title) else stringResource(Res.string.chat_conversation_title),
                onBack = { onAction(ConversationAction.Back) },
                actionText = if (state.readable) stringResource(Res.string.chat_conversation_menu) else null,
                onAction = { if (state.readable) onAction(ConversationAction.OpenMenu) },
            )
            if (!state.readable) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = screenPaddingValues(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    item {
                        StatusCard(stringResource(when (state.load) {
                            ConversationLoad.Loading -> Res.string.chat_conversation_loading
                            ConversationLoad.Closed -> Res.string.chat_conversation_closed
                            ConversationLoad.ConsentRequired -> Res.string.chat_access_consent
                            ConversationLoad.Active, ConversationLoad.Unavailable -> Res.string.chat_access_unavailable
                            else -> Res.string.chat_conversation_failed
                        }), stringResource(when (state.load) {
                            ConversationLoad.Loading -> Res.string.chat_conversation_loading_help
                            ConversationLoad.Closed -> Res.string.chat_conversation_closed_help
                            ConversationLoad.ConsentRequired -> Res.string.chat_access_consent_help
                            ConversationLoad.Active, ConversationLoad.Unavailable -> Res.string.chat_access_unavailable_help
                            else -> Res.string.chat_conversation_failed_help
                        }))
                    }
                    item {
                        if (state.load == ConversationLoad.Loading)
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppColors.SelectionBorder) }
                        else if (state.load == ConversationLoad.ConsentRequired) {
                            AppButton(stringResource(Res.string.chat_access_open_consent), Modifier.fillMaxWidth(),
                                onClick = { onAction(ConversationAction.OpenConsent) })
                        } else AppButton(stringResource(if (state.load == ConversationLoad.Closed || state.load == ConversationLoad.Unavailable)
                            Res.string.chat_conversation_back else Res.string.chat_conversation_retry),
                            Modifier.fillMaxWidth(), onClick = { onAction(if (state.load == ConversationLoad.Closed || state.load == ConversationLoad.Unavailable)
                                ConversationAction.Back else ConversationAction.Reload) })
                    }
                }
            } else {
                // 입력창을 먼저 측정하고 남은 높이를 안내/대화에 나눈다. 큰 글자나 IME 때문에
                // 안내가 길어져도 전송 버튼을 화면 밖으로 밀지 않고 안내 영역에서 스크롤한다.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // 여백을 남기는 가중치 대신 안내/추천 높이만 제한하고, 남는 높이는 대화 목록에 준다.
                    val noticeHeight = maxHeight * 0.35f
                    val supportHeight = minOf(240.dp, maxHeight * 0.5f)
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.heightIn(max = noticeHeight).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            TextButton(onClick = { onAction(ConversationAction.OpenPartner) },
                                modifier = Modifier.align(Alignment.End).padding(horizontal = AppSpacing.Large)) {
                                Text(stringResource(Res.string.chat_partner_open))
                            }
                            ConversationNotice(state, onAction)
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(), state = listState, reverseLayout = true,
                                contentPadding = screenPaddingValues(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                                if (state.messages.isEmpty()) item(key = "empty") {
                                    StatusCard(stringResource(if (state.nextCursor.isNullOrBlank()) Res.string.chat_conversation_empty else Res.string.chat_history_empty),
                                        stringResource(if (state.nextCursor.isNullOrBlank()) Res.string.chat_conversation_empty_help else Res.string.chat_history_empty_help))
                                }
                                items(state.messages.distinctBy { it.id }.asReversed(), key = { "message-${it.id}" }) { message ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
                                        val who = stringResource(if (message.mine) Res.string.chat_conversation_me else Res.string.chat_conversation_other)
                                        val time = message.timeLabel?.takeIf { it.isNotBlank() }
                                            ?: stringResource(Res.string.chat_conversation_time_unknown)
                                        Column(horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
                                            MessageBubble(message.text,
                                                if (message.mine && message.delivery != MessageDelivery.Sent) who
                                                else stringResource(Res.string.chat_conversation_message_meta, who, time))
                                            if (message.mine) MessageDeliveryStatus(message,
                                                enabled = state.inputEnabled,
                                                onRecovery = { action -> if (state.inputEnabled) onMessageRecovery(message.id, action) })
                                        }
                                    }
                                }
                                if (!state.nextCursor.isNullOrBlank()) item(key = "older-messages") {
                                    if (state.historyLoad == HistoryLoad.Failed) Text(stringResource(Res.string.chat_history_failed),
                                        style = MaterialTheme.typography.bodySmall, color = AppColors.Error)
                                    AppButton(stringResource(if (state.historyLoad == HistoryLoad.Failed) Res.string.chat_history_retry else Res.string.chat_history_more),
                                        Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                                        enabled = state.canLoadOlder, loading = state.historyLoad == HistoryLoad.Loading,
                                        loadingText = stringResource(Res.string.chat_history_loading),
                                        onClick = { if (state.canLoadOlder) onAction(ConversationAction.LoadOlder) })
                                }
                            }
                        }
                        // 추천은 말풍선 목록과 분리해 입력창 바로 위에 둔다. 작은 화면/키보드에서도
                        // 입력창 높이를 먼저 확보하고, 펼친 추천은 남은 영역 안에서 스크롤한다.
                        if (conversationSupport != null) Column(
                            Modifier.fillMaxWidth().heightIn(max = supportHeight)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = AppSpacing.Large),
                        ) { conversationSupport() }
                    }
                }
                ConversationComposer(state, onDraftChange, onAction)
            }
        }
    }
}

@Composable
private fun ConversationNotice(state: ChatConversationState, onAction: (ConversationAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        Text(state.closesAtLabel?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.chat_conversation_closes, it) }
            ?: stringResource(Res.string.chat_conversation_closes_unknown),
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        Text(stringResource(Res.string.chat_conversation_notice), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        TextButton(onClick = { onAction(ConversationAction.OpenAiNotice) }) {
            Text(stringResource(Res.string.chat_conversation_ai_notice), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ConversationComposer(state: ChatConversationState, onDraftChange: (String) -> Unit,
    onAction: (ConversationAction) -> Unit) {
    val validation = state.validatedDraft
    val inputError = validation.error?.takeIf { it != ChatDraftError.Blank }
    HorizontalDivider(color = AppColors.BorderSubtle)
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        if (!state.canSend) Text(stringResource(Res.string.chat_conversation_read_only),
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        // 여러 줄 입력은 이 기능의 UI이며 기존 한 줄 AppTextField 계약을 변경하지 않는다.
        OutlinedTextField(value = state.draft, onValueChange = { if (state.inputEnabled) onDraftChange(it) },
            enabled = state.inputEnabled, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
            label = { Text(stringResource(Res.string.chat_conversation_input)) },
            placeholder = { Text(stringResource(Res.string.chat_conversation_placeholder)) },
            shape = AppShapes.Control, isError = inputError != null, textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppColors.TextPrimary,
                unfocusedBorderColor = AppColors.BorderControl, errorBorderColor = AppColors.Error,
                disabledContainerColor = AppColors.SurfaceMuted))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
            Text(stringResource(Res.string.chat_conversation_count, validation.graphemes),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (inputError != null) AppColors.Error else AppColors.TextSecondary)
            AppButton(stringResource(Res.string.chat_conversation_send),
                enabled = state.submitEnabled, loading = state.submitting,
                loadingText = stringResource(Res.string.chat_conversation_sending),
                onClick = { if (state.submitEnabled) onAction(ConversationAction.Send) })
        }
        if (inputError != null) Text(stringResource(when (inputError) {
            ChatDraftError.Bytes -> Res.string.chat_input_bytes
            ChatDraftError.InvalidText -> Res.string.chat_input_invalid
            else -> Res.string.chat_conversation_too_long
        }),
            style = MaterialTheme.typography.bodySmall, color = AppColors.Error)
    }
}
