package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

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
    private val ownerIdentity = SyncIdentity("11111111-1111-4111-8111-111111111111", 4,
        "22222222-2222-4222-8222-222222222222", 12, "a".repeat(64), "A".repeat(43),
        "key-1", "tingle-keyring-settings-v3", 2, KeyringRegistrationProtocol.VERSION_2)

    private fun ownerPort() = ProbeFixture().apply {
        extraInfo = mapOf(
            "supportedFunctions" to JsonArray(listOf("GET_OWNER_CHALLENGE", "AUTHORIZE_OWNER").map(::JsonPrimitive)),
            "ownershipEpoch" to JsonPrimitive(2), "ownerState" to JsonPrimitive("BOUND"),
            "supportedProofProtocolVersions" to JsonArray(listOf(JsonPrimitive(KeyringRegistrationProtocol.VERSION_2))),
            "keyId" to JsonPrimitive(ownerIdentity.keyId),
            "profileStorageCharacteristic" to JsonPrimitive(BleProfileStorageInfo.CHARACTERISTIC),
            "profileRequiresEncryption" to JsonPrimitive(true),
        )
        commandReply = { input -> buildJsonObject {
            put("protocolVersion", 1)
            put("requestId", input.getValue("requestId"))
            if (input["type"] == JsonPrimitive("GET_OWNER_CHALLENGE")) {
                put("type", "OWNER_CHALLENGE")
                put("deviceNonce", "A".repeat(43))
                put("expiresInSeconds", 60)
            } else {
                put("type", "OWNER_RESULT")
                put("authorized", true)
                put("permissions", input.getValue("permissions"))
            }
        } }
    }

    private fun ownerGrant(request: KeyringOwnerGrantRequest) = KeyringOwnerGrant(
        "tingle-owner-session-v2", "dev-ed25519-test", ownerIdentity.deviceId,
        request.ownerGeneration, request.ownershipEpoch, request.deviceNonce, request.permissions,
        "2026-11-06T03:00:00Z", 1793934060L, "A".repeat(86))

    /** 이전 허가가 늦게 도착해도 자동 복구한 연결과 그 연결의 새 작업을 유지한다. */
    @Test fun lateOwnerGrantCannotClearReconnectedBoardOrBusyState() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port)
        val replies = List(2) { CompletableDeferred<Unit>() }
        var requests = 0
        val authorizer = KeyringOwnerAuthorizer(object : KeyringOwnerSessionRepository {
            override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest): ApiResult<KeyringOwnerGrant> {
                replies[requests++].await()
                return ApiResult.Ok(ownerGrant(body))
            }
            override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant> = error("Unexpected lookup")
        })
        vm.setForeground(true)
        vm.scan(); runCurrent(); vm.connect(port.currentId); runCurrent()
        val old = async {
            runCatching { vm.withOwnerSession("XIAO-test", ownerIdentity, KeyringOwnerPermission.Read, authorizer) {} }
        }
        runCurrent()
        assertEquals(1, requests)
        port.link.close()
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, port.connections)
        assertTrue(vm.state.value.connected)
        val current = async { vm.withOwnerSession("XIAO-test", ownerIdentity, KeyringOwnerPermission.Ack, authorizer) {} }
        runCurrent()
        assertEquals(2, requests)
        replies[0].complete(Unit)
        assertIs<BleException>(old.await().exceptionOrNull())
        assertTrue(vm.state.value.connected)
        assertTrue(vm.state.value.busy)
        replies[1].complete(Unit)
        current.await()
        assertFalse(vm.state.value.busy)
        assertEquals(1, port.commands.count { it == "PROTECTED_AUTHORIZE_OWNER" })
    }

    /** 지연된 허가는 폐기하고 재연결 뒤 새 challenge와 요청 키로만 권한을 연다. */
    @Test fun ownerSessionExpiresAndReconnectObtainsFreshGrant() = runTest(dispatcher) {
        val port = ownerPort()
        val initialReply = requireNotNull(port.commandReply)
        val vm = model(port)
        val time = TestTimeSource()
        val requests = mutableListOf<Pair<String, KeyringOwnerGrantRequest>>()
        val authorizer = KeyringOwnerAuthorizer(object : KeyringOwnerSessionRepository {
            override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest): ApiResult<KeyringOwnerGrant> {
                requests += key to body
                if (requests.size == 1) time += 60.seconds
                return ApiResult.Ok(ownerGrant(body))
            }
            override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant> = error("Unexpected lookup")
        }, time)
        vm.scan(); runCurrent(); vm.connect(port.currentId); runCurrent()
        val failure = assertFailsWith<BleCommandException> {
            vm.withOwnerSession("XIAO-test", ownerIdentity, KeyringOwnerPermission.Read, authorizer) {}
        }
        assertEquals("OWNER_CHALLENGE_REQUIRED", failure.code)
        assertFalse(port.commands.any { it.contains("AUTHORIZE_OWNER") })

        vm.leave()
        port.commandReply = { input ->
            val response = initialReply(input)
            if (input["type"] == JsonPrimitive("GET_OWNER_CHALLENGE"))
                JsonObject(response + ("deviceNonce" to JsonPrimitive("B" + "A".repeat(42))))
            else response
        }
        vm.scan(); runCurrent(); vm.connect(port.currentId); runCurrent()
        vm.withOwnerSession("XIAO-test", ownerIdentity, KeyringOwnerPermission.Read, authorizer) {}
        assertNotEquals(requests[0].first, requests[1].first)
        assertNotEquals(requests[0].second.deviceNonce, requests[1].second.deviceNonce)
        assertEquals(1, port.commands.count { it == "PROTECTED_AUTHORIZE_OWNER" })
    }

    /** 발급 응답 유실 뒤 같은 키로 조회하며, 현재 연결에서 최소 권한으로 인증한다. */
    @Test fun ownerSessionRecoversOriginalGrantOverProtectedChannel() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        lateinit var requestKey: String
        lateinit var request: KeyringOwnerGrantRequest
        val authorizer = KeyringOwnerAuthorizer(object : KeyringOwnerSessionRepository {
            override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest): ApiResult<KeyringOwnerGrant> {
                assertEquals(1, body.permissions)
                requestKey = key
                request = body
                return ApiResult.Fail(AppError.Offline)
            }
            override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant> {
                assertEquals(ownerIdentity.deviceId, deviceId)
                assertEquals(requestKey, key)
                return ApiResult.Ok(ownerGrant(request))
            }
        })
        assertEquals("read", vm.withOwnerSession("XIAO-test", ownerIdentity, KeyringOwnerPermission.Read, authorizer) { "read" })
        assertEquals(listOf("PROTECT", "PROTECTED_GET_OWNER_CHALLENGE", "PROTECTED_AUTHORIZE_OWNER"), port.commands.takeLast(3))
    }

    /** 소유 세대가 다르거나 서버가 다른 권한을 반환하면 권한을 설치하지 않는다. */
    @Test fun ownerSessionRejectsWrongBoardEpochAndOverbroadGrant() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port)
        var creations = 0
        val authorizer = KeyringOwnerAuthorizer(object : KeyringOwnerSessionRepository {
            override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest): ApiResult<KeyringOwnerGrant> {
                creations++
                return ApiResult.Ok(ownerGrant(body).copy(permissions = 3))
            }
            override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant> = error("Unexpected lookup")
        })
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        assertFails {
            vm.withOwnerSession("XIAO-test", ownerIdentity.copy(ownershipEpoch = 1), KeyringOwnerPermission.Ack, authorizer) {}
        }
        assertEquals(0, creations)
        vm.scan(); runCurrent(); vm.connect(port.currentId); runCurrent()
        assertFails { vm.withOwnerSession("XIAO-test", ownerIdentity, KeyringOwnerPermission.Ack, authorizer) {} }
        assertEquals(1, creations)
        assertFalse(port.commands.any { it.contains("AUTHORIZE_OWNER") })
    }

    /** 계정이 바뀌면 먼저 보낸 HTTP 요청이 성공하더라도 옛 허가를 보드에 전달하지 않는다. */
    @Test fun ownerSessionRejectsLateGrantAfterSignOut() = runTest(dispatcher) {
        val port = ownerPort()
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val vm = model(port, session)
        val request = CompletableDeferred<KeyringOwnerGrantRequest>()
        val result = CompletableDeferred<KeyringOwnerGrant>()
        val authorizer = KeyringOwnerAuthorizer(object : KeyringOwnerSessionRepository {
            override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest): ApiResult<KeyringOwnerGrant> {
                request.complete(body)
                return ApiResult.Ok(result.await())
            }
            override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant> = error("Unexpected lookup")
        })
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        val work = async {
            runCatching { vm.withOwnerSession("XIAO-test", ownerIdentity, KeyringOwnerPermission.Read, authorizer) {} }
        }
        val pending = request.await()
        session.state.value = Session.SignedOut
        runCurrent()
        result.complete(ownerGrant(pending))
        assertIs<BleException>(work.await().exceptionOrNull())
        assertFalse(port.commands.any { it.contains("AUTHORIZE_OWNER") })
    }

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
        // 실행 화면에서 사용하는 경로로 보호 확인과 명시적인 성향 발급 선택까지 검증한다.
        assertEquals(SyncChallengeBody(requireNotNull(run.deviceNonce), KeyringSyncProtocol.PROFILE_FORMAT),
            vm.syncIssuanceRequest("XIAO-test"))
        assertTrue(port.commands.indexOf("PROTECT") < port.commands.indexOf("GET_SYNC_CHALLENGE"))
        assertEquals(run.payloadHash, vm.syncSettings(run, "XIAO-test").ack.payloadHash)
        assertEquals(1, port.commands.count { it == "PROTECTED_SYNC_SETTINGS" })
        assertFalse("SYNC_SETTINGS" in port.commands)
    }

    /** 전체 설정은 v2 소유 세대를 대조하고 기존 보호 경로를 사용한다. */
    @Test fun fullSettingsSelectsTsv3AndRejectsAnotherOwnershipEpoch() = runTest(dispatcher) {
        val run = profileRun().copy(
            payload = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
                .encode("TSV3".encodeToByteArray() + ByteArray(483)),
            payloadFormat = KeyringSyncProtocol.FULL_SETTINGS_FORMAT,
            proofProtocolVersion = KeyringRegistrationProtocol.VERSION_2,
            serverProofProtocolVersion = "tingle-dev-settings-proof-v2",
            ownershipEpoch = 2L,
        )
        val port = profilePort(run).apply {
            extraInfo += mapOf(
                "ownershipEpoch" to JsonPrimitive(2),
                "ownerState" to JsonPrimitive("BOUND"),
                "supportedProofProtocolVersions" to JsonArray(listOf(JsonPrimitive(run.proofProtocolVersion))),
                "peerExchangeProtocol" to JsonPrimitive("tingle-peer-v3"),
                "peerOwnerScopeVersion" to JsonPrimitive(1),
                "accountLockProofVersion" to JsonPrimitive("tingle-account-lock-v2"),
            )
        }
        val vm = model(port)
        vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        assertEquals(run.payloadFormat, vm.syncIssuanceRequest("XIAO-test").payloadFormat)
        assertTrue(port.commands.indexOf("PROTECT") < port.commands.indexOf("GET_SYNC_CHALLENGE"))
        assertEquals(run.payloadHash, vm.syncSettings(run, "XIAO-test").ack.payloadHash)
        assertFails { vm.syncSettings(run.copy(ownershipEpoch = 1L), "XIAO-test") }
        assertEquals(1, port.commands.count { it == "PROTECTED_SYNC_SETTINGS" })
        assertFalse("SYNC_SETTINGS" in port.commands)
    }

    /** 지원 정보 누락 또는 보호 실패는 일반 RX로 우회하지 않고 전송 전에 중단한다. */
    @Test fun issuanceKeepsLegacyBodyAndDoesNotDowngradeProtectionFailure() = runTest(dispatcher) {
        val port = profilePort(profileRun()).apply { extraInfo = emptyMap() }
        val vm = model(port); vm.scan(); runCurrent(); vm.connect("a"); runCurrent()
        assertEquals(SyncChallengeBody("A".repeat(43)), vm.syncIssuanceRequest("XIAO-test"))
        assertFalse("PROTECT" in port.commands)
        // 같은 연결이어도 매번 최신 지원을 읽으며 보호 실패로 ON/OFF 발급을 대신하지 않는다.
        port.extraInfo = profilePort(profileRun()).extraInfo
        port.protect = { throw BleException(BleFailure.SecureTransportRequired) }
        assertFails { vm.syncIssuanceRequest("XIAO-test") }
        assertEquals(1, port.commands.count { it == "GET_SYNC_CHALLENGE" })
    }

    /** 실제 성향 쓰기에서도 지원 정보와 보호 연결을 다시 확인한다. */
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
        val bound = assertFailsWith<BleCommandException> { vm.registrationProof(challenge, "XIAO-test") }
        assertEquals("DEVICE_ALREADY_BOUND", bound.code)
        assertEquals(1, port.commands.count { it == "GET_REGISTRATION_PROOF" })
        // 실제 S20 회귀: BOUND 거절을 일반 프레임 실패로 처리해 연결까지 끊지 않는다.
        assertEquals(BleLinkState.Ready, port.linkState.value)
        assertEquals(RegistrationIssue.DeviceBound, registrationBlock(vm.state.value.info))
        port.extraInfo = port.extraInfo + ("ownerState" to JsonPrimitive("RESETTING"))
        assertEquals("RESET_IN_PROGRESS", assertFailsWith<BleCommandException> {
            vm.registrationProof(challenge, "XIAO-test")
        }.code)
        assertEquals(RegistrationIssue.ResetPending, registrationBlock(vm.state.value.info))
        assertEquals(BleLinkState.Ready, port.linkState.value)
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
    private fun model(
        port: ProbeFixture,
        session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED),
        list: suspend () -> ApiResult<List<OwnedKeyring>> = {
            ApiResult.Ok(listOf(OwnedKeyring(4, "READY", "test", "XIAO-test")))
        },
    ) = BleProbeViewModel(port, session, object : OwnedKeyringRepository {
        override suspend fun list() = list()
    }).also { store.put("ble", it) }

    /** 다른 실물을 연결하면 기기 정보 조회까지만 진행하고 연결을 즉시 닫는다. */
    @Test fun foreignBoardClosesBeforePublishingConnectedState() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port) { ApiResult.Ok(listOf(OwnedKeyring(7, "READY", "test", "XIAO-other"))) }

        vm.scan(); runCurrent()
        vm.connect(port.currentId); runCurrent()

        assertEquals(BleFailure.NotOwned, vm.state.value.error)
        assertFalse(vm.state.value.connected)
        assertNull(vm.state.value.info)
        assertEquals(BleLinkState.Closed, port.linkState.value)
        assertEquals(listOf("GET_DEVICE_INFO"), port.commands)
    }

    /** 재연결 직전에 귀속이 해제되면, 같은 serial이어도 이전 성공 판정을 재사용하지 않는다. */
    @Test fun automaticReconnectRechecksAccountOwnership() = runTest(dispatcher) {
        val port = ownerPort()
        var own = true
        var calls = 0
        val vm = model(port) {
            calls++
            ApiResult.Ok(if (own) listOf(OwnedKeyring(4, "READY", "test", "XIAO-test")) else emptyList())
        }
        vm.setForeground(true)
        vm.scan(); runCurrent()
        vm.connect(port.currentId); runCurrent()
        assertTrue(vm.state.value.connected)

        own = false
        port.link.close()
        advanceTimeBy(10_000); runCurrent()

        assertEquals(2, calls)
        assertEquals(2, port.connections)
        assertEquals(BleFailure.NotOwned, vm.state.value.error)
        assertFalse(vm.state.value.connected)
        assertEquals(BleLinkState.Closed, port.linkState.value)
    }

    /** 구 목록 응답을 타인 소유로 단정하지 않고, 식별자를 얻을 때까지 연결을 닫는다. */
    @Test fun missingSerialInOwnedListDoesNotApproveConnection() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port) { ApiResult.Ok(listOf(OwnedKeyring(4, "READY", "test"))) }

        vm.scan(); runCurrent()
        vm.connect(port.currentId); runCurrent()

        assertEquals(BleFailure.OwnershipUnverified, vm.state.value.error)
        assertFalse(vm.state.value.connected)
        assertEquals(BleLinkState.Closed, port.linkState.value)
    }

    /** 등록 예외를 일반 연결에서 재사용하면 미귀속 보드가 서비스 화면에 남을 수 있다. */
    @Test fun unownedBoardCanEnterOnlyTheRegistrationFlow() = runTest(dispatcher) {
        val port = ownerPort().apply {
            extraInfo = extraInfo + ("ownerState" to JsonPrimitive("UNOWNED"))
        }
        var reads = 0
        val vm = model(port) { reads++; ApiResult.Ok(emptyList()) }

        vm.scan(); runCurrent()
        vm.connect(port.currentId, registrationMode = true); runCurrent()

        assertTrue(vm.state.value.connected)
        assertEquals("UNOWNED", vm.state.value.info?.ownerState)
        assertEquals(0, reads)

        vm.leave()
        vm.scan(); runCurrent()
        vm.connect(port.currentId); runCurrent()

        assertEquals(1, reads)
        assertEquals(BleFailure.NotOwned, vm.state.value.error)
        assertFalse(vm.state.value.connected)
    }

    @Test fun normalConnectionRequiresAnExactCaseSensitiveSerial() = runTest(dispatcher) {
        val port = ownerPort()
        val session = TestSession(OnboardingStatus.READY)
        val vm = model(port, session) { ApiResult.Ok(listOf(OwnedKeyring(4, "READY", "test", "xiao-test"))) }

        vm.scan(); runCurrent()
        vm.connect(port.currentId, registrationMode = true); runCurrent()

        assertEquals(BleFailure.NotOwned, vm.state.value.error)
        assertEquals(BleLinkState.Closed, port.linkState.value)
    }

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
    @Test fun scanReportsWhetherItStarted() = runTest(dispatcher) {
        val vm = model(ProbeFixture())
        assertTrue(vm.scan())                                   // 화면은 true 일 때만 "찾았다"로 친다
        assertFalse(vm.scan())                                  // 이미 찾는 중
        runCurrent(); vm.connect("a"); assertTrue(vm.state.value.busy)
        assertFalse(vm.scan())                                  // 연결 중에는 시작하지 않는다
        val signedOut = TestSession(OnboardingStatus.KEYRING_REQUIRED).apply { state.value = Session.SignedOut }
        assertFalse(model(ProbeFixture(), signedOut).scan())
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
