package com.ssafy.e106.features.onboarding.ui

import com.ssafy.e106.core.auth.OnboardingStatus.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SignupHubTest {
    @Test fun hubRowsFollowSignupOrder() {
        assertEquals(listOf(1, 2, 3, 4), listOf(PROFILE_REQUIRED, SURVEY_REQUIRED, PREFERENCES_REQUIRED, SYNC_PENDING).map(::hubStep))
    }
    @Test fun stepsOutsideHubAreMinusOne() {
        assertEquals(listOf(-1, -1, -1), listOf(AGREEMENT_REQUIRED, KEYRING_REQUIRED, READY).map(::hubStep))
    }
    @Test fun returningFromStepShowsFinishedStepFirst() {
        assertEquals(1, hubFirstShown(previous = 1, current = 2))   // 프로필 끝내고 돌아옴 → 프로필 펼친 채 시작
        assertEquals(2, hubFirstShown(previous = -1, current = 2))  // 처음 들어옴
        assertEquals(2, hubFirstShown(previous = 2, current = 2))   // 같은 단계로 다시 들어옴
        assertEquals(1, hubFirstShown(previous = 3, current = 1))   // 서버가 앞 단계로 되돌림 → 연출 없음
    }
}
