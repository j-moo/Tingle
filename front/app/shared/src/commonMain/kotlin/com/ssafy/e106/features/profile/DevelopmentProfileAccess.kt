package com.ssafy.e106.features.profile

import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session

/** Screen access only: never changes the server session or registers a keyring. */
class DevelopmentProfileAccess(private val enabled: Boolean = false) {
    fun canManageProfile(session: Session.SignedIn) = session.isReady || canSkipKeyring(session) ||
        // 가입 중에도 이미 작성한 프로필은 가입 준비 화면에서 다시 열어 고칠 수 있다
        session.status in setOf(OnboardingStatus.SURVEY_REQUIRED, OnboardingStatus.PREFERENCES_REQUIRED, OnboardingStatus.SYNC_PENDING)
    // 개발용 키링 건너뛰기는 첫 가입에만. 가입을 마친 뒤 키링을 모두 초기화한 계정은 키링을 다시 등록해야 한다
    fun canSkipKeyring(session: Session.SignedIn) = enabled && !session.isReady && !session.signupCompleted &&
        session.status == OnboardingStatus.KEYRING_REQUIRED

    fun canEditProfile(session: Session.SignedIn) = !session.isReady &&
        (session.status == OnboardingStatus.PROFILE_REQUIRED || canSkipKeyring(session))
}
