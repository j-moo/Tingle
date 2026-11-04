package com.ssafy.e106.features.profile

import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session

/** Screen access only: never changes the server session or registers a keyring. */
class DevelopmentProfileAccess(private val enabled: Boolean = false) {
    // 동기화 단계에서는 등록을 다시 요구하지 않고 기존 기기를 연결할 개발 진입점을 제공한다.
    fun canSyncKeyring(session: Session.SignedIn) = enabled && session.status == OnboardingStatus.SYNC_PENDING
    fun canManageProfile(session: Session.SignedIn) = session.isReady || canSkipKeyring(session)
    fun canSkipKeyring(session: Session.SignedIn) = enabled && !session.isReady &&
        session.status == OnboardingStatus.KEYRING_REQUIRED

    fun canEditProfile(session: Session.SignedIn) = !session.isReady &&
        (session.status == OnboardingStatus.PROFILE_REQUIRED || canSkipKeyring(session))
}
