package com.ssafy.e106.features.report.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import org.koin.compose.viewmodel.koinViewModel

@Composable fun ConnectedReportRoute(session: Session.SignedIn, room: Long, onBack: () -> Unit,
    model: ReportViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(session, room) { model.bind(room) }
    // 저장 요청 조회가 중단됐다면 증거 조회보다 복구를 먼저 재개한다.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume(room) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.pause() }
    DisposableEffect(Unit) { onDispose { model.pause() } }
    ProfileBackHandler(true) { if(state.room != room || !state.busy || !state.recoveryComplete) onBack() }
    if(state.owner != session) return
    // 이전 방의 전송을 마무리하는 동안 새 방의 빈 화면 대신 대기 안내와 나가기를 제공한다.
    if(state.room != room) {
        Column {
            Text(stringResource(Res.string.report_switch_wait))
            AppButton(stringResource(Res.string.report_back), onClick = onBack)
        }
        return
    }
    Column {
        state.error?.let { Text(it.message) }
        state.storageIssue?.let { issue ->
            Text(stringResource(if(issue == ReportStorageIssue.Corrupt) Res.string.report_storage_corrupt else Res.string.report_restore_failed))
        }
        // 복구 중/실패를 '신고할 수 없음'과 구분하고 재조회·나가기 경로를 제공한다.
        if(!state.recoveryComplete) {
            if(state.storageIssue == null) Text(stringResource(if(state.error == null) Res.string.report_restoring else Res.string.report_restore_failed))
            if(!state.busy) AppButton(stringResource(Res.string.report_restore_retry), onClick = { model.resume(room) })
            AppButton(stringResource(Res.string.report_back), onClick = onBack)
            return@Column
        }
        ReportScreen(state.ui, model::reason, model::detail, model::select) { action ->
            if(action in listOf(ReportAction.Back, ReportAction.Close)) { if(!state.busy) onBack() }
            else model.action(action)
        }
    }
}
