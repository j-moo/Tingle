package com.ssafy.e106.features.keyring.ui

enum class KeyringResetStage { Loading, Ready, Confirm, Disconnected, Resetting, Unlinking, Partial, Unknown, Failed, Complete, Unavailable }
enum class KeyringResetAction { Back, OpenConfirm, Cancel, StartReset, CheckConnection, CheckResult, Retry, Refresh, Close }

/** 완료 여부는 동일한 초기화 작업의 기기 확인과 서버 연결 해제 확인을 모두 받은 경우에만 전달한다. */
data class KeyringResetState(
    val stage: KeyringResetStage = KeyringResetStage.Loading,
    val deviceLabel: String? = null,
    val ownershipConfirmed: Boolean = false,
    val connected: Boolean = false,
    val accepted: Boolean = false,
    val deviceResetConfirmed: Boolean = false,
    val ownershipReleased: Boolean = false,
) {
    val displayStage: KeyringResetStage get() = when {
        stage == KeyringResetStage.Complete && !(deviceResetConfirmed && ownershipReleased) -> KeyringResetStage.Unknown
        stage in listOf(KeyringResetStage.Ready, KeyringResetStage.Confirm, KeyringResetStage.Failed) &&
            (!ownershipConfirmed || deviceLabel.isNullOrBlank()) -> KeyringResetStage.Unavailable
        stage in listOf(KeyringResetStage.Ready, KeyringResetStage.Confirm, KeyringResetStage.Failed) && !connected -> KeyringResetStage.Disconnected
        else -> stage
    }
    val busy: Boolean get() = displayStage in listOf(KeyringResetStage.Loading, KeyringResetStage.Resetting, KeyringResetStage.Unlinking)
    val canConfirm: Boolean get() = displayStage == KeyringResetStage.Confirm && accepted
}
