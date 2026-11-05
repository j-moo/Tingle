package com.ssafy.e106.features.keyring.ui

/** 화면 표현용 상태. BLE/서버 상태를 추측하거나 등록을 실행하지 않는다. */
enum class KeyringRegistrationStep {
    Ready, PermissionRequired, PermissionDenied, BluetoothOff, Searching, NotFound,
    Found, ConfirmOwnership, Registering, Checking, Uncertain, Failed,
    OwnedByOther, Unusable, RegistrationLimit, Success,
}

/** 표시용 이름과 식별 문구. 실제 탐색 결과의 안정적인 ID를 호출부에서 전달한다. */
data class KeyringCandidate(val id: String, val name: String, val identifier: String)

data class KeyringRegistrationState(
    val step: KeyringRegistrationStep = KeyringRegistrationStep.Ready,
    val candidates: List<KeyringCandidate> = emptyList(),
    val selectedId: String? = null,
    val ownershipAccepted: Boolean = false,
) {
    val selected: KeyringCandidate? get() = candidates.find { it.id == selectedId }
    val busy: Boolean get() = step == KeyringRegistrationStep.Registering || step == KeyringRegistrationStep.Checking
    val primaryEnabled: Boolean get() = when (step) {
        KeyringRegistrationStep.Found -> selected != null
        KeyringRegistrationStep.ConfirmOwnership -> selected != null && ownershipAccepted
        else -> !busy
    }
}

/** 의도만 전달한다. 권한 요청·탐색·등록·재조회·이동은 이후 실제 연결부가 담당한다. */
enum class KeyringRegistrationAction {
    Search, RequestPermission, OpenSettings, CancelSearch, ReviewOwnership,
    Register, CheckRegistration, ChooseAgain, Purchase, ManageKeyring, ContactSupport, Continue,
}
