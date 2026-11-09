package com.ssafy.e106.features.profile

import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.features.profile.ui.ProfilePage
import com.ssafy.e106.features.profile.ui.reachable
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileAccordionTest {
    // 앞 섹션이 서버에 저장돼야 뒤 섹션 머리줄을 누를 수 있다
    @Test fun reachableFollowsSavedSections() {
        val basics = ProfileResponse(1, nickname = "초록 산책", birthDate = "1998-04-12", gender = "FEMALE")
        assertEquals(ProfilePage.Basics, (null as ProfileResponse?).reachable())
        assertEquals(ProfilePage.Basics, ProfileResponse(1, nickname = "초록 산책").reachable())
        assertEquals(ProfilePage.Lifestyle, basics.reachable())
        assertEquals(ProfilePage.Lifestyle, basics.copy(smokingStatus = "NON_SMOKER").reachable())
        assertEquals(ProfilePage.Optional, basics.copy(smokingStatus = "NON_SMOKER", drinkingFrequency = "MONTHLY").reachable())
    }
}
