package com.ssafy.e106.features.auth.ui

// UI 전용 타입. SDK·토큰은 화면에 전달하지 않는다.
enum class LoginProvider { Google, Kakao, Naver }

data class SocialLoginState(
    // 호출부에서 AuthRepository.available()로 확인한 제공자만 전달한다.
    val availableProviders: Set<LoginProvider> = emptySet(),
    val busyProvider: LoginProvider? = null,
    val errorMessage: String? = null,
    val errorId: Long = 0,
    val completed: Boolean = false,
    // 이 기기에서 마지막으로 로그인에 성공한 제공자("최근 로그인" 표시). 계정과 무관한 기기 기록
    val lastProvider: LoginProvider? = null,
)
