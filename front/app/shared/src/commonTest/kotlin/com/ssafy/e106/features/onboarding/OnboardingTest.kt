package com.ssafy.e106.features.onboarding

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.data.*
import com.ssafy.e106.features.onboarding.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
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

    @Test fun requiredOnlyConsentSendsOptionalFalseAndUsesExactVersions() = runTest(dispatcher) {
        val repo = TestAgreements(); val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED); val m = vm(repo, session)
        runCurrent(); assertFalse(m.state.value.canSubmit)
        m.check("TERMS", true); m.submit(); m.submit(); runCurrent()
        assertEquals(listOf(listOf(AgreementAnswer("TERMS", "v1", true), AgreementAnswer("OPTIONAL", "v1", false))), repo.submitted)
        assertTrue(m.state.value.submitted)
        assertEquals(1, session.refreshes)
        assertEquals(OnboardingStatus.AGREEMENT_REQUIRED, m.state.value.session?.status)
    }
    @Test fun refreshFailureAfterSuccessfulConsentDoesNotResubmit() = runTest(dispatcher) {
        val repo = TestAgreements(); val session = TestSession(OnboardingStatus.AGREEMENT_REQUIRED)
        session.refreshResult = ApiResult.Fail(AppError.Offline)
        val m = vm(repo, session); runCurrent(); m.check("TERMS", true); m.submit(); runCurrent()
        assertTrue(m.state.value.submitted); assertEquals(AppError.Offline, m.state.value.error)
        m.submit(); m.load(); runCurrent()
        assertEquals(1, repo.submitted.size); assertEquals(2, session.refreshes)
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
            AgreementAnswer("PRIVACY", "v2", true), AgreementAnswer("OPTIONAL", "v3", false))), repo.submitted)
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
    @Test fun staleActionBeforeSessionCollectorRunsCannotLogoutNewAccount() = runTest(dispatcher) {
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val m = vm(TestAgreements(), session); runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.KEYRING_REQUIRED, false)
        m.logout(); runCurrent()
        assertEquals(2, (session.state.value as Session.SignedIn).userId)
    }
}
