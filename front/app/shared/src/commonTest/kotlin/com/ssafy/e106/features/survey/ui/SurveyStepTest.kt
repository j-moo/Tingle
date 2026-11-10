package com.ssafy.e106.features.survey.ui

import com.ssafy.e106.core.network.AppError
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
}
