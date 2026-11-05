package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.profile.ui.*

private val confirmation = ProfileConfirmationState(
    nickname = "초록 산책", birthDateText = "2000-10-10", gender = ProfileGender.Male,
    smoking = ProfileSmoking.NonSmoker, drinking = ProfileDrinking.MonthlyTwoToFour,
    religion = ProfileReligion.None,
    introduction = "산책과 작은 카페를 좋아해요. ☕\n서로의 일상을 편하게 나누고 싶어요.",
    hasPhoto = true, canRegister = true,
)

@Preview(name = "01 등록 전 확인", group = "프로필 최종 확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ConfirmationReadyPreview() = ConfirmationPreview(confirmation)

@Preview(name = "02 선택 항목 없음", group = "프로필 최종 확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ConfirmationOptionalEmptyPreview() = ConfirmationPreview(
    confirmation.copy(religion = null, introduction = "", hasPhoto = false))

@Preview(name = "03 등록 중", group = "프로필 최종 확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ConfirmationRegisteringPreview() = ConfirmationPreview(confirmation.copy(registering = true))

@Preview(name = "04 등록 실패", group = "프로필 최종 확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ConfirmationFailedPreview() = ConfirmationPreview(confirmation.copy(
    registrationError = "프로필을 등록하지 못했어요. 입력 내용은 그대로예요. 다시 시도해 주세요."))

@Preview(name = "05 수정 필요", group = "프로필 최종 확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ConfirmationCorrectionPreview() = ConfirmationPreview(confirmation.copy(
    canRegister = false, registrationError = "이미 사용 중인 닉네임이에요. 기본 정보에서 수정해 주세요."))

@Preview(name = "06 작은 화면 · 큰 글자", group = "프로필 최종 확인 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun ConfirmationCompactPreview() = ConfirmationPreview(confirmation)

@Preview(name = "07 넓은 화면", group = "프로필 최종 확인 크기", widthDp = 900, heightDp = 600, locale = "ko")
@Composable fun ConfirmationWidePreview() = ConfirmationPreview(confirmation)

// 예시 데이터로 화면만 확인한다. 수정·등록·재시도·다음 단계 이동은 실행하지 않는다.
@Composable private fun ConfirmationPreview(state: ProfileConfirmationState) {
    AppTheme {
        ProfileConfirmationScreen(state, onEditBasics = {}, onEditLifestyle = {},
            onEditDetails = {}, onRegister = {}, photoContent = { PreviewPortrait() })
    }
}
