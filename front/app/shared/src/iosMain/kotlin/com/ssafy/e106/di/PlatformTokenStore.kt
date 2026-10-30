package com.ssafy.e106.di

import com.ssafy.e106.core.storage.TokenStore
import org.koin.core.scope.Scope

// iOS Keychain 저장소는 S15P31E106-197. 그 전에는 iOS 에서 세션을 쓰지 않는다
internal actual fun Scope.platformTokenStore(baseUrl: String): TokenStore = TODO("iOS TokenStore — S15P31E106-197")
