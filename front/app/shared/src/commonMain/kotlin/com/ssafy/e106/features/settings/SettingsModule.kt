package com.ssafy.e106.features.settings

import com.ssafy.e106.core.auth.*
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.MatchingSettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** 개발용 진입 버튼만 연다. 서버 가입 단계 제한을 우회하거나 READY를 만들지 않는다. */
class SettingsAccess(private val development: Boolean = false) {
    fun allowed(s: Session.SignedIn) = s.isReady || s.status in setOf(
        OnboardingStatus.PREFERENCES_REQUIRED, OnboardingStatus.SYNC_PENDING) ||
        (development && s.status == OnboardingStatus.KEYRING_REQUIRED)
}

fun settingsModule(development: Boolean = false) = module {
    single { SettingsAccess(development) }
    single<MatchingSettingsRepository> { HttpMatchingSettingsRepository(get()) }
    // 같은 계정의 서버 저장 성공을 전경 키링 조정자와 공유한다.
    viewModel { MatchingSettingsViewModel(get(), get(), get(), get()) }
}
