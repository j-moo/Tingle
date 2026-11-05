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
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

private val syncRun = KeyringSyncRun("11111111-1111-4111-8111-111111111111", "11111111-1111-4111-8111-111111111111",
    9007199254740993L, "22222222-2222-4222-8222-222222222222", Long.MAX_VALUE, Long.MAX_VALUE,
    "PENDING", false, "AQ", "base64url", "tingle-dev-settings-v1",
    "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a", "A".repeat(43), "key-1",
    "tingle-device-proof-v1", "2026-11-04T12:00:00Z", null, "2026-11-04T12:00:01Z", "tingle-dev-settings-proof-v1", "dev-ed25519-704492dbf4e64e2b", "A".repeat(43), "A".repeat(86))
private fun stored(run: KeyringSyncRun = syncRun) = KeyringStoredResult(
    KeyringSyncAck(run.settingsVersion, run.payloadHash, KeyringProof(run.proofProtocolVersion, run.keyId, "A".repeat(86))), false, false)

// 테스트 전용 기기 포트. 실제 앱은 BleProbeViewModel에서 보드 challenge를 읽는다.
private fun KeyringSyncViewModel.attach(serial: String, send: suspend (KeyringSyncRun) -> KeyringStoredResult) =
    attach(serial, { "A".repeat(43) }, send)

private class SyncPrefs : AppPrefs {
    val values = mutableMapOf<Pair<Long, String>, String>()
    var fault: PrefRead<Nothing>? = null
    var refuseWrites = false
    override fun <T> flow(key: PrefKey<T>) = flowOf<T?>(null)
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        assertTrue(key.durable)
        if (!refuseWrites && value != null) values[forUser to key.name] = ApiJson.encodeToString(key.serializer, value)
    }
    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> {
        fault?.let { return it }
        return values[forUser to key.name]?.let { PrefRead.Found(ApiJson.decodeFromString(key.serializer, it)) } ?: PrefRead.Missing
    }
}
private class SyncServer : KeyringSyncRepository, OwnedKeyringRepository {
    var run = syncRun
    val creates = mutableListOf<Pair<Long, String>>()
    val acks = mutableListOf<Pair<String, KeyringSyncAck>>()
    var getCount = 0
    var createAction: suspend () -> ApiResult<KeyringSyncRun> = { ApiResult.Ok(run) }
    var ackAction: suspend () -> ApiResult<KeyringSyncRun> = { run = run.copy(state = "ACKED"); ApiResult.Ok(run) }
    val bodies = mutableListOf<SyncChallengeBody>()
    override suspend fun create(deviceId: Long, key: String, body: SyncChallengeBody): ApiResult<KeyringSyncRun> {
        creates += deviceId to key; bodies += body; run = run.copy(deviceNonce = body.deviceNonce); return createAction()
    }
    override suspend fun get(deviceId: Long, runId: String): ApiResult<KeyringSyncRun> { getCount++; return ApiResult.Ok(run) }
    override suspend fun ack(deviceId: Long, runId: String, key: String, body: KeyringSyncAck): ApiResult<KeyringSyncRun> {
        acks += key to body; return ackAction()
    }
    override suspend fun list() = ApiResult.Ok(listOf(OwnedKeyring(run.deviceId, "REGISTERED", "test")))
}

@OptIn(ExperimentalCoroutinesApi::class)
class KeyringSyncTest {
    /** 서버 ACK 응답 유실 후 동일 증명은 복구하지만 같은 run의 소유 순번 변경은 거절한다. */
    @Test fun v2AckRecoveryPreservesEpochAndRejectsChangedIdentity() = runTest(dispatcher) {
        val server = SyncServer().apply { run = syncRun.copy(ownershipEpoch = 1,
            proofProtocolVersion = "tingle-device-proof-v2", serverProofProtocolVersion = "tingle-dev-settings-proof-v2") }
        val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        server.ackAction = { ApiResult.Fail(AppError.Offline) }
        val m = model(server, store); m.attach("XIAO-test") { stored(it) }; runCurrent()
        val original = server.acks.single(); assertEquals(1L, store.read(1).single().identity?.ownershipEpoch)
        models.clear(); server.run = server.run.copy(ownershipEpoch = 2)
        val restored = model(server, KeyringSyncStore(prefs)); runCurrent()
        assertEquals(1, server.acks.size); assertEquals(SyncPhase.Failed, restored.state.value.items.single().phase)
        server.run = server.run.copy(ownershipEpoch = 1)
        server.ackAction = { server.run = server.run.copy(state = "ACKED"); ApiResult.Ok(server.run) }
        restored.retry(syncRun.deviceId); runCurrent()
        assertEquals(original, server.acks.last()); assertEquals(SyncPhase.Confirmed, restored.state.value.items.single().phase)
    }
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() { models.clear(); Dispatchers.resetMain() }
    private fun model(server: SyncServer, store: KeyringSyncStore, session: TestSession = TestSession(OnboardingStatus.SYNC_PENDING)) =
        KeyringSyncViewModel(server, store, server, session).also { models.put("sync", it); it.resume() }

