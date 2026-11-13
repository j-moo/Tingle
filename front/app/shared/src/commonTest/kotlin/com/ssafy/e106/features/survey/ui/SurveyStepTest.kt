package com.ssafy.e106.features.survey.ui

import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.components.StateKind
import com.ssafy.e106.features.survey.data.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SurveyStepTest {
    private val reference = AnalysisReference(7, 3, "1.0")
    private fun completed(status: AnalysisStatus?) = SurveyUiState(page = SurveyPage.Completed, analysisReference = reference,
        analysisResult = status?.let { AnalysisResult(3, 7, AnalysisManifest("1.1", "1.0"), it) })

    // 문항 밖 화면은 페이지 · 오류 · 분석 상태로 단계를 정한다. 오류가 가장 앞선다
    @Test fun stepFollowsPageErrorAndAnalysis() {
        assertEquals(SurveyStep.Error, SurveyUiState(page = SurveyPage.Review, error = AppError.Offline).step())
        assertEquals(SurveyStep.Error, SurveyUiState(page = SurveyPage.Question, recoveryRequired = true).step())
        assertEquals(SurveyStep.Intro, SurveyUiState(page = SurveyPage.Intro, recoveryRequired = true).step())
        assertEquals(SurveyStep.Done, SurveyUiState(page = SurveyPage.Review).step())
        assertEquals(SurveyStep.Submitted, SurveyUiState(page = SurveyPage.Completed).step())
        assertEquals(SurveyStep.Pending, completed(null).step())
        assertEquals(SurveyStep.Pending, completed(AnalysisStatus.PENDING).step())
        assertEquals(SurveyStep.Succeeded, completed(AnalysisStatus.SUCCEEDED).step())
        assertEquals(SurveyStep.Insufficient, completed(AnalysisStatus.INSUFFICIENT).step())
        assertEquals(SurveyStep.Failed, completed(AnalysisStatus.FAILED).step())
    }

    // 이어하기 · 문항 화면은 따로 그린다. 바쁜 중에 여기로 오면 불러오는 중으로만 보여 준다(엉뚱한 "제출을 마쳤어요" 금지)
    @Test fun busyResumeAndQuestionShowLoading() {
        assertEquals(SurveyStep.Loading, SurveyUiState(page = SurveyPage.Loading).step())
        assertEquals(SurveyStep.Loading, SurveyUiState(page = SurveyPage.Resume, busy = true).step())
        assertEquals(SurveyStep.Loading, SurveyUiState(page = SurveyPage.Question, busy = true).step())
    }

    // 불러오기 실패는 오류에 맞는 상태 화면, 오류 없이 답 저장 결과만 모르면 "결과 확인 필요"
    @Test fun errorStepPicksStateKindFromErrorOrUnconfirmed() {
        assertEquals(StateKind.Offline, SurveyUiState(page = SurveyPage.Review, error = AppError.Offline).stepKind())
        assertEquals(StateKind.Session, SurveyUiState(page = SurveyPage.Intro, error = AppError.SessionExpired).stepKind())
        assertEquals(StateKind.Unconfirmed, SurveyUiState(page = SurveyPage.Question, recoveryRequired = true).stepKind())
        assertEquals(StateKind.Offline, SurveyUiState(page = SurveyPage.Question, recoveryRequired = true, error = AppError.Offline).stepKind())
    }

    // 분석 실패 · 답 부족 · 완료만 상태 화면 모양이고, 안내 · 제출 · 분석 중 · 불러오는 중은 보통 단계다
    @Test fun onlyResultStepsUseStateKind() {
        assertEquals(StateKind.Server, completed(AnalysisStatus.FAILED).stepKind())
        assertEquals(StateKind.Unknown, completed(AnalysisStatus.INSUFFICIENT).stepKind())
        assertEquals(StateKind.Done, completed(AnalysisStatus.SUCCEEDED).stepKind())
        assertEquals(null, completed(AnalysisStatus.PENDING).stepKind())
        assertEquals(null, SurveyUiState(page = SurveyPage.Intro).stepKind())
        assertEquals(null, SurveyUiState(page = SurveyPage.Review).stepKind())
        assertEquals(null, SurveyUiState(page = SurveyPage.Loading).stepKind())
    }
}
