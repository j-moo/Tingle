package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.profile.ui.ProfileBasicsScreen
import com.ssafy.e106.features.profile.ui.ProfileBasicsState
import com.ssafy.e106.features.profile.ui.ProfileGender

// 디자인용 예시만 debug에 둔다. ViewModel·Repository·통신·저장·앱 진입 경로가 없다.
private val filled = ProfileBasicsState(
    nickname = "", birthDateDigits = "20001010", gender = ProfileGender.Female,
    canContinue = true,
)

@Preview(name = "01 입력 전", group = "프로필 기본 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun ProfileEmptyPreview() = ProfilePreview(ProfileBasicsState())

@Preview(name = "02 입력 완료", group = "프로필 기본 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun ProfileFilledPreview() = ProfilePreview(filled)

@Preview(name = "03 입력 오류", group = "프로필 기본 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun ProfileErrorPreview() = ProfilePreview(ProfileBasicsState(
    nickname = "봄", birthDateDigits = "20000230",
    nicknameError = "닉네임은 2자 이상 입력해 주세요.",
    birthDateError = "존재하는 생년월일을 입력해 주세요.",
    genderError = "성별을 선택해 주세요.",
))

@Preview(name = "04 저장 중", group = "프로필 기본 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun ProfileSavingPreview() = ProfilePreview(filled.copy(saving = true))

@Preview(name = "05 좁은 화면", group = "프로필 크기", widthDp = 320, heightDp = 640, locale = "ko")
@Composable
fun ProfileCompactPreview() = ProfilePreview(filled)

@Preview(name = "06 넓은 화면", group = "프로필 크기", widthDp = 900, heightDp = 600, locale = "ko")
@Composable
fun ProfileWidePreview() = ProfilePreview(filled)

@Preview(name = "07 큰 글자", group = "프로필 크기", widthDp = 390, heightDp = 844, fontScale = 1.5f, locale = "ko")
@Composable
fun ProfileLargeTextPreview() = ProfilePreview(filled)

@Composable
private fun ProfilePreview(state: ProfileBasicsState) {
    AppTheme {
        ProfileBasicsScreen(state, onNicknameChange = {}, onBirthDateChange = {}, onGenderChange = {}, onContinue = {})
    }
}
