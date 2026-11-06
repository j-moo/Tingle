package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** VM의 동시 작업·계정 경계를 검증하는 테스트 전용 기기. 실제 무선 검증과 구분한다. */
private class ProbeFixture : KeyringBle {
    var scanning = false
    var connections = 0
    var delayResponse: suspend () -> Unit = {}
    var wrongRequest = false
    var serial = "XIAO-test"
    var visible = true
    var scans = 0
    var currentId = "a"
    var finiteScan = false
    // v2 검사에서 연결 이후 기기 정보가 바뀌는 상황과 실제 쓰기 전 재조회를 관찰한다.
    var extraInfo = emptyMap<String, JsonElement>()
    var commandReply: ((JsonObject) -> JsonObject)? = null
    val commands = mutableListOf<String>()
    // 보호 경로 실패와 계정 변경 대기를 제어하며 일반 RX 우회 여부를 기록한다.
    var protect: suspend () -> Unit = {}
    var protectedRequest = false
    val linkState = MutableStateFlow(BleLinkState.Ready)
    val link = object : BleConnection {
        override val state = linkState
        override suspend fun prepareProtectedTransport() { commands += "PROTECT"; protect() }
        override suspend fun exchangeProtected(request: ByteArray, maxResponseBytes: Int, responseTimeoutMillis: Long): ByteArray {
            protectedRequest = true
            try { return exchange(request, maxResponseBytes, responseTimeoutMillis) }
            finally { protectedRequest = false }
        }
        override suspend fun exchange(request: ByteArray, maxResponseBytes: Int, responseTimeoutMillis: Long): ByteArray {
            delayResponse()
            val input = Json.parseToJsonElement(request.decodeToString()).jsonObject
            commands += (if (protectedRequest) "PROTECTED_" else "") + input.getValue("type").jsonPrimitive.content
            if (input["type"] != JsonPrimitive("GET_DEVICE_INFO")) commandReply?.let { return it(input).toString().encodeToByteArray() }
            return buildJsonObject {
                put("protocolVersion", 1)
                put("requestId", if (wrongRequest) JsonPrimitive("wrong") else input.getValue("requestId"))
                if (input["type"] == JsonPrimitive("GET_DEVICE_INFO")) {
                    put("type", "DEVICE_INFO"); put("deviceSerial", serial); put("firmwareVersion", "0.1.0-registration")
                    put("supportedFunctions", JsonArray(listOf(JsonPrimitive("GET_STATUS"))))
                    extraInfo.forEach { (name, value) -> put(name, value) }
                } else { put("type", "STATUS"); put("batteryPercent", JsonNull) }
            }.toString().encodeToByteArray()
        }
        override fun close() { state.value = BleLinkState.Closed }
    }
    override fun scan() = flow {
        scanning = true
        scans++
        // 실물 Android처럼 두 번째 탐색부터 ID가 바뀐다. 이전 ID 비교로는 재연결 시험을 통과할 수 없다.
        currentId = if (scans == 1) "a" else "scan-$scans"
        try {
            emit(if (visible) listOf(BleCandidate(currentId, "Test", -50)) else emptyList())
            if (!finiteScan) awaitCancellation()
        }
        finally { scanning = false }
    }
    override suspend fun connect(candidateId: String): BleConnection {
        assertFalse(scanning, "스캔 정리를 기다린 뒤 연결해야 한다")
        assertEquals(currentId, candidateId, "이전 탐색의 ID를 재사용하면 안 된다")
        connections++
        linkState.value = BleLinkState.Ready
        return link
    }
    override fun isPreviousDevice(candidateId: String) = connections > 0 && candidateId == currentId
}

@OptIn(ExperimentalCoroutinesApi::class)
class BleProbeViewModelTest {
    /** 서버 응답을 중계하는 시험 봉투. 성향 계산이나 실제 Ed25519 검증을 대신하지 않는다. */
    private fun profileRun() = KeyringSyncRun("11111111-1111-4111-8111-111111111111", "11111111-1111-4111-8111-111111111111",
        4, "22222222-2222-4222-8222-222222222222", 12, 12, "PENDING", false,
        kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode("TPS1".encodeToByteArray() + ByteArray(192)), "base64url", KeyringSyncProtocol.PROFILE_FORMAT,
        "a".repeat(64), "A".repeat(43), "key-1", "tingle-device-proof-v1", "2026-11-06T00:00:00Z", null,
        "2026-11-06T00:00:01Z", "tingle-dev-settings-proof-v1", "dev-ed25519-799e2999c648fda3", "A".repeat(43), "A".repeat(86))

