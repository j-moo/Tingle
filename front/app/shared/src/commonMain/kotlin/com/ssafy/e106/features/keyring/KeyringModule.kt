package com.ssafy.e106.features.keyring

import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.OwnedKeyringsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** 실제 소유 목록 조회만 연결한다. BLE 기반은 담당자 인터페이스 준비 후 별도로 추가한다. */
val keyringModule = module {
    single<OwnedKeyringRepository> { HttpOwnedKeyringRepository(get()) }
    viewModel { OwnedKeyringsViewModel(get(), get()) }
}
