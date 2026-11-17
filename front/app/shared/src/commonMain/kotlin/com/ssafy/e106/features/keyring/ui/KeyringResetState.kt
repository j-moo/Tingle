package com.ssafy.e106.features.keyring.ui

// 화면 상태는 BE/BLE 코드와 분리한다. 실제 실행의 증명·소유 해제 결과를 ViewModel에서 매핑한다.
enum class KeyringResetStage { Loading, Ready, Confirm, Disconnected, Preparing, Resetting, Unlinking, Partial, Recovering, Waiting, Unknown, Failed, Complete, Unavailable }
enum class KeyringResetAction { Back, Cancel, StartReset, UploadRecords, CheckConnection, CheckResult, Retry, Refresh, Close }
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
    // Waiting: 기기 결과를 기다리는 실행의 키링 일련번호. 같은 실물에만 다시 연결해 결과를 읽는다
    val resumeSerial: String? = null,
    // Waiting 이 다시 설정될 때마다 올린다(같은 연결에서 키링이 아직 PENDING 이면 잠시 뒤 다시 조회하게)
    val waitTick: Int = 0,
    // 서버가 초기화 실행을 만들었다(실행 ID 있음). Unknown 안내를 실행 결과 미확인 / 시작 요청 미확인으로 나눈다
    val runCreated: Boolean = false,
) {
    val displayStage: KeyringResetStage get() = when {
        stage == KeyringResetStage.Complete && !(deviceResetConfirmed && ownershipReleased) -> KeyringResetStage.Unknown
        stage == KeyringResetStage.Partial && !deviceResetConfirmed -> KeyringResetStage.Unknown
        // 실패(Failed)는 연결 · 소유 확인과 무관하게 실패와 "다시 시도"로 보인다(다시 시도가 저장된 요청부터 다시 판정한다)
        stage in listOf(KeyringResetStage.Ready, KeyringResetStage.Confirm) &&
            (!ownershipConfirmed || deviceLabel.isNullOrBlank()) -> KeyringResetStage.Unavailable
        stage in listOf(KeyringResetStage.Ready, KeyringResetStage.Confirm) && !connected -> KeyringResetStage.Disconnected
        else -> stage
    }
    val busy: Boolean get() = displayStage in listOf(KeyringResetStage.Loading, KeyringResetStage.Preparing,
        KeyringResetStage.Resetting, KeyringResetStage.Unlinking, KeyringResetStage.Recovering)
    // 미확인도 별도 삭제 동의로만 해결한다. 선업로드 선택이나 잘못된 음수는 삭제 허가가 아니다.
    val recordsResolved: Boolean get() = pendingRecordCount == 0 ||
        ((pendingRecordCount == null || pendingRecordCount > 0) &&
            recordChoice == ResetRecordChoice.Delete && recordDeletionAccepted)
    val canConfirm: Boolean get() = displayStage == KeyringResetStage.Confirm && accepted && recordsResolved
    val canQueryRecords: Boolean get() = displayStage == KeyringResetStage.Confirm && recordQueryAvailable
    val canUploadRecords: Boolean get() = displayStage == KeyringResetStage.Confirm && recordUploadAvailable &&
        pendingRecordCount != null && pendingRecordCount > 0 && recordChoice == ResetRecordChoice.UploadFirst
}
