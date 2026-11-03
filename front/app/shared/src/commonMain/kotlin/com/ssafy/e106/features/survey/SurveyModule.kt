package com.ssafy.e106.features.survey

import com.ssafy.e106.features.survey.data.*
import com.ssafy.e106.features.survey.ui.*
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun surveyModule(development: Boolean = false) = module {
    single { SurveyAccess(development) }
    single<SurveyRepository> { HttpSurveyRepository(get()) }
    single<SurveyCursorStore> { PrefsSurveyCursorStore(get()) }
    viewModel { SurveyChangeViewModel(get(), get(), get(), get()) }
    viewModel { SurveyViewModel(get(), get(), get(), get()) }
}
