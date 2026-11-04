// 2026-11-01: 공개 처리 API 미확인. 정책 기반 보존 초안이며 API 기준 확정 설계/실행 경로가 아님.
package com.ssafy.e106.preview

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.ui.*

// 디자인용 예시이며 재인증·서버·BLE·운영 처리를 실행하지 않는다.
private val resetSample = KeyringResetState(stage = KeyringResetStage.Ready,
    deviceLabel = "Tingle 키링 · A1B2", ownershipConfirmed = true, connected = true)

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

@Preview(name = "작은 화면·큰 글자", group = "API 미확인 초안 · KeyringReset 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun KeyringResetCompactPreview() = KeyringResetPreview(resetSample.copy(stage = KeyringResetStage.Confirm))

@Composable
private fun KeyringResetPreview(initial: KeyringResetState) {
    var state by remember { mutableStateOf(initial) }
    AppTheme {
        KeyringResetScreen(state, onAccepted = { state = state.copy(accepted = it) }, onAction = {
            // 안내·확인·취소만 로컬 조작한다. 최종 버튼에서 성공/접수/재인증 결과를 만들지 않는다.
            when (it) {
                KeyringResetAction.OpenConfirm -> state = state.copy(stage = KeyringResetStage.Confirm, accepted = false)
                KeyringResetAction.Cancel -> state = state.copy(stage = KeyringResetStage.Ready, accepted = false)
                KeyringResetAction.Back -> if (state.displayStage == KeyringResetStage.Confirm)
                    state = state.copy(stage = KeyringResetStage.Ready, accepted = false)
                else -> Unit
            }
        })
    }
}
