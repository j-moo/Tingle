package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.time.Instant
import kotlin.test.*

private val challengeValue = KeyringChallenge("11111111-1111-4111-8111-111111111111", "A".repeat(43),
    "2026-11-04T12:05:00Z", "tingle-device-proof-v1")
private fun proofBody(c: KeyringChallenge = challengeValue, serial: String = "XIAO-test") =
    RegisterKeyringBody(c.challengeId, serial, KeyringProof(c.proofProtocolVersion, "key-1", "A".repeat(86)))

/** 실제 기반처럼 계정별 JSON을 읽고, 쓰기 실패/손상을 정상 빈 캐시와 구분하는 시험 저장소. */
private class RegistrationPrefs : AppPrefs {
    val values = mutableMapOf<Pair<Long, String>, String>()
    var fault: PrefRead<Nothing>? = null
    var refuseWrites = false
    var bodyWritten = false
    override fun <T> flow(key: PrefKey<T>): Flow<T?> = flowOf(null)
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        assertTrue(key.durable)
        if (refuseWrites) return
        if (value == null) values.remove(forUser to key.name)
        else {
            val json = ApiJson.encodeToString(key.serializer, value)
            if (json.contains("signature")) bodyWritten = true
            values[forUser to key.name] = json
        }
    }
    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> {
        fault?.let { return it }
        return values[forUser to key.name]?.let { PrefRead.Found(ApiJson.decodeFromString(key.serializer, it)) } ?: PrefRead.Missing
    }
}

private class RegistrationServer : KeyringRegistrationRepository, OwnedKeyringRepository {
    val sent = mutableListOf<Pair<String, RegisterKeyringBody>>()
    val challenges = mutableListOf<String>()
    val receipt = OwnedKeyring(12, "SYNC_PENDING", "0.1.0-registration")
    var items = emptyList<OwnedKeyring>()
    var listError: AppError? = null
    var challengeError: AppError? = null
    var challenge = challengeValue
    var onSend: suspend () -> ApiResult<OwnedKeyring> = { items = listOf(receipt); ApiResult.Ok(receipt) }
    override suspend fun challenge(key: String): ApiResult<KeyringChallenge> {
        challenges += key; return challengeError?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(challenge)
    }
    override suspend fun register(key: String, body: RegisterKeyringBody): ApiResult<OwnedKeyring> {
        sent += key to body; return onSend()
    }
    override suspend fun list(): ApiResult<List<OwnedKeyring>> = listError?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(items)
}

