package com.ssafy.e106.features.profile.ui

// 화면의 선택값이다. 서버 요청 코드로 변환하는 책임은 API 계약 확정 후 연결한다.
enum class ProfileSmoking { NonSmoker, Electronic, OccasionallyWhenDrinking, Smoker }
enum class ProfileDrinking { Never, SpecialOccasions, MonthlyOrLess, MonthlyTwoToFour, WeeklyTwoToThree, WeeklyFourOrMore }

data class ProfileLifestyleState(
    val smoking: ProfileSmoking? = null,
    val drinking: ProfileDrinking? = null,
    val smokingError: String? = null,
    val drinkingError: String? = null,
    val saving: Boolean = false,
) {
    val canContinue: Boolean
        get() = smoking != null && drinking != null && smokingError == null && drinkingError == null && !saving
}