    private fun profilePort(run: KeyringSyncRun) = ProbeFixture().apply {
        extraInfo = mapOf("supportedFunctions" to JsonArray(listOf("GET_SYNC_CHALLENGE", "SYNC_SETTINGS").map(::JsonPrimitive)),
            "supportedPayloadFormats" to JsonArray(listOf(JsonPrimitive(run.payloadFormat))),
            "profileStorageCharacteristic" to JsonPrimitive(BleProfileStorageInfo.CHARACTERISTIC),
            "profileRequiresEncryption" to JsonPrimitive(true))
        commandReply = { input -> buildJsonObject {
            put("protocolVersion", 1); put("requestId", input.getValue("requestId"))
            if (input["type"] == JsonPrimitive("GET_SYNC_CHALLENGE")) {
                put("type", "SYNC_CHALLENGE"); put("deviceNonce", "A".repeat(43))
            } else {
                assertEquals(run.payload, input["payload"]?.jsonPrimitive?.content)
                put("type", "SETTINGS_RESULT"); put("syncRunId", run.syncRunId)
                put("settingsVersion", run.settingsVersion); put("payloadHash", run.payloadHash)
                put("stored", true); put("applied", false); put("timeReady", false)
                putJsonObject("proof") { put("protocolVersion", run.proofProtocolVersion); put("keyId", run.keyId); put("signature", "A".repeat(86)) }
            }
        } }
    }

    /** 페어링은 nonce보다 먼저, 성향 데이터는 보호 경로로만 전송한다. */
    @Test fun profileUsesProtectedChannelAfterProtectionBeforeNonce() = runTest(dispatcher) {
        val run = profileRun(); val port = profilePort(run); val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        assertEquals(run.deviceNonce, vm.syncChallenge("XIAO-test"))
        assertTrue(port.commands.indexOf("PROTECT") < port.commands.indexOf("GET_SYNC_CHALLENGE"))
        assertEquals(run.payloadHash, vm.syncSettings(run, "XIAO-test").ack.payloadHash)
        assertEquals(1, port.commands.count { it == "PROTECTED_SYNC_SETTINGS" })
        assertFalse("SYNC_SETTINGS" in port.commands)
    }

    /** 지원 정보 누락 또는 보호 실패는 일반 RX로 우회하지 않고 전송 전에 중단한다. */
    @Test fun profileUnsupportedOrProtectionFailureNeverFallsBack() = runTest(dispatcher) {
        for (unsupported in listOf(true, false)) {
            val run = profileRun(); val port = profilePort(run)
            if (unsupported) port.extraInfo = emptyMap()
            else port.protect = { throw BleException(BleFailure.SecureTransportRequired) }
            val vm = model(port); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
            assertFails { vm.syncSettings(run, "XIAO-test") }
            assertFalse(port.commands.any { "SYNC_SETTINGS" in it })
            store.clear()
        }
    }

    /** OS 보호 확인 대기 중 로그아웃하면 늦게 성공해도 이전 계정의 성향을 보내지 않는다. */
    @Test fun accountChangeDuringProtectionCannotSendProfile() = runTest(dispatcher) {
        val run = profileRun(); val port = profilePort(run); val gate = CompletableDeferred<Unit>()
        val session = TestSession(OnboardingStatus.SYNC_PENDING); val vm = model(port, session)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        port.protect = { withContext(NonCancellable) { gate.await() } }
        val send = async { runCatching { vm.syncSettings(run, "XIAO-test") } }; runCurrent()
        session.state.value = Session.SignedOut; runCurrent(); gate.complete(Unit); runCurrent()
        assertTrue(send.await().isFailure); assertFalse(port.commands.any { "SYNC_SETTINGS" in it })
    }

