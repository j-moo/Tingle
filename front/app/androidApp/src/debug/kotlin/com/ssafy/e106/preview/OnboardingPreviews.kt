package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.onboarding.data.AgreementItem
import com.ssafy.e106.features.onboarding.ui.*

@Preview(name = "동의 · 실제 전문 준비 대기", showBackground = true, widthDp = 390, heightDp = 844)
@Composable fun AgreementPendingPreview() {
    AppTheme {
        OnboardingScreen(OnboardingState(session = Session.SignedIn(1, OnboardingStatus.AGREEMENT_REQUIRED, false),
            agreements = listOf(
                AgreementItem("TERMS", "v1", "서비스 이용약관", true),
                AgreementItem("PRIVACY", "v1", "개인정보 수집·이용 동의", true),
                AgreementItem("AI_RISK_DETECTION", "v1", "AI 대화 위험 감지를 위한 개인정보 처리 동의", true),
                AgreementItem("ADULT", "v1", "만 19세 이상입니다", true),
                AgreementItem("PROFILE_PHOTO", "v1", "프로필 사진 처리 동의", false),
                AgreementItem("PROFILE_INTRODUCTION", "v1", "자기소개 처리 동의", false),
                AgreementItem("PROFILE_RELIGION", "v1", "종교 정보 처리 동의", false))),
            { _, _ -> }, {}, {}, {}, {})
    }
}
@Preview(name = "키링 · 실제 기기 등록 준비 대기", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "키링 · 큰 글자", showBackground = true, widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Composable fun KeyringWaitingPreview() {
    AppTheme {
        OnboardingScreen(OnboardingState(session = Session.SignedIn(1, OnboardingStatus.KEYRING_REQUIRED, false)),
            { _, _ -> }, {}, {}, {}, {})
    }
}
