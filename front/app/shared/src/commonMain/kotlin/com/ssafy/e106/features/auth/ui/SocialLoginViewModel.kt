package com.ssafy.e106.features.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.LoginResult
import com.ssafy.e106.core.auth.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart

/** SDK와 서버 인증은 AuthRepository에 맡기고 화면 상태만 관리한다. */
class SocialLoginViewModel(private val auth: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SocialLoginState(
        availableProviders = LoginProvider.entries.filter { auth.available(it.provider()) }.toSet(),
    ))
    val state = mutableState.asStateFlow()

    // UI(Main)에서 호출. launch 전에 잠가 연속 탭도 한 번만 인증을 시작한다.
    fun login(provider: LoginProvider) {
        val current = state.value
        if (current.busyProvider != null || current.completed || provider !in current.availableProviders) return
        mutableState.value = current.copy(busyProvider = provider, errorMessage = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                when (val result = auth.login(provider.provider())) {
                    is LoginResult.Success -> mutableState.update { it.copy(completed = true) }
                    LoginResult.Cancelled -> Unit
                    is LoginResult.Failed -> mutableState.update {
                        it.copy(errorMessage = result.error.message, errorId = it.errorId + 1)
                    }
                }
            } finally {
                // 화면이 유지된 채 작업이 취소되어도 다시 시도할 수 있게 한다. 취소 예외는 삼키지 않는다.
                mutableState.update { it.copy(busyProvider = null) }
            }
        }
    }

    fun dismissError(id: Long) {
        mutableState.update { if (it.errorId == id) it.copy(errorMessage = null) else it }
    }

    /** Activity 소유 ViewModel은 회전 때 유지된다. 재로그인 화면에 돌아오면 이전 성공만 해제한다. */
    fun prepareForLogin() {
        mutableState.update { if (it.completed) it.copy(completed = false, errorMessage = null) else it }
    }
}

private fun LoginProvider.provider(): Provider = when (this) {
    LoginProvider.Google -> Provider.GOOGLE
    LoginProvider.Kakao -> Provider.KAKAO
    LoginProvider.Naver -> Provider.NAVER
}
