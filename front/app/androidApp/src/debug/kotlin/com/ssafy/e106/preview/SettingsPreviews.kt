package com.ssafy.e106.preview

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.settings.ui.*

// 화면에 직접 주입하는 예시. 서버·BLE·Repository·실제 소유권 변경을 호출하지 않는다.
private val matchingSample = MatchingSettingsState(load = SettingsLoad.Ready, matching = true,
    photoRequired = false, ageLabel = "만 24세 ~ 32세", sync = SettingsSync.Applied)
private val keyringSample = KeyringSettingsState(load = SettingsLoad.Ready, hasKeyring = true,
    connection = KeyringConnection.Connected, sync = SettingsSync.Applied, vibration = true, led = true,
    batteryPercent = 82, firmware = "1.0", lastSyncLabel = "11월 1일 10:30", pendingUploadCount = 2, waitingPeerCount = 1)

@Preview(name = "01 기본 설정", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingReadyPreview() = MatchingPreview(matchingSample)

@Preview(name = "02 매칭 OFF·기기 미반영", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingOffPreview() = MatchingPreview(matchingSample.copy(matching = false, sync = SettingsSync.Disconnected))

@Preview(name = "03 사진 조건 ON", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingPhotoPreview() = MatchingPreview(matchingSample.copy(photoRequired = true))

@Preview(name = "04 저장 중", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingSavingPreview() = MatchingPreview(matchingSample.copy(save = SettingsSave.Saving))

@Preview(name = "05 저장 실패", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingFailedPreview() = MatchingPreview(matchingSample.copy(save = SettingsSave.Failed))

@Preview(name = "06 저장 결과 불명", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingUnknownPreview() = MatchingPreview(matchingSample.copy(save = SettingsSave.Unknown))

@Preview(name = "07 저장 후 기기 적용 대기", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingPendingPreview() = MatchingPreview(matchingSample.copy(sync = SettingsSync.Pending))

@Preview(name = "08 키링 미등록", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingMissingPreview() = MatchingPreview(matchingSample.copy(sync = SettingsSync.NoKeyring))

@Preview(name = "09 일부 미확인", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingPartialPreview() = MatchingPreview(MatchingSettingsState(load = SettingsLoad.Ready))

@Preview(name = "10 조회 중", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingLoadingPreview() = MatchingPreview(matchingSample.copy(load = SettingsLoad.Loading))

@Preview(name = "11 조회 실패", group = "매칭 설정", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingLoadFailedPreview() = MatchingPreview(matchingSample.copy(load = SettingsLoad.Failed))

@Preview(name = "01 연결·적용 완료", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailReadyPreview() = KeyringDetailPreview(keyringSample)

@Preview(name = "02 미연결·오래된 적용 정보", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailDisconnectedPreview() = KeyringDetailPreview(keyringSample.copy(connection = KeyringConnection.Disconnected))

@Preview(name = "03 동기화 중", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailSyncingPreview() = KeyringDetailPreview(keyringSample.copy(sync = SettingsSync.Syncing))

@Preview(name = "04 동기화 실패", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailSyncFailedPreview() = KeyringDetailPreview(keyringSample.copy(sync = SettingsSync.Failed))

@Preview(name = "05 기기 재시작", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailRecheckPreview() = KeyringDetailPreview(keyringSample.copy(sync = SettingsSync.RecheckRequired))

@Preview(name = "06 초기화 후 키링 없음", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailMissingPreview() = KeyringDetailPreview(keyringSample.copy(hasKeyring = false))

@Preview(name = "07 등록 여부 미확인", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailUnknownPreview() = KeyringDetailPreview(keyringSample.copy(hasKeyring = null))

@Preview(name = "08 일부 기기 정보 미확인", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailPartialPreview() = KeyringDetailPreview(keyringSample.copy(batteryPercent = -1, firmware = null, lastSyncLabel = null, pendingUploadCount = -1, waitingPeerCount = null, vibration = null, led = null))

@Preview(name = "09 출력 설정 저장 중", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailSavingPreview() = KeyringDetailPreview(keyringSample.copy(save = SettingsSave.Saving))

@Preview(name = "10 출력 저장 결과 불명", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailSaveUnknownPreview() = KeyringDetailPreview(keyringSample.copy(save = SettingsSave.Unknown))

@Preview(name = "11 진동·LED OFF", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailOutputsOffPreview() = KeyringDetailPreview(keyringSample.copy(vibration = false, led = false))

@Preview(name = "12 조회 중", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailLoadingPreview() = KeyringDetailPreview(keyringSample.copy(load = SettingsLoad.Loading))

@Preview(name = "13 조회 실패", group = "키링 상세", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailLoadFailedPreview() = KeyringDetailPreview(keyringSample.copy(load = SettingsLoad.Failed))

@Preview(name = "직접 조작", group = "매칭 설정 조작", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun MatchingInteractivePreview() {
    var state by remember { mutableStateOf(matchingSample) }
    AppTheme { MatchingSettingsScreen(state,
        onMatchingChange = { state = state.copy(matching = it, save = SettingsSave.Changed) },
        onPhotoChange = { state = state.copy(photoRequired = it, save = SettingsSave.Changed) }, onAction = {}) }
}

@Preview(name = "직접 조작", group = "키링 상세 조작", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringDetailInteractivePreview() {
    var state by remember { mutableStateOf(keyringSample) }
    AppTheme { KeyringSettingsScreen(state,
        onVibrationChange = { state = state.copy(vibration = it, save = SettingsSave.Changed) },
        onLedChange = { state = state.copy(led = it, save = SettingsSave.Changed) }, onAction = {}) }
}

@Preview(name = "작은 화면·큰 글자", group = "매칭 설정 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun MatchingCompactPreview() = MatchingPreview(matchingSample)
@Preview(name = "작은 화면·큰 글자", group = "키링 상세 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun KeyringDetailCompactPreview() = KeyringDetailPreview(keyringSample)

@Composable private fun MatchingPreview(state: MatchingSettingsState) {
    AppTheme { MatchingSettingsScreen(state, onMatchingChange = {}, onPhotoChange = {}, onAction = {}) }
}
@Composable private fun KeyringDetailPreview(state: KeyringSettingsState) {
    AppTheme { KeyringSettingsScreen(state, onVibrationChange = {}, onLedChange = {}, onAction = {}) }
}
