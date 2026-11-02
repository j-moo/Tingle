package com.ssafy.e106.features.auth

import com.ssafy.e106.features.auth.ui.SocialLoginViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val authUiModule = module {
    viewModel { SocialLoginViewModel(auth = get()) }
}
