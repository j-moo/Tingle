package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
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
private fun appliedProof(identity: SyncIdentity) = KeyringSyncAck(identity.settingsVersion, identity.payloadHash,
    KeyringProof("tingle-settings-applied-v1", identity.keyId, "A".repeat(86)))
private fun tsv3Run() = syncRun.copy(payloadFormat = KeyringSyncProtocol.FULL_SETTINGS_FORMAT,
    payload = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
        .encode("TSV3".encodeToByteArray() + ByteArray(483)),
    proofProtocolVersion = KeyringRegistrationProtocol.VERSION_2, serverProofProtocolVersion = "tingle-dev-settings-proof-v2",
    ownershipEpoch = 2L, settingsVersion = 12L, currentSettingsVersion = 12L)

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
    val applications = mutableListOf<Pair<String, KeyringSyncAck>>()
    var appliedAction: suspend () -> ApiResult<Unit> = { ApiResult.Ok(Unit) }
    var getCount = 0
    // 조회409·응답유실을 발급/ACK와 독립적으로 재현한다.
    var getAction: suspend () -> ApiResult<KeyringSyncRun> = { ApiResult.Ok(run) }
    var createAction: suspend () -> ApiResult<KeyringSyncRun> = { ApiResult.Ok(run) }
    var ackAction: suspend () -> ApiResult<KeyringSyncRun> = { run = run.copy(state = "ACKED"); ApiResult.Ok(run) }
    val bodies = mutableListOf<SyncChallengeBody>()
    override suspend fun create(deviceId: Long, key: String, body: SyncChallengeBody): ApiResult<KeyringSyncRun> {
        creates += deviceId to key; bodies += body; run = run.copy(deviceNonce = body.deviceNonce); return createAction()
    }
    override suspend fun get(deviceId: Long, runId: String): ApiResult<KeyringSyncRun> { getCount++; return getAction() }
    override suspend fun ack(deviceId: Long, runId: String, key: String, body: KeyringSyncAck): ApiResult<KeyringSyncRun> {
        acks += key to body; return ackAction()
    }
    override suspend fun applied(deviceId: Long, runId: String, key: String, body: KeyringSyncAck): ApiResult<Unit> {
        applications += key to body; return appliedAction()
    }
    override suspend fun list() = ApiResult.Ok(listOf(OwnedKeyring(run.deviceId, "REGISTERED", "test", "XIAO-test")))
}

@OptIn(ExperimentalCoroutinesApi::class)
class KeyringSyncTest {
    /** 서버 성향 응답의 봉투를 검사하고 원본 바이트를 보존한다. 실제 성향 계산 fixture는 아니다. */
    @Test fun profileEnvelopeKeepsOpaquePayloadAndRejectsUnknownOrMalformedFormats() {
        val payload = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode("TPS1".encodeToByteArray() + ByteArray(192))
        val run = syncRun.copy(payloadFormat = KeyringSyncProtocol.PROFILE_FORMAT, payload = payload)
        assertTrue(KeyringSyncProtocol.signed(run, requireNotNull(run.deviceNonce)))
        val frame = Json.parseToJsonElement(KeyringSyncProtocol.request(run, "33333333-3333-4333-8333-333333333333").decodeToString()).jsonObject
        assertEquals(payload, frame.getValue("payload").jsonPrimitive.content)
        for (bad in listOf(run.copy(payload = "AQ"), run.copy(payloadFormat = "future-format"),
            run.copy(payload = "A".repeat(262)), run.copy(serverSignature = null))) {
            assertFalse(KeyringSyncProtocol.signed(bad, requireNotNull(run.deviceNonce)))
        }
    }

