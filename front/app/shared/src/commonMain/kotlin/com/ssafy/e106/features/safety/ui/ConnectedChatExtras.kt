package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 질문 선택은 초안 교체만 한다. 전송과 동의는 각각 사용자의 별도 행동이다. */
@Composable fun ConnectedChatExtras(room: Long, readable: Boolean, editable: Boolean, insert: (String) -> Unit,
    model: ChatExtrasViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var candidate by remember(room) { mutableStateOf<String?>(null) }
    LaunchedEffect(room, readable) { model.bind(room, readable) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.bind(room, readable) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { candidate = null; model.clear() }
    DisposableEffect(room) { onDispose { model.clear() } }
    if(state.room != room || !readable) return
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        state.notices.forEach { notice ->
            var visible by remember(notice.noticeId) { mutableStateOf(false) }
            Text(notice.text, Modifier.onGloballyPositioned { visible = !it.boundsInWindow().isEmpty })
            // LazyColumn은 화면 밖 항목도 미리 구성할 수 있어 실제 표시 좌표를 확인한다.
            LaunchedEffect(notice.noticeId, visible) { if(visible) { withFrameNanos { }; model.displayedNotice(notice.noticeId) } }
        }
        Text(stringResource(Res.string.chat_extras_questions), style = MaterialTheme.typography.titleMedium)
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.questions.forEach { question -> AppButton(question, Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
            enabled = editable, onClick = { candidate = question }) }
        if(state.status == "FAILED") Text(stringResource(Res.string.chat_extras_failed))
        state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        // 오류 복구는 생성 POST를 반복하지 않고 기존 결과 조회부터 재시도한다.
        if(state.accessRetryRequired) AppButton(stringResource(Res.string.chat_extras_retry),
            enabled = !state.busy, onClick = model::check)
        else if(state.questions.isEmpty()) AppButton(stringResource(if(state.status == null) Res.string.chat_extras_generate else Res.string.chat_extras_check),
            enabled = !state.busy, onClick = { if(state.status == null) model.generate() else model.check() })
    }
    if(candidate != null) AlertDialog(onDismissRequest = { candidate = null },
        title = { Text(stringResource(Res.string.chat_extras_insert_title)) },
        text = { Text(stringResource(Res.string.chat_extras_insert_help)) },
        confirmButton = { TextButton(enabled = editable && candidate in state.questions, onClick = {
            candidate?.takeIf { it in state.questions }?.let(insert); candidate = null
        }) { Text(stringResource(Res.string.chat_extras_insert)) } },
        dismissButton = { TextButton(onClick = { candidate = null }) { Text(stringResource(Res.string.chat_extras_cancel)) } })
}
