package com.ssafy.e106.features.profile.ui

/** 화면이 그릴 값. 인증·서버 저장·검증은 연결할 호출부가 맡는다. */
data class ProfileBasicsState(
    val nickname: String = "",
    val birthDateDigits: String = "",
    val gender: ProfileGender? = null,
    val nicknameError: String? = null,
    val birthDateError: String? = null,
    val genderError: String? = null,
    val canContinue: Boolean = false,
    val saving: Boolean = false,
)

enum class ProfileGender { Male, Female }
