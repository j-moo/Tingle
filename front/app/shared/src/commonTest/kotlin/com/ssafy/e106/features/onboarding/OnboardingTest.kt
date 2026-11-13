package com.ssafy.e106.features.onboarding

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.data.*
import com.ssafy.e106.features.onboarding.ui.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal class TestSession(status: OnboardingStatus) : SessionStore {
    override val state = MutableStateFlow<Session>(Session.SignedIn(1, status, false))
    override val events = emptyFlow<SessionEvent>()
    var refreshes = 0
    var refreshResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    override suspend fun refreshOnboarding(): ApiResult<Unit> { refreshes++; return refreshResult }
}
private class TestAuth(private val session: TestSession) : AuthRepository {
    override fun available(provider: Provider) = true
    override suspend fun login(provider: Provider) = LoginResult.Cancelled
    override suspend fun logout() { session.state.value = Session.SignedOut }
}
private class TestAgreements : OnboardingRepository {
    var items = listOf(AgreementItem("TERMS", "v1", "terms", true, "https://tingle.test/terms"),
        AgreementItem("OPTIONAL", "v1", "optional", false, "https://tingle.test/optional"))
    var submitted = mutableListOf<List<AgreementAnswer>>()
    var onAgree: suspend () -> ApiResult<Unit> = { ApiResult.Ok(Unit) }
    var onLoad: (suspend () -> ApiResult<AgreementsResponse>)? = null
    override suspend fun agreements() = onLoad?.invoke() ?: ApiResult.Ok(AgreementsResponse(items))
    override suspend fun agree(userId: Long, answers: List<AgreementAnswer>): ApiResult<Unit> { submitted += answers; return onAgree() }
    override suspend fun purchaseInfo() = ApiResult.Ok(PurchaseInfo("https://tingle.test/shop", true))
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun vm(repo: TestAgreements, session: TestSession, allowUnpublishedAgreements: Boolean = false) =
        OnboardingViewModel(repo, session, TestAuth(session), allowUnpublishedAgreements).also { store.put("onboard", it) }

