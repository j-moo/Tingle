package com.ssafy.e106.features.report

import com.ssafy.e106.features.report.data.*
import com.ssafy.e106.features.report.ui.ReportViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val reportModule = module {
    single<ReportRepository> { HttpReportRepository(get()) }
    viewModel { ReportViewModel(get(), get(), get(), get()) }
}
