package com.ssafy.e106.features.account.ui

enum class AccountMenuLoad { Loading, Ready, Failed }
enum class AccountSurveyStatus { Unknown, NotStarted, InProgress, Completed }
enum class AccountKeyringStatus { Unknown, Registered, NotRegistered }

/** 가입 후 메뉴의 표시 데이터. 등록 여부는 연결/동기화 완료 여부를 의미하지 않는다. */
data class AccountMenuState(
    val load: AccountMenuLoad = AccountMenuLoad.Loading,
    val nickname: String? = null,
    val survey: AccountSurveyStatus = AccountSurveyStatus.Unknown,
    val keyring: AccountKeyringStatus = AccountKeyringStatus.Unknown,
    val refreshFailed: Boolean = false,
    val appVersion: String? = null,
)

/** 메뉴 선택만 전달한다. 로그아웃/탈퇴도 즉시 실행이 아닌 확인 흐름의 진입점이다. */
enum class AccountMenuAction {
    Refresh, Profile, Survey, Matching, Keyring, Permissions,
    Terms, Privacy, Consents, Correction, Support, Logout, Withdrawal,
    Home, Proposals, Chats,
}
