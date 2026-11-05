package com.ssafy.e106.features.profile

import com.ssafy.e106.features.profile.ui.*
import kotlin.test.*

class ProfileEditStateTest {
    private val original = ProfileEditDraft("가을", ProfileSmoking.NonSmoker, ProfileDrinking.Never)
    private val state = ProfileEditState(original, birthDateLabel = "2000-10-10", gender = ProfileGender.Female, hasPhoto = true)

    @Test fun editAndDiscardNeverMutateSavedProfileOrLockedFields() {
        val changed = state.edit(original.copy(nickname = "새 이름", smoking = ProfileSmoking.Electronic))
        assertTrue(changed.canSave)
        assertEquals(original, changed.saved)
        val discarded = changed.copy(confirmDiscard = true).discard()
        assertEquals(state, discarded)
        assertFalse(discarded.canSave)
    }

    @Test fun successNormalizesOnlyAfterSavingAndKeepsIdentityAndPhoto() {
        val changed = state.edit(original.copy(nickname = "  가을 AB  ",
            introduction = "안녕\r\n반가워요", introductionConsent = true))
        assertEquals("  가을 AB  ", changed.draft.nickname)
        val saved = changed.savedSuccessfully()
        assertEquals("가을 AB", saved.saved.nickname)
        assertEquals("안녕\n반가워요", saved.saved.introduction)
        assertEquals(saved.saved, saved.draft)
        assertFalse(saved.dirty)
        assertEquals(state.birthDateLabel, saved.birthDateLabel)
        assertEquals(state.gender, saved.gender)
        assertTrue(saved.hasPhoto)
    }

    @Test fun failureRetainsDraftAndRetryButSavingLocksEdits() {
        val changed = state.edit(original.copy(nickname = "다른 이름"))
        val busy = changed.copy(saving = true)
        assertFalse(busy.canSave)
        assertEquals(busy, busy.edit(original.copy(nickname = "무시할 입력")))
        val failed = busy.copy(saving = false, saveError = "저장 실패")
        assertEquals(changed.draft, failed.draft)
        assertEquals(original, failed.saved)
        assertTrue(failed.canSave)
    }

    @Test fun requiredAndOptionalValidationBlocksInvalidSaveWithoutTruncatingInput() {
        assertFalse(state.edit(original.copy(nickname = "금지😀")).canSave)
        assertFalse(state.edit(original.copy(smoking = null)).canSave)
        assertFalse(state.edit(original.copy(drinking = null)).canSave)
        assertFalse(state.edit(original.copy(religion = ProfileReligion.Buddhist)).canSave)
        val long = "👨‍👩‍👧‍👦".repeat(201)
        val invalid = state.edit(original.copy(introduction = long, introductionConsent = true))
        assertEquals(long, invalid.draft.introduction)
        assertFalse(invalid.canSave)
        assertTrue(invalid.edit(invalid.draft.copy(introduction = "👨‍👩‍👧‍👦".repeat(200))).canSave)
        assertFalse(state.edit(original.copy(introduction = "a\nb\nc\nd\ne", introductionConsent = true)).canSave)
    }
}
