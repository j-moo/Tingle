package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.profile.ui.ProfileBasicsState
import com.ssafy.e106.features.profile.ui.ProfileFlowScreen
import com.ssafy.e106.features.profile.ui.ProfileFlowState
import com.ssafy.e106.features.profile.ui.ProfileGender

/** 실제 화면의 오류 표시 연결만 확인한다. 로그인·가입 상태나 API는 생성하지 않는다. */
@Preview(name = "프로필 연결 · 서버 닉네임 중복", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun ProfileServerDuplicatePreview() = ProfileFlowPreview(
    AppError.Server(409, "NICKNAME_DUPLICATED", "이미 사용 중인 닉네임이에요."),
)

@Preview(name = "프로필 연결 · 서버 생년월일 오류", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun ProfileServerBirthDatePreview() = ProfileFlowPreview(
    AppError.Server(422, "INVALID_BIRTH_DATE", "생년월일을 다시 확인해 주세요."),
)

@Composable
private fun ProfileFlowPreview(error: AppError) {
    AppTheme {
        ProfileFlowScreen(
            state = ProfileFlowState(
                loaded = true,
                basics = ProfileBasicsState("초록 산책", "20001010", ProfileGender.Male),
                error = error,
            ),
            onLoad = {}, onRefresh = {}, onNickname = {}, onBirthDate = {}, onGender = {},
            onSaveBasics = {}, onSmoking = {}, onDrinking = {}, onSaveLifestyle = {},
            onPage = {}, onComplete = {}, onReturnToLogin = {},
        )
    }
}

@Preview(name = "프로필 · 이용 연령으로 계정 종료", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun ProfileUnderAgeClosedPreview() = ClosedProfilePreview(false)

@Preview(name = "프로필 · 로그인 상태 정리 중", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun ProfileUnderAgeLeavingPreview() = ClosedProfilePreview(true)

@Preview(name = "프로필 · 계정 종료 작은 화면 큰 글자", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable
fun ProfileUnderAgeCompactPreview() = ClosedProfilePreview(false)

@Composable
private fun ClosedProfilePreview(busy: Boolean) {
    AppTheme {
        ProfileFlowScreen(
            state = ProfileFlowState(underAgeAccountClosed = true, busy = busy),
            onLoad = {}, onRefresh = {}, onNickname = {}, onBirthDate = {}, onGender = {},
            onSaveBasics = {}, onSmoking = {}, onDrinking = {}, onSaveLifestyle = {},
            onPage = {}, onComplete = {}, onReturnToLogin = {},
        )
    }
}
