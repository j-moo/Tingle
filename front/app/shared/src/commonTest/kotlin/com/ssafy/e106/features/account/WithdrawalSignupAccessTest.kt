package com.ssafy.e106.features.account

import com.ssafy.e106.core.auth.*
import com.ssafy.e106.features.home.ui.*
import com.ssafy.e106.features.proposals.ProposalAccess
import kotlin.test.*

/** 탈퇴 제한 해소가 서비스 전체의 가입 제한을 풀지 않는지 검사한다. */
class WithdrawalSignupAccessTest {
    @Test fun onlyOwnedKeyringCleanupPagesAreAllowedAfterAgreements() {
        for (status in OnboardingStatus.entries) for (page in ServicePage.entries) {
            val session = Session.SignedIn(75, status, false)
            val expected = status != OnboardingStatus.AGREEMENT_REQUIRED &&
                page in setOf(ServicePage.Keyrings, ServicePage.KeyringReset, ServicePage.BleProbe)
            assertEquals(expected, canManageOwnedKeyring(session, page), "$status / $page")
        }
    }

    @Test fun cleanupExceptionDoesNotMakeIncompleteSignupServiceReady() {
        for (status in OnboardingStatus.entries.filter { it != OnboardingStatus.READY }) {
            val session = Session.SignedIn(75, status, false)
            assertFalse(ProposalAccess(false).allowed(session))
            assertFalse(session.isReady)
        }
    }
}
