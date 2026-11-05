package com.ssafy.e106.features.profile

import com.ssafy.e106.features.profile.data.HttpProfileRepository
import com.ssafy.e106.features.profile.data.ProfileRepository
import com.ssafy.e106.features.profile.ui.ProfileViewModel
import com.ssafy.e106.features.profile.ui.OptionalProfileViewModel
import com.ssafy.e106.features.profile.ui.ProfileEditViewModel
import com.ssafy.e106.features.profile.data.OptionalProfileRepository
import com.ssafy.e106.features.profile.data.HttpOptionalProfileRepository
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun profileModule(allowKeyringSkip: Boolean = false, allowUnpublishedAgreements: Boolean = false) = module {
    single { DevelopmentProfileAccess(allowKeyringSkip) }
    single<ProfileRepository> { HttpProfileRepository(get()) }
    viewModel { ProfileViewModel(get(), get(), get(), get()) }
    single<OptionalProfileRepository> { HttpOptionalProfileRepository(get()) }
    // 선택 정보도 기본 프로필과 동일한 운영/개발 진입 정책을 사용한다.
    viewModel { OptionalProfileViewModel(get(), get(), get(), allowUnpublishedAgreements, get()) }
    viewModel { ProfileEditViewModel(get(), get(), get(), get()) }
}
