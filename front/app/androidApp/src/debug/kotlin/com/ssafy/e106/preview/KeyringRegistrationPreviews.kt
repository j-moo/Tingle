package com.ssafy.e106.preview

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.ui.*

// 디자인 예시. 실제 탐색·등록 결과가 아니며 API·ViewModel·Repository를 사용하지 않는다.
private val sampleKeyrings = listOf(
    KeyringCandidate("preview-a", "Tingle 키링", "예시 기기 · A102"),
    KeyringCandidate("preview-b", "Tingle 키링", "예시 기기 · B305"),
)
private fun sampleRegistration(step: KeyringRegistrationStep) = KeyringRegistrationState(
    step = step, candidates = sampleKeyrings,
    selectedId = if (step == KeyringRegistrationStep.Found) null else "preview-a",
)

@Preview(name = "01 준비", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringReadyPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Ready))

@Preview(name = "02 권한 안내", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringPermissionRequiredPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.PermissionRequired))

@Preview(name = "03 권한 거부", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringPermissionDeniedPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.PermissionDenied))

@Preview(name = "04 블루투스 꺼짐", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringBluetoothOffPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.BluetoothOff))

@Preview(name = "05 탐색 중", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringSearchingPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Searching))

@Preview(name = "06 미발견", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringNotFoundPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.NotFound))

@Preview(name = "07 발견 선택 전", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringFoundPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Found))

@Preview(name = "08 귀속 확인 전", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringConfirmOwnershipPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.ConfirmOwnership))

@Preview(name = "09 등록 중", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringRegisteringPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Registering))

@Preview(name = "10 등록 확인 중", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringCheckingPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Checking))

@Preview(name = "11 응답 유실", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringUncertainPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Uncertain))

@Preview(name = "12 등록 실패", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringFailedPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Failed))

@Preview(name = "13 타인 소유", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringOwnedByOtherPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.OwnedByOther))

@Preview(name = "14 사용 불가", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringUnusablePreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Unusable))

@Preview(name = "15 등록 한도", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringRegistrationLimitPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.RegistrationLimit))

@Preview(name = "16 등록 완료", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringSuccessPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Success))

@Preview(name = "17 선택 완료", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringSelectedPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Found).copy(selectedId = "preview-a"))

@Preview(name = "18 귀속 동의 완료", group = "키링 등록", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringAcceptedPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.ConfirmOwnership).copy(ownershipAccepted = true))

@Preview(name = "19 작은 화면·큰 글자", group = "키링 등록 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun KeyringCompactPreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.ConfirmOwnership))

@Preview(name = "20 넓은 화면", group = "키링 등록 크기", widthDp = 900, heightDp = 600, locale = "ko")
@Composable fun KeyringWidePreview() = RegistrationPreview(sampleRegistration(KeyringRegistrationStep.Found))

/** 선택과 귀속 안내만 조작한다. 등록/권한/구매/실제 화면 이동을 실행하지 않는다. */
@Preview(name = "21 선택·동의 직접 조작", group = "키링 등록 조작", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringInteractivePreview() {
    var state by remember { mutableStateOf(sampleRegistration(KeyringRegistrationStep.Found)) }
    AppTheme {
        KeyringRegistrationScreen(state,
            onSelectCandidate = { state = state.copy(selectedId = it, ownershipAccepted = false) },
            onAcceptOwnership = { state = state.copy(ownershipAccepted = it) },
            onAction = { action ->
                state = when (action) {
                    KeyringRegistrationAction.ReviewOwnership -> state.copy(step = KeyringRegistrationStep.ConfirmOwnership)
                    KeyringRegistrationAction.ChooseAgain -> state.copy(step = KeyringRegistrationStep.Found, ownershipAccepted = false)
                    else -> state
                }
            })
    }
}

@Composable
private fun RegistrationPreview(state: KeyringRegistrationState) {
    AppTheme { KeyringRegistrationScreen(state, onSelectCandidate = {}, onAcceptOwnership = {}, onAction = {}) }
}
