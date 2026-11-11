package com.ssafy.e106.features.account.ui

import androidx.compose.ui.graphics.ImageBitmap

enum class AccountMenuLoad { Loading, Ready, Failed }

/** 가입 후 메뉴의 표시 데이터. 사진은 화면 표시용 메모리에만 둔다(받는 중 · 실패 · 없음이면 null → 기본 사람 모양). */
data class AccountMenuState(
    val load: AccountMenuLoad = AccountMenuLoad.Loading,
    val nickname: String? = null,
    val hasPhoto: Boolean = false,
    val photo: ImageBitmap? = null,
    val appVersion: String? = null,
)

/** 메뉴 선택만 전달한다. 로그아웃/탈퇴도 즉시 실행이 아닌 확인 흐름의 진입점이다. */
enum class AccountMenuAction {
    Refresh, Profile, Survey, Matching, Keyring, Permissions,
    Consents, Help, Logout, Withdrawal,
    Home, Proposals, Chats,
}
