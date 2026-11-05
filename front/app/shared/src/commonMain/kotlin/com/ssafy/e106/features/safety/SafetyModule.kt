package com.ssafy.e106.features.safety

import com.ssafy.e106.features.safety.data.*
import com.ssafy.e106.features.safety.ui.*
import com.ssafy.e106.features.account.ui.AiConsentViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val safetyModule = module {
    single<SafetyRepository> { HttpSafetyRepository(get()) }
    single<ChatExtrasRepository> { HttpChatExtrasRepository(get()) }
    viewModel { SafetyViewModel(get(), get(), get(), get()) }
    viewModel { ChatExtrasViewModel(get(), get(), get()) }
    viewModel { AiConsentViewModel(get(), get(), get()) }
}
