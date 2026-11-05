package com.ssafy.e106.features.keyring.ui

// 화면 상태는 BE/BLE 코드와 분리한다. 실제 실행의 증명·소유 해제 결과를 ViewModel에서 매핑한다.
enum class KeyringResetStage { Loading, Ready, Confirm, Disconnected, Preparing, Resetting, Unlinking, Partial, Recovering, Unknown, Failed, Complete, Unavailable }
enum class KeyringResetAction { Back, OpenConfirm, Cancel, StartReset, UploadRecords, CheckConnection, CheckResult, Retry, Refresh, Close }
enum class ResetRecordChoice { UploadFirst, Delete }

/** 완료 여부는 동일한 초기화 작업의 기기 확인과 서버 연결 해제 확인을 모두 받은 경우에만 전달한다. */
data class KeyringResetState(
    val stage: KeyringResetStage = KeyringResetStage.Loading,
    val deviceLabel: String? = null,
    val ownershipConfirmed: Boolean = false,
    val connected: Boolean = false,
    val accepted: Boolean = false,
    val deviceResetConfirmed: Boolean = false,
    val ownershipReleased: Boolean = false,
    // null은 조회 전/실패다. 기록 없음(0)으로 해석해 삭제 동의 없이 진행하면 안 된다.
    val pendingRecordCount: Int? = null,
    val recordChoice: ResetRecordChoice? = null,
    val recordDeletionAccepted: Boolean = false,
    // 현재 펌웨어의 앱용 조회·선업로드는 미지원이다. 버전 이름만으로 켜지 않고 실제 연동 확인 후 전달한다.
    val recordQueryAvailable: Boolean = false,
    val recordUploadAvailable: Boolean = false,
    val errorMessage: String? = null,
) {
    val displayStage: KeyringResetStage get() = when {
        stage == KeyringResetStage.Complete && !(deviceResetConfirmed && ownershipReleased) -> KeyringResetStage.Unknown
        stage == KeyringResetStage.Partial && !deviceResetConfirmed -> KeyringResetStage.Unknown
        stage in listOf(KeyringResetStage.Ready, KeyringResetStage.Confirm, KeyringResetStage.Failed) &&
            (!ownershipConfirmed || deviceLabel.isNullOrBlank()) -> KeyringResetStage.Unavailable
        stage in listOf(KeyringResetStage.Ready, KeyringResetStage.Confirm, KeyringResetStage.Failed) && !connected -> KeyringResetStage.Disconnected
        else -> stage
    }
    val busy: Boolean get() = displayStage in listOf(KeyringResetStage.Loading, KeyringResetStage.Preparing,
        KeyringResetStage.Resetting, KeyringResetStage.Unlinking, KeyringResetStage.Recovering)
    // 먼저 보내기는 초기화 허가가 아니다. 전송 후 기록을 다시 조회한 상태에서 동의받는다.
    val recordsResolved: Boolean get() = pendingRecordCount == 0 ||
        (pendingRecordCount != null && pendingRecordCount > 0 &&
            recordChoice == ResetRecordChoice.Delete && recordDeletionAccepted)
    val canConfirm: Boolean get() = displayStage == KeyringResetStage.Confirm && accepted && recordsResolved
    val canQueryRecords: Boolean get() = displayStage == KeyringResetStage.Confirm && recordQueryAvailable
    val canUploadRecords: Boolean get() = displayStage == KeyringResetStage.Confirm && recordUploadAvailable &&
        pendingRecordCount != null && pendingRecordCount > 0 && recordChoice == ResetRecordChoice.UploadFirst
}
