// 2026-11-05: BE·IoT 검토안 기반 디자인. 공개 API/서명/펌웨어가 확정되기 전 실행 경로에 연결하지 않는다.
package com.ssafy.e106.preview

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.ui.*

// 디자인용 예시이며 재인증·서버·BLE·운영 처리를 실행하지 않는다.
private val resetSample = KeyringResetState(stage = KeyringResetStage.Ready,
    deviceLabel = "Tingle 키링 · A1B2 (예시)", ownershipConfirmed = true, connected = true, pendingRecordCount = 0)

@Preview(name = "01 안내", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetReadyPreview() = KeyringResetPreview(resetSample)

@Preview(name = "02 확인 전", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetConfirmPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm))

@Preview(name = "03 확인 완료", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetAcceptedPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm, accepted = true))

@Preview(name = "04 미연결", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetDisconnectedPreview() = KeyringResetPreview(resetSample.copy(connected = false))

@Preview(name = "05 기기 초기화 중", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetResettingPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Resetting))

@Preview(name = "06 서버 해제 확인 중", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetUnlinkingPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Unlinking, deviceResetConfirmed = true))

@Preview(name = "07 기기만 확인", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetPartialPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Partial, deviceResetConfirmed = true))

@Preview(name = "08 결과 불명", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetUnknownPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Unknown))

@Preview(name = "09 확인된 실패", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetFailedPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Failed))

@Preview(name = "10 양쪽 완료", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetCompletePreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Complete, deviceResetConfirmed = true, ownershipReleased = true))

@Preview(name = "11 잘못된 완료 방어", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetIncompletePreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Complete, deviceResetConfirmed = true))

@Preview(name = "12 소유 미확인", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetUnavailablePreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm, ownershipConfirmed = false, accepted = true))

@Preview(name = "13 조회 중", group = "API 미확인 초안 · KeyringReset", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetLoadingPreview() = KeyringResetPreview(KeyringResetState())

// 미전송 기록은 API 조회 결과가 아니라 화면 검토용 값이다. 최종 버튼으로 결과를 생성하지 않는다.
@Preview(name = "14 기록 처리 선택", group = "초기화 검토안 · 분리 동의", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetRecordsPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm, pendingRecordCount = 3))

@Preview(name = "15 삭제 동의 전", group = "초기화 검토안 · 분리 동의", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetDeleteConsentPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm,
    pendingRecordCount = 3, recordChoice = ResetRecordChoice.Delete, accepted = true))

@Preview(name = "16 두 동의 완료", group = "초기화 검토안 · 분리 동의", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetBothConsentsPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm,
    pendingRecordCount = 3, recordChoice = ResetRecordChoice.Delete, accepted = true, recordDeletionAccepted = true))

@Preview(name = "17 기록 미확인", group = "초기화 검토안 · 분리 동의", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetRecordsUnknownPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm,
    pendingRecordCount = null, accepted = true))

@Preview(name = "18 서버 실행 준비", group = "초기화 검토안 · 복구", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetPreparingPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Preparing))

@Preview(name = "19 앱 재시작 후 확인", group = "초기화 검토안 · 복구", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetRecoveryPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Recovering,
    connected = false, deviceResetConfirmed = true))

// 현재 IoT 회신 기준 선업로드는 미지원이다. 예시 개수가 있어도 버튼을 활성화하지 않는다.
@Preview(name = "20 선업로드 연결 준비 중", group = "초기화 검토안 · 분리 동의", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun KeyringResetUploadPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm,
    pendingRecordCount = 3, recordChoice = ResetRecordChoice.UploadFirst))

@Preview(name = "작은 화면·큰 글자", group = "API 미확인 초안 · KeyringReset 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun KeyringResetCompactPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm,
    pendingRecordCount = 3, recordChoice = ResetRecordChoice.Delete))

@Composable
private fun KeyringResetPreview(initial: KeyringResetState) {
    // Preview에서만 안내/동의의 로컬 상태를 유지한다. 프로세스 복구나 실제 실행 저장을 구현한 것이 아니다.
    var state by remember(initial) { mutableStateOf(initial) }
    AppTheme {
        KeyringResetScreen(state, onAccepted = { state = state.copy(accepted = it) }, onAction = {
            // 안내·확인·취소만 로컬 조작한다. 최종 버튼에서 성공/접수/재인증 결과를 만들지 않는다.
            when (it) {
                KeyringResetAction.OpenConfirm -> state = state.copy(stage = KeyringResetStage.Confirm,
                    accepted = false, recordDeletionAccepted = false, recordChoice = null)
                KeyringResetAction.Cancel -> state = state.copy(stage = KeyringResetStage.Ready,
                    accepted = false, recordDeletionAccepted = false, recordChoice = null)
                KeyringResetAction.Back -> if (state.displayStage == KeyringResetStage.Confirm)
                    state = state.copy(stage = KeyringResetStage.Ready, accepted = false,
                        recordDeletionAccepted = false, recordChoice = null)
                else -> Unit
            }
        }, onRecordChoice = {
            // 삭제를 취소했다가 다시 고르면 예전 동의를 재사용하지 않는다.
            state = state.copy(recordChoice = it, recordDeletionAccepted = false)
        }, onRecordDeletionAccepted = { state = state.copy(recordDeletionAccepted = it) }, showPreviewNotice = true)
    }
}
