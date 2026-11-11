package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** HTTP 계약 및 실패/계정 경계 검사. 가상 응답은 테스트에만 사용한다. */
class OwnedKeyringsApiTest {
    @Test fun listUsesAuthenticatedClientPathAndNoStore() = runTest {
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/dev/api/keyrings", request.url.encodedPath)
            assertEquals("no-store", request.headers[HttpHeaders.CacheControl])
            respond("""{"items":[{"deviceId":7,"status":"READY","firmwareVersion":"1.0","deviceSerial":"XIAO-MixedCase","extra":true}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val item = assertIs<ApiResult.Ok<List<OwnedKeyring>>>(HttpOwnedKeyringRepository(client).list()).value.single()
            assertEquals(7L, item.deviceId)
            assertEquals("READY", item.status)
            assertEquals("XIAO-MixedCase", item.deviceSerial)
        } finally { client.close() }
    }

    @Test fun invalidOrMissingPayloadNeverBecomesEmptyOwnership() = runTest {
        listOf("{}", """{"items":[{"deviceId":0,"status":"READY","firmwareVersion":"1"}]}""",
            """{"items":[{"deviceId":1,"status":"READY"}]}""").forEach { body ->
            val client = createHttpClient("https://test/api/", httpLog = false, engine = MockEngine {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try { assertIs<ApiResult.Fail>(HttpOwnedKeyringRepository(client).list()) }
            finally { client.close() }
        }
    }

    @Test fun onboardingRestrictionIsAnErrorNotEmptyList() = runTest {
        val client = createHttpClient("https://test/api/", httpLog = false, engine = MockEngine {
            respond("""{"code":"ONBOARDING_REQUIRED","message":"가입 단계 확인"}""", HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { assertIs<AppError.OnboardingRequired>(assertIs<ApiResult.Fail>(HttpOwnedKeyringRepository(client).list()).error) }
        finally { client.close() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedKeyringsFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun before() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun after() { models.clear(); Dispatchers.resetMain() }

    private fun model(session: TestSession, read: suspend () -> ApiResult<List<OwnedKeyring>>) =
        OwnedKeyringsViewModel(object : OwnedKeyringRepository {
            override suspend fun list() = read()
        }, session).also { models.put("keyrings", it) }

    @Test fun emptySuccessAndFailedRefreshRemainDistinct() = runTest(dispatcher) {
        var result: ApiResult<List<OwnedKeyring>> = ApiResult.Ok(emptyList())
        val vm = model(TestSession(OnboardingStatus.READY)) { result }
        vm.load(); runCurrent()
        assertEquals(emptyList(), vm.state.value.items)
        result = ApiResult.Fail(AppError.Offline)
        vm.load(); runCurrent()
        assertNull(vm.state.value.items)
        assertEquals(AppError.Offline, vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun latePreviousAccountResponseIsDiscarded() = runTest(dispatcher) {
        val session = TestSession(OnboardingStatus.READY)
        val pending = CompletableDeferred<ApiResult<List<OwnedKeyring>>>()
        val vm = model(session) { withContext(NonCancellable) { pending.await() } }
        vm.load(); runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.READY, true); runCurrent()
        pending.complete(ApiResult.Ok(listOf(OwnedKeyring(99, "READY", "1")))); runCurrent()
        assertEquals(2L, vm.state.value.owner?.userId)
        assertNull(vm.state.value.items)
    }

    @Test fun leavingDiscardsLateResponseAndReturnReadsAgain() = runTest(dispatcher) {
        val pending = CompletableDeferred<ApiResult<List<OwnedKeyring>>>()
        var calls = 0
        val vm = model(TestSession(OnboardingStatus.READY)) {
            calls++
            if (calls == 1) withContext(NonCancellable) { pending.await() } else ApiResult.Ok(emptyList())
        }
        vm.load(); runCurrent(); vm.leave()
        pending.complete(ApiResult.Ok(listOf(OwnedKeyring(99, "READY", "1")))); runCurrent()
        assertNull(vm.state.value.items)
        vm.load(); runCurrent()
        assertEquals(2, calls)
        assertEquals(emptyList(), vm.state.value.items)
    }
}
