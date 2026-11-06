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

// 실제 API가 돌려주는 가입 단계별 안내와 다음 버튼을 비교한다. 예시 상태는 Preview에만 전달하며 계정이나 서버를 변경하지 않는다.
@Preview(name = "가입 단계 · 성향테스트", showBackground = true, widthDp = 390, heightDp = 844)
@Composable fun SignupSurveyStepPreview() {
    SignupStepPreview(OnboardingStatus.SURVEY_REQUIRED)
}

@Preview(name = "가입 단계 · 희망 나이", showBackground = true, widthDp = 390, heightDp = 844)
@Composable fun SignupPreferencesStepPreview() {
    SignupStepPreview(OnboardingStatus.PREFERENCES_REQUIRED)
}

@Preview(name = "가입 단계 · 동기화 대기", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "가입 단계 · 동기화 큰 글자", showBackground = true, widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Composable fun SignupSyncStepPreview() {
    SignupStepPreview(OnboardingStatus.SYNC_PENDING)
}

// 완료 상태를 임의로 만들지 않고 미완료 단계의 화면만 확인한다. 실제 이동 콜백은 가입 경로에서 연결한다.
@Composable private fun SignupStepPreview(status: OnboardingStatus) {
    AppTheme {
        OnboardingScreen(
            state = OnboardingState(session = Session.SignedIn(1, status, false)),
            onCheck = { _, _ -> }, onSubmit = {}, onReload = {}, onRefresh = {}, onLogout = {},
            canOpenSurvey = status == OnboardingStatus.SURVEY_REQUIRED,
            canOpenSettings = status != OnboardingStatus.SURVEY_REQUIRED,
            onBleProbe = if (status == OnboardingStatus.SYNC_PENDING) ({}) else null,
        )
    }
}
