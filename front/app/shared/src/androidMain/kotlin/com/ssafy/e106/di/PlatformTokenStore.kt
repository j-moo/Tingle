package com.ssafy.e106.di

import com.ssafy.e106.core.storage.AndroidTokenStore
import com.ssafy.e106.core.storage.TokenStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope

internal actual fun Scope.platformTokenStore(baseUrl: String): TokenStore = AndroidTokenStore(androidContext(), baseUrl)
