package com.ssafy.e106.features.keyring.ui

enum class FirstSyncStep {
    Disconnected, Connecting, Preparing, Sending, AwaitingApply, CheckingAccount,
    Interrupted, Failed, ResultUnknown, AccountCheckFailed, SettingsChanged, Complete,
}

/** 첫 가입 전용 표시 모델. Complete는 최신 설정 적용과 가입 완료가 모두 확인된 경우에만 전달한다. */
data class FirstSyncState(
    val step: FirstSyncStep,
    // Complete에서 실제 저장된 값을 전달한다. 알 수 없는 값을 ON으로 추정하지 않는다.
    val matchingEnabled: Boolean? = null,
)

enum class FirstSyncAction { Connect, RetrySync, CheckDeviceStatus, CheckAccountStatus, OpenHome }
