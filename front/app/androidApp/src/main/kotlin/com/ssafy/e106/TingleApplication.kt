package com.ssafy.e106

import android.app.Application
import com.ssafy.e106.di.coreModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** Koin 시작. 토큰 저장소가 Context 를 써서 Activity 가 아닌 여기서 한다. 기능 모듈은 modules(…) 에 이어서 더한다 */
class TingleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TingleApplication)
            modules(coreModule(BuildConfig.API_BASE_URL, BuildConfig.HTTP_LOG))
        }
    }
}
