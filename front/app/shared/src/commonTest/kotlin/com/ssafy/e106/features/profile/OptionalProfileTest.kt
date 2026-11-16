package com.ssafy.e106.features.profile

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.onboarding.data.*
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private val intro = OptionalConsentType.PROFILE_INTRODUCTION
private val religion = OptionalConsentType.PROFILE_RELIGION
private val photo = OptionalConsentType.PROFILE_PHOTO

/** Test-only server: records writes and can delay, fail, or lose responses. */
internal class OptionalServer : OptionalProfileRepository, ProfileRepository {
    var data = ProfileResponse(1, "Tester", "2000-01-01", "MALE", "NON_SMOKER", "NEVER")
    var records = OptionalConsentType.entries.map { OptionalConsentRecord(it, "v2", false) }
    var docs = records.map { AgreementItem(it.type.name, "v2", it.type.name, false, "https://tingle.test/terms") }
    val changes = mutableListOf<Pair<OptionalConsentType, OptionalConsentRequest>>()
    val patches = mutableListOf<JsonObject>()
    var consentReadFailure: AppError? = null
    var documentFailure: AppError? = null
    var change: (suspend (OptionalConsentType, OptionalConsentRequest) -> ApiResult<OptionalConsentRecord>)? = null
    var patch: (suspend () -> ApiResult<ProfileResponse>)? = null
    var getProfile: (suspend () -> ApiResult<ProfileResponse>)? = null
    var uploaded = 0; var deleted = 0
    fun apply(type: OptionalConsentType, agreed: Boolean, agreedVersion: String = "v2"): OptionalConsentRecord {
        val record = OptionalConsentRecord(type, "v2", agreed, agreedVersion)
        records = records.map { if (it.type == type) record else it }
        if (!agreed) data = when(type) {
            OptionalConsentType.PROFILE_INTRODUCTION -> data.copy(introduction = null)
            OptionalConsentType.PROFILE_RELIGION -> data.copy(religion = null)
            OptionalConsentType.PROFILE_PHOTO -> data.copy(hasProfilePhoto = false)
        }
        return record
    }
    override suspend fun documents() = documentFailure?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(AgreementsResponse(docs))
    override suspend fun consents(userId: Long) = consentReadFailure?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(OptionalConsentsResponse(records))
    override suspend fun consent(userId: Long, type: OptionalConsentType, request: OptionalConsentRequest): ApiResult<OptionalConsentRecord> {
        changes += type to request
        return change?.invoke(type, request) ?: ApiResult.Ok(apply(type, request.agreed, request.version))
    }
    override suspend fun get(userId: Long) = getProfile?.invoke() ?: ApiResult.Ok(data)
    override suspend fun save(userId: Long, fields: JsonObject): ApiResult<ProfileResponse> {
        patches += fields
        patch?.let { return it() }
        data = data.copy(introduction = if ("introduction" in fields) fields["introduction"]!!.jsonPrimitive.contentOrNull else data.introduction,
            religion = if ("religion" in fields) fields["religion"]!!.jsonPrimitive.contentOrNull else data.religion)
        return ApiResult.Ok(data)
    }
    override suspend fun nickname(value: String) = ApiResult.Ok(NicknameAvailability(value, true))
    override suspend fun complete(userId: Long) = ApiResult.Ok(Unit)
    override suspend fun contactEmail(userId: Long) = ApiResult.Ok(ContactEmailResponse(null))
    override suspend fun photo(userId: Long) = ApiResult.Ok(byteArrayOf(1, 2, 3))
    override suspend fun upload(userId: Long, jpeg: ByteArray): ApiResult<ProfileResponse> {
        uploaded++; data = data.copy(hasProfilePhoto = true); return ApiResult.Ok(data)
    }
    override suspend fun deletePhoto(userId: Long): ApiResult<Unit> { deleted++; data = data.copy(hasProfilePhoto = false); return ApiResult.Ok(Unit) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OptionalProfileTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun vm(server: OptionalServer, session: TestSession = TestSession(OnboardingStatus.PROFILE_REQUIRED), development: Boolean = false) =
        OptionalProfileViewModel(server, server, session, development).also { store.put("optional", it); it.load() }

    // 화면 재생성 중에는 UI 콜백 없이도 결과가 남고, 새 화면이 한 번만 소비해야 한다.
    @Test fun completedSaveWaitsForNewScreenAndIsConsumedOnce() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(intro, true)
        val pending = CompletableDeferred<Unit>()
        s.patch = { pending.await(); s.data = s.data.copy(introduction = "saved"); ApiResult.Ok(s.data) }
        val m = vm(s); runCurrent(); m.introduction("saved"); m.save(); runCurrent()
        m.load(); runCurrent() // 회전 후 ON_RESUME이 호출되어도 진행 중인 쓰기를 덮어쓰지 않는다.
        assertTrue(m.state.value.busy); assertNull(m.takeCompletion())
        pending.complete(Unit); runCurrent()
        assertEquals("saved", m.takeCompletion()?.introduction)
        assertNull(m.takeCompletion()); assertEquals(1, s.patches.size)
    }
    @Test fun forbiddenStagesNeverReadOrWriteOptionalProfile() = runTest(dispatcher) {
        for (status in listOf(OnboardingStatus.KEYRING_REQUIRED, OnboardingStatus.AGREEMENT_REQUIRED)) {
            val s = OptionalServer(); var reads = 0
            s.getProfile = { reads++; ApiResult.Ok(s.data) }
            val m = vm(s, TestSession(status)); runCurrent()
            m.decide(photo, true); m.confirmDecision(); m.save(); m.skip(); runCurrent()
            assertEquals(0, reads); assertTrue(s.changes.isEmpty()); assertTrue(s.patches.isEmpty())
            assertNull(m.takeCompletion())
        }
    }
    @Test fun developmentKeyringAccessMustBeExplicitlyEnabled() = runTest(dispatcher) {
        val s = OptionalServer(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val m = OptionalProfileViewModel(s, s, session, true, DevelopmentProfileAccess(true))
        store.put("development", m); m.load(); runCurrent(); assertTrue(m.state.value.loaded)
    }
    @Test fun unknownResultSurvivesExplicitExitUntilServerRead() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(intro, true)
        s.patch = { ApiResult.Fail(AppError.Offline) }
        val m = vm(s); runCurrent(); m.introduction("draft")
        s.getProfile = { ApiResult.Fail(AppError.Offline) }; m.save(); runCurrent()
        assertTrue(m.state.value.recoveryRequired)
        m.leave(); assertTrue(m.state.value.recoveryRequired); assertNull(m.takeCompletion())
        s.getProfile = null; m.load(); runCurrent(); assertFalse(m.state.value.recoveryRequired)
        assertEquals(1, s.patches.size)
    }
    @Test fun missingConsentApiIsUnknownAndCanSkipWithoutWritingFalse() = runTest(dispatcher) {
        val s = OptionalServer(); s.consentReadFailure = AppError.Server(404, "NOT_FOUND", "missing")
        val m = vm(s); runCurrent(); var result: ProfileResponse? = null
        assertFalse(m.state.value.loaded); assertFalse(m.state.value.editable)
        m.decide(intro, true); m.confirmDecision(); m.save { result = it }; runCurrent()
        assertTrue(s.changes.isEmpty()); assertTrue(s.patches.isEmpty()); assertNull(result)
        m.skip { result = it }; assertEquals(s.data, result)
    }
    @Test fun confirmationRequiredAndGrantMustFinishBeforeInputsAndSave() = runTest(dispatcher) {
        val s = OptionalServer(); val pending = CompletableDeferred<ApiResult<OptionalConsentRecord>>()
        s.change = { _, _ -> pending.await() }; val m = vm(s); runCurrent()
        m.decide(intro, true); assertNotNull(m.state.value.decision); assertTrue(s.changes.isEmpty())
        m.dismissDecision(); m.confirmDecision(); runCurrent(); assertTrue(s.changes.isEmpty())
        m.decide(intro, true); m.confirmDecision(); m.confirmDecision(); runCurrent()
        m.introduction("premature"); m.save {}; assertTrue(s.patches.isEmpty()); assertEquals("", m.state.value.details.introduction)
        pending.complete(ApiResult.Ok(s.apply(intro, true))); runCurrent()
        m.introduction("hello"); var saved: ProfileResponse? = null; m.save { saved = it }; runCurrent()
        assertEquals(1, s.changes.size); assertEquals(setOf("introduction"), s.patches.single().keys)
        assertEquals("hello", saved?.introduction)
    }
    @Test fun olderAcceptedVersionRemainsValidButNewConsentRequiresCurrentPublishedDocument() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(intro, true, "v1"); s.data = s.data.copy(introduction = "existing")
        s.docs = s.docs.map { it.copy(contentUrl = null) }; val m = vm(s); runCurrent()
        assertTrue(m.state.value.details.introductionConsent); assertEquals("existing", m.state.value.details.introduction)
        assertFalse(m.state.value.canDecide(photo, true)); assertTrue(m.state.value.canDecide(intro, false))
        m.introduction("edited"); m.save {}; runCurrent(); assertEquals(1, s.patches.size)
    }
    @Test fun developmentExceptionStillNeedsExactVersionAndDoesNotSynthesizeConsent() = runTest(dispatcher) {
        val s = OptionalServer(); s.docs = s.docs.map { it.copy(contentUrl = null) }
        val m = vm(s, development = true); runCurrent()
        assertFalse(m.state.value.details.photoConsent); assertTrue(m.state.value.canDecide(photo, true))
        s.docs = s.docs.map { it.copy(version = "v3") }; m.load(); runCurrent()
        assertFalse(m.state.value.canDecide(photo, true)); assertTrue(s.changes.isEmpty())
    }
    @Test fun withdrawalDeletesOnlyConfirmedFieldAndReconsentDoesNotRestoreIt() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(intro, true); s.apply(religion, true); s.apply(photo, true)
        s.data = s.data.copy(introduction = "stored", religion = "NONE", hasProfilePhoto = true)
        val m = vm(s); runCurrent(); m.introduction("draft")
        m.decide(intro, false); m.dismissDecision(); assertEquals("stored", s.data.introduction)
        m.decide(intro, false); m.confirmDecision(); runCurrent()
        assertNull(s.data.introduction); assertEquals("", m.state.value.details.introduction)
        assertEquals("NONE", s.data.religion); assertTrue(s.data.hasProfilePhoto)
        m.decide(intro, true); m.confirmDecision(); runCurrent(); assertEquals("", m.state.value.details.introduction)
    }
    @Test fun successfulWithdrawalClearsLocalPhotoEvenWhenFollowupReadFails() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(photo, true); s.data = s.data.copy(hasProfilePhoto = true)
        val m = vm(s); runCurrent(); assertNotNull(m.state.value.photo)
        s.change = { type, req -> val record = s.apply(type, req.agreed); s.getProfile = { ApiResult.Fail(AppError.Offline) }; ApiResult.Ok(record) }
        m.decide(photo, false); m.confirmDecision(); runCurrent()
        assertNull(m.state.value.photo); assertFalse(m.state.value.details.hasPhoto); assertTrue(m.state.value.recoveryRequired)
    }
    @Test fun versionConflictReloadsAndNeverAutomaticallyConsentsToNewVersion() = runTest(dispatcher) {
        val s = OptionalServer(); val m = vm(s); runCurrent()
        s.change = { _, _ ->
            s.records = s.records.map { it.copy(version = "v3") }; s.docs = s.docs.map { it.copy(version = "v3") }
            ApiResult.Fail(AppError.Server(409, "AGREEMENT_VERSION_OUTDATED", "changed"))
        }
        m.decide(intro, true); m.confirmDecision(); runCurrent()
        assertEquals(1, s.changes.size); assertFalse(m.state.value.details.introductionConsent)
        assertEquals("v3", m.state.value.consent(intro)?.version)
        assertEquals(OptionalProfileNotice.VersionChanged, m.state.value.notice)
        m.confirmDecision(); runCurrent(); assertEquals(1, s.changes.size)
    }
    @Test fun lostConsentResponseDoesNotAllowOppositeWriteUntilReadConfirmsDesiredState() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(intro, true); val m = vm(s); runCurrent()
        s.change = { _, _ -> ApiResult.Fail(AppError.Offline) }
        m.decide(intro, false); m.confirmDecision(); runCurrent(); m.load(); runCurrent()
        assertTrue(m.state.value.recoveryRequired); assertFalse(m.state.value.canContinue)
        m.decide(intro, true); assertNull(m.state.value.decision); assertEquals(1, s.changes.size)
        s.change = null; m.retryUnresolvedConsent(); runCurrent()
        assertEquals(s.changes[0], s.changes[1]); assertFalse(m.state.value.recoveryRequired)
        assertFalse(m.state.value.details.introductionConsent)
    }
    @Test fun lostButAppliedConsentIsRecoveredByReadWithoutAnotherPut() = runTest(dispatcher) {
        val s = OptionalServer(); val m = vm(s); runCurrent()
        s.change = { type, request -> s.apply(type, request.agreed); ApiResult.Fail(AppError.Offline) }
        m.decide(intro, true); m.confirmDecision(); runCurrent(); assertTrue(m.state.value.recoveryRequired)
        m.load(); runCurrent(); assertFalse(m.state.value.recoveryRequired)
        assertTrue(m.state.value.details.introductionConsent); assertEquals(1, s.changes.size)
    }
    @Test fun failedPatchKeepsConsentAndDraftAndRequeryDoesNotUndoConsent() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(intro, true); val m = vm(s); runCurrent(); m.introduction("draft")
        s.patch = { ApiResult.Fail(AppError.Offline) }; var navigated = false; m.save { navigated = true }; runCurrent()
        assertFalse(navigated); assertTrue(m.state.value.details.introductionConsent)
        assertEquals("draft", m.state.value.details.introduction); assertTrue(s.changes.isEmpty())
        m.load(); runCurrent(); assertEquals("draft", m.state.value.details.introduction)
    }
    @Test fun blankIntroUsesNullAndUndisclosedRequiresReligionConsent() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(intro, true); val m = vm(s); runCurrent()
        m.introduction(" \n "); m.religion(ProfileReligion.Private); m.save {}; runCurrent()
        assertEquals(JsonNull, s.patches.single()["introduction"]); assertFalse("religion" in s.patches.single())
        assertFalse(ProfileDetailsState(religion = ProfileReligion.Private).canContinue)
        m.decide(religion, true); m.confirmDecision(); runCurrent(); m.religion(ProfileReligion.Private); m.save {}; runCurrent()
        assertEquals(JsonPrimitive("UNDISCLOSED"), s.patches.last()["religion"])
    }
    @Test fun photoUploadNeedsSavedConsentAndPhotoDeleteKeepsConsent() = runTest(dispatcher) {
        val s = OptionalServer(); val m = vm(s); runCurrent(); m.upload(byteArrayOf(1)); runCurrent(); assertEquals(0, s.uploaded)
        m.decide(photo, true); m.confirmDecision(); runCurrent(); m.upload(byteArrayOf(1)); runCurrent()
        assertEquals(1, s.uploaded); assertNotNull(m.state.value.photo)
        m.requestDeletePhoto(); assertEquals(0, s.deleted); m.deletePhoto(); runCurrent()
        assertEquals(1, s.deleted); assertTrue(m.state.value.details.photoConsent); assertNull(m.state.value.photo)
    }
    @Test fun accountChangeDiscardsLateResponseAndDoesNotNavigate() = runTest(dispatcher) {
        val s = OptionalServer(); s.apply(intro, true)
        val session = TestSession(OnboardingStatus.PROFILE_REQUIRED); val m = vm(s, session); runCurrent()
        val pending = CompletableDeferred<ApiResult<ProfileResponse>>()
        s.patch = { withContext(NonCancellable) { pending.await() } }
        m.introduction("private"); var navigated = false; m.save { navigated = true }; runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.PROFILE_REQUIRED, false); runCurrent()
        pending.complete(ApiResult.Ok(s.data)); runCurrent()
        assertFalse(navigated); assertNull(m.state.value.profile); assertEquals("", m.state.value.details.introduction)
    }
    @Test fun missingDuplicateOrMalformedConsentCannotEnableInputs() = runTest(dispatcher) {
        val s = OptionalServer(); s.records = listOf(s.records[0], s.records[0], s.records[2]); val m = vm(s); runCurrent()
        assertFalse(m.state.value.loaded); assertFalse(m.state.value.editable); assertIs<AppError.Unknown>(m.state.value.error)
    }
}
