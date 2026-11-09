package com.ssafy.e106.features.auth.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.login_rejoin_available_at
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 화면 상태와 클릭을 잇는 자리. Preview에서는 이 함수 대신 순수 Screen을 쓴다. */
@Composable
fun SocialLoginRoute(snackbar: SnackbarHostState, viewModel: SocialLoginViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel) { viewModel.prepareForLogin() }
    val availableAt = state.rejoinAvailableAt
    val message = state.errorMessage?.let { error ->
        // 시계 오차로 날짜를 계산하지 않는다. 서버 시각을 그대로 쓰고 시간대는 UTC로 명시한다.
        if (availableAt == null) error else stringResource(Res.string.login_rejoin_available_at, error,
            availableAt.toString().removeSuffix("Z").substringBefore('.').replace('T', ' '))
    }
    LaunchedEffect(state.errorId, message, lifecycle) {
        if (message == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snackbar.showSnackbar(message, duration = if (availableAt == null) SnackbarDuration.Short else SnackbarDuration.Long)
            viewModel.dismissError(state.errorId)
        }
    }
    SocialLoginScreen(state, viewModel::login, showInlineError = false)
}
