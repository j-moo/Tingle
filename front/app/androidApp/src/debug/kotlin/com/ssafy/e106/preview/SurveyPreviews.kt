package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.survey.ui.*

// Exact representative wording from SIS-Q-1.1-100 / SIS-WORDING-20261020-1.
// Debug-only display fixtures. No Repository, API calls, persistence or navigation.
private val firstQuestion = SurveyQuestionState(
    questionId = "Q001", ordinal = 1, total = 100,
    text = "나는 모임에서 분위기를 띄우는 사람이다.",
    options = listOf(
        SurveyOption("1", "전혀 그렇지 않다"),
        SurveyOption("2", "그렇지 않다"),
        SurveyOption("3", "보통이다"),
        SurveyOption("4", "그렇다"),
        SurveyOption("5", "매우 그렇다"),
    ), maxChoices = 1, allowUnknown = true, allowAny = false,
)

private val personalityQuestion = SurveyQuestionState(
    questionId = "Q016", ordinal = 16, total = 100,
    text = "나는 모임에서 눈에 띄지 않는 편이다.",
    options = listOf(
        SurveyOption("1", "전혀 그렇지 않다"),
        SurveyOption("2", "그렇지 않다"),
        SurveyOption("3", "보통이다"),
        SurveyOption("4", "그렇다"),
        SurveyOption("5", "매우 그렇다"),
    ), maxChoices = 1, allowUnknown = true, allowAny = false,
)

private val preferenceQuestion = SurveyQuestionState(
    questionId = "Q061", ordinal = 61, total = 100,
    text = "나는 상대가 사람들과 어울리고 대화하는 정도가 어느 쪽이면 좋나요?",
    options = listOf(
        SurveyOption("low", "사람들과 적게 어울리고 대화가 적은 편"),
        SurveyOption("mid", "보통 정도"),
        SurveyOption("high", "사람들과 자주 어울리고 대화가 많은 편"),
    ), maxChoices = 1, allowUnknown = true, allowAny = true,
)

private val multipleQuestion = SurveyQuestionState(
    questionId = "Q096", ordinal = 96, total = 100,
    text = "나는 상대를 만날 때 어떤 영역을 특히 중요하게 보나요?",
    options = listOf(
        SurveyOption("personality", "기본 성격"),
        SurveyOption("approach", "관계 시작·표현"),
        SurveyOption("connection", "연락·함께하는 시간"),
        SurveyOption("experience", "데이트·경험"),
        SurveyOption("conflict", "갈등·도움"),
        SurveyOption("boundaries", "친구 관계·공개 범위"),
        SurveyOption("money", "비용·선물"),
        SurveyOption("lifestyle", "생활 습관·시간"),
        SurveyOption("future", "장기적인 관계 방향"),
    ), maxChoices = 3, allowUnknown = true, allowAny = true,
)

private val selectedQuestion = personalityQuestion.copy(answer = SurveyAnswer.Choices(setOf("4")), saveState = SurveySaveState.Saved)

@Preview(name = "01 선택 전", group = "성향테스트 문항", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyEmptyPreview() = QuestionPreview(personalityQuestion)

@Preview(name = "02 선택 완료", group = "성향테스트 문항", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveySelectedPreview() = QuestionPreview(selectedQuestion)

@Preview(name = "03 저장 중", group = "성향테스트 문항", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveySavingPreview() = QuestionPreview(selectedQuestion.copy(saveState = SurveySaveState.Saving))

@Preview(name = "04 저장 실패", group = "성향테스트 문항", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveySaveFailedPreview() = QuestionPreview(selectedQuestion.copy(saveState = SurveySaveState.Failed))

@Preview(name = "05 상대 선호·상관없음", group = "성향테스트 문항", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyAnyPreview() = QuestionPreview(preferenceQuestion.copy(answer = SurveyAnswer.Any, saveState = SurveySaveState.Saved))

@Preview(name = "06 복수 선택·상한 도달", group = "성향테스트 문항", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyMultiplePreview() = QuestionPreview(multipleQuestion.copy(answer = SurveyAnswer.Choices(setOf("personality", "connection", "future")), saveState = SurveySaveState.Saved))

@Preview(name = "07 재검사·1번 미선택", group = "성향테스트 문항", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyRetestPreview() = QuestionPreview(firstQuestion)

@Preview(name = "08 이어하기", group = "성향테스트 흐름", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyResumePreview() = FlowPreview(SurveyFlowState(SurveyFlowStage.Resume))

@Preview(name = "09 전부 저장·미제출", group = "성향테스트 흐름", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyResumeAllPreview() = FlowPreview(SurveyFlowState(SurveyFlowStage.Resume, savedCount = 100))

@Preview(name = "10 제출 전", group = "성향테스트 흐름", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveySubmitPreview() = FlowPreview(SurveyFlowState(SurveyFlowStage.ReadyToSubmit))

@Preview(name = "11 제출 중", group = "성향테스트 흐름", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveySubmittingPreview() = FlowPreview(SurveyFlowState(SurveyFlowStage.Submitting))

@Preview(name = "12 제출 실패", group = "성향테스트 흐름", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveySubmitFailedPreview() = FlowPreview(SurveyFlowState(SurveyFlowStage.SubmitFailed))

@Preview(name = "13 계산 중", group = "성향테스트 흐름", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyCalculatingPreview() = FlowPreview(SurveyFlowState(SurveyFlowStage.Calculating))

@Preview(name = "14 재검사 계산 실패", group = "성향테스트 흐름", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyCalculationFailedPreview() = FlowPreview(SurveyFlowState(SurveyFlowStage.CalculationFailed, hasPreviousResult = true))

@Preview(name = "15 완료", group = "성향테스트 흐름", widthDp = 390, heightDp = 844, locale = "ko")
@Composable
fun SurveyCompletePreview() = FlowPreview(SurveyFlowState(SurveyFlowStage.Complete))

@Preview(name = "16 좁은 화면·큰 글자", group = "성향테스트 크기", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable
fun SurveyCompactPreview() = QuestionPreview(selectedQuestion)

@Preview(name = "17 넓은 화면", group = "성향테스트 크기", widthDp = 900, heightDp = 600, locale = "ko")
@Composable
fun SurveyWidePreview() = QuestionPreview(selectedQuestion)

@Composable
private fun QuestionPreview(state: SurveyQuestionState) {
    AppTheme {
        SurveyQuestionScreen(state, onAnswerChange = {}, onPrevious = {}, onContinue = {}, onRetrySave = {})
    }
}

@Composable
private fun FlowPreview(state: SurveyFlowState) {
    AppTheme { SurveyFlowScreen(state, onPrimary = {}, onResumeLater = {}, onRecoveryAction = {}) }
}
