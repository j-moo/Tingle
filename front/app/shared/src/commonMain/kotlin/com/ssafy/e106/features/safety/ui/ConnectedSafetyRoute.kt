package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
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
    Column {
        state.error?.let { Text(it.message) }
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
