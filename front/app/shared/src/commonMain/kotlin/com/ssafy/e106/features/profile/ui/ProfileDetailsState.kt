package com.ssafy.e106.features.profile.ui

import com.ssafy.e106.features.profile.normalizeProfileIntroduction
import com.ssafy.e106.features.profile.profileGraphemeCount

enum class ProfileReligion { None, Protestant, Catholic, Buddhist, Other, Private }
enum class ProfilePhotoStatus { Empty, Registered, Uploading, Replacing, UploadFailed, ReplaceFailed }

data class ProfileDetailsState(
    val religion: ProfileReligion? = null,
    val religionConsent: Boolean = false,
    val introduction: String = "",
    val introductionConsent: Boolean = false,
    val photoConsent: Boolean = false,
    val photoStatus: ProfilePhotoStatus = ProfilePhotoStatus.Empty,
    val introductionError: String? = null,
    val photoError: String? = null,
    val saving: Boolean = false,
    val interactionEnabled: Boolean = true,
) {
    // 표시 검사와 실제 PATCH가 같은 정규화 값을 사용한다.
    val normalizedIntroduction: String? = normalizeProfileIntroduction(introduction)
    val introductionLength: Int = profileGraphemeCount(normalizedIntroduction.orEmpty())
    val introductionLineBreaks: Int = normalizedIntroduction.orEmpty().count { it == '\n' }
    val introductionTooLong: Boolean get() = introductionLength > 200
    val introductionTooManyLines: Boolean get() = introductionLineBreaks > 3
    val hasPhoto get() = photoStatus in setOf(ProfilePhotoStatus.Registered, ProfilePhotoStatus.Replacing, ProfilePhotoStatus.ReplaceFailed)
    val photoBusy get() = photoStatus == ProfilePhotoStatus.Uploading || photoStatus == ProfilePhotoStatus.Replacing
    val editable get() = interactionEnabled && !saving && !photoBusy
    // 사진 실패는 미등록/기존 사진 유지 상태다. 선택 항목 실패만으로 다음 단계를 막지 않는다.
    val canContinue get() = editable && introductionError == null &&
        !introductionTooLong && !introductionTooManyLines &&
        (normalizedIntroduction == null || introductionConsent) &&
        (religion == null || religionConsent) &&
        (!hasPhoto || photoConsent)
}

