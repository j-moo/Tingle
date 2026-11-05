package com.ssafy.e106.features.proposals.reason

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val matchReasonModule = module {
    single<MatchReasonRepository> { HttpMatchReasonRepository(get()) }
    viewModel { MatchReasonViewModel(get(), get()) }
}
