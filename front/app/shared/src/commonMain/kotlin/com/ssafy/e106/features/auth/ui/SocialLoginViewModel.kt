package com.ssafy.e106.features.auth.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.LoginResult
import com.ssafy.e106.core.auth.Provider
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.catchingNonCancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart

/** SDK와 서버 인증은 AuthRepository에 맡기고 화면 상태만 관리한다. [devicePrefs] = 마지막 로그인 제공자 기록(계정 키가 아니라 로그아웃해도 남음) */
class SocialLoginViewModel(private val auth: AuthRepository, private val devicePrefs: DataStore<Preferences>? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(SocialLoginState(
        availableProviders = LoginProvider.entries.filter { auth.available(it.provider()) }.toSet(),
    ))
    val state = mutableState.asStateFlow()

    init {
        devicePrefs?.let { prefs ->
            viewModelScope.launch {
                val saved = catchingNonCancel { prefs.data.first()[LAST_PROVIDER] }.getOrNull()   // 못 읽으면 표시만 안 함
                // 읽는 사이 로그인이 먼저 성공했으면 그 값을 덮지 않는다
                LoginProvider.entries.find { it.name == saved }?.let { last -> mutableState.update { it.copy(lastProvider = it.lastProvider ?: last) } }
            }
        }
    }

    // UI(Main)에서 호출. launch 전에 잠가 연속 탭도 한 번만 인증을 시작한다.
    fun login(provider: LoginProvider) {
        val current = state.value
        if (current.busyProvider != null || current.completed || provider !in current.availableProviders) return
        mutableState.value = current.copy(busyProvider = provider, errorMessage = null, rejoinAvailableAt = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                when (val result = auth.login(provider.provider())) {
                    is LoginResult.Success -> {
                        mutableState.update { it.copy(completed = true, lastProvider = provider) }
                        devicePrefs?.let { prefs -> catchingNonCancel { prefs.edit { it[LAST_PROVIDER] = provider.name } } }   // 저장 실패는 다음 로그인에 표시만 빠짐
                    }
                    LoginResult.Cancelled -> Unit
                    is LoginResult.Failed -> mutableState.update {
                        it.copy(errorMessage = result.error.message, errorId = it.errorId + 1,
                            rejoinAvailableAt = (result.error as? AppError.Server)?.rejoinAvailableAt)
                    }
                }
            } finally {
                // 화면이 유지된 채 작업이 취소되어도 다시 시도할 수 있게 한다. 취소 예외는 삼키지 않는다.
                mutableState.update { it.copy(busyProvider = null) }
            }
        }
    }

    fun dismissError(id: Long) {
        mutableState.update { if (it.errorId == id) it.copy(errorMessage = null, rejoinAvailableAt = null) else it }
    }

    /** Activity 소유 ViewModel은 회전 때 유지된다. 재로그인 화면에 돌아오면 이전 성공만 해제한다. */
    fun prepareForLogin() {
        mutableState.update { if (it.completed) it.copy(completed = false, errorMessage = null) else it }
    }
}

// 키 이름에 "<userId>:" 가 없어 로그아웃 때 계정 기록 정리(DataStoreAppPrefs.clearUsers)에 지워지지 않는다
internal val LAST_PROVIDER = stringPreferencesKey("login.last_provider")

private fun LoginProvider.provider(): Provider = when (this) {
    LoginProvider.Google -> Provider.GOOGLE
    LoginProvider.Kakao -> Provider.KAKAO
    LoginProvider.Naver -> Provider.NAVER
}
