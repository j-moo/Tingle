package com.ssafy.e106.features.home

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionEvent
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.features.account.ui.AccountMenuLoad
import com.ssafy.e106.features.home.ui.HomeSessionViewModel
import com.ssafy.e106.features.keyring.data.KeyringSettingsChanges
import com.ssafy.e106.features.keyring.data.OwnedKeyringRepository
import com.ssafy.e106.features.onboarding.data.OnboardingRepository
import com.ssafy.e106.features.profile.profileModule
import com.ssafy.e106.features.settings.data.MatchingSettingsRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class HomeAccountPhotoWiringDeviceTest {
    @Test
    fun accountMenuLoadsAndDecodesPhotoThroughAppModules() = runBlocking {
        // 실제 안드로이드 디코더까지 검사할 수 있도록 테스트용 이미지를 메모리에서 만든다.
        val png = ByteArrayOutputStream().use { stream ->
            val image = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            try {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            } finally {
                image.recycle()
            }
        }
        val client = createHttpClient("https://test/dev/api/", false, engine = MockEngine { request ->
            check(request.method == HttpMethod.Get)
            when (request.url.encodedPath) {
                "/dev/api/users/7/profile" -> respond(
                    """{"userId":7,"nickname":"하늘","hasProfilePhoto":true}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/dev/api/users/7/profile/photo" -> respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
        })
        val session = object : SessionStore {
            override val state = MutableStateFlow<Session>(Session.SignedIn(7, OnboardingStatus.READY, false))
            override val events = emptyFlow<SessionEvent>()
            override suspend fun refreshOnboarding() = error("Unexpected onboarding refresh")
        }
        // 앱과 같은 두 모듈을 조합하고 사진 조회에 관여하지 않는 의존성만 대체한다.
        val di = koinApplication {
            modules(profileModule(), homeModule, module {
                single { client }
                single<SessionStore> { session }
                single { KeyringSettingsChanges() }
                single<AuthRepository> { unused() }
                single<MatchingSettingsRepository> { unused() }
                single<OnboardingRepository> { unused() }
                single<OwnedKeyringRepository> { unused() }
            })
        }
        val store = ViewModelStore()
        try {
            val vm = withContext(Dispatchers.Main) {
                di.koin.get<HomeSessionViewModel>().also { store.put("home", it); it.account() }
            }
            val account = withTimeout(10_000) { vm.state.first { it.account.photo != null }.account }
            assertEquals(AccountMenuLoad.Ready, account.load)
            assertEquals("하늘", account.nickname)
            assertEquals(3, checkNotNull(account.photo).width)
            assertEquals(2, checkNotNull(account.photo).height)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            di.close()
            client.close()
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected dependency call: ${method.name}") } as T
}
