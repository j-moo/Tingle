package com.ssafy.e106.features.auth

import com.ssafy.e106.di.CACHE_PREFS_FILE
import com.ssafy.e106.features.auth.ui.SocialLoginViewModel
import org.koin.core.qualifier.named
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val authUiModule = module {
    // 인증 화면과 서비스 화면에서 같은 앱 수명 조정자를 공유한다. 등록만으로 탈퇴 요청은 발생하지 않는다.
    single<com.ssafy.e106.features.account.data.WithdrawalRepository> {
        com.ssafy.e106.features.account.data.WithdrawalApi(get(), get(named(com.ssafy.e106.di.RECEIPT_HTTP_CLIENT)))
    }
    single { com.ssafy.e106.features.account.data.WithdrawalJournal(get()) }
    single { com.ssafy.e106.features.account.ui.WithdrawalCoordinator(get(), get(), get(), get(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)) }
    viewModel { SocialLoginViewModel(auth = get(), devicePrefs = get(named(CACHE_PREFS_FILE))) }
}
