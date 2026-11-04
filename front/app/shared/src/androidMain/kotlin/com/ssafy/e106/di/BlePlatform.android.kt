package com.ssafy.e106.di

import com.ssafy.e106.core.ble.AndroidKeyringBle
import com.ssafy.e106.core.ble.KeyringBle
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope

/** Application Context만 보관해 Activity/화면을 연결 수명에 붙잡아 두지 않는다. */
internal actual fun Scope.platformKeyringBle(): KeyringBle = AndroidKeyringBle(androidContext())
