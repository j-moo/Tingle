package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 서버 B/user/OnboardingStatus.java 와 같은 값. 로그인 응답의 onboardingStatus, 단계 조회 응답의 nextStep 을 이것으로 받는다 */
enum class OnboardingStatus { AGREEMENT_REQUIRED, KEYRING_REQUIRED, PROFILE_REQUIRED, SURVEY_REQUIRED, PREFERENCES_REQUIRED, SYNC_PENDING, READY }

/** 지금 사용자 상태. NavHost 는 이 셋으로 첫 화면을 고른다 */
sealed interface Session {
    /** 앱 시작 직후. 저장 토큰 읽기 + GET users/{id}/onboarding 끝날 때까지 */
    data object Restoring : Session

    data object SignedOut : Session

    data class SignedIn(
        val userId: Long,
        val status: OnboardingStatus,
        val signupCompleted: Boolean,
    ) : Session {
        /**
         * 서비스를 쓸 수 있는지. READY 판단은 이것만 쓴다(서버 canAccess 와 같은 규칙). 가입을 마쳤어도 약관 미동의면 막힌다.
         * 키링을 모두 초기화해 키링이 없는(KEYRING_REQUIRED) 가입 완료 계정은 막지 않는다: 홈 · 제안 · 채팅 · 마이를 그대로 쓰고,
         * 홈이 새 매칭 버튼 자리에 "키링을 연결해 주세요"와 "키링 연결하기"를 보여 준다
         */
        val isReady get() = status == OnboardingStatus.READY || (signupCompleted && status != OnboardingStatus.AGREEMENT_REQUIRED)
    }
}

/** 한 번만 보여줄 것(스낵바)용. 화면 이동은 [SessionStore.state] 가 바뀌면서 NavHost 가 한다 */
sealed interface SessionEvent {
    /** 세션이 끝나 SignedOut 이 된 직후 1회 */
    data object Expired : SessionEvent

    /** 403 을 받고 refreshOnboarding() 결과 단계가 실제로 바뀌었을 때 1회 */
    data class OnboardingRequired(val status: OnboardingStatus) : SessionEvent
}

interface SessionStore {
    /** 시작값 [Session.Restoring] */
    val state: StateFlow<Session>

    /** Channel(BUFFERED).receiveAsFlow(). 수집하는 곳이 없는 동안 쌓였다가 전달되고 받으면 사라진다. 수집하는 곳은 한 군데(NavHost 최상위) */
    val events: Flow<SessionEvent>

    /** GET users/{id}/onboarding 을 다시 읽고 state 갱신. 단계 API 가 Ok 인 온보딩 화면이 부른다. 동시에 여러 번 불려도 요청은 하나로 합친다 */
    suspend fun refreshOnboarding(): ApiResult<Unit>
}
