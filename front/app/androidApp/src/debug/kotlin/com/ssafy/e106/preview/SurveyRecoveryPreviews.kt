package com.ssafy.e106.preview

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.survey.ui.*

// 예시 문항/계약 상태만 전달한다. API, ViewModel, 저장 또는 가짜 성공은 만들지 않는다.
private val missing = listOf(
    SurveyMissingQuestion("Q016", 16, "나는 모임에서 눈에 띄지 않는 편이다."),
    SurveyMissingQuestion("Q061", 61, "나는 상대가 사람들과 어울리고 대화하는 정도가 어느 쪽이면 좋나요?"),
)
private val plan = SurveyMigrationReview(1024, "SIS-Q-1.2-100", "a".repeat(64),
    listOf("Q001", "Q002"), listOf("Q007"), listOf("Q050"))
private fun state(stage: SurveyRecoveryStage) = SurveyRecoveryState(stage, 1024, missing, plan, "SIS-Q-1.2-100")

@Composable private fun RecoveryPreview(state: SurveyRecoveryState) {
    AppTheme { SurveyFlowScreen(SurveyFlowState(SurveyFlowStage.CalculationFailed, recovery = state), {}, {}, {}) }
}

@Preview(name = "설문 · 제출 전 미응답", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyAnswersIncompletePreview() = RecoveryPreview(state(SurveyRecoveryStage.AnswersIncomplete))
@Preview(name = "설문 · 분석 후 응답 부족", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyInsufficientPreview() = RecoveryPreview(state(SurveyRecoveryStage.Insufficient))
@Preview(name = "설문 · 부족 문항 미확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMissingUnknownPreview() = RecoveryPreview(state(SurveyRecoveryStage.Insufficient).copy(missingQuestions = emptyList()))
@Preview(name = "설문 · 재검사 확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyRetestConfirmationPreview() = RecoveryPreview(state(SurveyRecoveryStage.RetestConfirmation))
@Preview(name = "설문 · 분석 버전 미지원", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyAnalysisUnsupportedPreview() = RecoveryPreview(state(SurveyRecoveryStage.AnalysisVersionUnsupported))
@Preview(name = "설문 · 전환 조회 중", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMigrationLoadingPreview() = RecoveryPreview(state(SurveyRecoveryStage.MigrationLoading))
@Preview(name = "설문 · 전환 확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMigrationReviewPreview() = RecoveryPreview(state(SurveyRecoveryStage.MigrationReview))
@Preview(name = "설문 · 전환 처리 중", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMigratingPreview() = RecoveryPreview(state(SurveyRecoveryStage.Migrating))
@Preview(name = "설문 · 전환 기준 변경", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMigrationChangedPreview() = RecoveryPreview(state(SurveyRecoveryStage.MigrationPolicyChanged))
@Preview(name = "설문 · 전환 미지원", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMigrationUnsupportedPreview() = RecoveryPreview(state(SurveyRecoveryStage.MigrationUnsupported))
@Preview(name = "설문 · 전환 조회 실패", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMigrationFailedPreview() = RecoveryPreview(state(SurveyRecoveryStage.MigrationLoadFailed))
@Preview(name = "설문 · 전환 결과 불명", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMigrationUnknownPreview() = RecoveryPreview(state(SurveyRecoveryStage.MigrationOutcomeUnknown))
@Preview(name = "설문 · 다른 회차 전환 정보", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyMigrationInvalidPreview() = RecoveryPreview(state(SurveyRecoveryStage.MigrationReview).copy(sourceSessionId = 9999))
@Preview(name = "설문 · 작은 화면 큰 글자", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun SurveyRecoveryCompactPreview() = RecoveryPreview(state(SurveyRecoveryStage.Insufficient))

@Preview(name = "설문 · 재검사 안내 열고 닫기", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyRecoveryInteractivePreview() {
    var current by remember { mutableStateOf(state(SurveyRecoveryStage.Insufficient)) }
    AppTheme {
        SurveyRecoveryScreen(current, onAction = { action ->
            when (action) {
                SurveyRecoveryAction.ReviewRetest -> current = current.copy(stage = SurveyRecoveryStage.RetestConfirmation)
                SurveyRecoveryAction.Back -> current = current.copy(stage = SurveyRecoveryStage.Insufficient)
                else -> Unit // 실제 생성/이관 요청·성공 전환은 하지 않는다.
            }
        })
    }
}
