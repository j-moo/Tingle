package com.ssafy.e106.features.profile

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.onboarding.data.AgreementsResponse
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private class EditServer : ProfileRepository, OptionalProfileRepository {
    var data = ProfileResponse(1, "Old Name", "2000-10-10", "MALE", "NON_SMOKER", "NEVER", "NONE", "existing", true, true)
    val patches = mutableListOf<JsonObject>()
    val nicknameQueries = mutableListOf<String>()
    var writesToOptional = 0
    var onPatch: (suspend (JsonObject) -> ApiResult<ProfileResponse>)? = null
    var onGet: (suspend () -> ApiResult<ProfileResponse>)? = null
    var available = true
    var photoFailure = false
    var onEmail: (suspend () -> ApiResult<ContactEmailResponse>)? = null
    override suspend fun contactEmail(userId: Long) = onEmail?.invoke() ?: ApiResult.Ok(ContactEmailResponse("me@example.com"))
    override suspend fun get(userId: Long) = onGet?.invoke() ?: ApiResult.Ok(data)
    override suspend fun nickname(value: String): ApiResult<NicknameAvailability> {
        nicknameQueries += value; return ApiResult.Ok(NicknameAvailability(value, available, if (available) null else "DUPLICATED"))
    }
    fun apply(fields: JsonObject): ProfileResponse {
        data = data.copy(nickname = fields["nickname"]?.jsonPrimitive?.content ?: data.nickname,
            smokingStatus = fields["smokingStatus"]?.jsonPrimitive?.content ?: data.smokingStatus,
            drinkingFrequency = fields["drinkingFrequency"]?.jsonPrimitive?.content ?: data.drinkingFrequency)
        return data
    }
    override suspend fun save(userId: Long, fields: JsonObject): ApiResult<ProfileResponse> {
        patches += fields; return onPatch?.invoke(fields) ?: ApiResult.Ok(apply(fields))
    }
    override suspend fun complete(userId: Long): ApiResult<Unit> = error("edit must not complete onboarding")
    override suspend fun documents() = ApiResult.Ok(AgreementsResponse(emptyList()))
    override suspend fun consents(userId: Long): ApiResult<OptionalConsentsResponse> = ApiResult.Fail(AppError.Server(404, "NOT_FOUND", "missing"))
    override suspend fun consent(userId: Long, type: OptionalConsentType, request: OptionalConsentRequest): ApiResult<OptionalConsentRecord> {
        writesToOptional++; error("basic editor must not mutate consent")
    }
    override suspend fun photo(userId: Long): ApiResult<ByteArray> = if (photoFailure) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(byteArrayOf(1, 2))
    override suspend fun upload(userId: Long, jpeg: ByteArray): ApiResult<ProfileResponse> { writesToOptional++; error("not called") }
    override suspend fun deletePhoto(userId: Long): ApiResult<Unit> { writesToOptional++; error("not called") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun vm(server: EditServer, session: TestSession = TestSession(OnboardingStatus.READY), dev: Boolean = false) =
        ProfileEditViewModel(server, server, session, DevelopmentProfileAccess(dev)).also { store.put("edit", it); it.load() }

    // 프로필 보기 사진: 다시 받을 때 같은 사진이면 그대로, 못 받으면 실패(옛 사진을 남기지 않음), 사진을 지우면 비움
    @Test fun overviewPhotoKeepsSameBytesAndClearsOnFailureOrDeletion() = runTest(dispatcher) {
        val server = EditServer(); val m = vm(server); runCurrent()
        val first = m.state.value.photo; assertNotNull(first); assertFalse(m.state.value.photoFailed)
        m.load(); runCurrent()
        assertSame(first, m.state.value.photo)
        server.photoFailure = true; m.load(); runCurrent()
        assertNull(m.state.value.photo); assertTrue(m.state.value.photoFailed)
        server.photoFailure = false; server.data = server.data.copy(hasProfilePhoto = false); m.load(); runCurrent()
        assertNull(m.state.value.photo); assertFalse(m.state.value.photoFailed)
    }

    // 연락 이메일: 있으면 보이고, 없거나 실패하면 숨긴다(프로필은 그대로 불러온다)
    @Test fun contactEmailShownWhenPresentHiddenWhenMissingOrFailed() = runTest(dispatcher) {
        val server = EditServer(); val m = vm(server); runCurrent()
        assertEquals("me@example.com", m.state.value.contactEmail)
        server.onEmail = { ApiResult.Ok(ContactEmailResponse(null)) }; m.load(); runCurrent()
        assertNull(m.state.value.contactEmail)
        server.onEmail = { ApiResult.Ok(ContactEmailResponse(" ")) }; m.load(); runCurrent()
        assertNull(m.state.value.contactEmail)
    }
    @Test fun profileStillLoadsWhenContactEmailFails() = runTest(dispatcher) {
        val server = EditServer(); server.onEmail = { ApiResult.Fail(AppError.Offline) }
        val m = vm(server); runCurrent()
        assertNotNull(m.state.value.editor); assertNull(m.state.value.error); assertNull(m.state.value.contactEmail)
        assertFalse(m.state.value.busy)
    }
    @Test fun otherAccountsLateContactEmailIsDropped() = runTest(dispatcher) {
        val server = EditServer(); val late = CompletableDeferred<Unit>()
        server.onEmail = { withContext(NonCancellable) { late.await() }; ApiResult.Ok(ContactEmailResponse("old@example.com")) }
        val session = TestSession(OnboardingStatus.READY); val m = vm(server, session); runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.READY, false); runCurrent()
        late.complete(Unit); runCurrent()
        assertNull(m.state.value.contactEmail)
    }
    @Test fun contactEmailResponseToleratesMissingAndUnknownKeys() {
        assertNull(ApiJson.decodeFromString<ContactEmailResponse>("{}").email)
        assertEquals("a@b.com", ApiJson.decodeFromString<ContactEmailResponse>("""{"email":"a@b.com","extra":1}""").email)
    }

    // 완료 이동은 화면이 다시 붙을 때까지 보관하며, 재개 조회가 저장을 취소하지 않아야 한다.
    @Test fun saveCompletionSurvivesReattachmentAndIsConsumedOnce() = runTest(dispatcher) {
        val server = EditServer(); val pending = CompletableDeferred<Unit>()
        server.onPatch = { pending.await(); ApiResult.Ok(server.apply(it)) }
        val m = vm(server); runCurrent()
        m.change(m.state.value.editor!!.draft.copy(nickname = "New Name")); m.save(); runCurrent()
        m.load(); runCurrent(); assertTrue(m.state.value.busy); assertFalse(m.takeSaveCompletion())
        pending.complete(Unit); runCurrent()
        assertTrue(m.takeSaveCompletion()); assertFalse(m.takeSaveCompletion())
        assertEquals(1, server.patches.size); assertEquals("New Name", m.state.value.profile?.nickname)
    }
    @Test fun unknownResultRemainsUnknownAfterExplicitExitAndFailedRead() = runTest(dispatcher) {
        val server = EditServer(); server.onPatch = { ApiResult.Fail(AppError.Offline) }
        val m = vm(server); runCurrent(); m.change(m.state.value.editor!!.draft.copy(nickname = "New Name"))
        m.save(); runCurrent(); m.leave()
        server.onGet = { ApiResult.Fail(AppError.Offline) }; m.load(); runCurrent()
        assertTrue(m.state.value.recoveryRequired); assertFalse(m.takeSaveCompletion())
        server.onGet = null; m.load(); runCurrent(); assertFalse(m.state.value.recoveryRequired)
        assertEquals(1, server.patches.size)
    }
    @Test fun changedBasicFieldsOnlyArePatchedWithoutLockedOrOptionalValues() = runTest(dispatcher) {
        val server = EditServer(); val m = vm(server); runCurrent()
        val original = m.state.value.editor!!; m.change(original.draft.copy(smoking = ProfileSmoking.Electronic))
        var exited = false; m.save { exited = true }; runCurrent()
        assertTrue(exited); assertEquals(setOf("smokingStatus"), server.patches.single().keys)
        assertEquals(JsonPrimitive("E_CIGARETTE"), server.patches.single()["smokingStatus"])
        assertTrue(server.nicknameQueries.isEmpty()); assertEquals(0, server.writesToOptional)
        assertEquals("2000-10-10", server.data.birthDate); assertEquals("existing", server.data.introduction)
        assertTrue(m.state.value.savedNotice); assertFalse(m.state.value.editor!!.dirty)
    }
    @Test fun normalizationAndAvailabilityCheckHappenBeforeNicknamePatch() = runTest(dispatcher) {
        val server = EditServer(); val m = vm(server); runCurrent()
        m.change(m.state.value.editor!!.draft.copy(nickname = "  가을 Ab  ")); m.save {}; runCurrent()
        assertEquals(listOf("가을 Ab"), server.nicknameQueries)
        assertEquals(JsonPrimitive("가을 Ab"), server.patches.single()["nickname"])
        assertEquals("가을 Ab", m.state.value.editor!!.draft.nickname)
    }
    @Test fun unchangedNormalizedNameDoesNotCheckOrPatch() = runTest(dispatcher) {
        val server = EditServer(); val m = vm(server); runCurrent(); var done = false
        m.change(m.state.value.editor!!.draft.copy(nickname = "  Old Name  ")); m.save { done = true }; runCurrent()
        assertTrue(done); assertTrue(server.patches.isEmpty()); assertTrue(server.nicknameQueries.isEmpty())
        assertFalse(m.state.value.editor!!.dirty)
    }
    @Test fun duplicateNameAndSaveTimeRaceKeepDraftWithoutNavigation() = runTest(dispatcher) {
        val server = EditServer(); server.available = false; val m = vm(server); runCurrent()
        m.change(m.state.value.editor!!.draft.copy(nickname = "Duplicate")); var navigated = false; m.save { navigated = true }; runCurrent()
        assertTrue(server.patches.isEmpty()); assertFalse(navigated)
        assertEquals(ProfileEditProblem.DuplicateNickname, m.state.value.problem)
        server.available = true; server.onPatch = { ApiResult.Fail(AppError.Server(409, "NICKNAME_DUPLICATED", "duplicate")) }
        m.save { navigated = true }; runCurrent()
        assertFalse(navigated); assertEquals("Duplicate", m.state.value.editor!!.draft.nickname)
        assertEquals("Old Name", m.state.value.editor!!.saved.nickname)
        assertEquals(ProfileEditProblem.DuplicateNickname, m.state.value.problem)
    }
    @Test fun inFlightSaveLocksDraftAndDoubleTapAndBack() = runTest(dispatcher) {
        val server = EditServer(); val wait = CompletableDeferred<Unit>(); server.onPatch = { wait.await(); ApiResult.Ok(server.apply(it)) }
        val m = vm(server); runCurrent(); m.change(m.state.value.editor!!.draft.copy(nickname = "New Name"))
        var done = 0; var back = false; m.save { done++ }; m.save { done++ }; runCurrent()
        m.change(m.state.value.editor!!.draft.copy(nickname = "Ignored")); m.requestBack { back = true }
        assertEquals("New Name", m.state.value.editor!!.draft.nickname); assertFalse(back); assertEquals(1, server.patches.size)
        wait.complete(Unit); runCurrent(); assertEquals(1, done)
    }
    @Test fun lostAppliedResponseIsRecoveredByReadWithoutDuplicatePatch() = runTest(dispatcher) {
        val server = EditServer(); server.onPatch = { server.apply(it); ApiResult.Fail(AppError.Offline) }
        val m = vm(server); runCurrent(); m.change(m.state.value.editor!!.draft.copy(nickname = "New Name"))
        var done = false; m.save { done = true }; runCurrent()
        assertTrue(m.state.value.recoveryRequired); assertFalse(done)
        m.save { done = true }; runCurrent(); assertEquals(1, server.patches.size)
        m.load(); runCurrent(); assertFalse(m.state.value.recoveryRequired); assertTrue(m.state.value.savedNotice)
        assertFalse(m.state.value.editor!!.dirty); assertEquals(1, server.patches.size)
    }
    @Test fun failedSaveAndFailedRefreshKeepDraftUntilSuccessfulReadThenExplicitRetry() = runTest(dispatcher) {
        val server = EditServer(); server.onPatch = { ApiResult.Fail(AppError.Offline) }
        val m = vm(server); runCurrent(); m.change(m.state.value.editor!!.draft.copy(nickname = "Draft Name")); m.save {}; runCurrent()
        server.onGet = { ApiResult.Fail(AppError.Offline) }; m.load(); runCurrent()
        assertTrue(m.state.value.recoveryRequired); assertEquals("Draft Name", m.state.value.editor!!.draft.nickname)
        server.onGet = null; m.load(); runCurrent(); assertFalse(m.state.value.recoveryRequired)
        assertEquals("Draft Name", m.state.value.editor!!.draft.nickname); assertEquals(1, server.patches.size)
        server.onPatch = null; m.save {}; runCurrent(); assertEquals("Draft Name", m.state.value.profile?.nickname)
    }
    @Test fun optionalReturnRefreshesSnapshotButPreservesTouchedBasicDraft() = runTest(dispatcher) {
        val server = EditServer(); val m = vm(server); runCurrent()
        m.change(m.state.value.editor!!.draft.copy(nickname = "Draft Name", religionConsent = true, introduction = "forged"))
        assertEquals("", m.state.value.editor!!.draft.introduction); assertFalse(m.state.value.editor!!.draft.religionConsent)
        server.data = server.data.copy(introduction = "updated separately", hasProfilePhoto = false, drinkingFrequency = "SPECIAL_OCCASIONS_ONLY")
        m.load(); runCurrent()
        assertEquals("Draft Name", m.state.value.editor!!.draft.nickname)
        assertEquals(ProfileDrinking.SpecialOccasions, m.state.value.editor!!.draft.drinking)
        assertEquals("updated separately", m.state.value.profile?.introduction); assertNull(m.state.value.photo)
        assertFalse(m.state.value.editor!!.hasPhoto)
    }
    @Test fun discardRequiresPromptAndDoesNotSendRequests() = runTest(dispatcher) {
        val server = EditServer(); val m = vm(server); runCurrent(); m.change(m.state.value.editor!!.draft.copy(nickname = "Draft Name"))
        var back = false; m.discard { back = true }; assertFalse(back)
        m.requestBack { back = true }; assertTrue(m.state.value.editor!!.confirmDiscard); assertFalse(back)
        m.keepEditing(); assertFalse(m.state.value.editor!!.confirmDiscard); assertTrue(m.state.value.editor!!.dirty)
        m.requestBack {}; m.discard { back = true }; assertTrue(back); assertFalse(m.state.value.editor!!.dirty)
        assertTrue(server.patches.isEmpty())
    }
    @Test fun lateUncancellableResponseFromOldAccountCannotUpdateOrNavigate() = runTest(dispatcher) {
        val server = EditServer(); val wait = CompletableDeferred<Unit>(); server.onPatch = { fields -> withContext(NonCancellable) { wait.await(); ApiResult.Ok(server.apply(fields)) } }
        val session = TestSession(OnboardingStatus.READY); val m = vm(server, session); runCurrent()
        m.change(m.state.value.editor!!.draft.copy(nickname = "New Name")); var done = false; m.save { done = true }; runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.READY, true); runCurrent(); wait.complete(Unit); runCurrent()
        assertFalse(done); assertNull(m.state.value.editor); assertNull(m.state.value.photo)
    }
    @Test fun wrongUserOrIncompleteReadyProfileIsNotShownAndPhotoFailureDoesNotDisableBasics() = runTest(dispatcher) {
        val server = EditServer(); server.photoFailure = true; val m = vm(server); runCurrent()
        assertTrue(m.state.value.canEdit); assertTrue(m.state.value.editor!!.hasPhoto); assertNull(m.state.value.photo)
        server.data = server.data.copy(userId = 2); m.load(); runCurrent(); assertNull(m.state.value.editor)
        server.data = server.data.copy(userId = 1, completed = false); m.load(); runCurrent(); assertNull(m.state.value.editor)
    }
    @Test fun developmentKeyringEntryDoesNotGrantProductionOnboardingBypass() = runTest(dispatcher) {
        val server = EditServer(); server.data = server.data.copy(completed = false)
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val prod = vm(server, session); runCurrent(); assertNull(prod.state.value.profile)
        val dev = vm(server, session, true); runCurrent(); assertNotNull(dev.state.value.editor)
        dev.change(dev.state.value.editor!!.draft.copy(smoking = ProfileSmoking.Smoker)); dev.save {}; runCurrent()
        assertFalse(server.data.completed); assertEquals(OnboardingStatus.KEYRING_REQUIRED, (session.state.value as Session.SignedIn).status)
    }
}
