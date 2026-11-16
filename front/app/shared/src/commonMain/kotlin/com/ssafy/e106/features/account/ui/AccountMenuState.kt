package com.ssafy.e106.features.account.ui

enum class AccountMenuLoad { Loading, Ready, Failed }

/** 가입 후 메뉴의 표시 데이터. 사진은 프로필 보기에서만 보여 준다. */
data class AccountMenuState(
    val load: AccountMenuLoad = AccountMenuLoad.Loading,
    val nickname: String? = null,
    val appVersion: String? = null,
)

/** 메뉴 선택만 전달한다. 로그아웃/탈퇴도 즉시 실행이 아닌 확인 흐름의 진입점이다. */
enum class AccountMenuAction {
    Refresh, Profile, Survey, Matching, Keyring, Permissions,
    Consents, Help, Logout, Withdrawal,
}
