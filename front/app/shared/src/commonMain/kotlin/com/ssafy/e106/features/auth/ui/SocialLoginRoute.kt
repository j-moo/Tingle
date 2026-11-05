package com.ssafy.e106.features.auth.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.koin.compose.viewmodel.koinViewModel

/** 화면 상태와 클릭을 잇는 자리. Preview에서는 이 함수 대신 순수 Screen을 쓴다. */
@Composable
fun SocialLoginRoute(snackbar: SnackbarHostState, viewModel: SocialLoginViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel) { viewModel.prepareForLogin() }
    LaunchedEffect(state.errorId, state.errorMessage, lifecycle) {
        val message = state.errorMessage ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snackbar.showSnackbar(message)
            viewModel.dismissError(state.errorId)
        }
    }
    SocialLoginScreen(state, viewModel::login, showInlineError = false)
}