    /** 앱이 캐시한 연결 정보 대신 직전 기기 정보로 v2 전송 자격을 확인하는 회귀 검사. */
    @Test fun registrationV2RechecksDeviceAndPreservesUnownedProof() = runTest(dispatcher) {
        val port = ProbeFixture().apply {
            extraInfo = mapOf("ownershipEpoch" to JsonPrimitive(1), "ownerState" to JsonPrimitive("UNOWNED"),
                "supportedFunctions" to JsonArray(listOf(JsonPrimitive("GET_REGISTRATION_PROOF"))),
                "supportedProofProtocolVersions" to JsonArray(listOf(JsonPrimitive("tingle-device-proof-v2"))))
            commandReply = { request -> buildJsonObject {
                put("protocolVersion", 1); put("requestId", request.getValue("requestId")); put("type", "REGISTRATION_PROOF")
                put("deviceSerial", serial); put("ownershipEpoch", 1); put("ownerState", "UNOWNED")
                putJsonObject("proof") { put("protocolVersion", "tingle-device-proof-v2"); put("keyId", "key-1"); put("signature", "A".repeat(86)) }
            } }
        }
        val vm = model(port); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        val challenge = KeyringChallenge("11111111-1111-4111-8111-111111111111", "A".repeat(43),
            "2026-11-06T00:00:00Z", "tingle-device-proof-v2")
        assertEquals(1L, vm.registrationProof(challenge, "XIAO-test").ownershipEpoch)
        assertEquals(listOf("GET_DEVICE_INFO", "GET_DEVICE_INFO", "GET_REGISTRATION_PROOF"), port.commands)
        port.extraInfo = port.extraInfo + ("ownerState" to JsonPrimitive("BOUND"))
        assertFails { vm.registrationProof(challenge, "XIAO-test") }
        assertEquals(1, port.commands.count { it == "GET_REGISTRATION_PROOF" })
    }

