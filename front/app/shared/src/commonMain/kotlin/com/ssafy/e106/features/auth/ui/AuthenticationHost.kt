package com.ssafy.e106.features.auth.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionEvent
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.startup.ui.StartupScreen
import com.ssafy.e106.features.onboarding.ui.OnboardingRoute
import org.koin.compose.koinInject

/** 서버 세션으로 로그인/가입 단계를 선택한다. SessionEvent는 이곳 한 군데에서만 받는다. */
@Composable
fun AuthenticationHost(
    sessionStore: SessionStore = koinInject(),
    signedIn: @Composable (Session.SignedIn) -> Unit = { OnboardingRoute(it) },
) {
    val session by sessionStore.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sessionStore, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            sessionStore.events.collect { event ->
                if (event == SessionEvent.Expired) snackbar.showSnackbar(AppError.SessionExpired.message)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        when (val current = session) {
            Session.Restoring -> StartupScreen()
            Session.SignedOut -> SocialLoginRoute(snackbar)
            is Session.SignedIn -> signedIn(current)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(AppSpacing.Medium))
    }
}
