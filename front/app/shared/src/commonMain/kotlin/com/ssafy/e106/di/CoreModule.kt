package com.ssafy.e106.di

import com.ssafy.e106.core.network.createHttpClient
import org.koin.dsl.module

/**
 * 기반 부품 등록. 기능 모듈(profileModule 등)은 `get()` 으로 여기 것을 받는다. `startKoin` 은 App.kt 가 부른다.
 * 주소·로그는 앱이 넘긴다. Android 는 `startKoin { modules(coreModule(BuildConfig.API_BASE_URL, BuildConfig.HTTP_LOG)) }`.
 * §2~§5 구현이 올라올 때마다 한 줄씩 더한다: single<SessionStore> { … } 등.
 */
fun coreModule(baseUrl: String, httpLog: Boolean) = module {
    single { createHttpClient(baseUrl, httpLog) }
}
