package com.ssafy.e106

import android.app.Application
import com.ssafy.e106.core.auth.SocialKeys
import com.ssafy.e106.di.coreModule
import com.ssafy.e106.features.profile.profileModule
import com.ssafy.e106.features.auth.authUiModule
import com.ssafy.e106.features.onboarding.onboardingModule
import com.ssafy.e106.features.survey.surveyModule
import com.ssafy.e106.features.settings.settingsModule
import com.ssafy.e106.features.home.homeModule
import com.ssafy.e106.features.proposals.proposalModule
import com.ssafy.e106.features.proposals.reason.matchReasonModule
import com.ssafy.e106.features.chat.chatModule
import com.ssafy.e106.features.safety.safetyModule
import com.ssafy.e106.features.report.reportModule
import com.ssafy.e106.features.keyring.keyringModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** Koin 시작. 토큰 저장소가 Context 를 써서 Activity 가 아닌 여기서 한다. 기능 모듈은 modules(…) 에 이어서 더한다 */
class TingleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TingleApplication)
            val socialKeys = SocialKeys(BuildConfig.GOOGLE_WEB_CLIENT_ID, BuildConfig.KAKAO_NATIVE_APP_KEY, BuildConfig.NAVER_CLIENT_ID, BuildConfig.NAVER_REDIRECT_URI)
            // 운영 앱 · 개발 앱은 같은 화면 · 같은 가입 흐름을 쓴다(개발용 우회 · 진단 화면 없음). 서버 주소 · 키만 다르다.
            modules(coreModule(BuildConfig.API_BASE_URL, BuildConfig.HTTP_LOG, socialKeys), authUiModule,
                onboardingModule(), profileModule(), surveyModule(), settingsModule(),
                // 키링 소유 조회는 실제 서버 모듈을 사용하며 BLE 연결 성공을 대체하지 않는다.
                proposalModule(), matchReasonModule, homeModule, chatModule, safetyModule, reportModule, keyringModule)
        }
    }
}
