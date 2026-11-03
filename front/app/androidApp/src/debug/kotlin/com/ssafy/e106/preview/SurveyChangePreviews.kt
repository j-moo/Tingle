package com.ssafy.e106.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.survey.ui.*
import com.ssafy.e106.features.survey.data.*

class SurveyChangePreviewStates : PreviewParameterProvider<SurveyChangeState> {
    override val values = SurveyChangePage.entries.filter { it != SurveyChangePage.Done }.map {
        SurveyChangeState(page = it, busy = it == SurveyChangePage.Loading,
            plan = if (it == SurveyChangePage.Migration) SurveyMigrationPlan(11, "v2", listOf("Q001"), listOf("Q002"), emptyList(), "a".repeat(64)) else null)
    }.asSequence()
}
@Preview(name = "설문 전환 실제 화면 상태", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun SurveyChangePreview(@PreviewParameter(SurveyChangePreviewStates::class) state: SurveyChangeState) {
    AppTheme { SurveyChangeScreen(state, {}, {}, {}) }
}
