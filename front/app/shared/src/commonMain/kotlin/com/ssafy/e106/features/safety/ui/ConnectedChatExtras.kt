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
    model: ChatExtrasViewModel = koinViewModel(), draft: String = "", onBlock: () -> Unit = {}, latestMessage: String? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(room, readable) { model.bind(room, readable) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.bind(room, readable) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.clear() }
    DisposableEffect(room) { onDispose { model.clear() } }
    // 새 메시지(내가 보낸 것 포함) 뒤에 위험 안내만 몇 번 다시 본다(시각은 ViewModel 이 정한다)
    LaunchedEffect(room, latestMessage) { if(latestMessage != null) model.watchSafety() }
    if(state.room != room || !readable) return
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        state.notices.forEach { notice ->
            var visible by remember(notice.noticeId) { mutableStateOf(false) }
            Text(notice.text, Modifier.onGloballyPositioned { visible = !it.boundsInWindow().isEmpty })
            // LazyColumn은 화면 밖 항목도 미리 구성할 수 있어 실제 표시 좌표를 확인한다.
            LaunchedEffect(notice.noticeId, visible) { if(visible) { withFrameNanos { }; model.displayedNotice(notice.noticeId) } }
        }
        state.safety.forEach { notice -> SafetyNoticeCard(notice, onBlock) { model.closeSafety(notice.noticeId) } }
        // 패널을 접어도 연결 컴포넌트는 유지한다. 여닫기는 POST/GET을 새로 시작하지 않는다.
        ChatQuestionCards(state, editable, draft, insert, model::generate, model::check, model::setTopicConsent)
    }
}
