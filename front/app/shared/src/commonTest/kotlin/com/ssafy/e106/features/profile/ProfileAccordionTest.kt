package com.ssafy.e106.features.profile

import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.features.profile.ui.ProfileEntry
import com.ssafy.e106.features.profile.ui.ProfileFlowState
import com.ssafy.e106.features.profile.ui.ProfilePage
import com.ssafy.e106.features.profile.ui.entry
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

    // 들어왔을 때: 불러오는 중은 뼈대, 못 불러왔으면 상태 화면(다시 불러오는 동안은 다시 뼈대), 불러왔으면 입력 폼
    @Test fun entryShowsSkeletonWhileLoadingAndStateScreenAfterFailure() {
        assertEquals(ProfileEntry.Loading, ProfileFlowState(busy = true).entry())
        assertEquals(ProfileEntry.Failed, ProfileFlowState(error = AppError.Offline).entry())
        assertEquals(ProfileEntry.Failed, ProfileFlowState().entry())
        assertEquals(ProfileEntry.Loading, ProfileFlowState(busy = true, error = AppError.Offline).entry())
        assertEquals(ProfileEntry.Form, ProfileFlowState(loaded = true).entry())
        // 저장하다 실패해도 입력 폼은 그대로다(버튼 위 한 줄로만 알린다)
        assertEquals(ProfileEntry.Form, ProfileFlowState(loaded = true, error = AppError.Offline).entry())
    }

    // 저장 완료 · 만 19세 미만 탈퇴 안내는 불러오기 상태보다 먼저다
    @Test fun entryPrefersSavedAndUnderAgeNotice() {
        assertEquals(ProfileEntry.Saved, ProfileFlowState(loaded = true, completed = true).entry())
        assertEquals(ProfileEntry.Saved, ProfileFlowState(loaded = true, completed = true, error = AppError.Offline).entry())
        assertEquals(ProfileEntry.UnderAge, ProfileFlowState(underAgeAccountClosed = true).entry())
        assertEquals(ProfileEntry.UnderAge, ProfileFlowState(underAgeAccountClosed = true, busy = true).entry())
    }
}
