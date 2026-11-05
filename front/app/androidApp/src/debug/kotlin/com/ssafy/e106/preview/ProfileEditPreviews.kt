package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.profile.ui.*

private val editSaved = ProfileEditDraft("봄날", ProfileSmoking.NonSmoker, ProfileDrinking.MonthlyTwoToFour)
private val editState = ProfileEditState(editSaved, birthDateLabel = "2000-10-10", gender = ProfileGender.Female)
private val changed = editState.edit(editSaved.copy(nickname = "봄날 산책"))

@Preview(name = "01 기존 프로필", group = "가입 후 프로필 수정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ProfileEditReadyPreview() = EditPreview(editState)
@Preview(name = "02 수정 중", group = "가입 후 프로필 수정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ProfileEditDirtyPreview() = EditPreview(changed)
@Preview(name = "03 잘못된 닉네임", group = "가입 후 프로필 수정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ProfileEditInvalidPreview() = EditPreview(editState.edit(editSaved.copy(nickname = "봄날!")))
@Preview(name = "04 저장 중", group = "가입 후 프로필 수정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ProfileEditSavingPreview() = EditPreview(changed.copy(saving = true))
@Preview(name = "05 저장 실패", group = "가입 후 프로필 수정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ProfileEditFailedPreview() = EditPreview(changed.copy(saveError = "저장하지 못했어요. 입력한 내용은 유지돼요. 다시 시도해 주세요."))
@Preview(name = "06 저장하지 않고 나가기", group = "가입 후 프로필 수정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun ProfileEditDiscardPreview() = EditPreview(changed.copy(confirmDiscard = true))
@Preview(name = "07 작은 화면·큰 글자", group = "가입 후 프로필 수정", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun ProfileEditCompactPreview() = EditPreview(changed)
@Preview(name = "08 넓은 화면", group = "가입 후 프로필 수정", widthDp = 900, heightDp = 600, locale = "ko")
@Composable fun ProfileEditWidePreview() = EditPreview(changed)

@Composable private fun EditPreview(state: ProfileEditState) {
    AppTheme { ProfileEditScreen(state, {}, {}, {}, {}, {}, {}, {}) }
}
