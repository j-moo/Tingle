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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionEvent
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.startup.ui.StartupScreen
import com.ssafy.e106.features.onboarding.ui.OnboardingRoute
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 서버 세션으로 로그인/가입 단계를 선택한다. SessionEvent는 이곳 한 군데에서만 받는다. */
@Composable
fun AuthenticationHost(
    sessionStore: SessionStore = koinInject(),
    auth: AuthRepository = koinInject(),
    signedIn: @Composable (Session.SignedIn) -> Unit = { OnboardingRoute(it) },
) {
    val session by sessionStore.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    // 이번 실행에서 로그인 화면을 거쳤는지. 앱을 완전히 껐다 켜면(저장 상태 없음) false — 그때 가입 약관 동의 전 세션은 자동 로그아웃한다.
    // 시스템이 뒤에서 프로세스를 정리했다가 복귀하는 경우는 저장 상태가 남아 이어서 동의할 수 있다
    var passedLogin by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(session) {
        val current = session
        if (current is Session.SignedOut) passedLogin = true
        if (current is Session.SignedIn && current.needsSignupAgreement() && !passedLogin) auth.logout()
    }
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
            is Session.SignedIn -> when {
                !current.needsAgreement() -> signedIn(current)
                current.needsSignupAgreement() && !passedLogin -> StartupScreen()   // 위 자동 로그아웃이 끝날 때까지
                // 로그인 직후 약관: 로그인 화면 위에 시트로. ✕ · 뒤로 가기 = 로그아웃
                // 약관 개정으로 다시 동의하는 기존 회원은 이미 로그인한 사람이라 뒤에 로그인 화면 대신 시작 화면
                else -> AgreementSheet(onClose = { scope.launch { auth.logout() } },
                    background = { if (current.signupCompleted) StartupScreen() else SocialLoginRoute(snackbar) }) { signedIn(current) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(AppSpacing.Medium))
    }
}

/** 약관 동의 단계(가입 완료 회원의 약관 개정 재동의 포함) */
private fun Session.SignedIn.needsAgreement() = status == OnboardingStatus.AGREEMENT_REQUIRED && !isReady

/** 가입 전 첫 약관 동의 — 앱을 껐다 켜면 로그아웃하는 대상. 약관 개정으로 다시 동의하는 기존 회원은 로그아웃하지 않는다 */
private fun Session.SignedIn.needsSignupAgreement() = needsAgreement() && !signupCompleted
