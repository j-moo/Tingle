package com.ssafy.e106.features.report.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.StateAction
import com.ssafy.e106.core.ui.components.StateKind
import com.ssafy.e106.core.ui.components.StateScreen
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import com.ssafy.e106.features.safety.ui.StateLoadingScreen
import com.ssafy.e106.features.safety.ui.StateNoteScreen
import org.koin.compose.viewmodel.koinViewModel

@Composable fun ConnectedReportRoute(session: Session.SignedIn, room: Long, onBack: () -> Unit,
    model: ReportViewModel = koinViewModel(), onBlock: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(session, room) { model.bind(room) }
    // 저장 요청 조회가 중단됐다면 증거 조회보다 복구를 먼저 재개한다.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume(room) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.pause() }
    DisposableEffect(Unit) { onDispose { model.pause() } }
    ProfileBackHandler(true) { if(state.room != room || !state.busy || !state.recoveryComplete) onBack() }
    if(state.owner != session) return
    val bar = stringResource(Res.string.report_title)
    // 이전 방의 전송을 마무리하는 동안 새 방의 빈 화면 대신 뼈대와 나가기(위쪽 이전)를 제공한다.
    if(state.room != room) { StateLoadingScreen(bar, stringResource(Res.string.report_switch_wait), onBack); return }
    // 복구 중/실패를 '신고할 수 없음'과 구분하고 재조회·나가기 경로를 제공한다. 확인 중은 뼈대, 못 읽었으면 상태 화면
    if(!state.recoveryComplete) {
        if(state.storageIssue == null && state.error == null) StateLoadingScreen(bar, stringResource(Res.string.report_restoring), onBack)
        else {
            val retry = if(state.busy) null else StateAction(stringResource(Res.string.report_restore_retry)) { model.resume(room) }
            val back = StateAction(stringResource(Res.string.report_back), onClick = onBack)
            // 기록이 손상됐으면 중복 접수 방지 · 문의 안내를 함께 둔다
            if(state.storageIssue == ReportStorageIssue.Corrupt) StateNoteScreen(StateKind.Unconfirmed, stringResource(Res.string.report_storage_corrupt_title),
                stringResource(Res.string.report_storage_corrupt_note), barTitle = bar, primary = retry, secondary = back)
            else StateScreen(StateKind.Unconfirmed, stringResource(Res.string.report_restore_failed), barTitle = bar, primary = retry, secondary = back)
        }
        return
    }
    Column {
        // 권한 재조회 오류는 안전영역 안의 상태 카드가 설명한다. 화면 위에 같은 오류를 중복 출력하지 않는다.
        if(!state.ui.accessRetryAvailable) state.error?.let { Text(it.message) }
        state.storageIssue?.let { issue ->
            Text(stringResource(if(issue == ReportStorageIssue.Corrupt) Res.string.report_storage_corrupt else Res.string.report_restore_failed))
        }
        ReportScreen(state.ui, model::reason, model::detail, model::select) { action ->
            if(action in listOf(ReportAction.Back, ReportAction.Close)) { if(!state.busy) onBack() }
            else if(action == ReportAction.Block) { if(!state.busy) onBlock() }
            else model.action(action)
        }
    }
}
