package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.profile.ui.ProfileDrinking
import com.ssafy.e106.features.profile.ui.ProfileLifestyleScreen
import com.ssafy.e106.features.profile.ui.ProfileLifestyleState
import com.ssafy.e106.features.profile.ui.ProfileSmoking

private val selectedLifestyle = ProfileLifestyleState(
    smoking = ProfileSmoking.NonSmoker,
    drinking = ProfileDrinking.MonthlyTwoToFour,
)

@Preview(name = "01 선택 전", group = "흡연·음주", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun LifestyleEmptyPreview() = LifestylePreview(ProfileLifestyleState())

@Preview(name = "02 선택 완료", group = "흡연·음주", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun LifestyleSelectedPreview() = LifestylePreview(selectedLifestyle)

@Preview(name = "03 필수 선택 오류", group = "흡연·음주", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun LifestyleErrorPreview() = LifestylePreview(ProfileLifestyleState(
    smokingError = "흡연 항목을 선택해 주세요.", drinkingError = "음주 항목을 선택해 주세요.",
))

@Preview(name = "04 저장 중", group = "흡연·음주", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun LifestyleSavingPreview() = LifestylePreview(selectedLifestyle.copy(saving = true))

@Preview(name = "05 좁은 화면", group = "흡연·음주 크기", widthDp = 320, heightDp = 640, locale = "ko")
@Composable
fun LifestyleCompactPreview() = LifestylePreview(selectedLifestyle)

@Preview(name = "06 넓은 화면", group = "흡연·음주 크기", widthDp = 900, heightDp = 600, locale = "ko")
@Composable
fun LifestyleWidePreview() = LifestylePreview(selectedLifestyle)

@Preview(name = "07 큰 글자", group = "흡연·음주 크기", widthDp = 390, heightDp = 844, fontScale = 1.5f, locale = "ko")
@Composable
fun LifestyleLargeTextPreview() = LifestylePreview(selectedLifestyle)

// 예시 상태만 전달한다. 선택·다음 버튼은 실제 저장이나 화면 이동을 실행하지 않는다.
@Composable
private fun LifestylePreview(state: ProfileLifestyleState) {
    AppTheme {
        ProfileLifestyleScreen(state, onSmokingChange = {}, onDrinkingChange = {}, onContinue = {})
    }
}