    @Test fun requiredOnlyConsentOmitsOptionalAndUsesExactVersions() = runTest(dispatcher) {
        val repo = TestAgreements(); val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED); val m = vm(repo, session)
        runCurrent(); assertFalse(m.state.value.canSubmit)
        m.check("TERMS", true); m.submit(); m.submit(); runCurrent()
        assertEquals(listOf(listOf(AgreementAnswer("TERMS", "v1", true))), repo.submitted)
        assertTrue(m.state.value.submitted)
        assertEquals(1, session.refreshes)
        assertEquals(OnboardingStatus.AGREEMENT_REQUIRED, m.state.value.session?.status)
    }
    // 약관 개정으로 다시 동의하는 기존 회원에게는 사진 · 자기소개 동의를 묶지 않는다(계정 메뉴에서 따로 관리)
    @Test fun completedMemberReconsentKeepsProfileConsentsOptional() = runTest(dispatcher) {
        val repo = TestAgreements()
        repo.items += AgreementItem("PROFILE_PHOTO", "v1", "photo", false, "https://tingle.test/photo")
        val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED)
        session.state.value = Session.SignedIn(1, OnboardingStatus.AGREEMENT_REQUIRED, true)
        val m = vm(repo, session); runCurrent()
        m.check("TERMS", true); m.submit(); runCurrent()
        assertEquals(listOf(listOf(AgreementAnswer("TERMS", "v1", true))), repo.submitted)
    }
    // 문서 주소가 없는 프로필 동의는 체크할 수 없으니 필수로 묶지 않는다(묶으면 가입이 막힌다)
    @Test fun profileConsentWithoutDocumentDoesNotBlockSignup() = runTest(dispatcher) {
        val repo = TestAgreements()
        repo.items += AgreementItem("PROFILE_PHOTO", "v1", "(선택) photo", false)
        val m = vm(repo, TestSession(OnboardingStatus.AGREEMENT_REQUIRED)); runCurrent()
        m.check("TERMS", true); m.submit(); runCurrent()
        assertEquals(listOf(listOf(AgreementAnswer("TERMS", "v1", true))), repo.submitted)
    }
    // 서버가 전문 주소를 안 줘도 랜딩 정적 페이지로 채워 운영 · 개발 모두 약관 시트에서 필수로 받는다. 서버 주소가 있으면 그걸 쓴다
    @Test fun profileConsentDocumentFallsBackToLandingPage() {
        val photo = AgreementItem("PROFILE_PHOTO", "v1", "photo", false)
        assertEquals("https://tingle.test/dev/profile-photo/", photo.withProfileDocument("https://tingle.test/dev/").reviewUrl)
        assertEquals("https://tingle.test/profile-introduction/",
            AgreementItem("PROFILE_INTRODUCTION", "v1", "intro", false).withProfileDocument("https://tingle.test/").reviewUrl)
        assertEquals("https://server.test/photo", photo.copy(contentUrl = "https://server.test/photo").withProfileDocument("https://tingle.test/").reviewUrl)
        // 종교도 전문을 채운다(없으면 종교 동의 칸이 잠겨 종교를 고를 수 없다)
        assertEquals("https://tingle.test/profile-religion/",
            AgreementItem("PROFILE_RELIGION", "v1", "religion", false).withProfileDocument("https://tingle.test/").reviewUrl)
        assertNull(AgreementItem("ADULT", "v1", "adult", true).withProfileDocument("https://tingle.test/").contentUrl)
        assertNull(photo.withProfileDocument(null).contentUrl)
    }
    // 종교를 체크하지 않으면 보내지 않는다(false 를 보내면 철회 · 삭제)
    @Test fun uncheckedReligionIsOmitted() = runTest(dispatcher) {
        val repo = TestAgreements()
        repo.items += AgreementItem("PROFILE_RELIGION", "v1", "(선택) religion", false, "https://tingle.test/religion")
        val m = vm(repo, TestSession(OnboardingStatus.AGREEMENT_REQUIRED)); runCurrent()
        assertEquals(listOf("PROFILE_RELIGION"), m.state.value.optionalAgreements.map { it.type })
        m.check("TERMS", true); m.submit(); runCurrent()
        assertEquals(listOf(listOf(AgreementAnswer("TERMS", "v1", true))), repo.submitted)
        // 전문이 없어 체크할 수 없는 종교 줄은 시트에 두지 않는다
        val noDoc = TestAgreements().apply { items += AgreementItem("PROFILE_RELIGION", "v1", "religion", false) }
        val n = OnboardingViewModel(noDoc, TestSession(OnboardingStatus.AGREEMENT_REQUIRED), TestAuth(TestSession(OnboardingStatus.AGREEMENT_REQUIRED)))
            .also { store.put("onboard2", it) }; runCurrent()
        assertTrue(n.state.value.optionalAgreements.isEmpty())
    }
    @Test fun refreshFailureAfterSuccessfulConsentDoesNotResubmit() = runTest(dispatcher) {
        val repo = TestAgreements(); val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED)
        session.refreshResult = ApiResult.Fail(AppError.Offline)
        val m = vm(repo, session); runCurrent(); m.check("TERMS", true); m.submit(); runCurrent()
        assertTrue(m.state.value.submitted); assertEquals(AppError.Offline, m.state.value.error)
        m.submit(); m.load(); runCurrent()
        assertEquals(1, repo.submitted.size); assertEquals(2, session.refreshes)
    }
    @Test fun optionalActionsAreIgnoredAndOptionalOnlyResponseCannotSubmit() = runTest(dispatcher) {
        val repo = TestAgreements()
        repo.items = repo.items.filterNot { it.required }
        val m = vm(repo, TestSession(OnboardingStatus.AGREEMENT_REQUIRED), allowUnpublishedAgreements = true)
        runCurrent()
        m.check("OPTIONAL", true); m.submit(); runCurrent()
        assertTrue(m.state.value.checked.isEmpty())
        assertTrue(m.state.value.requiredAgreements.isEmpty())
        assertFalse(m.state.value.canSubmit)
        assertTrue(repo.submitted.isEmpty())
    }
    // 사진 · 자기소개 동의는 가입 때 필수, 종교는 선택(체크했을 때만 함께 보낸다)
    @Test fun signupPayloadIncludesProfileConsents() = runTest(dispatcher) {
        val bodies = mutableListOf<JsonArray>()
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            val response = when (request.method) {
                HttpMethod.Get -> """{"agreements":[
                    {"type":"TERMS","version":"v2","title":"terms","required":true,"contentUrl":"https://tingle.test/terms"},
                    {"type":"ADULT","version":"v1","title":"adult","required":true},
                    {"type":"PROFILE_PHOTO","version":"v3","title":"photo","required":false,"contentUrl":"https://tingle.test/photo"},
                    {"type":"PROFILE_INTRODUCTION","version":"v4","title":"introduction","required":false,"contentUrl":"https://tingle.test/introduction"},
                    {"type":"PROFILE_RELIGION","version":"v5","title":"religion","required":false,"contentUrl":"https://tingle.test/religion"}]}"""
                HttpMethod.Post -> {
                    assertEquals("/dev/api/users/1/agreements", request.url.encodedPath)
                    bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString())
                        .jsonObject.getValue("agreements").jsonArray
                    "{}"
                }
                else -> error("Unexpected request: ${request.method}")
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED)
            val m = OnboardingViewModel(HttpOnboardingRepository(client), session, TestAuth(session))
            store.put("onboard", m)
            m.state.first { !it.busy }
            assertNull(m.state.value.error)
            assertEquals(5, m.state.value.agreements.size)
            m.check("TERMS", true); m.check("ADULT", true); m.check("PROFILE_PHOTO", true)
            assertFalse(m.state.value.canSubmit)
            m.check("PROFILE_INTRODUCTION", true)
            assertTrue(m.state.value.canSubmit)   // 종교는 선택이라 안 골라도 가입 가능
            m.check("PROFILE_RELIGION", true); m.submit()
            m.state.first { !it.busy }
            assertTrue(m.state.value.submitted, "Consent failed: ${m.state.value.error}")
            val answers = bodies.single().map { it.jsonObject }
            assertEquals(listOf("TERMS", "ADULT", "PROFILE_PHOTO", "PROFILE_INTRODUCTION", "PROFILE_RELIGION"), answers.map { it.getValue("type").jsonPrimitive.content })
            assertEquals(listOf("v2", "v1", "v3", "v4", "v5"), answers.map { it.getValue("version").jsonPrimitive.content })
            assertTrue(answers.all { it.getValue("agreed").jsonPrimitive.boolean })
        } finally { client.close() }
    }
    @Test fun outdatedVersionRequiresFreshChecksAndNeverCopiesOldConsent() = runTest(dispatcher) {
        val repo = TestAgreements(); val m = vm(repo, TestSession(OnboardingStatus.AGREEMENT_REQUIRED)); runCurrent()
        repo.onAgree = {
            repo.items = repo.items.map { it.copy(version = "v2") }
            ApiResult.Fail(AppError.Server(409, "AGREEMENT_VERSION_OUTDATED", "updated"))
        }
        m.check("TERMS", true); m.submit(); runCurrent()
        assertEquals("v2", m.state.value.agreements.first().version)
        assertTrue(m.state.value.checked.isEmpty()); assertFalse(m.state.value.canSubmit)
        repo.onAgree = { ApiResult.Ok(Unit) }
        m.check("TERMS", true); m.submit(); runCurrent()
        assertEquals(listOf(AgreementAnswer("TERMS", "v2", true)), repo.submitted.last())
    }
    @Test fun unresolvedTermsCannotBeConsentedTo() = runTest(dispatcher) {
        val repo = TestAgreements(); repo.items = listOf(AgreementItem("TERMS", "v1", "terms", true, "https://example.com/terms"))
        val m = vm(repo, TestSession(OnboardingStatus.AGREEMENT_REQUIRED)); runCurrent(); m.check("TERMS", true); m.submit(); runCurrent()
        assertTrue(m.state.value.checked.isEmpty()); assertTrue(repo.submitted.isEmpty())
        assertTrue(AgreementItem("ADULT", "v1", "adult", true).canReview)
    }
    @Test fun developmentAllowsMissingTermsOnlyAfterExplicitRequiredChecksAndUsesRealRepository() = runTest(dispatcher) {
        val repo = TestAgreements()
        repo.items = listOf(
            AgreementItem("TERMS", "v1", "terms", true, "https://example.com/terms"),
            AgreementItem("PRIVACY", "v2", "privacy", true),
            AgreementItem("OPTIONAL", "v3", "optional", false),
        )
        val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED)
        session.refreshResult = ApiResult.Fail(AppError.Offline)
        val m = vm(repo, session, allowUnpublishedAgreements = true)
        runCurrent()
        assertFalse(m.state.value.canSubmit)
        m.check("TERMS", true); m.submit(); runCurrent()
        assertTrue(repo.submitted.isEmpty())
        m.check("PRIVACY", true)
        assertTrue(m.state.value.canSubmit)
        m.submit(); m.submit(); runCurrent()
        assertEquals(listOf(listOf(AgreementAnswer("TERMS", "v1", true),
            AgreementAnswer("PRIVACY", "v2", true))), repo.submitted)
        assertTrue(m.state.value.submitted)
        assertEquals(AppError.Offline, m.state.value.error)
        assertEquals(OnboardingStatus.AGREEMENT_REQUIRED, m.state.value.session?.status)
        m.load(); runCurrent()
        assertEquals(1, repo.submitted.size)
        assertEquals(2, session.refreshes)
    }

    @Test fun productionDefaultStillRejectsMissingTermsEvenIfChecksAreRestored() = runTest(dispatcher) {
        val items = listOf(AgreementItem("TERMS", "v1", "terms", true))
        val restored = OnboardingState(agreements = items, checked = setOf("TERMS"))
        assertFalse(restored.allowUnpublishedAgreements)
        assertFalse(restored.canSubmit)
        assertFalse(restored.canCheck(items.single()))
        val repo = TestAgreements(); repo.items = items
        val m = vm(repo, TestSession(OnboardingStatus.AGREEMENT_REQUIRED))
        runCurrent(); m.check("TERMS", true); m.submit(); runCurrent()
        assertTrue(m.state.value.checked.isEmpty())
        assertTrue(repo.submitted.isEmpty())
    }
    @Test fun keyringRefreshNeverAdvancesLocally() = runTest(dispatcher) {
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val m = vm(TestAgreements(), session); runCurrent()
        m.refresh(); runCurrent()
        assertEquals(OnboardingStatus.KEYRING_REQUIRED, m.state.value.session?.status)
        assertEquals("https://tingle.test/shop", m.state.value.purchaseUrl)
    }
    @Test fun signOutCancelsPendingLoadAndErasesChecks() = runTest(dispatcher) {
        val repo = TestAgreements(); val pending = CompletableDeferred<ApiResult<AgreementsResponse>>()
        repo.onLoad = { pending.await() }
        val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED); val m = vm(repo, session); runCurrent()
        session.state.value = Session.SignedOut; runCurrent()
        pending.complete(ApiResult.Ok(AgreementsResponse(repo.items))); runCurrent()
        assertNull(m.state.value.session); assertTrue(m.state.value.agreements.isEmpty()); assertFalse(m.state.value.busy)
    }
    @Test fun busySubmitLocksChecksAndLogoutReturnsToLogin() = runTest(dispatcher) {
        val repo = TestAgreements(); val pending = CompletableDeferred<ApiResult<Unit>>()
        repo.onAgree = { pending.await() }
        val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED); val m = vm(repo, session); runCurrent()
        m.check("TERMS", true); m.submit(); m.check("TERMS", false); runCurrent()
        assertTrue("TERMS" in m.state.value.checked)
        pending.complete(ApiResult.Fail(AppError.Offline)); runCurrent(); m.logout(); runCurrent()
        assertEquals(Session.SignedOut, session.state.value); assertNull(m.state.value.session)
    }
    @Test fun logoutWorksWhileLoading() = runTest(dispatcher) {
        val repo = TestAgreements(); val pending = CompletableDeferred<ApiResult<AgreementsResponse>>()
        repo.onLoad = { pending.await() }
        val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED); val m = vm(repo, session); runCurrent()
        assertTrue(m.state.value.busy)
        m.logout(); runCurrent()
        assertEquals(Session.SignedOut, session.state.value)
    }
    @Test fun staleActionBeforeSessionCollectorRunsCannotLogoutNewAccount() = runTest(dispatcher) {
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val m = vm(TestAgreements(), session); runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.KEYRING_REQUIRED, false)
        m.logout(); runCurrent()
        assertEquals(2, (session.state.value as Session.SignedIn).userId)
    }
}
