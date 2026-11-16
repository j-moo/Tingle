package com.ssafy.e106.core.auth

import kotlin.test.*

class SessionReadyTest {
    @Test fun completedAccountStaysReadyWithoutKeyringButNotWithoutAgreement() {
        fun ready(status: OnboardingStatus, completed: Boolean) = Session.SignedIn(1, status, completed).isReady
        assertTrue(ready(OnboardingStatus.READY, false))
        // 가입을 마친 계정: 뒤 단계(설문 재검사 등)나 키링이 없는 상태(모두 초기화)로 가도 쓴다. 약관 재동의만 막는다
        assertTrue(ready(OnboardingStatus.SURVEY_REQUIRED, true))
        assertTrue(ready(OnboardingStatus.SYNC_PENDING, true))
        assertFalse(ready(OnboardingStatus.AGREEMENT_REQUIRED, true))
        assertTrue(ready(OnboardingStatus.KEYRING_REQUIRED, true))
        assertFalse(ready(OnboardingStatus.KEYRING_REQUIRED, false))
    }
}
