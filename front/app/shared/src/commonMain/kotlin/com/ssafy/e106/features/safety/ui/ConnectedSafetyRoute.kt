package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.StateAction
import com.ssafy.e106.core.ui.components.StateKind
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import com.ssafy.e106.features.profile.ui.ProfileBackHandler
import org.koin.compose.viewmodel.koinViewModel

@Composable fun ConnectedSafetyRoute(session: Session.SignedIn, room: Long, operation: SafetyOperation,
    onBack: () -> Unit, onClosed: () -> Unit, model: SafetyViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(session, room, operation) { model.bind(room, operation) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.bind(room, operation) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.pause() }
    DisposableEffect(Unit) { onDispose { model.pause() } }
    ProfileBackHandler(true) { if(!state.busy) onBack() }
    if(state.owner != session || state.room != room) return
    // 이전 요청을 확인하기 전에는 시트를 열지 않는다. 확인 중(잠깐 멈춘 때 포함. 돌아오면 다시 확인한다)은 뼈대, 못 읽었으면 상태 화면(중복 요청 방지 안내는 그대로 둔다)
    if(!state.recoveryComplete) {
        val bar = stringResource(if(operation == SafetyOperation.Block) Res.string.safety_block_confirm else Res.string.safety_leave_confirm)
        if(state.storageIssue == null && state.error == null) StateLoadingScreen(bar, stringResource(Res.string.safety_restoring), onBack)
        else {
            val corrupt = state.storageIssue == SafetyStorageIssue.Corrupt
            StateNoteScreen(StateKind.Unconfirmed,
                stringResource(if(corrupt) Res.string.safety_storage_corrupt_title else Res.string.safety_restore_failed_title),
                stringResource(if(corrupt) Res.string.safety_storage_corrupt_note else Res.string.safety_restore_failed_note), barTitle = bar,
                primary = if(state.busy) null else StateAction(stringResource(Res.string.safety_restore_retry)) { model.bind(room, operation) },
                secondary = StateAction(stringResource(Res.string.safety_restore_back), onClick = onBack))
        }
        return
    }
    Column {
        state.error?.let { Text(it.message) }
        state.storageIssue?.let { issue ->
            Text(stringResource(if(issue == SafetyStorageIssue.Corrupt) Res.string.safety_storage_corrupt else Res.string.safety_restore_failed))
        }
        SafetyActionScreen(state.ui, onAction = { action -> when(action) {
            // 시트 한 장에서 바로 확인한다: 확인 단계로 올린 뒤 곧바로 보낸다(ask 는 조건이 안 맞으면 아무것도 안 하고, submit 도 그때는 보내지 않는다)
            SafetyUiAction.Confirm -> { model.ask(); model.submit() }
            SafetyUiAction.Retry, SafetyUiAction.CheckResult -> model.recover()
            SafetyUiAction.Close -> if(state.ui.phase in listOf(SafetyPhase.Succeeded, SafetyPhase.ClosedUnconfirmed)) onClosed() else onBack()
            SafetyUiAction.Back -> if(!state.busy) onBack()
        } })
    }
}
