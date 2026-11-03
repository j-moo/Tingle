package com.ssafy.e106.di

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.ssafy.e106.core.auth.AndroidSocialLogin
import com.ssafy.e106.core.auth.SocialKeys
import com.ssafy.e106.core.auth.SocialLogin
import com.ssafy.e106.core.storage.AndroidTokenStore
import com.ssafy.e106.core.storage.TokenStore
import org.koin.android.ext.koin.androidContext
import okio.Path.Companion.toPath
import org.koin.core.scope.Scope

internal actual fun Scope.platformTokenStore(baseUrl: String): TokenStore = AndroidTokenStore(androidContext(), baseUrl)

internal actual fun Scope.platformSocialLogin(keys: SocialKeys): SocialLogin = AndroidSocialLogin(androidContext() as Application, keys)

// 파일이 깨졌으면 비우고 시작한다(캐시라 잃어도 된다). 경로는 첫 읽기 때 계산되므로 Context 는 지금 받아 둔다(그때는 Koin 밖일 수 있다)
internal actual fun Scope.platformPrefsStore(): DataStore<Preferences> {
    val file = androidContext().filesDir.resolve("app_prefs.preferences_pb")
    return PreferenceDataStoreFactory.createWithPath(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, produceFile = { file.absolutePath.toPath() })
}
