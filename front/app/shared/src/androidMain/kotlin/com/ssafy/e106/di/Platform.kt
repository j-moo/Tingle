package com.ssafy.e106.di

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.ssafy.e106.core.auth.AndroidSocialLogin
import com.ssafy.e106.core.auth.SocialKeys
import com.ssafy.e106.core.auth.SocialLogin
import com.ssafy.e106.core.push.AndroidPushDevice
import com.ssafy.e106.core.push.PushDevice
import com.ssafy.e106.core.realtime.appForeground
import com.ssafy.e106.core.storage.AndroidTokenStore
import com.ssafy.e106.core.storage.TokenStore
import org.koin.android.ext.koin.androidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import okio.Path.Companion.toPath
import org.koin.core.scope.Scope

internal actual fun Scope.platformTokenStore(baseUrl: String): TokenStore = AndroidTokenStore(androidContext(), baseUrl)

internal actual fun Scope.platformSocialLogin(keys: SocialKeys): SocialLogin = AndroidSocialLogin(androidContext() as Application, keys)

internal actual fun Scope.platformPushDevice(): PushDevice = AndroidPushDevice(androidContext())

internal actual fun Scope.platformPrefsStore(fileName: String, resetOnCorruption: Boolean): DataStore<Preferences> = androidPrefsStore(androidContext(), fileName, resetOnCorruption)

// 캐시 파일은 깨졌으면 비우고 시작한다(잃어도 된다). 복구용 기록 파일은 비우지 않고 깨진 채 둔다(읽기가 CorruptionException).
// 경로는 첫 읽기 때 계산되므로 Context 에서 파일을 지금 정해 둔다(그때는 Koin 밖일 수 있다)
internal fun androidPrefsStore(context: Context, fileName: String, resetOnCorruption: Boolean, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences> {
    val file = context.filesDir.resolve(fileName)
    return PreferenceDataStoreFactory.createWithPath(corruptionHandler = if (resetOnCorruption) ReplaceFileCorruptionHandler { emptyPreferences() } else null, scope = scope, produceFile = { file.absolutePath.toPath() })
}

internal actual fun Scope.platformForeground(): StateFlow<Boolean> = appForeground(androidContext() as Application)
