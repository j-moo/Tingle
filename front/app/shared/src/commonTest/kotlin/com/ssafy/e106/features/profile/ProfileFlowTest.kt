package com.ssafy.e106.features.profile

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private class TestProfiles : ProfileRepository {
    var data = ProfileResponse(1)
    var fields = mutableListOf<JsonObject>()
    var available = true
    var completed = 0
    val nicknameQueries = mutableListOf<String>()
    var onSave: (suspend () -> ApiResult<ProfileResponse>)? = null
    var onGet: (suspend () -> ApiResult<ProfileResponse>)? = null
    override suspend fun get(userId: Long) = onGet?.invoke() ?: ApiResult.Ok(data)
    override suspend fun nickname(value: String): ApiResult<NicknameAvailability> {
        nicknameQueries += value
        return ApiResult.Ok(NicknameAvailability(value, available, if (available) null else "DUPLICATED"))
    }
    override suspend fun save(userId: Long, fields: JsonObject): ApiResult<ProfileResponse> {
        this.fields += fields
        onSave?.let { return it() }
        fun field(key: String, old: String?) = fields[key]?.jsonPrimitive?.content ?: old
        data = data.copy(nickname = field("nickname", data.nickname), birthDate = field("birthDate", data.birthDate),
            gender = field("gender", data.gender), smokingStatus = field("smokingStatus", data.smokingStatus), drinkingFrequency = field("drinkingFrequency", data.drinkingFrequency))
        return ApiResult.Ok(data)
    }
    override suspend fun complete(userId: Long): ApiResult<Unit> { completed++; return ApiResult.Ok(Unit) }
    override suspend fun contactEmail(userId: Long) = ApiResult.Ok(ContactEmailResponse(null))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileFlowTest {
    private class TestAuth(private val session: TestSession) : AuthRepository {
        var logouts = 0
        var pending: CompletableDeferred<Unit>? = null
        override fun available(provider: Provider) = false
        override suspend fun login(provider: Provider): LoginResult = error("not used")
        override suspend fun logout() {
            logouts++
            pending?.await()
            session.state.value = Session.SignedOut
        }
    }
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun vm(repo: TestProfiles, session: TestSession = TestSession(OnboardingStatus.PROFILE_REQUIRED), auth: AuthRepository = TestAuth(session)) = ProfileViewModel(repo, session, auth).also { store.put("profile", it) }
    private fun fill(m: ProfileViewModel) { m.nickname("Test User"); m.birthDate("20001010"); m.gender(ProfileGender.Male) }

    private fun ProfileViewModel.submitBasics() { saveBasics(); confirmBasics() }

    @Test fun basicsRequireExplicitConfirmationBeforeAnyServerRequest() = runTest(dispatcher) {
        val repo = TestProfiles(); val m = vm(repo); runCurrent(); fill(m)
        m.birthDate("20251010"); m.saveBasics(); runCurrent()
        assertEquals("20251010", m.state.value.basicsConfirmation?.birthDateDigits)
        assertTrue(repo.fields.isEmpty()); assertTrue(repo.nicknameQueries.isEmpty())
        m.cancelBasicsConfirmation(); m.confirmBasics(); runCurrent()
        assertTrue(repo.fields.isEmpty()); assertFalse(m.state.value.underAgeAccountClosed)
        assertEquals("20251010", m.state.value.basics.birthDateDigits)
        m.birthDate("20001010"); m.saveBasics(); m.confirmBasics(); m.confirmBasics(); runCurrent()
        assertEquals(1, repo.fields.size)
        assertEquals("2000-10-10", repo.fields.single()["birthDate"]?.jsonPrimitive?.content)
    }

    @Test fun changedInputInvalidatesPendingConfirmation() = runTest(dispatcher) {
        val repo = TestProfiles(); val m = vm(repo); runCurrent(); fill(m)
        m.saveBasics(); m.birthDate("19990101"); m.confirmBasics(); runCurrent()
        assertNull(m.state.value.basicsConfirmation); assertTrue(repo.fields.isEmpty())
    }

    @Test fun accountChangeInvalidatesPendingConfirmation() = runTest(dispatcher) {
        val repo = TestProfiles(); val session = TestSession(OnboardingStatus.PROFILE_REQUIRED)
        val m = vm(repo, session); runCurrent(); fill(m); m.saveBasics()
        session.state.value = Session.SignedIn(2, OnboardingStatus.PROFILE_REQUIRED, false)
        m.confirmBasics(); runCurrent()
        assertNull(m.state.value.basicsConfirmation); assertTrue(repo.fields.isEmpty())
    }

    @Test fun saveEachPageWithExactWireCodesAndPreserveOptionalFields() = runTest(dispatcher) {
        val repo = TestProfiles(); repo.data = repo.data.copy(introduction = "keep", religion = "NONE", hasProfilePhoto = true)
        val m = vm(repo); runCurrent(); fill(m); m.submitBasics(); runCurrent()
        assertEquals(ProfilePage.Lifestyle, m.state.value.page)
        assertEquals(setOf("nickname", "birthDate", "gender"), repo.fields.first().keys)
        assertEquals("2000-10-10", repo.fields.first()["birthDate"]?.jsonPrimitive?.content)
        m.smoking(ProfileSmoking.OccasionallyWhenDrinking); m.drinking(ProfileDrinking.WeeklyFourOrMore); m.saveLifestyle(); runCurrent()
        assertEquals(ProfilePage.Optional, m.state.value.page)
        assertEquals("ONLY_WHEN_DRINKING", repo.fields.last()["smokingStatus"]?.jsonPrimitive?.content)
        assertEquals("WEEKLY_FOUR_OR_MORE", repo.fields.last()["drinkingFrequency"]?.jsonPrimitive?.content)
        assertEquals("keep", m.state.value.saved?.introduction); assertTrue(m.state.value.saved!!.hasProfilePhoto)
    }
    @Test fun duplicateNicknameNeverSavesAndPreservesInputs() = runTest(dispatcher) {
        val repo = TestProfiles(); repo.available = false; val m = vm(repo); runCurrent(); fill(m); m.submitBasics(); runCurrent()
        assertEquals(ProfileValidation.Duplicate, m.state.value.validation); assertTrue(repo.fields.isEmpty())
        assertEquals("Test User", m.state.value.basics.nickname); assertEquals(ProfilePage.Basics, m.state.value.page)
    }
    @Test fun nicknameCompositionStaysUntouchedUntilSaveAndBothRequestsUseNfc() = runTest(dispatcher) {
        val repo = TestProfiles(); val m = vm(repo); runCurrent(); fill(m)
        val raw = "  가을  Ab  "
        m.nickname(raw); assertEquals(raw, m.state.value.basics.nickname)
        m.submitBasics(); runCurrent()
        assertEquals(listOf("가을  Ab"), repo.nicknameQueries)
        assertEquals("가을  Ab", repo.fields.single()["nickname"]?.jsonPrimitive?.content)
        assertEquals("가을  Ab", m.state.value.basics.nickname)
        assertEquals(ProfilePage.Lifestyle, m.state.value.page)
    }
    @Test fun slowSaveLocksInputAndDoubleClicksAndFailureKeepsDraft() = runTest(dispatcher) {
        val repo = TestProfiles(); val pending = CompletableDeferred<ApiResult<ProfileResponse>>()
        repo.onSave = { pending.await() }; val m = vm(repo); runCurrent(); fill(m)
        m.submitBasics(); m.submitBasics(); m.nickname("changed"); runCurrent()
        assertEquals(1, repo.fields.size); assertEquals("Test User", m.state.value.basics.nickname)
        pending.complete(ApiResult.Fail(AppError.Offline)); runCurrent()
        assertFalse(m.state.value.busy); assertEquals(AppError.Offline, m.state.value.error)
        assertEquals(ProfilePage.Basics, m.state.value.page); assertEquals("20001010", m.state.value.basics.birthDateDigits)
    }
    @Test fun completeThenRefreshFailureRetriesOnlyRead() = runTest(dispatcher) {
        val repo = TestProfiles(); val s = TestSession(OnboardingStatus.PROFILE_REQUIRED); val m = vm(repo, s); runCurrent()
        fill(m); m.submitBasics(); runCurrent(); m.smoking(ProfileSmoking.NonSmoker); m.drinking(ProfileDrinking.Never); m.saveLifestyle(); runCurrent()
        m.page(ProfilePage.Confirmation); s.refreshResult = ApiResult.Fail(AppError.Offline); m.complete(); runCurrent()
        assertTrue(m.state.value.completed); m.complete(); runCurrent()
        assertEquals(1, repo.completed); assertEquals(2, s.refreshes)
        assertEquals(OnboardingStatus.PROFILE_REQUIRED, (s.state.value as Session.SignedIn).status)
    }
    @Test fun completedProfileRestoresReadRetryWithoutUnlockingImmutableFields() = runTest(dispatcher) {
        val repo = TestProfiles(); repo.data = repo.data.copy(completed = true, nickname = "saved")
        val s = TestSession(OnboardingStatus.PROFILE_REQUIRED); s.refreshResult = ApiResult.Fail(AppError.Offline)
        val m = vm(repo, s); runCurrent(); m.nickname("changed"); m.submitBasics(); runCurrent()
        assertEquals("saved", m.state.value.basics.nickname); assertTrue(repo.fields.isEmpty()); assertEquals(1, s.refreshes)
    }
    @Test fun accountChangeClearsPrivateDraftEvenWhenOldRequestReturnsLate() = runTest(dispatcher) {
        val repo = TestProfiles(); val pending = CompletableDeferred<ApiResult<ProfileResponse>>()
        repo.onGet = { pending.await() }; val s = TestSession(OnboardingStatus.PROFILE_REQUIRED); val m = vm(repo, s); runCurrent()
        s.state.value = Session.SignedIn(2, OnboardingStatus.KEYRING_REQUIRED, false); runCurrent()
        pending.complete(ApiResult.Ok(ProfileResponse(1, nickname = "private"))); runCurrent()
        assertNull(m.state.value.saved); assertEquals("", m.state.value.basics.nickname); assertFalse(m.state.value.busy)
    }
    @Test fun keyringGateNeverLoadsProfile() = runTest(dispatcher) {
        val repo = TestProfiles(); repo.onGet = { error("must not call profile") }
        val m = vm(repo, TestSession(OnboardingStatus.KEYRING_REQUIRED)); runCurrent(); m.load(); runCurrent()
        assertFalse(m.state.value.loaded)
    }
    @Test fun developmentKeyringAccessUsesRepositoryWithoutAdvancingSession() = runTest(dispatcher) {
        val repo = TestProfiles(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val original = session.state.value
        val m = ProfileViewModel(repo, session, TestAuth(session), DevelopmentProfileAccess(true)).also { store.put("profile", it) }
        runCurrent(); assertTrue(m.state.value.loaded)
        fill(m); m.submitBasics(); runCurrent()
        assertEquals(1, repo.fields.size); assertEquals(ProfilePage.Lifestyle, m.state.value.page)
        m.smoking(ProfileSmoking.OccasionallyWhenDrinking); m.drinking(ProfileDrinking.WeeklyFourOrMore)
        m.saveLifestyle(); runCurrent(); m.page(ProfilePage.Confirmation); m.complete(); runCurrent()
        assertEquals(1, repo.completed); assertTrue(m.state.value.completed)
        assertEquals(original, session.state.value); assertFalse(m.state.value.busy)
        m.complete(); runCurrent(); assertEquals(1, repo.completed)
        session.state.value = Session.SignedOut; runCurrent()
        assertNull(m.state.value.saved); assertFalse(m.state.value.loaded)
    }
    @Test fun developmentKeyringAccessShowsServerFailureWithoutInventingProfile() = runTest(dispatcher) {
        val repo = TestProfiles(); repo.onGet = { ApiResult.Fail(AppError.Server(403, "ONBOARDING_REQUIRED", "등록 필요")) }
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val m = ProfileViewModel(repo, session, TestAuth(session), DevelopmentProfileAccess(true)).also { store.put("profile", it) }
        runCurrent(); assertFalse(m.state.value.loaded); assertNull(m.state.value.saved)
        assertIs<AppError.Server>(m.state.value.error)
        fill(m); m.submitBasics(); runCurrent(); assertTrue(repo.fields.isEmpty())
    }
    @Test fun developmentAccessCannotSkipAgreementsOrEnableReadyAccounts() {
        val development = DevelopmentProfileAccess(true); val production = DevelopmentProfileAccess()
        OnboardingStatus.entries.forEach { status ->
            val session = Session.SignedIn(1, status, false)
            assertEquals(status == OnboardingStatus.KEYRING_REQUIRED, development.canSkipKeyring(session))
            assertFalse(production.canSkipKeyring(session))
            assertEquals(status == OnboardingStatus.PROFILE_REQUIRED, production.canEditProfile(session))
            assertEquals(status == OnboardingStatus.KEYRING_REQUIRED || status == OnboardingStatus.PROFILE_REQUIRED,
                development.canEditProfile(session))
            assertFalse(development.canEditProfile(session.copy(signupCompleted = true)))
            // 가입 준비에서 프로필 고치기: 프로필을 이미 쓴 단계(성향테스트 · 희망 나이 · 동기화)와 가입을 마친 회원만
            assertEquals(status in setOf(OnboardingStatus.READY, OnboardingStatus.SURVEY_REQUIRED,
                OnboardingStatus.PREFERENCES_REQUIRED, OnboardingStatus.SYNC_PENDING), production.canManageProfile(session))
        }
    }
    @Test fun invalidDatesAndNicknameNeverReachServer() = runTest(dispatcher) {
        assertTrue(validBirthDate("20000229")); assertFalse(validBirthDate("19000229")); assertFalse(validBirthDate("20001310"))
        val repo = TestProfiles(); val m = vm(repo); runCurrent(); fill(m); m.birthDate("20000230"); m.submitBasics(); runCurrent()
        assertEquals(ProfileValidation.BirthDate, m.state.value.validation); assertTrue(repo.fields.isEmpty())
        m.birthDate("20001010"); m.nickname("@name"); m.submitBasics(); runCurrent()
        assertEquals(ProfileValidation.Nickname, m.state.value.validation); assertTrue(repo.fields.isEmpty())
    }

    @Test fun nicknameTakenAfterAvailabilityCheckMarksFieldAndKeepsDraftForRetry() = runTest(dispatcher) {
        val repo = TestProfiles()
        repo.onSave = { ApiResult.Fail(AppError.Server(409, "NICKNAME_DUPLICATED", "이미 사용 중인 닉네임이에요.")) }
        val m = vm(repo); runCurrent(); fill(m); m.submitBasics(); runCurrent()
        val screen = m.state.value.basicsForDisplay(null)
        assertEquals("이미 사용 중인 닉네임이에요.", screen.nicknameError)
        assertNull(screen.birthDateError)
        assertEquals("Test User", screen.nickname)
        assertEquals(ProfilePage.Basics, m.state.value.page)
        assertFalse(screen.saving)
        m.nickname("New Name")
        assertNull(m.state.value.basicsForDisplay(null).nicknameError)
        repo.onSave = null
        m.submitBasics(); runCurrent()
        assertEquals(ProfilePage.Lifestyle, m.state.value.page)
        assertEquals("New Name", m.state.value.saved?.nickname)
    }

    @Test fun invalidBirthDateRemainsEditableWithoutTreatingOfflineAsInputError() = runTest(dispatcher) {
        val repo = TestProfiles()
        repo.onSave = { ApiResult.Fail(AppError.Server(422, "INVALID_BIRTH_DATE", "생년월일을 확인해 주세요.")) }
        val m = vm(repo); runCurrent(); fill(m); m.submitBasics(); runCurrent()
        assertEquals("생년월일을 확인해 주세요.", m.state.value.basicsForDisplay(null).birthDateError)
        assertNull(m.state.value.basicsForDisplay(null).nicknameError)
        m.birthDate("19981010")
        repo.onSave = { ApiResult.Fail(AppError.Offline) }
        m.submitBasics(); runCurrent()
        assertEquals(AppError.Offline, m.state.value.error)
        assertNull(m.state.value.basicsForDisplay(null).birthDateError)
        assertEquals("19981010", m.state.value.basics.birthDateDigits)
    }

    @Test fun underAgeClosesProfileAndAcknowledgementSignsOutOnlyOnce() = runTest(dispatcher) {
        val repo = TestProfiles()
        repo.onSave = { ApiResult.Fail(AppError.Server(403, "UNDER_AGE", "under age")) }
        val session = TestSession(OnboardingStatus.PROFILE_REQUIRED)
        val auth = TestAuth(session); auth.pending = CompletableDeferred()
        val m = vm(repo, session, auth); runCurrent()
        m.returnToLogin(); runCurrent(); assertEquals(0, auth.logouts)
        fill(m); m.submitBasics(); runCurrent()
        assertTrue(m.state.value.underAgeAccountClosed)
        assertEquals("under age", m.state.value.underAgeMessage)
        assertNull(m.state.value.saved); assertEquals("", m.state.value.basics.nickname)
        assertNull(m.state.value.basicsForDisplay(null).birthDateError)
        m.nickname("changed"); m.submitBasics(); m.load(); m.refresh(); m.complete(); runCurrent()
        assertEquals(1, repo.fields.size); assertEquals(0, repo.completed); assertEquals(0, session.refreshes)
        assertEquals("", m.state.value.basics.nickname)
        assertIs<Session.SignedIn>(session.state.value); assertEquals(0, auth.logouts)
        m.returnToLogin(); m.returnToLogin(); runCurrent()
        assertEquals(1, auth.logouts); assertTrue(m.state.value.busy)
        auth.pending!!.complete(Unit); runCurrent()
        assertEquals(Session.SignedOut, session.state.value)
        assertNull(m.state.value.session); assertFalse(m.state.value.underAgeAccountClosed)
    }

    @Test fun blankUnderAgeMessageUsesUiFallback() = runTest(dispatcher) {
        val repo = TestProfiles(); repo.onSave = { ApiResult.Fail(AppError.Server(403, "UNDER_AGE", "  ")) }
        val m = vm(repo); runCurrent(); fill(m); m.submitBasics(); runCurrent()
        assertTrue(m.state.value.underAgeAccountClosed); assertNull(m.state.value.underAgeMessage)
    }
    @Test fun lateUnderAgeFromPreviousAccountCannotCloseNewAccount() = runTest(dispatcher) {
        val repo = TestProfiles(); val response = CompletableDeferred<ApiResult<ProfileResponse>>()
        repo.onSave = { withContext(NonCancellable) { response.await() } }
        val session = TestSession(OnboardingStatus.PROFILE_REQUIRED); val auth = TestAuth(session)
        val m = vm(repo, session, auth); runCurrent(); fill(m); m.submitBasics(); runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.KEYRING_REQUIRED, false); runCurrent()
        response.complete(ApiResult.Fail(AppError.Server(403, "UNDER_AGE", "old response"))); runCurrent()
        m.returnToLogin(); runCurrent()
        assertFalse(m.state.value.underAgeAccountClosed); assertEquals(0, auth.logouts)
        assertEquals(2L, (session.state.value as Session.SignedIn).userId)
    }
}
