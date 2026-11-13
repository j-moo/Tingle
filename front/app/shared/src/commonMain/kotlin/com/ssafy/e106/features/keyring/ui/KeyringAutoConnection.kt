package com.ssafy.e106.features.keyring.ui

import androidx.compose.runtime.Composable
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.features.keyring.data.OwnedKeyring

// Android는 앱 화면과 잠금 중 서비스가 같은 연결과 전송 조정자를 공유한다.
@Composable internal expect fun keyringBleModel(): BleProbeViewModel
@Composable internal expect fun keyringEncounterModel(): KeyringEncounterViewModel

@Composable internal expect fun KeyringAutoConnectionControl(
    session: Session.SignedIn, selected: OwnedKeyring?, link: BleProbeState,
)

@Composable internal expect fun KeyringAutoConnectionHost(suspended: Boolean)
internal expect fun retainKeyringAutoConnection(link: BleProbeState): Boolean
internal expect fun managesKeyringAutoConnection(serial: String): Boolean

// 키링에 새 기록 이벤트가 없으므로 활성 연결에서만 저빈도로 확인한다.
internal const val KEYRING_RECORD_CHECK_INTERVAL_MILLIS = 120_000L
