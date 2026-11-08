package com.ssafy.e106.features.account.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import org.koin.compose.koinInject

/** 인증 분기보다 바깥에 둬 SignedOut 전환/Activity 회전에도 결과 화면을 유지한다. */
@Composable
internal fun WithdrawalRoot(session: Session, content: @Composable () -> Unit,
    controller: WithdrawalCoordinator = koinInject()) {
    val state by controller.state.collectAsStateWithLifecycle()
    // 다른 계정 로그인 중에는 이전 사용자의 탈퇴 시각/정보를 표시하지 않는다.
    val visible = state.visible && session != Session.Restoring &&
        ((session == Session.SignedOut && state.owner == state.lastAccount) || (session as? Session.SignedIn)?.userId == state.owner)
    if (visible) {
        val ui = if (session == Session.SignedOut && state.ui.stage in setOf(WithdrawalStage.Confirm, WithdrawalStage.Ready))
            state.ui.resultUnknown() else state.ui
        ProfileBackHandler(true) { if (!state.ui.busy && !state.ui.localCleanupPending) controller.close() }
        WithdrawalScreen(ui, controller::accepted) { action -> when (action) {
            WithdrawalAction.Prepare -> controller.prepare()
            WithdrawalAction.Execute -> controller.execute()
            WithdrawalAction.Reload, WithdrawalAction.CheckResult -> controller.reload()
            WithdrawalAction.CancelPreparation -> controller.cancel()
            WithdrawalAction.OpenKeyring -> controller.openKeyringManagement()
            WithdrawalAction.Back, WithdrawalAction.OpenStart -> controller.close()
        } }
    } else content()
}

/** 탈퇴 화면이 닫혀 서비스 호스트가 다시 그려진 뒤, 현재 계정의 키링 이동을 한 번만 소비한다. */
@Composable
internal fun WithdrawalKeyringNavigation(userId: Long, controller: WithdrawalCoordinator, onNavigate: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.keyringOwner, userId) {
        if (controller.consumeKeyringNavigation(userId)) onNavigate()
    }
}
