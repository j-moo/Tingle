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
    var listFail = false
    var challenge = challengeValue
    var onSend: suspend () -> ApiResult<OwnedKeyring> = { items = listOf(receipt); ApiResult.Ok(receipt) }
    override suspend fun challenge(key: String): ApiResult<KeyringChallenge> { challenges += key; return ApiResult.Ok(challenge) }
    override suspend fun register(key: String, body: RegisterKeyringBody): ApiResult<OwnedKeyring> {
        sent += key to body; return onSend()
    }
    override suspend fun list(): ApiResult<List<OwnedKeyring>> = if (listFail) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(items)
}

@OptIn(ExperimentalCoroutinesApi::class)
class KeyringRegistrationTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() { models.clear(); Dispatchers.resetMain() }
    private fun model(server: RegistrationServer, prefs: RegistrationPrefs = RegistrationPrefs(),
        session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED), name: String = "registration") =
        KeyringRegistrationViewModel(server, server, KeyringRegistrationStore(prefs), session, KeyringSyncStore(prefs),
            { Instant.parse("2026-11-04T12:00:00Z") }).also { models.put(name, it) }

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
        m.restart(); runCurrent(); server.challenge = challengeValue.copy(expiresAt = "2026-11-04T11:59:59Z")
        m.start("XIAO-test") { error("Expired challenge") }; runCurrent()
        assertEquals(RegistrationIssue.ExpiredChallenge, m.state.value.issue); assertTrue(server.sent.isEmpty())
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
    @Test fun serverFiveHundredKeepsRequestButExplicitProofRejectionAllowsRestart() = runTest(dispatcher) {
        val server = RegistrationServer(); val m = model(server); runCurrent()
        server.onSend = { ApiResult.Fail(AppError.Server(503, "SERVICE_UNAVAILABLE", "retry")) }
        m.start("XIAO-test") { proofBody(it) }; runCurrent(); m.restart(); runCurrent()
        assertNotNull(m.state.value.pending); assertFalse(m.state.value.canRestart)
        server.onSend = { ApiResult.Fail(AppError.Server(422, "INVALID_DEVICE_PROOF", "proof")) }
        m.retry { error("No new proof") }; runCurrent(); assertTrue(m.state.value.canRestart)
        m.restart(); runCurrent(); assertNull(m.state.value.pending)
    }
    @Test fun boundDeviceRejectionAllowsExplicitRestartWithAnotherBoard() = runTest(dispatcher) {
        val server = RegistrationServer(); val prefs = RegistrationPrefs(); val m = model(server, prefs); runCurrent()
        val existing = OwnedKeyring(2, "REGISTERED", "test")
        server.items = listOf(existing)
        server.onSend = { ApiResult.Fail(AppError.Server(409, "DEVICE_ALREADY_BOUND", "bound")) }
        m.start("XIAO-bound") { proofBody(it, "XIAO-bound") }; runCurrent()
        val rejected = server.sent.single()
        // 명시적 귀속 거절은 성공 미확인과 다르다. 사용자가 재시작하기 전까지 기록은 보존한다.
        assertTrue(m.state.value.canRestart); assertNotNull(m.state.value.pending)
        assertNull(m.state.value.confirmed)
        m.restart(); runCurrent()
        assertNull(m.state.value.pending); assertEquals(PrefRead.Found(null), KeyringRegistrationStore(prefs).read(1))
        server.onSend = { server.items = server.items + server.receipt; ApiResult.Ok(server.receipt) }
        m.start("XIAO-new") { proofBody(it, "XIAO-new") }; runCurrent()
        assertEquals(2, server.sent.size); assertNotEquals(rejected.first, server.sent.last().first)
        assertEquals("XIAO-new", server.sent.last().second.deviceSerial)
        assertEquals(listOf(existing, server.receipt), server.items)
        assertEquals(server.receipt, m.state.value.confirmed)
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
