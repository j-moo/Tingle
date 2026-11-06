package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.ProfileStorageDiagnostics

/** 실제 연결/서버 호출 없이 기기 지원·저장 보고의 차이만 비교한다. 저장됨은 가입 완료가 아니다. */
@Preview(name = "01 지원 정보 없음", widthDp = 390, heightDp = 300, locale = "ko", showBackground = true)
@Composable fun ProfileStorageUnknownPreview() = ProfileDiagnosticsPreview(BleProfileStorageInfo())

@Preview(name = "02 지원 기기 저장 전", widthDp = 390, heightDp = 300, locale = "ko", showBackground = true)
@Composable fun ProfileStorageEmptyPreview() = ProfileDiagnosticsPreview(profileCapability())

@Preview(name = "03 저장 보고 서버 확인 별도", widthDp = 390, heightDp = 300, locale = "ko", showBackground = true)
@Preview(name = "04 작은 화면 큰 글자", widthDp = 320, heightDp = 500, fontScale = 1.5f, locale = "ko", showBackground = true)
@Composable fun ProfileStorageReportedPreview() = ProfileDiagnosticsPreview(profileCapability().copy(
    storedFormat = BleProfileStorageInfo.FORMAT, stored = true, storedBytes = 196))

private fun profileCapability() = BleProfileStorageInfo(listOf(BleProfileStorageInfo.FORMAT),
    BleProfileStorageInfo.CHARACTERISTIC, true, "", false, 0)

@Composable private fun ProfileDiagnosticsPreview(storage: BleProfileStorageInfo) {
    val info = BleDeviceInfo("preview-keyring", "preview", supportsStatus = true,
        supportedFunctions = listOf("GET_SYNC_CHALLENGE", "SYNC_SETTINGS"), profileStorage = storage)
    AppTheme {
        ProfileStorageDiagnostics(info)
    }
}
