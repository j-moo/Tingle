package com.ssafy.e106.features.onboarding

import com.ssafy.e106.features.onboarding.data.*
import com.ssafy.e106.features.onboarding.ui.OnboardingViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun onboardingModule(allowUnpublishedAgreements: Boolean = false) = module {
    single<OnboardingRepository> { HttpOnboardingRepository(get()) }
    viewModel { OnboardingViewModel(get(), get(), get(), allowUnpublishedAgreements) }
}
