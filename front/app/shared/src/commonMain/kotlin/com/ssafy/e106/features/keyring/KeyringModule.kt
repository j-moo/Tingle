package com.ssafy.e106.features.keyring

import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.OwnedKeyringsViewModel
import com.ssafy.e106.features.keyring.ui.BleProbeViewModel
import com.ssafy.e106.features.keyring.ui.KeyringRegistrationViewModel
import com.ssafy.e106.features.keyring.ui.KeyringSyncViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** 서버 소유 조회·BLE 통신·등록 복구를 분리한다. 연결/등록 응답/소유 확인은 서로 다른 단계다. */
val keyringModule = module {
    single<OwnedKeyringRepository> { HttpOwnedKeyringRepository(get()) }
    // 복구 저장소는 순철님의 계정별 durable AppPrefs를 사용하며 별도 인증 기반을 만들지 않는다.
    single<KeyringRegistrationRepository> { HttpKeyringRegistrationRepository(get()) }
    single { KeyringRegistrationStore(get()) }
    single<KeyringSyncRepository> { HttpKeyringSyncRepository(get()) }
    single { KeyringSyncStore(get()) }
    // 초기화 복구도 계정별 내구 저장소를 사용한다. DI 등록만으로 서버/BLE 호출은 발생하지 않는다.
    single<KeyringResetRepository> { HttpKeyringResetRepository(get()) }
    single { KeyringResetStore(get()) }
    single { KeyringResetRecovery(get(), get(), get(), get(), get()) }
    viewModel { com.ssafy.e106.features.keyring.ui.KeyringResetViewModel(get(), get(), get(), get()) }
    single { com.ssafy.e106.features.keyring.data.KeyringSettingsChanges() }
    viewModel { KeyringSyncViewModel(get(), get(), get(), get(), changes = get()) }
    viewModel { KeyringRegistrationViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { OwnedKeyringsViewModel(get(), get()) }
    viewModel { BleProbeViewModel(get(), get()) }
}
