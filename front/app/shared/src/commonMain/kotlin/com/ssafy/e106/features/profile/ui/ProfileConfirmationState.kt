package com.ssafy.e106.features.profile.ui

/** 등록 직전 요약용 상태. 검증 결과와 날짜 표시는 호출부가 전달한다. */
data class ProfileConfirmationState(
    val nickname: String,
    val birthDateText: String,
    val gender: ProfileGender,
    val smoking: ProfileSmoking,
    val drinking: ProfileDrinking,
    val religion: ProfileReligion? = null,
    val introduction: String = "",
    val hasPhoto: Boolean = false,
    val canRegister: Boolean = false,
    val registering: Boolean = false,
    val registrationError: String? = null,
)
