package com.ssafy.e106.di

import android.app.Application
import com.ssafy.e106.core.auth.AndroidSocialLogin
import com.ssafy.e106.core.auth.SocialKeys
import com.ssafy.e106.core.auth.SocialLogin
import com.ssafy.e106.core.storage.AndroidTokenStore
import com.ssafy.e106.core.storage.TokenStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope

internal actual fun Scope.platformTokenStore(baseUrl: String): TokenStore = AndroidTokenStore(androidContext(), baseUrl)

internal actual fun Scope.platformSocialLogin(keys: SocialKeys): SocialLogin = AndroidSocialLogin(androidContext() as Application, keys)
