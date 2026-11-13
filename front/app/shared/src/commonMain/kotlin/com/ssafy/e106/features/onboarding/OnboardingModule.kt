package com.ssafy.e106.features.onboarding

import com.ssafy.e106.features.onboarding.data.*
import com.ssafy.e106.features.onboarding.ui.OnboardingViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** [siteBase]: 랜딩 정적 페이지 주소 앞부분(예: https://도메인/ · https://도메인/dev/). 서버가 전문 주소를 안 준 프로필 동의를 여기로 채운다 */
fun onboardingModule(allowUnpublishedAgreements: Boolean = false, siteBase: String? = null) = module {
    single<OnboardingRepository> { HttpOnboardingRepository(get()) }
    viewModel { OnboardingViewModel(get(), get(), get(), allowUnpublishedAgreements, siteBase) }
}
