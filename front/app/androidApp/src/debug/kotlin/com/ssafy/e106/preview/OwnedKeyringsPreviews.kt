package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.data.OwnedKeyring
import com.ssafy.e106.features.keyring.ui.*

// 디자인 확인용 직접 입력이며 실제 계정의 서버/기기 정보로 사용하지 않는다.
@Preview(name = "등록 키링 서버 상태", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun OwnedKeyringsReadyPreview() = AppTheme {
    OwnedKeyringsScreen(OwnedKeyringsState(items = listOf(OwnedKeyring(1, "READY", "1.0.0"))), {}, {})
}

@Preview(name = "등록 없음", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun OwnedKeyringsEmptyPreview() = AppTheme {
    OwnedKeyringsScreen(OwnedKeyringsState(items = emptyList()), {}, {})
}

@Preview(name = "조회 실패 · 큰 글자", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun OwnedKeyringsFailedPreview() = AppTheme {
    OwnedKeyringsScreen(OwnedKeyringsState(error = AppError.Offline), {}, {})
}