    @Test fun settingsV2DoesNotWriteAfterOwnershipEpochChanges() = runTest(dispatcher) {
        val port = ProbeFixture().apply { extraInfo = mapOf("ownershipEpoch" to JsonPrimitive(1),
            "ownerState" to JsonPrimitive("BOUND"), "supportedProofProtocolVersions" to JsonArray(listOf(JsonPrimitive("tingle-device-proof-v2")))) }
        val vm = model(port); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        val run = KeyringSyncRun("11111111-1111-4111-8111-111111111111", "11111111-1111-4111-8111-111111111111",
            4, "22222222-2222-4222-8222-222222222222", 1, 1, "PENDING", false, "AQ", "base64url", "tingle-dev-settings-v1",
            "a".repeat(64), "A".repeat(43), "key-1", "tingle-device-proof-v2", "2026-11-05T12:00:00Z", null,
            "2026-11-05T12:00:01Z", "tingle-dev-settings-proof-v2", "dev-ed25519-799e2999c648fda3", "A".repeat(43), "A".repeat(86), 1)
        port.commandReply = { request -> buildJsonObject {
            put("protocolVersion", 1); put("requestId", request.getValue("requestId")); put("type", "SETTINGS_RESULT")
            put("syncRunId", run.syncRunId); put("settingsVersion", 1); put("payloadHash", run.payloadHash)
            put("stored", true); put("applied", false); put("timeReady", false)
            putJsonObject("proof") { put("protocolVersion", run.proofProtocolVersion); put("keyId", run.keyId); put("signature", "A".repeat(86)) }
        } }
        assertEquals("tingle-device-proof-v2", vm.syncSettings(run, "XIAO-test").ack.proof.protocolVersion)
        port.extraInfo = port.extraInfo + ("ownershipEpoch" to JsonPrimitive(2))
        assertFails { vm.syncSettings(run, "XIAO-test") }
        assertEquals(1, port.commands.count { it == "SYNC_SETTINGS" })
        assertEquals(3, port.commands.count { it == "GET_DEVICE_INFO" })
    }
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun before() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun after() { store.clear(); Dispatchers.resetMain() }
    private fun model(port: ProbeFixture, session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED)) =
        BleProbeViewModel(port, session).also { store.put("ble", it) }

    @Test fun scanStopsBeforeConnectAndDoubleClickIsIgnored() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); vm.connect("a"); runCurrent()
        assertEquals(1, port.connections); assertTrue(vm.state.value.connected)
        assertEquals("XIAO-test", vm.state.value.info?.serial)
    }
    @Test fun readingStatusDoesNotInventBatteryOrAdvanceSignup() = runTest(dispatcher) {
        val port = ProbeFixture(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val vm = model(port, session)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent(); vm.readStatus(); runCurrent()
        assertNotNull(vm.state.value.status); assertNull(vm.state.value.status?.batteryPercent)
        assertEquals(0, session.refreshes); assertEquals(OnboardingStatus.KEYRING_REQUIRED, (session.state.value as Session.SignedIn).status)
    }
    @Test fun leavingClosesConnectionAndClearsDeviceDetails() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent(); vm.leave(); runCurrent()
        assertEquals(BleLinkState.Closed, port.link.state.value); assertEquals(BleProbeState(), vm.state.value)
    }
    @Test fun disconnectedDeviceCannotKeepCurrentStatus() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent(); vm.readStatus(); runCurrent()
        port.link.close(); runCurrent()
        assertFalse(vm.state.value.connected); assertNull(vm.state.value.status); assertNull(vm.state.value.info)
    }
    @Test fun previousAccountLateReplyIsDiscarded() = runTest(dispatcher) {
        val port = ProbeFixture(); val pending = CompletableDeferred<Unit>()
        port.delayResponse = { withContext(NonCancellable) { pending.await() } }
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val vm = model(port, session)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.READY, true); runCurrent()
        pending.complete(Unit); runCurrent()
        assertEquals(BleProbeState(), vm.state.value); assertEquals(BleLinkState.Closed, port.link.state.value)
    }
    @Test fun wrongRequestClosesConnectionWithoutShowingInfo() = runTest(dispatcher) {
        val port = ProbeFixture(); port.wrongRequest = true
        val vm = model(port); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        assertFalse(vm.state.value.connected); assertNull(vm.state.value.info)
        assertEquals(BleFailure.InvalidFrame, vm.state.value.error); assertEquals(BleLinkState.Closed, port.link.state.value)
    }
    @Test fun signedOutCannotStartWirelessOperation() = runTest(dispatcher) {
        val port = ProbeFixture(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        session.state.value = Session.SignedOut
        val vm = model(port, session); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        assertFalse(port.scanning); assertEquals(0, port.connections)
    }
    @Test fun sameAccountOnboardingRefreshKeepsLinkButLogoutClosesIt() = runTest(dispatcher) {
        val port = ProbeFixture(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val vm = model(port, session)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        // 같은 계정의 서버 가입 상태 갱신과 실제 로그아웃을 구분한다.
        session.state.value = Session.SignedIn(1, OnboardingStatus.PROFILE_REQUIRED, false); runCurrent()
        assertTrue(vm.state.value.connected); assertEquals(BleLinkState.Ready, port.link.state.value)
        vm.readStatus(); runCurrent(); assertNotNull(vm.state.value.status)
        session.state.value = Session.SignedOut; runCurrent()
        assertFalse(vm.state.value.connected); assertEquals(BleLinkState.Closed, port.link.state.value)
    }
    @Test fun foregroundDisconnectReconnectsSameVerifiedBoard() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port); vm.setForeground(true)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        port.link.close(); runCurrent(); assertFalse(vm.state.value.connected)
        advanceUntilIdle()
        assertEquals(2, port.connections); assertTrue(vm.state.value.connected)
        assertEquals("XIAO-test", vm.state.value.info?.serial)
    }
    @Test fun missingBoardHasThreeAttemptsAndManualStopCancelsRecovery() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port); vm.setForeground(true)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        port.visible = false; port.finiteScan = true; port.link.close(); advanceUntilIdle()
        assertEquals(4, port.scans); assertFalse(vm.state.value.scanning); assertEquals(1, port.connections)
        port.visible = true; vm.scan(); runCurrent(); vm.connect(port.currentId); runCurrent()
        port.link.close(); runCurrent(); vm.leave(); advanceUntilIdle()
        assertEquals(2, port.connections); assertEquals(BleProbeState(), vm.state.value)
    }
    @Test fun backgroundOrLogoutCancelsPendingReconnect() = runTest(dispatcher) {
        val port = ProbeFixture(); val session = TestSession(OnboardingStatus.READY); val vm = model(port, session)
        vm.setForeground(true); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        port.link.close(); runCurrent(); vm.setForeground(false); advanceUntilIdle()
        assertEquals(1, port.connections)
        vm.setForeground(true); vm.scan(); runCurrent(); vm.connect(port.currentId); runCurrent()
        port.link.close(); runCurrent(); session.state.value = Session.SignedOut; advanceUntilIdle()
        assertEquals(2, port.connections); assertEquals(BleProbeState(), vm.state.value)
    }
    @Test fun sameAddressWithDifferentSerialIsRejectedWithoutAutomaticRetry() = runTest(dispatcher) {
        val port = ProbeFixture(); val vm = model(port); vm.setForeground(true)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        port.serial = "XIAO-other"; port.link.close(); advanceUntilIdle()
        assertFalse(vm.state.value.connected); assertNull(vm.state.value.info)
        assertEquals(BleFailure.InvalidFrame, vm.state.value.error); assertEquals(2, port.connections)
    }
}
