package com.ssafy.e106.features.settings

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.preferences.ui.*
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 테스트 전용 서버. 제품 앱에는 등록하지 않는다. */
private class SettingsServer : MatchingSettingsRepository {
    var doc = SettingsDocument(ConflictValues.Age(20, 30), "\"1\"")
    var getError: AppError? = null
    var saveError: AppError? = null
    var pending: CompletableDeferred<ApiResult<SettingsDocument>>? = null
    var writes = 0
    var lastTag: String? = null
    override suspend fun get(userId: Long, kind: SettingsKind): ApiResult<SettingsDocument> = getError?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(doc)
    override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String): ApiResult<SettingsDocument> {
        writes++; lastTag = etag
        pending?.let { return withContext(NonCancellable) { it.await() } }
        saveError?.let { return ApiResult.Fail(it) }
        doc = doc.copy(values = values, etag = "\"2\"")
        return ApiResult.Ok(doc)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun before() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun after() { models.clear(); Dispatchers.resetMain() }
    private fun vm(server: SettingsServer, session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED)) =
        MatchingSettingsViewModel(server, session, SettingsAccess(true)).also { models.put("settings", it) }
    private fun MatchingSettingsViewModel.change() = editAge(state.value.age.copy(mode = AgePreferenceMode.Any))

    @Test fun loadDoesNotWriteAndSavingDisablesDuplicatePut() = runTest(dispatcher) {
        val server = SettingsServer(); val m = vm(server); m.load(); runCurrent()
        assertEquals(0, server.writes); assertFalse(m.state.value.canSave)
        m.change(); m.save(); m.save(); runCurrent()
        assertEquals(1, server.writes); assertTrue(m.state.value.saved); assertFalse(m.state.value.canSave)
        assertEquals("\"1\"", server.lastTag)
    }
    @Test fun conflictRequiresGetAndExplicitReviewedChoice() = runTest(dispatcher) {
        val server = SettingsServer(); val m = vm(server); m.load(); runCurrent(); m.change()
        server.saveError = AppError.Server(412, "PRECONDITION_FAILED", "changed")
        m.save(); runCurrent(); assertEquals(ConflictStage.Changed, m.state.value.conflict!!.stage)
        m.save(); runCurrent(); assertEquals(1, server.writes)
        server.doc = server.doc.copy(etag = "\"9\""); m.load(); runCurrent()
        assertEquals(1, server.writes); assertEquals(ConflictStage.Review, m.state.value.conflict!!.stage)
        server.saveError = null; m.conflictAction(m.state.value.conflict!!.saveAction!!); runCurrent()
        assertEquals("\"9\"", server.lastTag); assertEquals(2, server.writes)
    }
    @Test fun lostReplyThenEqualGetDoesNotRepeatPut() = runTest(dispatcher) {
        val server = SettingsServer(); val m = vm(server); m.load(); runCurrent(); m.change()
        server.saveError = AppError.Offline; m.save(); runCurrent()
        assertEquals(ConflictStage.OutcomeUnknown, m.state.value.conflict!!.stage)
        server.doc = SettingsDocument(ConflictValues.Age(any = true), "\"2\"")
        m.load(); runCurrent(); assertNull(m.state.value.conflict!!.saveAction)
        m.conflictAction(ConflictAction.UseLatest(m.state.value.conflict!!.reviewed!!))
        assertNull(m.state.value.conflict); assertEquals(1, server.writes); assertFalse(m.state.value.saved)
    }
    @Test fun missingEtagBlocksSavingUntilReload() = runTest(dispatcher) {
        val server = SettingsServer().also { it.doc = it.doc.copy(etag = null) }; val m = vm(server)
        m.load(); runCurrent(); m.change(); m.save(); runCurrent()
        assertEquals(ConflictStage.VersionMissing, m.state.value.conflict!!.stage); assertEquals(0, server.writes)
    }
    @Test fun onboardingDenialDoesNotInventSettingsOrChangeSession() = runTest(dispatcher) {
        val server = SettingsServer().also { it.getError = AppError.OnboardingRequired("blocked") }
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val m = vm(server, session)
        m.load(); runCurrent(); m.save(); assertNull(m.state.value.document); assertFalse(m.state.value.canSave)
        assertEquals(OnboardingStatus.KEYRING_REQUIRED, (session.state.value as Session.SignedIn).status)
    }
    @Test fun logoutDiscardsDelayedSaveAndPersonalDraft() = runTest(dispatcher) {
        val pending = CompletableDeferred<ApiResult<SettingsDocument>>()
        val server = SettingsServer().also { it.pending = pending }; val session = TestSession(OnboardingStatus.READY)
        val m = vm(server, session); m.load(); runCurrent(); m.change(); m.save(); runCurrent()
        session.state.value = Session.SignedOut; runCurrent()
        pending.complete(ApiResult.Ok(SettingsDocument(ConflictValues.Age(any = true), "\"2\""))); runCurrent()
        assertNull(m.state.value.document); assertNull(m.state.value.owner); assertFalse(m.state.value.saved)
    }
    @Test fun backgroundDuringPutKeepsRequestAndAcceptsItsResult() = runTest(dispatcher) {
        val pending = CompletableDeferred<ApiResult<SettingsDocument>>()
        val server = SettingsServer().also { it.pending = pending }; val m = vm(server)
        m.load(); runCurrent(); m.change(); m.save(); runCurrent(); m.background()
        assertTrue(m.state.value.saving); assertNull(m.state.value.conflict)
        pending.complete(ApiResult.Ok(SettingsDocument(ConflictValues.Age(any = true), "\"2\""))); runCurrent()
        assertTrue(m.state.value.saved); assertEquals(1, server.writes); assertNull(m.state.value.conflict)
    }
    @Test fun productionEntryDoesNotAllowKeyringSkip() {
        val s = Session.SignedIn(1, OnboardingStatus.KEYRING_REQUIRED, false)
        assertFalse(SettingsAccess().allowed(s)); assertTrue(SettingsAccess(true).allowed(s))
    }
}
