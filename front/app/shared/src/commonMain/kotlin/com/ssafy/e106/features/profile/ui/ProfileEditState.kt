package com.ssafy.e106.features.profile.ui

import com.ssafy.e106.features.profile.normalizeProfileNickname

/** 수정 가능한 값만 담는다. 생년월일·성별·별도 API인 사진은 수정 초안에 포함하지 않는다. */
data class ProfileEditDraft(
    val nickname: String,
    val smoking: ProfileSmoking?,
    val drinking: ProfileDrinking?,
    val religion: ProfileReligion? = null,
    val religionConsent: Boolean = false,
    val introduction: String = "",
    val introductionConsent: Boolean = false,
) {
    val details get() = ProfileDetailsState(religion = religion, religionConsent = religionConsent,
        introduction = introduction, introductionConsent = introductionConsent)
    val normalizedNickname get() = normalizeProfileNickname(nickname)
    val nicknameValid get() = validNickname(nickname)
    val valid get() = nicknameValid && smoking != null && drinking != null && details.canContinue
    fun normalized() = copy(nickname = normalizedNickname, introduction = details.normalizedIntroduction.orEmpty())
}

/** 호출자가 서버의 저장 결과와 편집 초안을 별도로 관리한다. 이 상태 자체는 통신하지 않는다. */
data class ProfileEditState(
    val saved: ProfileEditDraft,
    val draft: ProfileEditDraft = saved,
    val birthDateLabel: String,
    val gender: ProfileGender,
    val hasPhoto: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    val confirmDiscard: Boolean = false,
    val interactionEnabled: Boolean = true,
) {
    // 원문 입력까지 비교하여 입력 도중 뒤로가기로 의도치 않게 내용이 사라지는 것을 방지한다.
    val dirty get() = draft != saved
    val canSave get() = dirty && draft.valid && !saving && interactionEnabled
    fun edit(value: ProfileEditDraft) = if (saving || !interactionEnabled) this else copy(draft = value, saveError = null)
    fun discard() = copy(draft = saved, saveError = null, confirmDiscard = false)
    /** 실제 연결 시에는 요청 전이 아니라 서버 성공을 확인한 뒤 사용한다. */
    fun savedSuccessfully(): ProfileEditState {
        val normalized = draft.normalized()
        return copy(saved = normalized, draft = normalized, saving = false, saveError = null, confirmDiscard = false)
    }
}
