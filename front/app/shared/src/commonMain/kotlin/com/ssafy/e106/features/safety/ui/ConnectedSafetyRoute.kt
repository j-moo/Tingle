package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.theme.AppSpacing
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
    // 복구 전에는 본 화면의 Scaffold가 없으므로 안전영역·스크롤을 직접 적용한다.
    // 짧은 화면이나 큰 글씨에서도 오류 안내와 재조회/돌아가기가 가려지지 않아야 한다.
    if(!state.recoveryComplete) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                val message = when {
                    state.storageIssue == SafetyStorageIssue.Corrupt -> Res.string.safety_storage_corrupt
                    state.storageIssue != null || state.error != null -> Res.string.safety_restore_failed
                    else -> Res.string.safety_restoring
                }
                Text(stringResource(message))
                if(!state.busy) AppButton(stringResource(Res.string.safety_restore_retry), onClick = { model.bind(room, operation) })
                AppButton(stringResource(Res.string.safety_restore_back), onClick = onBack)
            }
        }
        return
    }
    Column {
        state.error?.let { Text(it.message) }
        state.storageIssue?.let { issue ->
            Text(stringResource(if(issue == SafetyStorageIssue.Corrupt) Res.string.safety_storage_corrupt else Res.string.safety_restore_failed))
        }
        SafetyActionScreen(state.ui, onAction = { action -> when(action) {
            SafetyUiAction.OpenConfirmation -> model.ask()
            SafetyUiAction.Confirm -> model.submit()
            SafetyUiAction.Cancel -> model.cancel()
            SafetyUiAction.Retry, SafetyUiAction.CheckResult -> model.recover()
            SafetyUiAction.Close -> if(state.ui.phase in listOf(SafetyPhase.Succeeded, SafetyPhase.ClosedUnconfirmed)) onClosed() else onBack()
            SafetyUiAction.Back -> if(!state.busy) onBack()
        } })
    }
}
