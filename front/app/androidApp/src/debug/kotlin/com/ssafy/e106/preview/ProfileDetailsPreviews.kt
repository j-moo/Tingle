package com.ssafy.e106.preview

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.profile.ui.*

private val filledDetails = ProfileDetailsState(
    religion = ProfileReligion.None, religionConsent = true,
    introduction = "산책과 작은 카페를 좋아해요. ☕\n서로의 일상을 편하게 나누고 싶어요.",
    introductionConsent = true, photoConsent = true, photoStatus = ProfilePhotoStatus.Registered,
)

@Preview(name = "01 입력 전", group = "선택 프로필", widthDp = 390, heightDp = 844, locale = "ko")
@Preview(name = "01 입력 전", group = "선택 프로필", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsEmptyPreview() = DetailsPreview(ProfileDetailsState())

@Preview(name = "02 입력 완료", group = "선택 프로필", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsFilledPreview() = DetailsPreview(filledDetails)

@Preview(name = "03 입력 오류", group = "선택 프로필", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsErrorPreview() = DetailsPreview(filledDetails.copy(
    introduction = "가".repeat(201), introductionError = "자기소개는 200자 이내로 입력해 주세요.",
    photoStatus = ProfilePhotoStatus.UploadFailed, photoError = "사진을 등록하지 못했어요. 파일을 다시 선택해 주세요.",
))

@Preview(name = "04 저장 중", group = "선택 프로필", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsSavingPreview() = DetailsPreview(filledDetails.copy(saving = true))

@Preview(name = "05 사진 교체 중", group = "선택 프로필", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsReplacingPreview() = DetailsPreview(filledDetails.copy(photoStatus = ProfilePhotoStatus.Replacing))

@Preview(name = "06 사진 교체 실패", group = "선택 프로필", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsReplaceFailedPreview() = DetailsPreview(filledDetails.copy(
    photoStatus = ProfilePhotoStatus.ReplaceFailed, photoError = "사진을 바꾸지 못했어요. 기존 사진은 유지돼요.",
))

@Preview(name = "07 종교 비공개", group = "선택 프로필", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsPrivatePreview() = DetailsPreview(ProfileDetailsState(religion = ProfileReligion.Private, religionConsent = true))

@Preview(name = "08 작은 화면 · 큰 글자", group = "선택 프로필 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun DetailsCompactPreview() = DetailsPreview(filledDetails)

@Preview(name = "09 넓은 화면", group = "선택 프로필 크기", widthDp = 900, heightDp = 600, locale = "ko")
@Composable fun DetailsWidePreview() = DetailsPreview(filledDetails)

@Preview(name = "글자 규칙 · 결합 문자 직접 입력", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsUnicodePreview() {
    var state by remember { mutableStateOf(ProfileDetailsState(
        introduction = "👨‍👩‍👧‍👦👍🏽🇰🇷e\u0301가", introductionConsent = true,
    )) }
    AppTheme {
        ProfileDetailsScreen(state, onReligionChange = {}, onReligionConsentChange = {},
            onIntroductionChange = { state = state.copy(introduction = it) },
            onIntroductionConsentChange = { state = state.copy(introductionConsent = it) },
            onPhotoConsentChange = {}, onChoosePhoto = {}, onDeletePhoto = {}, onContinue = {},
            photoContent = {})
    }
}

@Preview(name = "글자 규칙 · 200자", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsUnicodeLimitPreview() = DetailsPreview(ProfileDetailsState(
    introduction = "가".repeat(199) + "👨‍👩‍👧‍👦", introductionConsent = true,
))

@Preview(name = "글자 규칙 · 201자 오류", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsUnicodeOverPreview() = DetailsPreview(ProfileDetailsState(
    introduction = "가".repeat(199) + "👨‍👩‍👧‍👦👍🏽", introductionConsent = true,
))

@Preview(name = "글자 규칙 · 줄바꿈 오류", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun DetailsNewlineOverPreview() = DetailsPreview(ProfileDetailsState(
    introduction = "첫째\r\n둘째\r셋째\n넷째\r다섯째", introductionConsent = true,
))

// 예시 상태만 전달한다. 동의·선택·삭제·저장·이동 콜백은 실행하지 않는다.
@Composable private fun DetailsPreview(state: ProfileDetailsState) {
    AppTheme {
        ProfileDetailsScreen(state, onReligionChange = {}, onReligionConsentChange = {},
            onIntroductionChange = {}, onIntroductionConsentChange = {}, onPhotoConsentChange = {},
            onChoosePhoto = {}, onDeletePhoto = {}, onContinue = {}, photoContent = { PreviewPortrait() })
    }
}

// 실사용자 사진이나 업로드 결과가 아닌 debug 전용 자리 표시 일러스트다.
@Composable internal fun PreviewPortrait() {
    Layout(content = {}, modifier = Modifier.semantics { contentDescription = "미리보기용 프로필 일러스트" }.drawBehind {
            drawRect(AppColors.SelectionContainer)
            drawCircle(AppColors.OnSelection, radius = size.width * 0.17f, center = Offset(size.width * 0.5f, size.height * 0.35f))
            drawOval(AppColors.OnSelection, topLeft = Offset(size.width * 0.18f, size.height * 0.56f),
                size = Size(size.width * 0.64f, size.height * 0.65f))
        }) { _, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {}
    }
}

