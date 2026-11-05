package com.ssafy.e106.preview

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.onboarding.data.AgreementItem
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.ui.*

private val optionalReady = OptionalProfileState(loaded = true, draftLoaded = true,
    profile = ProfileResponse(7),
    consents = OptionalConsentType.entries.map { OptionalConsentRecord(it, "v2", true, "v1") },
    documents = OptionalConsentType.entries.map { AgreementItem(it.name, "v2", it.name, false, "https://tingle.test/terms") },
    details = ProfileDetailsState(introductionConsent = true, religionConsent = true, photoConsent = true,
        introduction = "산책과 음악을 좋아해요.", religion = ProfileReligion.None))

@Composable private fun OptionalPreview(initial: OptionalProfileState) {
    var s by remember { mutableStateOf(initial) }
    AppTheme {
        OptionalProfileScreen(s,
            onDecision = { type, agreed -> s = s.copy(decision = ConsentDecision(type, agreed, "v2")) },
            onConfirm = {}, onDismiss = { s = s.copy(decision = null, deletePhotoPrompt = false) },
            onReload = {}, onRetryConsent = {}, onIntroduction = { s = s.copy(details = s.details.copy(introduction = it)) },
            onReligion = { s = s.copy(details = s.details.copy(religion = it)) },
            onChoosePhoto = {}, onDeletePhoto = {}, onConfirmDeletePhoto = {}, onSave = {}, onSkip = {}, onBack = {})
    }
}
@Preview(name = "선택 동의 · 실제 연결 화면", widthDp = 390, heightDp = 844)
@Composable fun OptionalConsentReadyPreview() = OptionalPreview(optionalReady)
@Preview(name = "선택 동의 · 미조회", widthDp = 390, heightDp = 844)
@Composable fun OptionalConsentUnavailablePreview() = OptionalPreview(OptionalProfileState(profile = ProfileResponse(7), error = AppError.Offline))
@Preview(name = "선택 동의 · 응답 유실", widthDp = 390, heightDp = 844)
@Composable fun OptionalConsentUnknownPreview() = OptionalPreview(optionalReady.copy(recoveryRequired = true, canRetryConsent = true, notice = OptionalProfileNotice.Unknown))
@Preview(name = "선택 동의 · 철회 확인", widthDp = 390, heightDp = 844)
@Composable fun OptionalConsentWithdrawPreview() = OptionalPreview(optionalReady.copy(decision = ConsentDecision(OptionalConsentType.PROFILE_INTRODUCTION, false, "v2")))
@Preview(name = "선택 동의 · 저장 중", widthDp = 390, heightDp = 844)
@Composable fun OptionalConsentSavingPreview() = OptionalPreview(optionalReady.copy(busy = true))
@Preview(name = "선택 동의 · 작은 화면 큰 글자", widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Composable fun OptionalConsentCompactPreview() = OptionalPreview(optionalReady)