@OptIn(ExperimentalCoroutinesApi::class)
class KeyringRegistrationTest {
    /** 소유 상태 거절은 재부팅 안내로 바꾸지 않고, 준비 기록 정리도 서버 귀속/기존 동기화를 지우지 않는다. */
    @Test fun boundProofShowsOwnershipReasonWithoutRegisteringOrRemovingExistingTarget() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs()
        val sync = KeyringSyncStore(prefs); sync.rememberTarget(1, 4, "XIAO-test")
        val m = model(server, prefs); runCurrent()
        m.start("XIAO-test") { throw BleCommandException("DEVICE_ALREADY_BOUND") }; runCurrent()
        assertEquals(RegistrationIssue.DeviceBound, m.state.value.issue)
        assertNull(m.state.value.pending?.body)
        assertTrue(server.sent.isEmpty()); assertNull(m.state.value.confirmed)
        assertEquals(4L, sync.read(1).single().deviceId)
        // 증명 전에 멈춘 요청은 "이 키링 등록하기"로 다시 시작한다(같은 보드면 같은 challenge 키)
        m.start("XIAO-test") { throw BleCommandException("OWNER_MISMATCH") }; runCurrent()
        assertEquals(RegistrationIssue.DeviceBound, m.state.value.issue)
        m.start("XIAO-test") { throw BleCommandException("RESET_IN_PROGRESS") }; runCurrent()
        assertEquals(RegistrationIssue.ResetPending, m.state.value.issue)
        assertTrue(server.sent.isEmpty())
    }
    /** MR230 재현: 초기화 ACK 유실 → 재등록 시도는 새 challenge/POST 전에 막고 기존 대상도 보존한다. */
    @Test fun pendingResetBlocksRegistrationUntilSameRunIsRecovered() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs(); val resets = KeyringResetStore(prefs)
        val sync = KeyringSyncStore(prefs); sync.rememberTarget(1, resetRun.deviceId, resetSerial)
        val ticket = ResetTicket(resetRun.deviceId, resetSerial, resetId, ResetStartBody(false, resetChallenge), resetRun, resetProof)
        resets.insert(1, ticket)
        val m = model(server, prefs); runCurrent()
        m.start(resetSerial) { error("초기화 결과 확인 전에는 BLE도 금지") }; runCurrent()
        assertEquals(RegistrationIssue.ResetPending, m.state.value.issue)
        assertTrue(server.challenges.isEmpty()); assertTrue(server.sent.isEmpty())
        assertEquals(resetRun.deviceId, sync.read(1).single().deviceId)
        // 실제 복구와 같은 순서로 옛 대상 정리 후 완료를 기록해야 재등록을 허용한다.
        sync.afterReset(1, resetDone(), resetSerial); resets.save(1, ticket.copy(run = resetDone()))
        m.start(resetSerial) { proofBody(it) }; runCurrent()
        assertEquals(1, server.sent.size); assertEquals(server.receipt, m.state.value.confirmed)
        assertEquals(server.receipt.deviceId, sync.read(1).single().deviceId)
    }
    @Test fun restoredRegistrationCannotBypassPendingOrUnreadableReset() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs()
        KeyringRegistrationStore(prefs).save(1, PendingKeyringRegistration(1, resetSerial, resetId, resetGeneration, proofBody()))
        val m = model(server, prefs); runCurrent()
        KeyringResetStore(prefs).insert(1, ResetTicket(resetRun.deviceId, resetSerial, resetId, ResetStartBody(false, resetChallenge)))
        m.retry { error("저장된 등록 본문을 재전송하지 않는다") }; runCurrent()
        assertEquals(RegistrationIssue.ResetPending, m.state.value.issue); assertTrue(server.sent.isEmpty())
        prefs.fault = PrefRead.Corrupt
        m.retry { error("손상 기록을 허용으로 바꾸지 않는다") }; runCurrent()
        assertEquals(RegistrationIssue.Storage, m.state.value.issue); assertTrue(server.sent.isEmpty())
    }
    /** 앱 재생성 뒤에도 v2 epoch·UNOWNED·멱등키를 보존하며 BLE 증명을 다시 발급하지 않는다. */
    @Test fun v2LostResponseRestoresSameBodyAndRejectsVersionDowngrade() = runTest(dispatcher) {
        val server = RegistrationServer().apply { challenge = challengeValue.copy(proofProtocolVersion = "tingle-device-proof-v2") }
        val prefs = RegistrationPrefs(); val m = model(server, prefs); runCurrent()
        m.start("XIAO-test") { proofBody(challengeValue) }; runCurrent()
        assertTrue(server.sent.isEmpty()); assertEquals(RegistrationIssue.InvalidResponse, m.state.value.issue)
        server.onSend = { ApiResult.Fail(AppError.Offline) }
        m.retry { proofBody(it).copy(ownershipEpoch = 9007199254740993L, ownerState = "UNOWNED") }; runCurrent()
        val sent = server.sent.single(); models.clear()
        val restored = model(server, prefs); runCurrent()
        server.onSend = { server.items = listOf(server.receipt); ApiResult.Ok(server.receipt) }
        restored.retry { error("Must replay persisted v2 body") }; runCurrent()
        assertEquals(sent, server.sent.last()); assertEquals(server.receipt, restored.state.value.confirmed)
    }
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() { models.clear(); Dispatchers.resetMain() }
    private fun model(server: RegistrationServer, prefs: RegistrationPrefs = RegistrationPrefs(),
        session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED), name: String = "registration",
        now: () -> Instant = { Instant.parse("2026-11-04T12:00:00Z") }) =
        KeyringRegistrationViewModel(server, server, KeyringRegistrationStore(prefs), session, KeyringSyncStore(prefs), KeyringResetStore(prefs),
            now).also { models.put(name, it) }

    @Test fun readyScreenNeverRegistersUntilExplicitStartAndChecksOwnership() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs(); val m = model(server, prefs)
        runCurrent(); assertTrue(m.state.value.loaded); assertTrue(server.sent.isEmpty())
        server.onSend = { assertTrue(prefs.bodyWritten); server.items = listOf(server.receipt); ApiResult.Ok(server.receipt) }
        m.start("XIAO-test") { proofBody(it) }; m.start("XIAO-test") { error("Double click") }; runCurrent()
        assertEquals(1, server.sent.size); assertEquals(server.receipt, m.state.value.confirmed)
        assertNotEquals(server.challenges.single(), server.sent.single().first)
        assertEquals(PrefRead.Found(null), KeyringRegistrationStore(prefs).read(1))
    }
    @Test fun additionalKeyringKeepsExistingOwnershipAndCanRetryProof() = runTest(dispatcher) {
        val previous = OwnedKeyring(2, "REGISTERED", "test")
        val server = RegistrationServer().apply {
            items = listOf(previous)
            onSend = { items = items + receipt; ApiResult.Ok(receipt) }
        }
        val m = model(server); runCurrent()
        // 기존 키링이 있어도 새 보드 증명을 요청하며, BLE 실패 후 같은 challenge로 재시도한다.
        m.start("XIAO-test") { error("BLE disconnected") }; runCurrent()
        assertEquals(RegistrationIssue.Device, m.state.value.issue)
        assertEquals(listOf(previous), server.items)
        m.retry { proofBody(it) }; runCurrent()
        assertEquals(1, server.sent.size)
        assertEquals(1, server.challenges.distinct().size)
        assertEquals(listOf(previous, server.receipt), server.items)
        assertEquals(server.receipt, m.state.value.confirmed)
    }
    @Test fun responseLossRestoresSameKeyAndBodyWithoutBoardOrNewChallenge() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs(); val m = model(server, prefs); runCurrent()
        server.onSend = { server.items = listOf(server.receipt); ApiResult.Fail(AppError.Offline) }
        m.start("XIAO-test") { proofBody(it) }; runCurrent(); val original = server.sent.single()
        models.clear()
        val restored = model(server, prefs); runCurrent()
        assertNotNull(restored.state.value.pending?.body)
        server.onSend = { ApiResult.Ok(server.receipt) }
        restored.retry { error("Stored proof must be reused") }; runCurrent()
        assertEquals(original, server.sent.last()); assertEquals(1, server.challenges.size)
        assertEquals(server.receipt, restored.state.value.confirmed)
    }
    @Test fun revisitingAfterSuccessAllowsAnotherKeyringWithNewRequestKeys() = runTest(dispatcher) {
        val server = RegistrationServer(); val m = model(server); runCurrent()
        m.start("XIAO-first") { proofBody(it, "XIAO-first") }; runCurrent()
        val first = server.sent.single()
        assertNotNull(m.state.value.confirmed)
        // 화면 함수가 같은 Activity ViewModel을 재사용해도 이전 완료 UI가 새 등록을 막지 않아야 한다.
        m.pause(); m.restore(); runCurrent()
        assertNull(m.state.value.confirmed); assertNull(m.state.value.pending)
        val second = OwnedKeyring(13, "SYNC_PENDING", "0.1.0-registration")
        server.onSend = { server.items = server.items + second; ApiResult.Ok(second) }
        m.start("XIAO-second") { proofBody(it, "XIAO-second") }; runCurrent()
        assertEquals(2, server.sent.size); assertNotEquals(first.first, server.sent.last().first)
        assertEquals(2, server.challenges.distinct().size)
        assertEquals(listOf(server.receipt, second), server.items)
        assertEquals(second, m.state.value.confirmed)
    }
    @Test fun receiptWithoutOwnedListEntryIsNotSuccessAndOnlyRechecksList() = runTest(dispatcher) {
        // 다른 기존 키링이 조회돼도 이번 응답의 deviceId가 없으면 성공으로 표시하면 안 된다.
        val server = RegistrationServer().apply {
            items = listOf(OwnedKeyring(2, "REGISTERED", "test"))
            onSend = { ApiResult.Ok(receipt) }
        }; val m = model(server); runCurrent()
        m.start("XIAO-test") { proofBody(it) }; runCurrent()
        assertNull(m.state.value.confirmed); assertEquals(RegistrationIssue.NotInOwnedList, m.state.value.issue)
        server.items = listOf(server.receipt); m.retry { error("No BLE for receipt recovery") }; runCurrent()
        assertEquals(1, server.sent.size); assertEquals(server.receipt, m.state.value.confirmed)
    }
    @Test fun corruptAndUnreadableRecoveryNeverOverwritesOrSends() = runTest(dispatcher) {
        for (fault in listOf(PrefRead.Corrupt, PrefRead.Unreadable)) {
            val prefs = RegistrationPrefs().apply { this.fault = fault }; val server = RegistrationServer()
            val m = model(server, prefs); runCurrent(); m.start("XIAO-test") { proofBody(it) }; runCurrent()
            assertFalse(m.state.value.loaded); assertTrue(server.sent.isEmpty()); assertTrue(prefs.values.isEmpty())
        }
    }
    @Test fun persistenceFailurePreventsNetworkMutation() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs().apply { refuseWrites = true }
        val m = model(server, prefs); runCurrent(); m.start("XIAO-test") { proofBody(it) }; runCurrent()
        assertEquals(RegistrationIssue.Storage, m.state.value.issue); assertTrue(server.challenges.isEmpty()); assertTrue(server.sent.isEmpty())
    }
    @Test fun wrongDeviceProofNeverReachesServerAndExpiredChallengeNeedsNewPreparation() = runTest(dispatcher) {
        val server = RegistrationServer(); val m = model(server); runCurrent()
        m.start("XIAO-test") { proofBody(it, "XIAO-other") }; runCurrent()
        assertEquals(RegistrationIssue.InvalidResponse, m.state.value.issue); assertTrue(server.sent.isEmpty())
        server.challenge = challengeValue.copy(expiresAt = "2026-11-04T11:59:59Z")
        m.start("XIAO-test") { error("Expired challenge") }; runCurrent()
        assertEquals(RegistrationIssue.ExpiredChallenge, m.state.value.issue); assertTrue(server.sent.isEmpty())
        // 만료된 증명 전 요청은 지워져 같은 보드도 새 키로 다시 등록할 수 있다(같은 만료 challenge에 갇히지 않는다)
        assertNull(m.state.value.pending)
        server.challenge = challengeValue
        m.start("XIAO-test") { proofBody(it) }; runCurrent()
        assertEquals(2, server.challenges.distinct().size); assertEquals(server.receipt, m.state.value.confirmed)
    }
    @Test fun challengeExpiringDuringProofClearsRequestAndNeverPosts() = runTest(dispatcher) {
        val server = RegistrationServer(); var now = Instant.parse("2026-11-04T12:00:00Z")
        val m = model(server, now = { now }); runCurrent()
        // 증명을 받는 사이 5분이 지나면 보내지 않고 기록을 지운다
        m.start("XIAO-test") { now = Instant.parse("2026-11-04T12:06:00Z"); proofBody(it) }; runCurrent()
        assertEquals(RegistrationIssue.ExpiredChallenge, m.state.value.issue)
        assertTrue(server.sent.isEmpty()); assertNull(m.state.value.pending)
    }
    @Test fun invalidRequestRejectionClearsStoredRequest() = runTest(dispatcher) {
        val server = RegistrationServer().apply { onSend = { ApiResult.Fail(AppError.Server(400, "INVALID_REQUEST", "bad")) } }
        val prefs = RegistrationPrefs(); val m = model(server, prefs); runCurrent()
        m.start("XIAO-test") { proofBody(it) }; runCurrent()
        assertNull(m.state.value.pending); assertEquals(PrefRead.Found(null), KeyringRegistrationStore(prefs).read(1))
    }
    @Test fun screenStopDuringPostKeepsRecoveryRequest() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs(); val m = model(server, prefs); runCurrent()
        server.onSend = { awaitCancellation() }
        m.start("XIAO-test") { proofBody(it) }; runCurrent(); val original = server.sent.single()
        m.pause(); runCurrent(); assertFalse(m.state.value.busy); m.restore(); runCurrent()
        server.onSend = { server.items = listOf(server.receipt); ApiResult.Ok(server.receipt) }
        m.retry { error("No new proof") }; runCurrent()
        assertEquals(original, server.sent.last()); assertNotNull(m.state.value.confirmed)
    }
    @Test fun accountSwitchDiscardsLateSuccess() = runTest(dispatcher) {
        val server = RegistrationServer(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val m = model(server, session = session); runCurrent(); val gate = CompletableDeferred<Unit>()
        server.onSend = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.receipt) }
        m.start("XIAO-test") { proofBody(it) }; runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.KEYRING_REQUIRED, false); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(2L, m.state.value.owner); assertNull(m.state.value.pending); assertNull(m.state.value.confirmed)
    }
    @Test fun serverFiveHundredKeepsRequestButExplicitProofRejectionClearsIt() = runTest(dispatcher) {
        val server = RegistrationServer(); var now = Instant.parse("2026-11-04T12:00:00Z")
        val m = model(server, now = { now }); runCurrent()
        server.onSend = { ApiResult.Fail(AppError.Server(503, "SERVICE_UNAVAILABLE", "retry")) }
        m.start("XIAO-test") { proofBody(it) }; runCurrent()
        // 5xx는 같은 요청으로 다시 시도할 수 있게 증명까지 보존한다. 새 등록으로 덮어쓰지 않는다
        val kept = m.state.value.pending; assertNotNull(kept?.body)
        assertEquals(RegistrationIssue.ServerFailure, m.state.value.issue); assertNull(m.state.value.error)
        assertEquals(now + REGISTRATION_SERVER_COOLDOWN, m.state.value.retryAfter)
        // 서버 처리 실패 직후에는 같은 요청도 바로 다시 보내지 않는다
        m.start("XIAO-test") { error("No new proof") }; m.retry { error("No new proof") }; runCurrent()
        assertEquals(1, server.sent.size); assertEquals(kept, m.state.value.pending)
        now += REGISTRATION_SERVER_COOLDOWN
        server.onSend = { ApiResult.Fail(AppError.Server(422, "INVALID_DEVICE_PROOF", "proof")) }
        // 증명 거절은 다시 보내도 같다. "다시 시작"을 누르지 않아도 기록을 지워 다음 시도가 새 요청으로 시작된다
        m.retry { error("No new proof") }; runCurrent()
        assertEquals(listOf(kept!!.registerKey, kept.registerKey), server.sent.map { it.first })
        assertNull(m.state.value.pending); assertEquals(RegistrationIssue.ProofRejected, m.state.value.issue)
    }
    @Test fun lostResponseAfterProofIsConfirmingNotFailure() = runTest(dispatcher) {
        val server = RegistrationServer().apply { onSend = { ApiResult.Fail(AppError.Offline) } }
        val m = model(server); runCurrent()
        m.start("XIAO-test") { proofBody(it) }; runCurrent()
        assertEquals(RegistrationIssue.Confirming, m.state.value.issue); assertNotNull(m.state.value.pending?.body)
        assertNull(m.state.value.confirmed)
    }
    @Test fun windowClosedIsSeparatedFromDeviceSilence() = runTest(dispatcher) {
        val server = RegistrationServer(); val m = model(server); runCurrent()
        m.start("XIAO-test") { throw BleCommandException("REGISTRATION_WINDOW_CLOSED") }; runCurrent()
        assertEquals(RegistrationIssue.WindowClosed, m.state.value.issue); assertTrue(server.sent.isEmpty())
    }
    @Test fun registrationErrorsAreClassifiedByStage() {
        fun server(status: Int, code: String) = AppError.Server(status, code, "")
        val prepare = LiveRegistrationState(pending = PendingKeyringRegistration(1, "XIAO-test", "c", "r"))
        val sent = prepare.copy(pending = prepare.pending!!.copy(body = proofBody()))
        val received = sent.copy(pending = sent.pending!!.copy(receipt = OwnedKeyring(12, "SYNC_PENDING", "1")))
        val confirmed = LiveRegistrationState(confirmed = OwnedKeyring(12, "SYNC_PENDING", "1"))
        val down = server(500, "INTERNAL_SERVER_ERROR")
        assertEquals(RegistrationIssue.ProofRejected, registrationIssue(server(422, "INVALID_DEVICE_PROOF"), sent))
        assertEquals(RegistrationIssue.ProofRejected, registrationIssue(server(400, "INVALID_REQUEST"), sent))
        assertEquals(RegistrationIssue.DeviceBound, registrationIssue(server(409, "DEVICE_ALREADY_BOUND"), sent))
        // 증명 전(challenge) 5xx: 대기 없는 서버 오류 / 증명 후 영수증 전 5xx: 대기 후 같은 요청
        assertEquals(RegistrationIssue.ServerUnavailable, registrationIssue(down, prepare))
        assertEquals(RegistrationIssue.ServerUnavailable, registrationIssue(down, LiveRegistrationState()))
        assertEquals(RegistrationIssue.ServerFailure, registrationIssue(down, sent))
        assertEquals(RegistrationIssue.Confirming, registrationIssue(AppError.Offline, sent))
        // 등록 POST가 성공한 뒤의 목록 조회 실패는 서버 오류가 아니라 확인 중이다
        assertEquals(RegistrationIssue.Confirming, registrationIssue(down, received))
        assertEquals(RegistrationIssue.Confirming, registrationIssue(AppError.Offline, received))
        // 등록이 확인된 뒤(다음 단계 갱신 실패)는 등록 안내를 붙이지 않는다
        assertNull(registrationIssue(down, confirmed))
        assertNull(registrationIssue(AppError.Offline, prepare))
        assertNull(registrationIssue(AppError.SessionExpired, sent))
        assertNull(registrationIssue(server(409, "IDEMPOTENCY_CONFLICT"), sent))
    }
    @Test fun ownedListFailureAfterReceiptIsConfirmingWithoutCooldown() = runTest(dispatcher) {
        val server = RegistrationServer().apply { onSend = { ApiResult.Ok(receipt) }; listError = AppError.Server(503, "SERVICE_UNAVAILABLE", "") }
        val m = model(server); runCurrent()
        m.start("XIAO-test") { proofBody(it) }; runCurrent()
        assertEquals(RegistrationIssue.Confirming, m.state.value.issue); assertNull(m.state.value.retryAfter)
        server.listError = null; server.items = listOf(server.receipt)
        m.retry { error("No new proof") }; runCurrent()
        assertEquals(1, server.sent.size); assertEquals(server.receipt, m.state.value.confirmed)
    }
    @Test fun onboardingRefreshFailureAfterSuccessShowsNoRegistrationIssue() = runTest(dispatcher) {
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED).apply {
            refreshResult = ApiResult.Fail(AppError.Server(500, "INTERNAL_SERVER_ERROR", ""))
        }
        val server = RegistrationServer(); val m = model(server, session = session); runCurrent()
        m.start("XIAO-test") { proofBody(it) }; runCurrent()
        assertEquals(server.receipt, m.state.value.confirmed); assertNull(m.state.value.issue); assertNull(m.state.value.retryAfter)
    }
    @Test fun challengeServerErrorDoesNotBlockAnotherKeyring() = runTest(dispatcher) {
        val server = RegistrationServer().apply { challengeError = AppError.Server(500, "INTERNAL_SERVER_ERROR", "") }
        val m = model(server); runCurrent()
        m.start("XIAO-first") { error("No proof before challenge") }; runCurrent()
        assertEquals(RegistrationIssue.ServerUnavailable, m.state.value.issue); assertNull(m.state.value.retryAfter)
        server.challengeError = null
        m.start("XIAO-second") { proofBody(it, "XIAO-second") }; runCurrent()
        assertEquals(server.receipt, m.state.value.confirmed)
    }
    @Test fun boundDeviceRejectionClearsRequestSoAnotherBoardCanRegister() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs(); val m = model(server, prefs); runCurrent()
        val existing = OwnedKeyring(2, "REGISTERED", "test")
        server.items = listOf(existing)
        server.onSend = { ApiResult.Fail(AppError.Server(409, "DEVICE_ALREADY_BOUND", "bound")) }
        m.start("XIAO-bound") { proofBody(it, "XIAO-bound") }; runCurrent()
        val rejected = server.sent.single()
        // 명시적 귀속 거절은 다시 보내도 같다. 기록을 바로 지워 다른 보드로 새로 등록할 수 있게 한다
        assertEquals(RegistrationIssue.DeviceBound, m.state.value.issue)
        assertNull(m.state.value.confirmed)
        assertNull(m.state.value.pending); assertEquals(PrefRead.Found(null), KeyringRegistrationStore(prefs).read(1))
        server.onSend = { server.items = server.items + server.receipt; ApiResult.Ok(server.receipt) }
        m.start("XIAO-new") { proofBody(it, "XIAO-new") }; runCurrent()
        assertEquals(2, server.sent.size); assertNotEquals(rejected.first, server.sent.last().first)
        assertEquals("XIAO-new", server.sent.last().second.deviceSerial)
        assertEquals(listOf(existing, server.receipt), server.items)
        assertEquals(server.receipt, m.state.value.confirmed)
    }
    @Test fun unprovedRequestResumesOnSameBoardAndRestartsOnAnotherBoard() = runTest(dispatcher) {
        val server = RegistrationServer(); val m = model(server); runCurrent()
        m.start("XIAO-first") { error("BLE disconnected") }; runCurrent()
        val first = m.state.value.pending; assertNotNull(first); assertNull(first.body)
        // 같은 보드: 같은 challenge 키로 이어서
        m.start("XIAO-first") { error("BLE disconnected") }; runCurrent()
        assertEquals(first, m.state.value.pending); assertEquals(1, server.challenges.distinct().size)
        // 다른 보드: 앞 요청을 버리고 새 키로 등록
        m.start("XIAO-second") { proofBody(it, "XIAO-second") }; runCurrent()
        assertEquals(2, server.challenges.distinct().size); assertNotEquals(first.registerKey, server.sent.single().first)
        assertEquals("XIAO-second", server.sent.single().second.deviceSerial); assertEquals(server.receipt, m.state.value.confirmed)
    }
    @Test fun protocolPreservesChallengeAndRejectsWrongRequestIdOrSignature() {
        val id = "22222222-2222-4222-8222-222222222222"
        val request = KeyringRegistrationProtocol.request(challengeValue, id).decodeToString()
        assertTrue(request.endsWith("\n")); assertEquals(challengeValue.nonce, Json.parseToJsonElement(request).jsonObject["nonce"]!!.jsonPrimitive.content)
        val response = buildJsonObject { put("protocolVersion", 1); put("type", "REGISTRATION_PROOF"); put("requestId", id)
            put("deviceSerial", "XIAO-test"); put("proof", ApiJson.encodeToJsonElement(proofBody().proof)) }.toString().encodeToByteArray()
        assertEquals(proofBody(), KeyringRegistrationProtocol.response(response, id, "XIAO-test", challengeValue))
        assertFails { KeyringRegistrationProtocol.response(response, "wrong", "XIAO-test", challengeValue) }
        assertFails { KeyringRegistrationProtocol.response(response, id, "other", challengeValue) }
        assertFalse(KeyringRegistrationProtocol.validBody(proofBody().copy(proof = proofBody().proof.copy(signature = "bad"))))
    }
    @Test fun httpContractUsesEmptyChallengeBodyAndExactRegisterFields() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = createHttpClient("https://tingle.test/api/", false, MockEngine { request ->
            calls += request
            respond(if (request.url.encodedPath.endsWith("registration-challenges")) ApiJson.encodeToString(KeyringChallenge.serializer(), challengeValue)
                else """{"deviceId":12,"status":"SYNC_PENDING","firmwareVersion":"0.1.0-registration"}""",
                HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = HttpKeyringRegistrationRepository(client)
            assertIs<ApiResult.Ok<*>>(api.challenge("first")); assertIs<ApiResult.Ok<*>>(api.register("second", proofBody()))
            assertEquals(listOf("/api/keyrings/registration-challenges", "/api/keyrings"), calls.map { it.url.encodedPath })
            assertEquals(listOf("first", "second"), calls.map { it.headers["Idempotency-Key"] })
            assertEquals(0, calls.first().body.toByteArray().size)
            val json = Json.parseToJsonElement(calls.last().body.toByteArray().decodeToString()).jsonObject
            assertEquals(setOf("challengeId", "deviceSerial", "proof"), json.keys)
        } finally { client.close() }
    }
}
