package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.ui.*

// 예시 상태만 전달한다. 실제 동기화/가입 완료/홈 이동을 실행하지 않는다.

@Preview(name = "01 연결 대기", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncDisconnectedPreview() = SyncPreview(FirstSyncState(FirstSyncStep.Disconnected))

@Preview(name = "02 연결 중", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncConnectingPreview() = SyncPreview(FirstSyncState(FirstSyncStep.Connecting))

@Preview(name = "03 설정 준비", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncPreparingPreview() = SyncPreview(FirstSyncState(FirstSyncStep.Preparing))

@Preview(name = "04 설정 전달", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncSendingPreview() = SyncPreview(FirstSyncState(FirstSyncStep.Sending))

@Preview(name = "05 기기 적용 확인", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncAwaitingApplyPreview() = SyncPreview(FirstSyncState(FirstSyncStep.AwaitingApply))

@Preview(name = "06 가입 확인", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncCheckingAccountPreview() = SyncPreview(FirstSyncState(FirstSyncStep.CheckingAccount))

@Preview(name = "07 연결 끊김", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncInterruptedPreview() = SyncPreview(FirstSyncState(FirstSyncStep.Interrupted))

@Preview(name = "08 동기화 실패", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncFailedPreview() = SyncPreview(FirstSyncState(FirstSyncStep.Failed))

@Preview(name = "09 결과 불명", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncResultUnknownPreview() = SyncPreview(FirstSyncState(FirstSyncStep.ResultUnknown))

@Preview(name = "10 가입 조회 실패", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncAccountCheckFailedPreview() = SyncPreview(FirstSyncState(FirstSyncStep.AccountCheckFailed))

@Preview(name = "11 설정 변경", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncSettingsChangedPreview() = SyncPreview(FirstSyncState(FirstSyncStep.SettingsChanged))

@Preview(name = "12 완료 매칭 ON", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncCompletePreview() = SyncPreview(FirstSyncState(FirstSyncStep.Complete, matchingEnabled = true))

@Preview(name = "13 완료 매칭 OFF", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncMatchingOffPreview() = SyncPreview(FirstSyncState(FirstSyncStep.Complete, matchingEnabled = false))

@Preview(name = "14 완료 매칭 상태 미확인", group = "첫 동기화", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun FirstSyncMatchingUnknownPreview() = SyncPreview(FirstSyncState(FirstSyncStep.Complete))

@Preview(name = "15 작은 화면·큰 글자", group = "첫 동기화 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun FirstSyncCompactPreview() = SyncPreview(FirstSyncState(FirstSyncStep.AwaitingApply))

@Preview(name = "16 넓은 화면", group = "첫 동기화 크기", widthDp = 900, heightDp = 600, locale = "ko")
@Composable fun FirstSyncWidePreview() = SyncPreview(FirstSyncState(FirstSyncStep.Complete, matchingEnabled = true))

@Composable
private fun SyncPreview(state: FirstSyncState) {
    AppTheme { FirstSyncScreen(state, onAction = {}) }
}