    /** 발급409는 새 nonce/키로 한 번 갱신하지만 반복409는 사용자 확인 상태로 멈춘다. */
    @Test fun createStaleUsesFreshNonceAndKeyWithBoundedRenewal() = runTest(dispatcher) {
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "old")) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        var challenges = 0
        val m = model(server, store)
        m.attach("XIAO-test", { challenges++; if (challenges == 1) "A".repeat(43) else "B".repeat(42) + "A" }) { error("No stale BLE") }
        runCurrent()
        assertEquals(2, challenges); assertEquals(2, server.creates.size)
        assertNotEquals(server.creates[0].second, server.creates[1].second)
        assertNotEquals(server.bodies[0], server.bodies[1]); assertTrue(server.acks.isEmpty())
        assertEquals(SyncPhase.Stale, m.state.value.items.single().phase)
    }

    /** 저장 증명 복구 GET이409일 때 이전 ACK를 보내지 않고 새 실행으로 이동한다. */
    @Test fun recoveryGetStaleReissuesButDoesNotPromoteSignup() = runTest(dispatcher) {
        val server = SyncServer().apply { ackAction = { ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val session = TestSession(OnboardingStatus.SYNC_PENDING)
        val m = model(server, store, session); m.attach("XIAO-test") { stored(it) }; runCurrent()
        val oldAck = server.acks.single()
        server.getAction = { ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "old")) }
        server.ackAction = { server.run = server.run.copy(state = "ACKED"); ApiResult.Ok(server.run) }
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(2, server.creates.size); assertEquals(2, server.acks.size)
        assertNotEquals(oldAck.first, server.acks.last().first)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
        assertEquals(OnboardingStatus.SYNC_PENDING, (session.state.value as Session.SignedIn).status)
    }

    /** GET 오류가 확정409가 아니면 기존 ACK·발급 기록을 보존한다. */
    @Test fun unknownGetFailurePreservesPendingProof() = runTest(dispatcher) {
        val server = SyncServer().apply { ackAction = { ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { stored(it) }; runCurrent()
        val before = store.read(1).single()
        server.getAction = { ApiResult.Fail(AppError.Server(503, "SERVICE_UNAVAILABLE", "temporary")) }
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(before, store.read(1).single()); assertEquals(1, server.creates.size); assertEquals(1, server.acks.size)
        assertEquals(SyncPhase.Failed, m.state.value.items.single().phase)
    }

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
    private fun model(server: SyncServer, store: KeyringSyncStore, session: SessionStore = TestSession(OnboardingStatus.SYNC_PENDING)) =
        KeyringSyncViewModel(server, store, server, session).also { models.put("sync", it); it.resume() }

    /** 같은 버전이라도 해시가 다르면 다시 보내고, 둘 다 일치할 때만 최신으로 표시한다. */
    @Test fun manualSyncChecksCurrentBoardBeforeSendingAgain() = runTest(dispatcher) {
        val server = SyncServer()
        val store = KeyringSyncStore(SyncPrefs())
        val serial = "XIAO-test"
        store.rememberTarget(1, syncRun.deviceId, serial)
        var hash = syncRun.payloadHash
        var writes = 0
        val m = model(server, store, TestSession(OnboardingStatus.READY))
        m.attachRequest(serial, { SyncChallengeBody("A".repeat(43)) },
            getDeviceInfo = { BleDeviceInfo(serial, "test", true,
                storedSettingsVersion = syncRun.settingsVersion, storedPayloadHash = hash) }) {
            writes++
            stored(it)
        }
        runCurrent()
        assertEquals(1, writes)

        m.useExisting(syncRun.deviceId, serial, checkLatest = true)
        runCurrent()
        assertTrue(m.state.value.items.single().alreadyLatest)
        assertEquals(1, writes)
        assertEquals(1, server.creates.size)

        hash = "0".repeat(64)
        m.useExisting(syncRun.deviceId, serial, checkLatest = true)
        runCurrent()
        assertEquals(2, writes)
        assertEquals(2, server.acks.size)
        assertFalse(m.state.value.items.single().alreadyLatest)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }

    // 실제 NetworkMonitor는 OS 콜백을 Flow로 제공한다. 시험은 신호만 제어하고 기존 내구 복구 경로를 검증한다.
    @Test fun internetRestoreRetriesFailedCreateWithSameKeyAndBody() = runTest(dispatcher) {
        val availability = MutableStateFlow(false)
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val monitor = object : NetworkMonitor { override val availability = availability }
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.READY), network = monitor)
        models.put("sync", m); m.resume(); m.attach("XIAO-test") { stored(it) }; runCurrent()
        val firstKey = server.creates.single(); val firstBody = server.bodies.single()
        server.createAction = { ApiResult.Ok(server.run) }; availability.value = true; runCurrent()
        assertEquals(listOf(firstKey, firstKey), server.creates)
        assertEquals(listOf(firstBody, firstBody), server.bodies)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
        availability.value = false; runCurrent(); availability.value = true; runCurrent()
        assertEquals(2, server.creates.size) // 완료된 작업은 다음 인터넷 복원에서도 다시 보내지 않는다.
    }

    @Test fun internetRestoreConfirmsLostAckBeforeBleReauthentication() = runTest(dispatcher) {
        val server = SyncServer().apply { ackAction = { ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.onNetworkAvailabilityChanged(false)
        var sends = 0
        m.attach("XIAO-test") { sends++; stored(it) }; runCurrent()
        val proofAndKey = server.acks.single()
        server.ackAction = {
            // 기존 ACK가 확인되기 전에는 BLE를 다시 쓰면 안 된다. 확인 뒤의 재인증은 기존 계약대로 허용한다.
            if (server.acks.size == 2) assertEquals(1, sends)
            server.run = server.run.copy(state = "ACKED"); ApiResult.Ok(server.run)
        }
        m.onNetworkAvailabilityChanged(true); runCurrent()
        assertEquals(listOf(proofAndKey, proofAndKey), server.acks.take(2))
        assertEquals(3, server.acks.size); assertEquals(2, sends)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }

    @Test fun repeatedOnlineSignalsDoNotLoopWhenServerIsStillUnreachable() = runTest(dispatcher) {
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.onNetworkAvailabilityChanged(false)
        m.attach("XIAO-test") { stored(it) }; runCurrent()
        m.onNetworkAvailabilityChanged(true); runCurrent()
        repeat(4) { m.onNetworkAvailabilityChanged(true); runCurrent() }
        assertEquals(2, server.creates.size)
        assertEquals(SyncPhase.Failed, m.state.value.items.single().phase)
    }

    @Test fun restoreDuringRequestRetriesOnceAfterLateOfflineFailure() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val server = SyncServer().apply { createAction = { gate.await(); ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.onNetworkAvailabilityChanged(false)
        m.attach("XIAO-test") { stored(it) }; runCurrent()
        m.onNetworkAvailabilityChanged(true); runCurrent()
        assertEquals(1, server.creates.size) // 진행 중인 호출과 병렬로 새 요청을 시작하지 않는다.
        server.createAction = { ApiResult.Ok(server.run) }; gate.complete(Unit); runCurrent()
        assertEquals(2, server.creates.size); assertEquals(1, server.acks.size)
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }

    @Test fun internetRestoreDoesNotRetryServerRejection() = runTest(dispatcher) {
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Server(403, "FORBIDDEN", "권한 없음")) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.onNetworkAvailabilityChanged(false)
        m.attach("XIAO-test") { stored(it) }; runCurrent()
        m.onNetworkAvailabilityChanged(true); runCurrent()
        assertEquals(1, server.creates.size); assertTrue(server.acks.isEmpty())
    }

    @Test fun pauseStopsNetworkCollectionAndDoesNotRetryInBackground() = runTest(dispatcher) {
        val availability = MutableStateFlow(false)
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Offline) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val monitor = object : NetworkMonitor { override val availability = availability }
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.READY), network = monitor)
        models.put("sync", m); m.resume(); m.attach("XIAO-test") { stored(it) }; runCurrent()
        assertEquals(1, availability.subscriptionCount.value)
        m.pause(); runCurrent(); availability.value = true; runCurrent()
        assertEquals(0, availability.subscriptionCount.value); assertEquals(1, server.creates.size)
    }

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
    // 모델 재생성은 불명확한 POST 결과부터 같은 키/본문으로 확인한 뒤 새 BLE challenge로 전환한다.
    @Test fun lostCreateResponseRenewsChallengeAcrossRecreation() = runTest(dispatcher) {
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Offline) } }
        val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store); m.attach("XIAO-test") { stored(it) }; runCurrent(); val first = server.creates.single(); models.clear()
        server.createAction = { ApiResult.Ok(server.run) }
        val second = model(server, KeyringSyncStore(prefs)); second.attach("XIAO-test", { "B".repeat(42) + "A" }) {
            assertEquals("B".repeat(42) + "A", it.deviceNonce); stored(it)
        }; runCurrent()
        assertEquals(3, server.creates.size)
        assertEquals(first, server.creates[1]); assertEquals(server.bodies.first(), server.bodies[1])
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
        assertEquals(3, server.creates.size); assertEquals(oldKey, server.creates[1].second); assertEquals(1, server.acks.size)
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
    // 기존 ON/OFF의 GET200/stale:true도 구버전 확정이다. 복구 ACK를 반복하지 않고 갱신한다.
    @Test fun restoredPendingAckRenewsAfterGetConfirmsStale() = runTest(dispatcher) {
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
            assertEquals(original, server.acks.last()); assertEquals(1, server.acks.size)
            "B".repeat(42) + "A"
        }) { assertEquals(2L, it.settingsVersion); stored(it) }; runCurrent()
        assertEquals(2, server.acks.size); assertEquals(2, server.creates.size)
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
    @Test fun wrongBoardForOwnedDeviceIdKeepsFailureEvenWhenAnotherRunWasQueued() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); val m = model(server, store)
        m.attach("XIAO-other") { error("No send") }; runCurrent()
        // 내 계정의 deviceId라도 연결된 실물의 serial이 다르면 대상을 저장하지 않는다.
        m.useExisting(syncRun.deviceId, "XIAO-other")
        m.retryRecovery()                                       // 실패 처리 중에 들어온 다시 돌기 요청
        runCurrent()
        assertTrue(m.state.value.recoveryFailed); assertFalse(m.state.value.busy)
        assertTrue(store.read(1).isEmpty()); assertTrue(server.creates.isEmpty())
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

    /** 실제 HTTP 직렬화에서 구형 요청은 필드 하나, 성향 요청은 정확히 두 필드를 보낸다. */
    @Test fun httpExplicitProfileSelectionContainsOnlyNonceAndFormat() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val client = createHttpClient("https://tingle.test/dev/api/", false, MockEngine { request ->
            bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond(ApiJson.encodeToString(KeyringSyncRun.serializer(), syncRun), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            HttpKeyringSyncRepository(client).create(syncRun.deviceId, "same-key",
                SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.PROFILE_FORMAT))
            assertEquals(setOf("deviceNonce", "payloadFormat"), bodies.single().keys)
            assertEquals(KeyringSyncProtocol.PROFILE_FORMAT, bodies.single().getValue("payloadFormat").jsonPrimitive.content)
        } finally { client.close() }
    }

    /** 발급 전에 본문을 디스크에 고정하고 HTTP 유실 후 지원 정보가 바뀌어도 같은 본문을 쓴다. */
    @Test fun profileSelectionIsDurableAndOfflineRetryDoesNotReevaluateCapability() = runTest(dispatcher) {
        val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs)
        val server = SyncServer(); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val payload = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode("TPS1".encodeToByteArray() + ByteArray(192))
        server.run = syncRun.copy(payloadFormat = KeyringSyncProtocol.PROFILE_FORMAT, payload = payload)
        server.createAction = {
            assertEquals(server.bodies.last(), KeyringSyncStore(prefs).read(1).single().createBody)
            ApiResult.Fail(AppError.Offline)
        }
        var challenges = 0
        val m = model(server, store)
        m.attachRequest("XIAO-test", { challenges++; SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.PROFILE_FORMAT) }) { stored(it) }
        runCurrent()
        server.createAction = { ApiResult.Ok(server.run) }
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(1, challenges); assertEquals(server.creates[0], server.creates[1])
        assertEquals(server.bodies[0], server.bodies[1]); assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }

    /** TSV3 응답이 유실돼도 원래 본문과 키를 다시 쓰고, 저장 증명 접수 전에는 완료로 올리지 않는다. */
    @Test fun fullSettingsSelectionSurvivesHttpRetryWithOriginalBody() = runTest(dispatcher) {
        val prefs = SyncPrefs()
        val store = KeyringSyncStore(prefs)
        val server = SyncServer()
        val payload = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode("TSV3".encodeToByteArray() + ByteArray(483))
        server.run = syncRun.copy(payloadFormat = KeyringSyncProtocol.FULL_SETTINGS_FORMAT, payload = payload,
            proofProtocolVersion = KeyringRegistrationProtocol.VERSION_2,
            serverProofProtocolVersion = "tingle-dev-settings-proof-v2", ownershipEpoch = 2L,
            settingsVersion = 12L, currentSettingsVersion = 12L)
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        server.createAction = { ApiResult.Fail(AppError.Offline) }
        val request = SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.FULL_SETTINGS_FORMAT)
        val m = model(server, store)
        m.attachRequest("XIAO-test", { request }) { run ->
            assertEquals(payload, run.payload)
            assertEquals(SyncPhase.Sending, m.state.value.items.single().phase)
            stored(run)
        }
        runCurrent()
        val persisted = KeyringSyncStore(prefs).read(1).single()
        assertEquals(request, persisted.createBody)
        server.createAction = { ApiResult.Ok(server.run) }
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(listOf(persisted.createKey, persisted.createKey), server.creates.map { it.second })
        assertEquals(listOf(request, request), server.bodies)
        assertEquals(SyncPhase.ApplyFirmwareRequired, m.state.value.items.single().phase)
        assertTrue(store.read(1).single().complete)
        assertTrue(server.applications.isEmpty())
    }

    /** 첫 READY 직후 발급한 새 버전도 적용 POST에 성공한 뒤 서버에서 READY를 다시 읽는다. */
    @Test fun signupAndFollowUpVersionBothRequireApplicationBeforeReadyLookup() = runTest(dispatcher) {
        val signup = tsv3Run().copy(settingsVersion = 8, currentSettingsVersion = 8)
        val nextId = "44444444-4444-4444-8444-444444444444"
        val ready = signup.copy(syncRunId = nextId, settingsSnapshotId = nextId,
            settingsVersion = 9, currentSettingsVersion = 9, payloadHash = "b".repeat(64))
        val followUpResponse = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val lookups = mutableListOf<Long>()
        val server = SyncServer().apply {
            run = signup
            createAction = {
                val issued = listOf(signup, ready)[creates.lastIndex]
                run = issued.copy(deviceNonce = bodies.last().deviceNonce)
                events += "issue:${run.settingsVersion}"
                ApiResult.Ok(run)
            }
            appliedAction = {
                if (applications.last().second.settingsVersion == 9L) followUpResponse.await()
                events += "applied:${applications.last().second.settingsVersion}"
                ApiResult.Ok(Unit)
            }
        }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val session = object : SessionStore {
            override val state = MutableStateFlow<Session>(Session.SignedIn(1, OnboardingStatus.SYNC_PENDING, false))
            override val events = emptyFlow<SessionEvent>()
            override suspend fun refreshOnboarding(): ApiResult<Unit> {
                val confirmedVersion = server.applications.last().second.settingsVersion
                assertTrue(store.read(1).single().appliedConfirmed)
                lookups += confirmedVersion
                state.value = Session.SignedIn(1, OnboardingStatus.READY, true)
                return ApiResult.Ok(Unit)
            }
        }
        val m = model(server, store, session)
        m.attachRequest("XIAO-test", { SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.FULL_SETTINGS_FORMAT) },
            getApplied = {
                assertEquals(it.settingsVersion, server.acks.last().second.settingsVersion)
                assertEquals(it.syncRunId, store.read(1).single().takeIf { ticket -> ticket.complete }?.identity?.syncRunId)
                assertEquals(if (it.settingsVersion == 8L) OnboardingStatus.SYNC_PENDING else OnboardingStatus.READY,
                    (session.state.value as Session.SignedIn).status)
                events += "proof:${it.settingsVersion}"
                appliedProof(it)
            }) { stored(it) }
        runCurrent()
        assertEquals(listOf(8L), lookups)
        assertEquals(listOf(8L, 9L), server.applications.map { it.second.settingsVersion })
        assertFalse(store.read(1).single().appliedConfirmed)
        followUpResponse.complete(Unit); runCurrent()
        assertEquals(listOf("issue:8", "proof:8", "applied:8", "issue:9", "proof:9", "applied:9"), events)
        assertEquals(listOf(8L, 9L), lookups)
        assertEquals(4, (server.acks + server.applications).map { it.first }.distinct().size)
        assertEquals(2, server.creates.size)
        assertEquals(9L, store.read(1).single().takeIf { it.appliedOnboardingChecked }?.identity?.settingsVersion)
        assertEquals(OnboardingStatus.READY, (session.state.value as Session.SignedIn).status)
    }

    /** 적용 POST는 성공했어도 그 사이 새 버전이 생기면 조회만 반복하지 않고 최신 설정으로 넘어간다. */
    @Test fun appliedRecoveryRenewsWhenOnboardingNeedsNewerSettings() = runTest(dispatcher) {
        for (rejectStale in listOf(false, true)) {
            val old = tsv3Run().copy(settingsVersion = 8, currentSettingsVersion = 8)
            val nextId = "44444444-4444-4444-8444-444444444444"
            val next = old.copy(syncRunId = nextId, settingsSnapshotId = nextId,
                settingsVersion = 9, currentSettingsVersion = 9, payloadHash = "b".repeat(64))
            val server = SyncServer().apply {
                run = old
                createAction = {
                    run = (if (creates.size == 1) old else next).copy(deviceNonce = bodies.last().deviceNonce)
                    ApiResult.Ok(run)
                }
                getAction = {
                    if (applications.last().second.settingsVersion == 8L) {
                        if (rejectStale) ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "old"))
                        else ApiResult.Ok(run.copy(stale = true, currentSettingsVersion = 9))
                    } else ApiResult.Ok(run)
                }
            }
            val store = KeyringSyncStore(SyncPrefs())
            store.rememberTarget(1, old.deviceId, "XIAO-test")
            val session = object : SessionStore {
                // 이미 가입한 계정도 새 설정 때문에 서버의 nextStep만 SYNC_PENDING일 수 있다.
                override val state = MutableStateFlow<Session>(Session.SignedIn(1, OnboardingStatus.READY, true))
                override val events = emptyFlow<SessionEvent>()
                override suspend fun refreshOnboarding(): ApiResult<Unit> {
                    val ready = server.applications.last().second.settingsVersion == 9L
                    state.value = Session.SignedIn(1,
                        if (ready) OnboardingStatus.READY else OnboardingStatus.SYNC_PENDING, true)
                    return ApiResult.Ok(Unit)
                }
            }
            val m = model(server, store, session)
            m.attachRequest("XIAO-test", { SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.FULL_SETTINGS_FORMAT) },
                getApplied = ::appliedProof) { stored(it) }
            runCurrent()
            assertEquals(listOf(8L, 9L), server.applications.map { it.second.settingsVersion })
            assertEquals(2, server.creates.size)
            assertEquals(2, server.acks.size)
            assertEquals(1, server.getCount)
            assertEquals(9L, store.read(1).single().identity?.settingsVersion)
            assertTrue(store.read(1).single().appliedOnboardingChecked)
            assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
            models.clear()
        }
    }

    /** 설정이 최신이면 SYNC_PENDING만으로 새 실행을 만들지 않고 사용자의 상태 재조회를 기다린다. */
    @Test fun pendingOnboardingKeepsCurrentAppliedRunWithoutRepostingProof() = runTest(dispatcher) {
        val server = SyncServer().apply { run = tsv3Run() }
        val store = KeyringSyncStore(SyncPrefs())
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val session = TestSession(OnboardingStatus.SYNC_PENDING)
        val m = model(server, store, session)
        m.attachRequest("XIAO-test", { SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.FULL_SETTINGS_FORMAT) },
            getApplied = ::appliedProof) { stored(it) }
        runCurrent()
        assertEquals(SyncPhase.ApplyConfirmed, m.state.value.items.single().phase)
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(1, server.creates.size)
        assertEquals(1, server.acks.size)
        assertEquals(1, server.applications.size)
        assertEquals(2, server.getCount)
        assertTrue(store.read(1).single().appliedConfirmed)
        assertFalse(store.read(1).single().appliedOnboardingChecked)
    }

    /** READY 계정에서 적용 확인 뒤 상태 조회가 끊기면, 재시작 후 조회만 다시 한다. */
    @Test fun readyAccountRetriesOnlyOnboardingAfterAppliedSuccess() = runTest(dispatcher) {
        val server = SyncServer().apply { run = tsv3Run() }
        val prefs = SyncPrefs()
        val store = KeyringSyncStore(prefs); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val session = TestSession(OnboardingStatus.READY).apply { refreshResult = ApiResult.Fail(AppError.Offline) }
        val m = model(server, store, session)
        m.attachRequest("XIAO-test", { SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.FULL_SETTINGS_FORMAT) },
            getApplied = ::appliedProof) { stored(it) }
        runCurrent()
        assertEquals(SyncPhase.ApplyRetry, m.state.value.items.single().phase)
        assertTrue(store.read(1).single().appliedConfirmed)
        assertFalse(store.read(1).single().appliedOnboardingChecked)
        models.clear()
        session.refreshResult = ApiResult.Ok(Unit)
        model(server, KeyringSyncStore(prefs), session); runCurrent()
        assertEquals(1, server.creates.size)
        assertEquals(1, server.acks.size)
        assertEquals(1, server.applications.size)
        assertEquals(2, session.refreshes)
        assertTrue(store.read(1).single().appliedOnboardingChecked)
    }

    /** HTTP 결과가 유실되면 앱 재시작 뒤에도 BLE나 저장 ACK 없이 동일 적용 본문과 키를 사용한다. */
    @Test fun unknownAppliedResponseReusesProofWithoutBleAfterRestart() = runTest(dispatcher) {
        val server = SyncServer().apply { run = tsv3Run(); appliedAction = { ApiResult.Fail(AppError.Offline) } }
        val prefs = SyncPrefs()
        val store = KeyringSyncStore(prefs); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val session = TestSession(OnboardingStatus.SYNC_PENDING)
        val m = model(server, store, session)
        m.attachRequest("XIAO-test", { SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.FULL_SETTINGS_FORMAT) },
            getApplied = ::appliedProof) { stored(it) }
        runCurrent()
        assertEquals(SyncPhase.ApplyRetry, m.state.value.items.single().phase)
        assertEquals(0, session.refreshes)
        val first = server.applications.single()
        models.clear()
        server.appliedAction = { ApiResult.Ok(Unit) }
        model(server, KeyringSyncStore(prefs), session); runCurrent()
        assertEquals(listOf(first, first), server.applications)
        assertEquals(1, server.acks.size)
        assertEquals(1, session.refreshes)
        assertTrue(store.read(1).single().appliedConfirmed)
    }

    /** 기기 적용 대기는 제한된 횟수로 멈춘 뒤 저장을 반복하지 않고 적용 확인만 재시도한다. */
    @Test fun pendingProofStopsPollingAndCanRetrySameStoredRun() = runTest(dispatcher) {
        val server = SyncServer().apply { run = tsv3Run() }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store)
        var reads = 0
        m.attachRequest("XIAO-test", { SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.FULL_SETTINGS_FORMAT) },
            getApplied = { identity ->
                if (++reads <= 3) throw BleCommandException("SETTINGS_APPLY_PENDING")
                appliedProof(identity)
            }) { stored(it) }
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, reads)
        assertEquals(SyncPhase.ApplyRetry, m.state.value.items.single().phase)
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(4, reads)
        assertEquals(1, server.creates.size)
        assertEquals(1, server.acks.size)
        assertEquals(1, server.applications.size)
    }

    @Test fun appliedProofMustMatchRequestAndStoredRun() {
        val identity = tsv3Run().identity()
        val id = "33333333-3333-4333-8333-333333333333"
        val request = Json.parseToJsonElement(KeyringSyncProtocol.appliedRequest(identity, id).decodeToString()).jsonObject
        assertEquals(JsonPrimitive("GET_SETTINGS_APPLIED_PROOF"), request["type"])
        assertEquals(JsonPrimitive(identity.syncRunId), request["syncRunId"])
        val response = buildJsonObject {
            put("protocolVersion", 1); put("type", "SETTINGS_APPLIED_PROOF"); put("requestId", id)
            put("syncRunId", identity.syncRunId); put("settingsVersion", identity.settingsVersion); put("payloadHash", identity.payloadHash)
            put("proof", ApiJson.encodeToJsonElement(KeyringProof.serializer(), appliedProof(identity).proof))
        }
        assertEquals(appliedProof(identity), KeyringSyncProtocol.appliedResponse(response.toString().encodeToByteArray(), id, identity))
        for ((field, wrong) in mapOf("requestId" to JsonPrimitive("wrong"), "syncRunId" to JsonPrimitive("wrong"),
            "settingsVersion" to JsonPrimitive(identity.settingsVersion + 1), "payloadHash" to JsonPrimitive("wrong"))) {
            val bad = JsonObject(response + (field to wrong)).toString().encodeToByteArray()
            assertFails { KeyringSyncProtocol.appliedResponse(bad, id, identity) }
        }
    }

    /** 구형 내구 파일은 형식을 추측해 덧붙이지 않고 기존 nonce 단독 요청을 먼저 복구한다. */
    @Test fun legacyPendingBodyIsReplayedBeforeNewProfileRequest() = runTest(dispatcher) {
        val prefs = SyncPrefs(); val store = KeyringSyncStore(prefs)
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val legacy = store.read(1).single().copy(deviceNonce = "A".repeat(43), nonceReceivedAt = "2026-11-04T12:00:00Z")
        store.save(1, legacy)
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Offline) } }
        val m = model(server, KeyringSyncStore(prefs)); runCurrent()
        assertEquals(legacy.createKey, server.creates.single().second)
        assertEquals(SyncChallengeBody("A".repeat(43)), server.bodies.single())
        assertTrue(server.acks.isEmpty()); assertEquals(legacy, store.read(1).single())
        m.pause()
    }

    /** 미허용 응답은 ON/OFF 재요청으로 우회하지 않고 정책 대기로 남긴다. */
    @Test fun rejectedProfileIssuanceDoesNotFallbackOrDiscardRequest() = runTest(dispatcher) {
        val server = SyncServer().apply { createAction = { ApiResult.Fail(AppError.Server(422, "POLICY_UNRESOLVED", "wait")) } }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store)
        m.attachRequest("XIAO-test", { SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.PROFILE_FORMAT) }) { error("No BLE") }
        runCurrent(); m.retry(syncRun.deviceId); runCurrent()
        assertEquals(server.creates[0], server.creates[1]); assertEquals(server.bodies[0], server.bodies[1])
        assertEquals(KeyringSyncProtocol.PROFILE_FORMAT, store.read(1).single().createBody?.payloadFormat)
        assertEquals(SyncPhase.PolicyWaiting, m.state.value.items.single().phase); assertTrue(server.acks.isEmpty())
    }

    /** 서버가 다른 형식을 반환하면 성향 저장 완료로 잘못 표시하지 않는다. */
    @Test fun unexpectedOnOffResponseForProfileRequestNeverReachesBoard() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs())
        store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store)
        m.attachRequest("XIAO-test", { SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.PROFILE_FORMAT) }) { error("Wrong format") }
        runCurrent(); assertEquals(SyncPhase.Failed, m.state.value.items.single().phase); assertTrue(server.acks.isEmpty())
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

    // ---- 재연결 준비 상태(재부팅·OFF·미확인)와 끊긴 전송 재개 ----
    private val fullBody = SyncChallengeBody("A".repeat(43), KeyringSyncProtocol.FULL_SETTINGS_FORMAT)
    private fun board(run: KeyringSyncRun, applied: Boolean?, timeReady: Boolean?, matchingReady: Boolean?) =
        BleDeviceInfo("XIAO-test", "test", true, ownershipEpoch = run.ownershipEpoch, storedSettingsVersion = run.settingsVersion,
            storedPayloadHash = run.payloadHash, readiness = BleOwnerFlowStatus(applied = applied, timeReady = timeReady, matchingReady = matchingReady))
    private class MatchingChoice(var enabled: Boolean) : MatchingSettingsRepository {
        override suspend fun get(userId: Long, kind: SettingsKind): ApiResult<SettingsDocument> =
            ApiResult.Ok(SettingsDocument(ConflictValues.Matching(enabled, false), "\"e\""))
        override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String): ApiResult<SettingsDocument> =
            error("No save")
    }
    private fun tsv3Server() = SyncServer().apply {
        run = tsv3Run()
        createAction = { run = run.copy(state = "PENDING"); ApiResult.Ok(run) }
    }

    @Test fun boardDecisionUsesFreshReadinessNotOnlyStoredHash() {
        val run = tsv3Run()
        assertFalse(KeyringBoardSync.needsResync(board(run, true, true, true), run))
        // 매칭 OFF·가입 전 matchingReady=false는 재전송 조건이 아니다.
        assertFalse(KeyringBoardSync.needsResync(board(run, true, true, false), run))
        // 재부팅: 같은 버전·해시라도 미적용·시간 미확보·미확인·복구 필요면 다시 보낸다.
        val ready = board(run, true, true, true)
        for (info in listOf(board(run, false, true, false), board(run, true, false, false), board(run, null, null, null),
            ready.copy(readiness = ready.readiness.copy(settingsRecoveryRequired = true)),
            ready.copy(ownershipEpoch = 3), ready.copy(storedPayloadHash = "0".repeat(64))))
            assertTrue(KeyringBoardSync.needsResync(info, run))
        // 이전 ON/OFF 형식은 두 플래그를 항상 false로 보고하므로 반복 전송하지 않는다.
        assertFalse(KeyringBoardSync.needsResync(board(syncRun, false, false, false), syncRun))
    }

    @Test fun readinessNeverReadyWhenUnknownAndSeparatesOffSignupAndRestricted() {
        val ready = BleOwnerFlowStatus(applied = true, timeReady = true, matchingReady = true)
        val waiting = ready.copy(matchingReady = false)
        assertEquals(KeyringMatchReadiness.Ready, KeyringBoardSync.readiness(ready, null, false))
        for (unknown in listOf(null, BleOwnerFlowStatus(), ready.copy(applied = null), ready.copy(timeReady = null),
            ready.copy(matchingReady = null), ready.copy(applied = false)))
            assertNotEquals(KeyringMatchReadiness.Ready, KeyringBoardSync.readiness(unknown, true, false))
        assertEquals(KeyringMatchReadiness.Unknown, KeyringBoardSync.readiness(null, true, false))
        assertEquals(KeyringMatchReadiness.NotApplied, KeyringBoardSync.readiness(waiting.copy(timeReady = false), true, false))
        assertEquals(KeyringMatchReadiness.MatchingOff, KeyringBoardSync.readiness(waiting, false, true))
        assertEquals(KeyringMatchReadiness.SignupPending, KeyringBoardSync.readiness(waiting, true, true))
        assertEquals(KeyringMatchReadiness.Restricted, KeyringBoardSync.readiness(waiting, true, false))
        assertEquals(KeyringMatchReadiness.Unknown, KeyringBoardSync.readiness(waiting, null, false))
    }

    /** 재부팅 뒤 같은 버전·해시라도 applied/timeReady=false면 새 challenge로 다시 보내고 매칭 준비까지 확인한다. */
    @Test fun rebootWithSameHashResyncsWithFreshChallengeAndBecomesReady() = runTest(dispatcher) {
        val server = tsv3Server(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        var info = board(server.run, true, true, true); var sends = 0
        val m = model(server, store, TestSession(OnboardingStatus.READY))
        fun connect(nonce: String) = m.attachRequest("XIAO-test", { fullBody.copy(deviceNonce = nonce) }, getApplied = ::appliedProof,
            getDeviceInfo = { info }) { sends++; info = board(it, true, true, true); stored(it) }
        connect("A".repeat(43)); runCurrent()
        assertEquals(1, sends); assertEquals(KeyringMatchReadiness.Ready, m.state.value.items.single().readiness)
        m.detach(); info = board(server.run, false, false, false)
        connect("B".repeat(42) + "A"); runCurrent()
        assertEquals(2, sends); assertEquals(2, server.creates.size); assertEquals(2, server.applications.size)
        assertNotEquals(server.creates[0].second, server.creates[1].second)
        assertEquals("B".repeat(42) + "A", server.bodies.last().deviceNonce)
        val item = m.state.value.items.single()
        assertEquals(SyncPhase.Confirmed, item.phase); assertEquals(KeyringMatchReadiness.Ready, item.readiness)
        m.retry(syncRun.deviceId); runCurrent(); assertEquals(2, sends)
    }

    /** 매칭 OFF는 적용 성공이다. matchingReady=false만으로 재전송하지 않고 OFF로 보여준다. */
    @Test fun matchingOffDoesNotRetryAndIsShownAsOff() = runTest(dispatcher) {
        val server = tsv3Server(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val info = board(server.run, true, true, false); var sends = 0
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.READY), matching = MatchingChoice(false))
            .also { models.put("sync", it); it.resume() }
        repeat(3) {
            m.detach()
            m.attachRequest("XIAO-test", { fullBody }, getApplied = ::appliedProof, getDeviceInfo = { info }) { sends++; stored(it) }
            runCurrent()
        }
        assertEquals(1, sends); assertEquals(1, server.creates.size)
        assertEquals(KeyringMatchReadiness.MatchingOff, m.state.value.items.single().readiness)
    }

    /** DEVICE_INFO를 읽지 못하면 최신으로 단정하지 않고, 준비 상태도 Ready로 표시하지 않는다. */
    @Test fun unreadableReadinessIsNeverShownReady() = runTest(dispatcher) {
        val server = tsv3Server(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store, TestSession(OnboardingStatus.READY))
        m.attachRequest("XIAO-test", { fullBody }, getApplied = ::appliedProof,
            getDeviceInfo = { throw BleCommandException("BUSY") }) { stored(it) }
        runCurrent()
        val item = m.state.value.items.single()
        assertEquals(SyncPhase.Confirmed, item.phase); assertEquals(KeyringMatchReadiness.Unknown, item.readiness)
    }

    /** OFF→ON 변경 중 전송이 끊기면 재연결에서 새 challenge·새 키로 이어 ON 버전을 적용한다. */
    @Test fun interruptedOnTransferResumesOnReconnectWithFreshChallenge() = runTest(dispatcher) {
        val off = tsv3Run().copy(settingsVersion = 16, currentSettingsVersion = 16)
        val onId = "55555555-5555-4555-8555-555555555555"
        val on = off.copy(syncRunId = onId, settingsSnapshotId = onId, settingsVersion = 17, currentSettingsVersion = 17,
            payloadHash = "c".repeat(64))
        var current = off
        val server = SyncServer().apply {
            run = off
            createAction = { run = current.copy(deviceNonce = bodies.last().deviceNonce); ApiResult.Ok(run) }
            getAction = {
                if (run.settingsVersion != current.settingsVersion) ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "old"))
                else ApiResult.Ok(run)
            }
        }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val changes = KeyringSettingsChanges()
        val m = KeyringSyncViewModel(server, store, server, TestSession(OnboardingStatus.READY), changes = changes)
            .also { models.put("sync", it); it.resume() }
        val sent = mutableListOf<Long>(); var cut = false; var nonce = 0
        fun connect() = m.attachRequest("XIAO-test", { nonce++; fullBody.copy(deviceNonce = ('A' + nonce).toString().repeat(42) + "A") },
            getApplied = ::appliedProof) { sent += it.settingsVersion; if (cut) { cut = false; error("Disconnected") }; stored(it) }
        connect(); runCurrent()
        assertEquals(16L, m.state.value.items.single().confirmedTsv3Version)
        current = on; cut = true; changes.saved(1); runCurrent()
        assertNotEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
        m.detach(); runCurrent(); connect(); runCurrent()
        assertEquals(listOf(16L, 17L, 17L), sent)
        // 끊긴 전송의 challenge를 재사용하지 않는다.
        assertEquals(3, server.bodies.map { it.deviceNonce }.distinct().size)
        assertEquals(3, server.creates.map { it.second }.distinct().size)
        assertEquals(listOf(16L, 17L), server.acks.map { it.second.settingsVersion })
        assertEquals(17L, m.state.value.items.single().confirmedTsv3Version)
        assertEquals(17L, store.read(1).single().takeIf { it.appliedOnboardingChecked }?.identity?.settingsVersion)
    }

    /** 보드 저장 증명을 받은 뒤 서버 확인 중 BLE가 끊기면 다시 보내지 않고 같은 ACK로 이어간다. */
    @Test fun disconnectDuringServerAckResumesFromStoredProofWithoutBle() = runTest(dispatcher) {
        val server = SyncServer(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val hang = CompletableDeferred<Unit>()
        server.ackAction = {
            if (server.acks.size == 1) hang.await()
            server.run = server.run.copy(state = "ACKED"); ApiResult.Ok(server.run)
        }
        var sends = 0
        val m = model(server, store); m.attach("XIAO-test") { sends++; stored(it) }; runCurrent()
        assertEquals(SyncPhase.Confirming, m.state.value.items.single().phase)
        m.detach(); runCurrent()
        assertEquals(1, sends); assertEquals(2, server.acks.size); assertEquals(server.acks[0], server.acks[1])
        assertEquals(SyncPhase.Confirmed, m.state.value.items.single().phase)
    }

    /** 이전 버전의 보드 응답은 새 버전을 완료로 만들지 않는다. 서버가 거절하면 최신 버전을 새로 보낸다. */
    @Test fun olderStoredResponseNeverCompletesNewerVersion() = runTest(dispatcher) {
        val v17 = tsv3Run().copy(settingsVersion = 17, currentSettingsVersion = 17)
        val nextId = "66666666-6666-4666-8666-666666666666"
        val v18 = v17.copy(syncRunId = nextId, settingsSnapshotId = nextId, settingsVersion = 18, currentSettingsVersion = 18,
            payloadHash = "d".repeat(64))
        var current = v17
        val server = SyncServer().apply {
            run = v17
            createAction = { run = current.copy(deviceNonce = bodies.last().deviceNonce); ApiResult.Ok(run) }
            ackAction = {
                if (acks.last().second.settingsVersion != current.settingsVersion)
                    ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "old"))
                else { run = run.copy(state = "ACKED"); ApiResult.Ok(run) }
            }
        }
        val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        val m = model(server, store, TestSession(OnboardingStatus.READY))
        var nonce = 0
        m.attachRequest("XIAO-test", { nonce++; fullBody.copy(deviceNonce = ('A' + nonce).toString().repeat(42) + "A") },
            getApplied = ::appliedProof) {
            // 보드가 v17을 저장하는 사이 사용자가 설정을 다시 바꿨다.
            if (it.settingsVersion == 17L) current = v18
            stored(it)
        }
        runCurrent()
        assertEquals(listOf(17L, 18L), server.acks.map { it.second.settingsVersion })
        assertEquals(listOf(18L), server.applications.map { it.second.settingsVersion })
        assertEquals(18L, m.state.value.items.single().confirmedTsv3Version)
        assertEquals(18L, store.read(1).single().identity?.settingsVersion)
    }

    /** 기록 저장소 오류 보드는 적용해도 applied=false로 남는다. 같은 설정을 연결마다 다시 보내지 않는다. */
    @Test fun storageErrorBoardIsNotResentEveryConnection() = runTest(dispatcher) {
        val run = tsv3Run()
        val broken = board(run, false, true, false).let { it.copy(readiness = it.readiness.copy(storageHealthy = false)) }
        assertFalse(KeyringBoardSync.needsResync(broken, run))
        assertTrue(KeyringBoardSync.needsResync(broken.copy(storedSettingsVersion = 11), run))
        assertEquals(KeyringMatchReadiness.StorageError, KeyringBoardSync.readiness(broken.readiness, true, false))
        val server = tsv3Server(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        var sends = 0
        val m = model(server, store, TestSession(OnboardingStatus.READY))
        repeat(3) {
            m.detach()
            m.attachRequest("XIAO-test", { fullBody }, getApplied = ::appliedProof, getDeviceInfo = { broken }) { sends++; stored(it) }
            runCurrent()
        }
        assertEquals(1, sends); assertEquals(1, server.creates.size)
        assertEquals(KeyringMatchReadiness.StorageError, m.state.value.items.single().readiness)
    }

    /** 복구 중 보드가 같은 버전을 거부하면 서버 버전이 바뀌거나 사용자가 재시도하기 전까지 발급·전송을 반복하지 않는다. */
    @Test fun recoveryRejectionOfSameVersionStopsAutomaticResend() = runTest(dispatcher) {
        val server = tsv3Server(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        var info = board(server.run, true, true, true); var sends = 0; var rejecting = false
        val m = model(server, store, TestSession(OnboardingStatus.READY))
        fun connect() {
            m.detach()
            m.attachRequest("XIAO-test", { fullBody }, getApplied = ::appliedProof, getDeviceInfo = { info }) {
                sends++; if (rejecting) throw BleCommandException("SETTINGS_RECOVERY_REQUIRED"); stored(it)
            }
            runCurrent()
        }
        connect(); assertEquals(1, sends)
        info = board(server.run, false, false, false).let { it.copy(readiness = it.readiness.copy(settingsRecoveryRequired = true)) }
        rejecting = true
        connect()
        assertEquals(2, sends); assertEquals(2, server.creates.size)
        assertEquals(SyncPhase.RecoveryVersionRequired, m.state.value.items.single().phase)
        repeat(2) { connect() }
        assertEquals(2, sends); assertEquals(2, server.creates.size)
        assertEquals(SyncPhase.RecoveryVersionRequired, m.state.value.items.single().phase)
        // 사용자가 다시 시도하면 한 번 더 보낸다.
        m.retry(syncRun.deviceId); runCurrent()
        assertEquals(3, sends); assertEquals(SyncPhase.RecoveryVersionRequired, m.state.value.items.single().phase)
        // 서버가 더 높은 버전을 준비하면 자동으로 새 버전을 보낸다.
        val nextId = "77777777-7777-4777-8777-777777777777"
        val next = server.run.copy(syncRunId = nextId, settingsSnapshotId = nextId, settingsVersion = 13, currentSettingsVersion = 13,
            payloadHash = "e".repeat(64), state = "PENDING")
        server.getAction = { ApiResult.Fail(AppError.Server(409, "SYNC_VERSION_STALE", "old")) }
        server.createAction = { server.run = next.copy(deviceNonce = server.bodies.last().deviceNonce); ApiResult.Ok(server.run) }
        rejecting = false; info = board(next, true, true, true)
        connect()
        assertEquals(4, sends); assertEquals(13L, m.state.value.items.single().confirmedTsv3Version)
    }

    private fun storage(info: BleDeviceInfo, quarantined: Boolean?) =
        info.copy(readiness = info.readiness.copy(storageHealthy = false, recordsRecoveryRequired = quarantined))

    /** 격리 기록 보드는 storageHealthy=false라도 재부팅 뒤 한 번 다시 보내 적용하고, 준비되면 Ready로 보인다. */
    @Test fun quarantinedBoardResyncsOnceAfterRebootAndCanBeReady() = runTest(dispatcher) {
        val run = tsv3Run()
        assertTrue(KeyringBoardSync.needsResync(storage(board(run, false, false, false), true), run))
        // 기록 저장소 오류만 있는 보드도 재부팅(시간 미확보) 뒤에는 한 번 보낸다.
        assertTrue(KeyringBoardSync.needsResync(storage(board(run, false, false, false), false), run))
        assertEquals(KeyringMatchReadiness.Ready,
            KeyringBoardSync.readiness(storage(board(run, true, true, true), true).readiness, true, false))
        val server = tsv3Server(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        var info = storage(board(run, true, true, true), true); var sends = 0
        val m = model(server, store, TestSession(OnboardingStatus.READY))
        fun connect() {
            m.detach()
            m.attachRequest("XIAO-test", { fullBody }, getApplied = ::appliedProof, getDeviceInfo = { info }) {
                sends++; info = storage(board(it, true, true, true), true); stored(it)
            }
            runCurrent()
        }
        connect(); assertEquals(1, sends)
        info = storage(board(run, false, false, false), true)
        connect(); assertEquals(2, sends)
        assertEquals(KeyringMatchReadiness.Ready, m.state.value.items.single().readiness)
        connect(); assertEquals(2, sends)
    }

    /** 보드가 재부팅해 복구 표시가 풀리면 기억한 차단을 해제하고 같은 버전을 다시 보낸다. */
    @Test fun recoveryBlockClearsWhenBoardNoLongerNeedsRecovery() = runTest(dispatcher) {
        val server = tsv3Server(); val store = KeyringSyncStore(SyncPrefs()); store.rememberTarget(1, syncRun.deviceId, "XIAO-test")
        var info = board(server.run, true, true, true); var sends = 0; var rejecting = false
        val m = model(server, store, TestSession(OnboardingStatus.READY))
        fun connect() {
            m.detach()
            m.attachRequest("XIAO-test", { fullBody }, getApplied = ::appliedProof, getDeviceInfo = { info }) {
                sends++; if (rejecting) throw BleCommandException("SETTINGS_RECOVERY_REQUIRED")
                info = board(it, true, true, true); stored(it)
            }
            runCurrent()
        }
        connect()
        info = board(server.run, false, false, false).let { it.copy(readiness = it.readiness.copy(settingsRecoveryRequired = true)) }
        rejecting = true
        connect(); connect()
        assertEquals(2, sends); assertEquals(SyncPhase.RecoveryVersionRequired, m.state.value.items.single().phase)
        info = board(server.run, false, false, false); rejecting = false
        connect()
        assertEquals(3, sends)
        val item = m.state.value.items.single()
        assertEquals(SyncPhase.Confirmed, item.phase); assertEquals(KeyringMatchReadiness.Ready, item.readiness)
    }
}
