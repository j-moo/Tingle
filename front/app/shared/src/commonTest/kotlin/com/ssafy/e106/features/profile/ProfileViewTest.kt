package com.ssafy.e106.features.profile

import com.ssafy.e106.features.chat.ui.PublicChatPartner
import com.ssafy.e106.features.chat.ui.heroInfo
import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.features.profile.ui.*
import kotlin.test.*

/** 프로필 보기(내 프로필 · 상대 프로필)의 사진 위 칩과 생활 칩에 들어갈 값 */
class ProfileViewTest {
    private val today = Triple(2026, 11, 17)

    // 모르는 나이 · 성별은 그 칩만 숨긴다(null)
    @Test fun unknownAgeOrGenderHidesOnlyThatChip() {
        val mine = ProfileResponse(1, "하늘", "1999-05-01", "FEMALE")
        assertEquals(ProfileHeroInfo("하늘", 27, ProfileGender.Female), mine.heroInfo(today))
        assertEquals(ProfileHeroInfo("하늘", null, ProfileGender.Female), mine.copy(birthDate = "bad").heroInfo(today))
        assertEquals(ProfileHeroInfo("하늘", 27, null), mine.copy(gender = "OTHER").heroInfo(today))
        assertEquals(ProfileHeroInfo("하늘", null, null), mine.copy(birthDate = null, gender = null).heroInfo(today))
        val partner = PublicChatPartner("봄날", 26, "MALE", "NON_SMOKER", "NEVER")
        assertEquals(ProfileHeroInfo("봄날", 26, ProfileGender.Male), partner.heroInfo())
        assertEquals(ProfileHeroInfo("봄날", null, null), partner.copy(age = 0, gender = "UNKNOWN").heroInfo())
    }
}
