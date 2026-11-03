package com.ssafy.e106.features.profile

import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session

/** Screen access only: never changes the server session or registers a keyring. */
class DevelopmentProfileAccess(private val enabled: Boolean = false) {
    fun canManageProfile(session: Session.SignedIn) = session.isReady || canSkipKeyring(session)
    fun canSkipKeyring(session: Session.SignedIn) = enabled && !session.isReady &&
        session.status == OnboardingStatus.KEYRING_REQUIRED

    fun canEditProfile(session: Session.SignedIn) = !session.isReady &&
        (session.status == OnboardingStatus.PROFILE_REQUIRED || canSkipKeyring(session))
}