    @Test fun waitsForSignupAndBoardChallengeBeforeIssuing() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); val session = TestSession(OnboardingStatus.PROFILE_REQUIRED)
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store, session); runCurrent()
        assertTrue(server.creates.isEmpty()); assertEquals(SyncPhase.WaitingStage, m.state.value.items.single().phase)
        session.state.value = Session.SignedIn(1, OnboardingStatus.SYNC_PENDING, false); runCurrent()
        assertTrue(server.creates.isEmpty()); assertTrue(server.acks.isEmpty())
        assertEquals(SyncPhase.WaitingConnection, m.state.value.items.single().phase)
        m.attach("XIAO-test") { stored(it) }; runCurrent()
        assertEquals(1, server.creates.size); assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }
    @Test fun newRegistrationTargetWakesExistingCoordinatorOnce() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); val m = model(server, store); m.attach("XIAO-test") { stored(it) }; runCurrent()
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test"); runCurrent()
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test"); runCurrent()
        assertEquals(1, server.creates.size)
    }
    @Test fun policyUnresolvedIsWaitingNotSuccessAndDoesNotLoop() = runTest(dispatcher) {
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Server(422, "POLICY_UNRESOLVED", "wait")) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { error("No payload means no BLE") }; runCurrent()
        assertEquals(SyncPhase.PolicyWaiting, m.state.value.items.single().phase)
        assertEquals(1, server.creates.size); assertTrue(server.acks.isEmpty())
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(server.creates[0], server.creates[1])
    }
    // 같은 BLE 연결에서 HTTP 응답만 잃으면 아직 유효한 challenge와 요청 키를 그대로 재사용한다.
    @Test fun lostCreateResponseReusesKeyWithinSameConnection() = runTest(dispatcher) {
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); var challenges = 0
        m.attach("XIAO-test", { challenges++; "A".repeat(43) }) { stored(it) }; runCurrent()
        val first = server.creates.single(); val body = server.bodies.single()
        server.createAction = { ApiResult.Ok(server.run) }; m.retry(syncRun.deviceId); runCurrent()
        assertEquals(1, challenges); assertEquals(listOf(first, first), server.creates)
        assertEquals(listOf(body, body), server.bodies)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }
    // 새 모델에서는 예전 BLE 연결이 살아 있는지 알 수 없다. ACK 증명이 없는 발급은 새 challenge로 시작한다.
    @Test fun lostCreateResponseRenewsChallengeAcrossRecreation() = runTest(dispatcher) {
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Offline) } }
        val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { stored(it) }; runCurrent(); val first = server.creates.single(); models.clear()
        server.createAction = { ApiResult.Ok(server.run) }
        val second = model(server, KeyringSyncStore(prefs)); second.attach("XIAO-test", { "B".repeat(42) + "A" }) {
            assertEquals("B".repeat(42) + "A", it.deviceNonce); stored(it)
        }; runCurrent()
        assertEquals(2, server.creates.size)
        assertNotEquals(first.second, server.creates.last().second); assertNotEquals(server.bodies.first(), server.bodies.last())
        assertEquals(SyncPhase.Confirmed, second.state.value.items.single().phase)
    }
    // 서버 발급 대기 중 재부팅하면 RAM challenge가 사라진다. 55초가 지나지 않아도 자동 갱신해야 한다.
    // 취소를 무시하고 늦게 도착한 구 연결 응답이 새 연결에 전송되는지도 함께 검사한다.
    @Test fun rebootWhileCreatePendingUsesFreshChallengeAndIgnoresLateResponse() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs())
        val gate = CompletableDeferred<Unit>(); val sent = mutableListOf<String>()
        val now = Instant.parse("2026-11-04T12:00:01Z")
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        server.createAction = {
            val response = server.run
            if (server.creates.size == 1) withContext(NonCancellable) { gate.await() }
            ApiResult.Ok(response)
        }
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.SYNC_PENDING), now = { now })
        models.put("sync", m); m.resume()
        m.attach("XIAO-test", { "A".repeat(43) }) { error("Old connection must never transmit") }; runCurrent()
        assertFalse(store.read(1).single().bleAttempted)
        val oldKey = server.creates.single().second
        m.detach()
        m.attach("XIAO-test", { "B".repeat(42) + "A" }) {
            if (it.deviceNonce != "B".repeat(42) + "A") throw BleCommandException("SYNC_CHALLENGE_REQUIRED")
            sent += it.deviceNonce!!; stored(it)
        }; runCurrent()
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
        assertEquals(listOf("B".repeat(42) + "A"), sent)
        assertNotEquals(oldKey, server.creates.last().second)
        val completed = store.read(1).single()
        gate.complete(Unit); runCurrent()
        assertEquals(completed, store.read(1).single())
        assertEquals(2, server.creates.size); assertEquals(1, server.acks.size)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }
    @Test fun matchingBoardSendsServerPayloadAndAckOnlyAfterStored() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-other") { error("Wrong board") }; runCurrent()
        assertTrue(server.acks.isEmpty())
        m.attach("XIAO-test") { assertEquals(syncRun, it); stored(it) }; runCurrent()
        assertEquals(1, server.acks.size)
        val progress = m.state.value.items.single()
        assertEquals(SyncPhase.Confirmed, progress.phase); assertEquals(false, progress.applied); assertEquals(false, progress.timeReady)
    }
    @Test fun lostAckResponseReusesDurableProofAndKeyWithoutBle() = runTest(dispatcher) {
        val server = SyncServer().apply { ackAction = { run = run.copy(state = "ACKED"); ApiResult.Fail(AppError.Offline) } }
        val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { stored(it) }; runCurrent()
        val first = server.acks.single(); models.clear()
        server.ackAction = { ApiResult.Ok(server.run) }
        val restored = model(server, KeyringSyncStore(prefs)); runCurrent()
        assertEquals(first, server.acks.last()); assertEquals(2, server.acks.size)
        assertEquals(SyncPhase.Confirmed, restored.state.value.items.single().phase)
    }
    @Test fun disconnectReissuesServerProofWithNewKey() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); val sent = mutableListOf<KeyringSyncRun>()
        m.attach("XIAO-test") { sent += it; throw IllegalStateException("Disconnected") }; runCurrent()
        m.detach(); m.attach("XIAO-test") { sent += it; stored(it) }; runCurrent()
        assertEquals(2, server.creates.size); assertNotEquals(server.creates.first().second, server.creates.last().second)
        assertEquals(2, sent.size); assertEquals(sent.first().identity(), sent.last().identity())
    }
    @Test fun mismatchedStoredProofNeverAcknowledged() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { stored(it).let { result -> result.copy(ack = result.ack.copy(settingsVersion = 1)) } }; runCurrent()
        assertEquals(SyncPhase.Failed, m.state.value.items.single().phase); assertTrue(server.acks.isEmpty())
    }
    @Test fun staleRunRetriesLatestOnceWithoutSendingOldPayloadOrLooping() = runTest(dispatcher) {
        val server = SyncServer().apply { run = run.copy(currentSettingsVersion = 3, stale = true) }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { error("Old payload") }; runCurrent()
        val first = server.creates.first().second
        assertEquals(2, server.creates.size)
        assertEquals(SyncPhase.Stale, m.state.value.items.single().phase)
        m.latest(syncRun.deviceId); runCurrent(); assertNotEquals(first, server.creates.last().second)
    }
    @Test fun newConnectionReauthenticatesCompletedRunOnceAndSameBindingDoesNotRepeat() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs())
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); var sends = 0
        m.attach("XIAO-test") { sends++; stored(it) }; runCurrent()
        m.detach(); m.attach("XIAO-test", { "B".repeat(42) + "A" }) { sends++; stored(it) }; runCurrent()
        m.attach("XIAO-test") { error("Same binding must remain") }; m.retry(syncRun.deviceId); runCurrent()
        assertEquals(2, sends); assertEquals(2, server.creates.size)
        assertEquals("B".repeat(42) + "A", server.bodies.last().deviceNonce)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }
    @Test fun savedSettingsWakesConnectedBoardButOtherAccountDoesNot() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); val changes = KeyringSettingsChanges()
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.READY), changes = changes)
        models.put("sync", m); m.resume(); m.attach("XIAO-test") { stored(it) }; runCurrent()
        changes.saved(2); runCurrent(); assertEquals(1, server.creates.size)
        changes.saved(1); runCurrent(); assertEquals(2, server.creates.size)
        assertNotEquals(server.creates.first().second, server.creates.last().second)
        m.detach(); changes.saved(1); runCurrent()
        assertEquals(SyncPhase.WaitingConnection, m.state.value.items.single().phase)
        assertEquals(2, server.creates.size)
        m.attach("XIAO-test") { stored(it) }; runCurrent(); assertEquals(3, server.creates.size)
    }
    @Test fun settingsChangeDuringTransmissionIsDeliveredAfterPreviousAck() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); val changes = KeyringSettingsChanges()
        val gate = CompletableDeferred<Unit>(); var sends = 0
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.READY), changes = changes)
        models.put("sync", m); m.resume()
        m.attach("XIAO-test") { sends++; if (sends == 1) gate.await(); stored(it) }; runCurrent()
        changes.saved(1); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(2, sends); assertEquals(2, server.acks.size)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }
    // BLE가 구버전을 저장하는 사이 서버 설정이 변경되면, 실제 서버처럼 이전 ACK를 409로 거절한다.
    // 성공만 돌려주는 fixture로는 최신 버전으로 자동 전환되는지 검증할 수 없다.
    @Test fun staleAckDuringTransmissionAutomaticallySendsNewSettings() = runTest(dispatcher) {
        val server = SyncServer().apply { run = run.copy(settingsVersion = 1, currentSettingsVersion = 1) }
        val store = KeyringSyncStore(SyncPrefs()); val changes = KeyringSettingsChanges()
        val gate = CompletableDeferred<Unit>(); val sent = mutableListOf<Long>(); var version = 1L
        server.createAction = {
            server.run = server.run.copy(settingsVersion = version, currentSettingsVersion = version, stale = false, state = "PENDING")
            ApiResult.Ok(server.run)
        }
        server.ackAction = {
            if (server.acks.last().second.settingsVersion != version)
                ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "최신 설정 필요"))
            else { server.run = server.run.copy(state = "ACKED"); ApiResult.Ok(server.run) }
        }
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.READY), changes = changes)
        models.put("sync", m); m.resume()
        m.attach("XIAO-test", { if (sent.isEmpty()) "A".repeat(43) else "B".repeat(42) + "A" }) {
            sent += it.settingsVersion; if (sent.size == 1) gate.await(); stored(it)
        }; runCurrent()
        version = 2; changes.saved(1); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(listOf(1L, 2L), sent)
        assertEquals(listOf(1L, 2L), server.acks.map { it.second.settingsVersion })
        assertEquals(2, server.creates.size)
        assertNotEquals(server.creates.first().second, server.creates.last().second)
        assertNotEquals(server.bodies.first().deviceNonce, server.bodies.last().deviceNonce)
        assertNotEquals(server.acks.first().first, server.acks.last().first)
        assertTrue(store.read(1).single().complete)
        assertEquals(2L, store.read(1).single().ack!!.settingsVersion)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }
    // 응답을 신뢰할 수 없는 실패는 구버전 확정 거절과 다르다. 재시도에서도 같은 ACK를 보존한다.
    @Test fun uncertainAckFailureKeepsOriginalProofAndKey() = runTest(dispatcher) {
        for (failure in listOf(AppError.Offline, AppError.Server(503, "SERVICE_UNAVAILABLE", "wait"),
            AppError.Server(500, "SYNC_VERSION_STALE", "not a confirmed rejection"),
            AppError.Server(409, "INVALID_PROOF", "invalid"))) {
            val server = SyncServer().apply { ackAction = { ApiResult.Fail(failure) } }
            val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
            var sends = 0
            val m = model(server, store); m.attach("XIAO-test") { sends++; stored(it) }; runCurrent()
            val ticket = store.read(1).single(); val first = server.acks.single()
            m.retry(syncRun.deviceId); runCurrent()
            assertEquals(listOf(first, first), server.acks)
            assertEquals(ticket, store.read(1).single())
            assertFalse(ticket.complete); assertEquals(1, sends); assertEquals(1, server.creates.size)
            models.clear()
        }
    }
    // 앱 재생성 후에도 저장된 ACK의 확정 거절을 먼저 확인하고 새 보드 challenge를 요청한다.
    @Test fun restoredPendingAckRenewsOnlyAfterConfirmedStaleRejection() = runTest(dispatcher) {
        val server = SyncServer().apply { run = run.copy(settingsVersion = 1, currentSettingsVersion = 1)
            ackAction = { ApiResult.Fail(AppError.Offline) } }
        val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs)
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val first = model(server, store); first.attach("XIAO-test") { stored(it) }; runCurrent()
        val original = server.acks.single(); models.clear()
        server.run = server.run.copy(currentSettingsVersion = 2, stale = true)
        server.ackAction = {
            if (server.acks.last().second.settingsVersion == 1L) ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "stale"))
            else { server.run = server.run.copy(state = "ACKED"); ApiResult.Ok(server.run) }
        }
        server.createAction = {
            server.run = server.run.copy(settingsVersion = 2, currentSettingsVersion = 2, stale = false)
            ApiResult.Ok(server.run)
        }
        val restored = model(server, KeyringSyncStore(prefs))
        restored.attach("XIAO-test", {
            assertEquals(original, server.acks.last()); assertEquals(2, server.acks.size)
            "B".repeat(42) + "A"
        }) { assertEquals(2L, it.settingsVersion); stored(it) }; runCurrent()
        assertEquals(3, server.acks.size); assertEquals(2, server.creates.size)
        assertEquals(SyncPhase.Confirmed, restored.state.value.items.single().phase)
    }
    // 최신 설정도 전송 중 다시 바뀔 수 있다. 한 진행에서 갱신 횟수를 제한해 무한 발급을 막는다.
    @Test fun repeatedStaleAckStopsAfterOneRenewal() = runTest(dispatcher) {
        val server = SyncServer().apply { ackAction = { ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "stale")) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { stored(it) }; runCurrent()
        assertEquals(2, server.creates.size); assertEquals(2, server.acks.size)
        assertFalse(store.read(1).single().complete)
        assertEquals(SyncPhase.Stale, m.state.value.items.single().phase)
    }
    @Test fun reconnectFinishesLostAckWithOriginalKeyBeforeFreshChallenge() = runTest(dispatcher) {
        val server = SyncServer().apply { ackAction = { run = run.copy(state = "ACKED"); ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { stored(it) }; runCurrent()
        val original = server.acks.single()
        m.detach(); server.ackAction = { ApiResult.Ok(server.run) }
        m.attach("XIAO-test", {
            assertEquals(2, server.acks.size); assertEquals(original, server.acks.last()); "B".repeat(42) + "A"
        }) { stored(it) }; runCurrent()
        assertEquals(3, server.acks.size); assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }
    @Test fun accountSwitchDiscardsLateResultAndBoardBinding() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val session = TestSession(OnboardingStatus.SYNC_PENDING); val gate = CompletableDeferred<Unit>()
        server.createAction = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.run) }
        val m = model(server, store, session); m.attach("XIAO-test") { error("Old account") }; runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.SYNC_PENDING, false); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(2, m.state.value.owner); assertTrue(m.state.value.items.isEmpty()); assertTrue(server.acks.isEmpty())
    }
    @Test fun unreadableRecoveryDoesNotCreateNewExecution() = runTest(dispatcher) {
        val server = SyncServer(); val prefs = SyncPrefs().apply { fault = PrefRead.Corrupt }
        val m = model(server, KeyringSyncStore(prefs)); runCurrent()
        assertTrue(m.state.value.recoveryFailed); assertTrue(server.creates.isEmpty())
    }
    @Test fun multiDeviceTargetsSurviveUpdatesAndPayloadIsNotPersisted() = runTest {
        val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs)
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test"); store.rememberTarget(1, 2, "XIAO-second")
        store.save(1, store.read(1).first().copy(identity = syncRun.identity()))
        assertEquals(2, store.read(1).size); assertFalse(prefs.values.values.any { "YWJj" in it })
    }
    @Test fun protocolKeepsInt64AndOpaqueBytesAndRejectsWrongResponse() {
        val id = "33333333-3333-4333-8333-333333333333"
        val bytes = KeyringSyncProtocol.request(syncRun, id)
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(10.toByte(), bytes.last()); assertEquals(syncRun.deviceId, json["deviceId"]!!.jsonPrimitive.long)
        assertEquals(Long.MAX_VALUE, json["settingsVersion"]!!.jsonPrimitive.long); assertEquals("AQ", json["payload"]!!.jsonPrimitive.content)
        assertEquals(syncRun.serverSignature, json["serverSignature"]!!.jsonPrimitive.content)
        assertEquals(syncRun.serverTime, json["serverTime"]!!.jsonPrimitive.content)
        val response = buildJsonObject {
            put("protocolVersion", 1); put("type", "SETTINGS_RESULT"); put("requestId", id)
            put("syncRunId", syncRun.syncRunId); put("settingsVersion", syncRun.settingsVersion); put("payloadHash", syncRun.payloadHash)
            put("stored", true); put("applied", false); put("timeReady", false)
            put("proof", ApiJson.encodeToJsonElement(stored().ack.proof))
        }
        fun encode(value: JsonObject) = value.toString().encodeToByteArray()
        assertEquals(stored(), KeyringSyncProtocol.response(encode(response), id, syncRun))
        for ((field, invalid) in listOf("requestId" to JsonPrimitive("old"), "syncRunId" to JsonPrimitive("old"),
            "settingsVersion" to JsonPrimitive(2), "payloadHash" to JsonPrimitive("bad"), "stored" to JsonPrimitive(false))) {
            assertFails { KeyringSyncProtocol.response(encode(JsonObject(response + (field to invalid))), id, syncRun) }
        }
    }
    @Test fun httpUsesDeviceChallengeBodyAndExactAckContract() = runTest {
        val calls = mutableListOf<HttpRequestData>()
        val client = createHttpClient("https://tingle.test/dev/api/", false, MockEngine { request ->
            calls += request
            respond(ApiJson.encodeToString(KeyringSyncRun.serializer(), syncRun), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = HttpKeyringSyncRepository(client)
            api.create(syncRun.deviceId, "create-key", SyncChallengeBody("A".repeat(43))); api.get(syncRun.deviceId, syncRun.syncRunId)
            api.ack(syncRun.deviceId, syncRun.syncRunId, "ack-key", stored().ack)
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Post), calls.map { it.method })
            assertEquals(setOf("deviceNonce"), Json.parseToJsonElement(calls[0].body.toByteArray().decodeToString()).jsonObject.keys); assertEquals("create-key", calls[0].headers["Idempotency-Key"])
            assertEquals("ack-key", calls[2].headers["Idempotency-Key"])
            assertEquals(setOf("settingsVersion", "payloadHash", "proof"), Json.parseToJsonElement(calls[2].body.toByteArray().decodeToString()).jsonObject.keys)
            assertTrue(calls[0].url.encodedPath.startsWith("/dev/api/keyrings/${syncRun.deviceId}/"))
        } finally { client.close() }
    }
    @Test fun storageFailureAfterBoardProofDoesNotPostAck() = runTest(dispatcher) {
        val server = SyncServer(); val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs)
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { prefs.refuseWrites = true; stored(it) }; runCurrent()
        assertTrue(server.acks.isEmpty()); assertEquals(SyncPhase.Failed, m.state.value.items.single().phase)
    }
    @Test fun policyErrorFromActualBleIsWaitingWithoutAck() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { throw BleCommandException("POLICY_UNRESOLVED") }; runCurrent()
        assertEquals(SyncPhase.PolicyWaiting, m.state.value.items.single().phase); assertTrue(server.acks.isEmpty())
    }
    @Test fun lostAuthorizationCancelsTransmissionAndIgnoresLateStoredResult() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); val session = TestSession(OnboardingStatus.SYNC_PENDING)
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val gate = CompletableDeferred<Unit>(); val m = model(server, store, session)
        m.attach("XIAO-test") { withContext(NonCancellable) { gate.await() }; stored(it) }; runCurrent()
        session.state.value = Session.SignedIn(1, OnboardingStatus.AGREEMENT_REQUIRED, false); runCurrent()
        gate.complete(Unit); runCurrent(); assertTrue(server.acks.isEmpty())
        assertEquals(SyncPhase.WaitingStage, m.state.value.items.single().phase)
    }
    @Test fun targetCorrectionPreservesPendingRequestIdentity() = runTest {
        val store = KeyringSyncStore(SyncPrefs()); store.selectTarget(1, syncRun.deviceId, "XIAO-wrong")
        val original = store.read(1).single().copy(identity = syncRun.identity()); store.save(1, original)
        store.selectTarget(1, syncRun.deviceId, "XIAO-correct")
        assertEquals(original.copy(serial = "XIAO-correct"), store.read(1).single())
    }
    @Test fun deviceChallengeProtocolRejectsWrongRequestAndInvalidNonce() {
        val id = "33333333-3333-4333-8333-333333333333"
        val request = Json.parseToJsonElement(KeyringSyncProtocol.challengeRequest(id).decodeToString()).jsonObject
        assertEquals("GET_SYNC_CHALLENGE", request["type"]!!.jsonPrimitive.content)
        val reply = buildJsonObject { put("protocolVersion", 1); put("type", "SYNC_CHALLENGE"); put("requestId", id); put("deviceNonce", "A".repeat(43)) }
        assertEquals("A".repeat(43), KeyringSyncProtocol.challengeResponse(reply.toString().encodeToByteArray(), id))
        assertFails { KeyringSyncProtocol.challengeResponse(reply.toString().encodeToByteArray(), "old") }
        assertFalse(KeyringSyncProtocol.validDeviceNonce("bad"))
    }
    @Test fun missingServerSignatureOrDifferentChallengeNeverReachesBoard() = runTest(dispatcher) {
        for (bad in listOf(syncRun.copy(serverSignature = null), syncRun.copy(deviceNonce = "B".repeat(43)))) {
            models.clear()
            val server = SyncServer().apply { createAction = { ApiResult.Ok(bad) } }
            val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
            val m = model(server, store); m.attach("XIAO-test") { error("Unsigned response") }; runCurrent()
            assertEquals(SyncPhase.Failed, m.state.value.items.single().phase); assertTrue(server.acks.isEmpty())
        }
    }
    @Test fun consumedChallengeUsesFreshNonceAndKey() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); var calls = 0
        val nonces = listOf("A".repeat(43), "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE")
        m.attach("XIAO-test", { nonces[calls++] }) { throw BleCommandException("SYNC_CHALLENGE_REQUIRED") }; runCurrent()
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(2, calls); assertEquals(nonces, server.bodies.map { it.deviceNonce })
        assertNotEquals(server.creates[0].second, server.creates[1].second)
        assertTrue(server.acks.isEmpty())
    }
    @Test fun expiredServerResponseIsNotSentAndNextAttemptRenewsChallenge() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        var time = Instant.parse("2026-11-04T12:00:00Z"); var challenges = 0; var transmissions = 0
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.SYNC_PENDING), { time })
            .also { models.put("sync", it); it.resume() }
        server.createAction = { time += 60.seconds; ApiResult.Ok(server.run) }
        m.attach("XIAO-test", { challenges++; "A".repeat(43) }) { transmissions++; stored(it) }; runCurrent()
        assertEquals(0, transmissions); assertEquals(SyncPhase.Failed, m.state.value.items.single().phase)
        server.createAction = { ApiResult.Ok(server.run) }; m.retry(syncRun.deviceId); runCurrent()
        assertEquals(2, challenges); assertEquals(1, transmissions)
        assertNotEquals(server.creates.first().second, server.creates.last().second)
    }
    @Test fun explicitResendAfterServerConfirmationGetsNewSignedRequest() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); var sends = 0
        m.attach("XIAO-test") { sends++; stored(it) }; runCurrent()
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
        m.latest(syncRun.deviceId); runCurrent()
        assertEquals(2, sends); assertEquals(2, server.acks.size)
        assertNotEquals(server.creates.first().second, server.creates.last().second)
    }
}
