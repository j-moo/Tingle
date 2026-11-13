package com.ssafy.e106.features.account.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable fun ConnectedAiConsentRoute(session: Session.SignedIn, onBack: () -> Unit,
    model: AiConsentViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var openedVersion by rememberSaveable(session.userId) { mutableStateOf<String?>(null) }
    var openFailed by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LaunchedEffect(session) {
        // 메뉴로 나가도 ViewModel은 유지될 수 있으므로 재진입 때 서버의 최신 동의·고지를 조회한다.
        // 화면 회전 중 진행 중인 저장은 load()의 busy 검사로 보존하며 중복 요청하지 않는다.
        model.load()
    }
    ProfileBackHandler(true) { if(!state.ui.busy) onBack() }
    if(state.owner != session) return
    // 오류 종류와 전문 열기 실패는 화면 안(상태 화면 · 버튼 근처 빨간 줄)에서 보인다. 화면 밖 위쪽에 글을 따로 얹지 않는다
    AiConsentScreen(state.ui, model::check, { action -> when(action) {
        AiConsentAction.Back, AiConsentAction.Login -> onBack()
        AiConsentAction.Reload, AiConsentAction.CheckResult -> model.load()
        AiConsentAction.OpenNotice -> state.ui.notice?.contentUrl?.let { url ->
            // 열기 성공은 읽음/동의가 아니다. 복귀 후 사용자가 확인 버튼을 눌러야 한다.
            runCatching { uri.openUri(url) }.onSuccess { openedVersion = state.ui.notice?.version; openFailed = false }
                .onFailure { openFailed = true }
        }
        AiConsentAction.Grant -> model.save(true)
        AiConsentAction.Withdraw -> model.withdraw()
        AiConsentAction.CancelWithdrawal -> model.cancel()
        AiConsentAction.ConfirmWithdrawal -> model.save(false)
    } }, error = state.error, noticeOpenFailed = openFailed)
    if(openedVersion != null && openedVersion == state.ui.notice?.version) AlertDialog(
        onDismissRequest = { openedVersion = null }, title = { Text(stringResource(Res.string.ai_consent_notice_title)) },
        text = { Text(stringResource(Res.string.chat_extras_document_review)) },
        confirmButton = { TextButton(onClick = { model.reviewed(); openedVersion = null }) { Text(stringResource(Res.string.ai_consent_notice_read)) } },
        dismissButton = { TextButton(onClick = { openedVersion = null }) { Text(stringResource(Res.string.chat_extras_cancel)) } })
}
