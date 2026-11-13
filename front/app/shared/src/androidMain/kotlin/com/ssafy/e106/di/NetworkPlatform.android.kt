package com.ssafy.e106.di

import com.ssafy.e106.core.network.AndroidNetworkMonitor
import com.ssafy.e106.core.network.NetworkMonitor
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope

/** 생성만으로 감시를 시작하지 않는다. 기능의 전경 구독이 OS 콜백 수명을 소유한다. */
internal actual fun Scope.platformNetworkMonitor(): NetworkMonitor = AndroidNetworkMonitor(androidContext())
