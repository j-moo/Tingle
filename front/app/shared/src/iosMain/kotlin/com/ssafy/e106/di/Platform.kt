package com.ssafy.e106.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ssafy.e106.core.auth.SocialKeys
import com.ssafy.e106.core.auth.SocialLogin
import com.ssafy.e106.core.push.PushDevice
import com.ssafy.e106.core.storage.TokenStore
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.scope.Scope

// iOS Keychain 저장소는 S15P31E106-197. 그 전에는 iOS 에서 세션을 쓰지 않는다
internal actual fun Scope.platformTokenStore(baseUrl: String): TokenStore = TODO("iOS TokenStore — S15P31E106-197")

internal actual fun Scope.platformSocialLogin(keys: SocialKeys): SocialLogin = TODO("iOS 소셜 로그인 — S15P31E106-197")

internal actual fun Scope.platformPushDevice(): PushDevice = TODO("iOS 푸시(APNs) — S15P31E106-197")

internal actual fun Scope.platformPrefsStore(fileName: String, resetOnCorruption: Boolean): DataStore<Preferences> = TODO("iOS AppPrefs 파일 — S15P31E106-197")

internal actual fun Scope.platformForeground(): StateFlow<Boolean> = TODO("iOS 앱 앞/뒤 상태 — S15P31E106-197")
