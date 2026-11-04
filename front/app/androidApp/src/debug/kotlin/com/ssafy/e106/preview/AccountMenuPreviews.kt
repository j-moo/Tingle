package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.account.ui.*

// 디자인용 예시 데이터. 로그인/가입 상태를 만들거나 실제 API·화면 이동을 실행하지 않는다.
private val accountSample = AccountMenuState(
    load = AccountMenuLoad.Ready, nickname = "봄날",
    survey = AccountSurveyStatus.Completed, keyring = AccountKeyringStatus.Registered,
    appVersion = "0.1.0 (개발)",
)

@Preview(name = "01 내 정보·설정 메뉴", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountReadyPreview() = AccountPreview(accountSample)

@Preview(name = "02 조회 중", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountLoadingPreview() = AccountPreview(accountSample.copy(load = AccountMenuLoad.Loading))

@Preview(name = "03 조회 실패", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountFailedPreview() = AccountPreview(accountSample.copy(load = AccountMenuLoad.Failed))

@Preview(name = "04 일부 미확인", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountUnknownPreview() = AccountPreview(AccountMenuState(load = AccountMenuLoad.Ready))

@Preview(name = "05 키링 초기화 후", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountNoKeyringPreview() = AccountPreview(accountSample.copy(keyring = AccountKeyringStatus.NotRegistered))

@Preview(name = "06 검사 작성 중", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountInProgressPreview() = AccountPreview(accountSample.copy(survey = AccountSurveyStatus.InProgress))

@Preview(name = "07 검사 시작 전", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountNotStartedPreview() = AccountPreview(accountSample.copy(survey = AccountSurveyStatus.NotStarted))

@Preview(name = "08 갱신 실패", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountRefreshFailedPreview() = AccountPreview(accountSample.copy(refreshFailed = true))

@Preview(name = "09 긴 닉네임", group = "내 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun AccountLongNamePreview() = AccountPreview(accountSample.copy(nickname = "Alexander Montgomery"))

@Preview(name = "10 작은 화면·큰 글자", group = "내 정보 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun AccountCompactPreview() = AccountPreview(accountSample)

@Preview(name = "11 넓은 화면", group = "내 정보 크기", widthDp = 900, heightDp = 600, locale = "ko")
@Composable fun AccountWidePreview() = AccountPreview(accountSample)

@Composable
private fun AccountPreview(state: AccountMenuState) {
    AppTheme { AccountMenuScreen(state, onAction = {}) }
}
