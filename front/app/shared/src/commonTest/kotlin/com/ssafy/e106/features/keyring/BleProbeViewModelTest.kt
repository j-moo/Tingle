package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.core.storage.ProtectedRead
import com.ssafy.e106.core.storage.ProtectedRecordStore
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.onboarding.TestSession
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
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
    var otherSerial: String? = null
    var finiteScan = false
    var bondedCandidates = emptyList<String>()
    var targetVisible = true
    // Android 처럼 일련번호와 상관없이 마지막으로 연 기기를 "직전 기기"로 기억한다("target" · "other")
    var previous: String? = null
    var scanFailure: BleFailure? = null
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
            scanFailure?.let { throw BleException(it) }
            emit(if (visible) listOfNotNull(
                otherSerial?.let { BleCandidate("other", "Test", -30) }, BleCandidate(currentId, "Test", -50).takeIf { targetVisible },
            ) else emptyList())
            if (!finiteScan) awaitCancellation()
        }
        finally { scanning = false }
    }
    override suspend fun bondedKeyringCandidates(): List<String> {
        assertFalse(scanning, "스캔을 끝낸 뒤 페어링된 후보를 받아야 한다")
        return bondedCandidates
    }
    override suspend fun connect(candidateId: String): BleConnection {
        assertFalse(scanning, "스캔 정리를 기다린 뒤 연결해야 한다")
        if (candidateId == "other" && otherSerial != null) {
            previous = "other"
            val wrong = JsonPrimitive(requireNotNull(otherSerial))
            return object : BleConnection by link {
                override suspend fun exchange(request: ByteArray, maxResponseBytes: Int, responseTimeoutMillis: Long): ByteArray {
                    val info = Json.parseToJsonElement(link.exchange(request, maxResponseBytes, responseTimeoutMillis)
                        .decodeToString()).jsonObject
                    return JsonObject(info + ("deviceSerial" to wrong)).toString().encodeToByteArray()
                }
            }
        }
        assertTrue(candidateId == currentId || candidateId in bondedCandidates, "이전 탐색의 ID를 재사용하면 안 된다")
        connections++
        previous = "target"
        linkState.value = BleLinkState.Ready
        return link
    }
    override fun isPreviousDevice(candidateId: String) = when (previous) {
        "target" -> candidateId == currentId
        "other" -> candidateId == "other"
        else -> false
    }
    override fun forgetPreviousDevice() { previous = null; pinned = null }
    // 초기화 대상: 고정한 순간의 직전 기기. 이후 다른 연결로 바뀌지 않는다
    var pinned: String? = null
    override fun pinResetTarget() { pinned = previous }
    override fun isResetTarget(candidateId: String) = when (pinned) {
        "target" -> candidateId == currentId
        "other" -> candidateId == "other"
        else -> false
    }
    override fun hasResetTarget() = pinned != null
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

    /** 공개 설정은 소유 기기에만 전송되며, 재조회 값이 다르면 성공하지 않는다. */
    @Test fun publicNameRequiresOwnedLinkAndProtectedReadback() = runTest(dispatcher) {
        val port = ownerPort()
        port.extraInfo = port.extraInfo + mapOf(
            "supportedFunctions" to JsonArray(listOf("GET_OWNER_CHALLENGE", "AUTHORIZE_OWNER",
                "GET_PUBLIC_NAME", "SET_PUBLIC_NAME", "CLEAR_PUBLIC_NAME").map(::JsonPrimitive)),
            "maxPublicNameBytes" to JsonPrimitive(29),
        )
        val ownerReply = requireNotNull(port.commandReply)
        var advertised: String? = null
        var failReadback = false
        port.commandReply = { request ->
            val type = request.getValue("type").jsonPrimitive.content
            if (type !in setOf("SET_PUBLIC_NAME", "CLEAR_PUBLIC_NAME", "GET_PUBLIC_NAME")) ownerReply(request)
            else {
                if (type == "SET_PUBLIC_NAME") advertised = request.getValue("name").jsonPrimitive.content
                if (type == "CLEAR_PUBLIC_NAME") advertised = null
                val responseName = if (failReadback && type == "GET_PUBLIC_NAME") "이전 이름" else advertised
                buildJsonObject {
                    put("protocolVersion", 1); put("type", "PUBLIC_NAME_RESULT")
                    put("requestId", request.getValue("requestId"))
                    put("enabled", responseName != null)
                    put("name", responseName?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }
        val session = TestSession(OnboardingStatus.READY)
        val vm = model(port, session)
        val store = KeyringSyncStore(ResetPrefs())
        store.rememberTarget(1, ownerIdentity.deviceId, port.serial)
        store.save(1, store.read(1).single().copy(identity = ownerIdentity, complete = true))
        val authorizer = KeyringOwnerAuthorizer(object : KeyringOwnerSessionRepository {
            override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest): ApiResult<KeyringOwnerGrant> {
                assertEquals(ownerIdentity.deviceId, deviceId)
                assertEquals(8, body.permissions)
                return ApiResult.Ok(ownerGrant(body))
            }
            override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant> = error("Unexpected lookup")
        })
        vm.scan(); runCurrent(); vm.connect(port.currentId); runCurrent()
        val stamp = requireNotNull(vm.state.value.connectionGeneration)
        val device = OwnedKeyring(4, "READY", "test", port.serial)
        assertFailsWith<BleException> {
            vm.publicName(1, device.copy(deviceSerial = "other"), stamp, store, authorizer, KeyringPublicNameCommand.Set, "팅글")
        }
        assertFalse(port.commands.any { "SET_PUBLIC_NAME" in it })
        assertEquals("팅글", vm.publicName(1, device, stamp, store, authorizer, KeyringPublicNameCommand.Set, "팅글"))
        assertEquals(listOf("PROTECTED_SET_PUBLIC_NAME", "PROTECTED_GET_PUBLIC_NAME"), port.commands.takeLast(2))
        assertNull(vm.publicName(1, device, stamp, store, authorizer, KeyringPublicNameCommand.Clear))
        assertEquals(listOf("PROTECTED_CLEAR_PUBLIC_NAME", "PROTECTED_GET_PUBLIC_NAME"), port.commands.takeLast(2))
        failReadback = true
        assertFailsWith<IllegalStateException> {
            vm.publicName(1, device, stamp, store, authorizer, KeyringPublicNameCommand.Set, "새 이름")
        }
    }

    @Test fun ownedStatusUsesProtectedReadAndClearsOnAccountChange() = runTest(dispatcher) {
        val port = ownerPort()
        port.extraInfo = port.extraInfo + ("supportedFunctions" to JsonArray(listOf(
            "GET_STATUS", "GET_OWNER_CHALLENGE", "AUTHORIZE_OWNER", "GET_RECORD", "READ_RECORD_CHUNK", "ACK_RECORD",
        ).map(::JsonPrimitive)))
        val reply = requireNotNull(port.commandReply)
        port.commandReply = { request ->
            if (request["type"] == JsonPrimitive("GET_STATUS")) buildJsonObject {
                put("protocolVersion", 1); put("type", "STATUS"); put("requestId", request.getValue("requestId"))
                put("ownerAuthorized", true); put("storageState", "AVAILABLE")
                put("pendingRecordCount", 0); put("reviewRecordCount", 1); put("batteryPercent", 0)
            } else reply(request)
        }
        val session = TestSession(OnboardingStatus.READY)
        val vm = model(port, session)
        val sync = KeyringSyncStore(ResetPrefs())
        sync.rememberTarget(1, ownerIdentity.deviceId, port.serial)
        sync.save(1, sync.read(1).single().copy(identity = ownerIdentity, complete = true))
        val authorizer = KeyringOwnerAuthorizer(object : KeyringOwnerSessionRepository {
            override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest) = ApiResult.Ok(ownerGrant(body))
            override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant> = error("Unexpected lookup")
        })
        vm.scan(); runCurrent(); vm.connect(port.currentId); runCurrent()
        vm.readOwnedStatus(1, OwnedKeyring(4, "READY", "test", "other"), sync, authorizer)
        assertNull(vm.state.value.ownedStatus)
        vm.readOwnedStatus(1, OwnedKeyring(4, "READY", "test", port.serial), sync, authorizer)
        assertTrue(vm.state.value.ownedStatus?.loading == true)
        runCurrent()
        val result = requireNotNull(vm.state.value.ownedStatus)
        assertEquals(0L, result.metrics?.batteryPercent)
        assertEquals(1L, result.metrics?.unsentCount)
        assertTrue(port.commands.contains("PROTECTED_GET_STATUS"))
        assertFalse(port.commands.contains("GET_STATUS"))
        session.state.value = Session.SignedIn(2, OnboardingStatus.READY, false)
        runCurrent()
        assertNull(vm.state.value.ownedStatus)
    }

    /** 접수증 없는 첫 기록이 뒤의 정상 기록을 막거나 ACK되면 안 된다. */
    @Test fun automaticTransferSkipsHeldRecordAndAcknowledgesTheNextRecord() = runTest(dispatcher) {
        val port = ownerPort()
        val session = TestSession(OnboardingStatus.READY)
        val ble = model(port, session)
        val sync = KeyringSyncStore(ResetPrefs())
        sync.rememberTarget(1, ownerIdentity.deviceId, port.serial)
        sync.save(1, sync.read(1).single().copy(identity = ownerIdentity, complete = true))
        val authorizer = KeyringOwnerAuthorizer(object : KeyringOwnerSessionRepository {
            override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest) = ApiResult.Ok(ownerGrant(body))
            override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant> = error("Unexpected lookup")
        })
        val data = "TER2".encodeToByteArray() + ByteArray(1161)
        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val epoch = "a".repeat(32)
        fun header(seq: Long) = KeyringEncounterHeader(
            epoch, seq, "REVIEW", "11111111-1111-4111-8111-11111111111$seq",
            "22222222-2222-4222-8222-22222222222$seq", ownerIdentity.deviceId, ownerIdentity.ownerGeneration, 2,
            1793934000, 1793937600, "tingle-policy-v2", "r2-c-20261106", "44444444-4444-4444-8444-444444444444",
            "", "tingle-encounter-v2", data.size, data.copyOf().apply { this[lastIndex] = seq.toByte() }.toByteString().sha256().hex(),
        )
        val first = header(1)
        val second = header(2)
        val records = mutableListOf(first, second)
        val cleaned = mutableListOf<String>()
        val originalReply = requireNotNull(port.commandReply)
        port.commandReply = { request ->
            when (request.getValue("type").jsonPrimitive.content) {
                "GET_STATUS" -> buildJsonObject {
                    put("type", "STATUS"); put("requestId", request.getValue("requestId"))
                    put("protocolVersion", 1); put("ownerAuthorized", true); put("recordCursorEpoch", epoch)
                }
                "GET_RECORD" -> buildJsonObject {
                    val next = records.firstOrNull { it.sequence > request.getValue("afterSequence").jsonPrimitive.long }
                    put("type", if (next == null) "RECORD_END" else "RECORD")
                    put("requestId", request.getValue("requestId")); put("protocolVersion", 1); put("recordCursorEpoch", epoch)
                    next?.let { ApiJson.encodeToJsonElement(KeyringEncounterHeader.serializer(), it).jsonObject.forEach { (k, v) -> put(k, v) } }
                }
                "READ_RECORD_CHUNK" -> buildJsonObject {
                    val seq = request.getValue("sequence").jsonPrimitive.long
                    val offset = request.getValue("offset").jsonPrimitive.int
                    val length = request.getValue("length").jsonPrimitive.int
                    val original = data.copyOf().apply { this[lastIndex] = seq.toByte() }
                    put("type", "RECORD_CHUNK"); put("requestId", request.getValue("requestId")); put("protocolVersion", 1)
                    put("sequence", seq); put("offset", offset); put("totalBytes", original.size)
                    put("envelopeHash", request.getValue("envelopeHash")); put("data", base64.encode(original.copyOfRange(offset, offset + length)))
                }
                "ACK_RECORD" -> buildJsonObject {
                    val id = request.getValue("submissionId").jsonPrimitive.content
                    cleaned += id
                    records.removeAll { it.submissionId == id }
                    put("type", "RECORD_ACK"); put("requestId", request.getValue("requestId")); put("protocolVersion", 1)
                    put("result", "REMOVED_OR_ALREADY_REMOVED")
                }
                else -> originalReply(request)
            }
        }
        val uploaded = mutableListOf<String>()
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            val sent = ApiJson.decodeFromString(KeyringEncounterUpload.serializer(), (request.body as TextContent).text).submissions.single()
            uploaded += sent.submissionId
            val proof = if (sent.submissionId == second.submissionId) KeyringEncounterProof(
                "tingle-record-receipt-v2", "server-key", second.deviceId, second.ownerGeneration, 2, second.eventId,
                second.submissionId, second.envelopeHash, "55555555-5555-4555-8555-555555555555", 1793934001,
                "STORED", "DELETE_ORIGINAL", "A".repeat(86),
            ) else null
            val receipt = KeyringEncounterResponse.Receipt(sent.submissionId, sent.eventId,
                if (proof == null) "NOT_STORED" else "STORED", proof?.receiptId,
                errorCode = if (proof == null) "POLICY_UNRESOLVED" else null, retryable = false, serverProof = proof)
            respond(ApiJson.encodeToString(KeyringEncounterResponse.serializer(), KeyringEncounterResponse(listOf(receipt))),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val journal = KeyringEncounterJournal(object : ProtectedRecordStore {
            private val values = mutableMapOf<String, String>()
            override suspend fun read(key: String) = values[key]?.let { ProtectedRead.Found(it) } ?: ProtectedRead.Missing
            override suspend fun write(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String, expectedValue: String) = values.remove(key) == expectedValue
        })
        try {
            val vm = KeyringEncounterViewModel(session, sync, authorizer, KeyringEncounterRepository(client), journal)
            store.put("encounters", vm)
            ble.scan(); runCurrent(); ble.connect(port.currentId); runCurrent()
            val target = KeyringEncounterViewModel.Target(1, port.serial, requireNotNull(ble.state.value.connectionGeneration))
            vm.update(target, true, null, ble)
            assertEquals(EncounterTransferState.Idle, vm.state.value)
            vm.update(target, false, null, ble)
            assertEquals(EncounterTransferState.PolicyWaiting, vm.state.first { it != EncounterTransferState.Sending })
            assertEquals(listOf(first.submissionId, second.submissionId), uploaded)
            assertEquals(listOf(second.submissionId), cleaned)
            assertEquals(listOf(first), records)
            // 같은 연결에 설정 완료가 도착해도 이미 영수증 없이 거절된 항목은 자동 반복하지 않는다.
            vm.update(target, false, ownerIdentity.syncRunId, ble)
            assertEquals(EncounterTransferState.PolicyWaiting, vm.state.first { it != EncounterTransferState.Sending })
            assertEquals(listOf(first.submissionId, second.submissionId), uploaded)
            assertEquals(2, port.commands.count { it == "PROTECTED_GET_STATUS" })
            assertNull(journal.read(1, ownerIdentity.deviceId))
        } finally { client.close() }
    }

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

    /** 서비스가 기다리는 동안 목록은 비어 있고, 소유 확인 뒤에만 연결 정보를 내보낸다. */
    @Test fun backgroundConnectionWaitsForVerifiedOwnedBoard() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port, TestSession(OnboardingStatus.READY))
        val nearby = CompletableDeferred<Unit>()
        val pending = launch {
            vm.connectOwnedAutomatically(1, 4, port.serial, 2) {
                nearby.await()
                port.link
            }
        }
        runCurrent()
        assertFalse(vm.state.value.connected)
        assertFalse(vm.state.value.busy)
        nearby.complete(Unit)
        pending.join()
        assertEquals(port.serial, vm.state.value.info?.serial)
        assertTrue(vm.state.value.connected)
        assertEquals(listOf("GET_DEVICE_INFO"), port.commands)
        assertEquals(0, port.scans)
    }

    @Test fun backgroundConnectionRejectsOldOwnershipEpoch() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port, TestSession(OnboardingStatus.READY))
        runCurrent()
        val failure = assertFailsWith<BleException> {
            vm.connectOwnedAutomatically(1, 4, port.serial, 1) { port.link }
        }
        assertEquals(BleFailure.NotOwned, failure.reason)
        assertEquals(BleLinkState.Closed, port.linkState.value)
        assertFalse(vm.state.value.connected)
    }

    @Test fun backgroundConnectionRejectsDeviceRemovedFromAccount() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port, TestSession(OnboardingStatus.READY)) { ApiResult.Ok(emptyList()) }
        runCurrent()
        val failure = assertFailsWith<BleException> {
            vm.connectOwnedAutomatically(1, 4, port.serial, 2) { port.link }
        }
        assertEquals(BleFailure.NotOwned, failure.reason)
        assertEquals(BleLinkState.Closed, port.linkState.value)
        assertNull(vm.state.value.info)
    }

    @Test fun manualScanCancelsOnlyPendingAutomaticConnection() = runTest(dispatcher) {
        val port = ownerPort()
        val vm = model(port, TestSession(OnboardingStatus.READY))
        runCurrent()
        val pending = launch {
            vm.connectOwnedAutomatically(1, 4, port.serial, 2) { awaitCancellation() }
        }
        runCurrent()
        assertTrue(vm.scan())
        runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(1, port.scans)
        assertEquals("a", vm.state.value.candidates.single().id)
    }

    /** 이름이 같고 더 가까운 키링이 있어도 계정 목록에서 고른 실물만 연결 완료로 공개한다. */
    @Test fun ownedSelectionSkipsStrongerCandidateWithDifferentSerial() = runTest(dispatcher) {
        val port = ProbeFixture().apply { otherSerial = "XIAO-other"; finiteScan = true }
        val selected = OwnedKeyring(4, "READY", "test", port.serial)
        val vm = model(port) { ApiResult.Ok(listOf(selected, OwnedKeyring(7, "READY", "test", "XIAO-other"))) }

        vm.connectOwned(selected); runCurrent()

        assertTrue(vm.state.value.connected)
        assertEquals(selected.deviceSerial, vm.state.value.info?.serial)
        assertTrue(vm.state.value.candidates.isEmpty())
        assertEquals(listOf("GET_DEVICE_INFO", "GET_DEVICE_INFO"), port.commands)
    }

    /** 초기화 중 끊긴 키링은 같은 일련번호에만 다시 붙는다. 초기화를 마친 UNOWNED 보드도 소유 목록 없이 연결한다. */
    @Test fun resetReconnectAcceptsUnownedSameSerialOnly() = runTest(dispatcher) {
        val port = ProbeFixture().apply {
            otherSerial = "XIAO-other"; finiteScan = true
            extraInfo = extraInfo + ("ownerState" to JsonPrimitive("UNOWNED"))
        }
        val vm = model(port) { ApiResult.Ok(emptyList()) }

        vm.reconnectForReset(port.serial); runCurrent()

        assertTrue(vm.state.value.connected)
        assertEquals(port.serial, vm.state.value.info?.serial)
        assertEquals("UNOWNED", vm.state.value.info?.ownerState)
        // 키링이 지우면서 끊긴 뒤: 대상을 기억하면 더 가까운 다른 키링은 연결해 보지 않는다(그사이 다른 연결로 직전 기기가 바뀌어도)
        port.linkState.value = BleLinkState.Closed; runCurrent()
        port.previous = "other"
        assertFalse(vm.state.value.connected)
        val before = port.commands.size
        vm.reconnectForReset(port.serial); runCurrent()
        assertTrue(vm.state.value.connected)
        assertEquals(listOf("GET_DEVICE_INFO"), port.commands.drop(before))   // 다른 키링(other)에 붙었다 끊지 않았다
    }

    /** 대상을 모르고 전체를 확인하다 다른 키링만 열었으면 그 키링을 대상으로 기억하지 않는다(다음 시도가 대상을 찾는다). */
    @Test fun resetReconnectDoesNotLockOntoAProbedOtherBoard() = runTest(dispatcher) {
        val port = ProbeFixture().apply {
            otherSerial = "XIAO-other"; finiteScan = true; targetVisible = false
            extraInfo = extraInfo + ("ownerState" to JsonPrimitive("UNOWNED"))
        }
        val vm = model(port) { ApiResult.Ok(emptyList()) }
        vm.reconnectForReset(port.serial); advanceTimeBy(70_000); runCurrent()
        assertFalse(vm.state.value.connected); assertFalse(port.hasResetTarget())   // 확인만 한 다른 키링은 대상으로 기억하지 않는다
        port.targetVisible = true
        vm.reconnectForReset(port.serial); advanceTimeBy(70_000); runCurrent()
        assertTrue(vm.state.value.connected); assertEquals(port.serial, vm.state.value.info?.serial)
    }

    /** 다른 키링을 대상으로 기억하고 있으면, 지금 찾는 키링(다른 일련번호)은 그 대상에 묶이지 않고 전체를 확인해 찾는다. */
    @Test fun resetReconnectForAnotherSerialIgnoresTheRememberedTarget() = runTest(dispatcher) {
        val port = ProbeFixture().apply {
            otherSerial = "XIAO-b"; finiteScan = true
            extraInfo = extraInfo + ("ownerState" to JsonPrimitive("UNOWNED"))
        }
        val vm = model(port) { ApiResult.Ok(emptyList()) }
        vm.reconnectForReset(port.serial); runCurrent()      // 키링 A(port.serial)를 대상으로 기억
        assertTrue(vm.state.value.connected); assertTrue(port.hasResetTarget())
        vm.leave(); port.linkState.value = BleLinkState.Ready   // 가짜 BLE 의 다른 키링 연결은 같은 링크 상태를 쓴다
        vm.reconnectForReset("XIAO-b"); advanceTimeBy(70_000); runCurrent()
        assertTrue(vm.state.value.connected); assertEquals("XIAO-b", vm.state.value.info?.serial)
    }

    /** 초기화 재연결은 같은 일련번호여도 BOUND · UNOWNED 가 아닌 보드(초기화 중 등)는 연결 완료로 공개하지 않는다. */
    @Test fun resetReconnectRejectsUnexpectedOwnerState() = runTest(dispatcher) {
        val port = ProbeFixture().apply { finiteScan = true; extraInfo = extraInfo + ("ownerState" to JsonPrimitive("RESETTING")) }
        val vm = model(port) { ApiResult.Ok(emptyList()) }
        vm.reconnectForReset(port.serial); runCurrent()
        assertFalse(vm.state.value.connected); assertEquals(BleFailure.NotOwned, vm.state.value.error)
    }

    /** 탐색 중 선택 기기가 계정에서 빠지면 기기 정보만 읽고 연결을 닫는다. */
    @Test fun ownedSelectionRejectsSerialNowAssignedToDifferentDeviceId() = runTest(dispatcher) {
        val port = ProbeFixture().apply { finiteScan = true }
        val selected = OwnedKeyring(4, "READY", "test", port.serial)
        val vm = model(port) { ApiResult.Ok(listOf(selected.copy(deviceId = 7))) }

        vm.connectOwned(selected); runCurrent()

        assertFalse(vm.state.value.connected)
        assertEquals(BleFailure.OwnershipUnverified, vm.state.value.error)
        assertEquals(BleLinkState.Closed, port.linkState.value)
    }

    @Test fun ownedSelectionUsesBondedKeyringWhenScanReturnsNothing() = runTest(dispatcher) {
        val port = ProbeFixture().apply {
            visible = false
            otherSerial = "XIAO-other"
            bondedCandidates = listOf("other", "bonded")
        }
        val vm = model(port)

        vm.connectOwned(OwnedKeyring(4, "READY", "test", port.serial))
        advanceTimeBy(15_000)
        runCurrent()

        assertTrue(vm.state.value.connected)
        assertEquals(port.serial, vm.state.value.info?.serial)
        assertEquals(listOf("GET_DEVICE_INFO", "GET_DEVICE_INFO"), port.commands)
    }

    @Test fun ownedSelectionRejectsBondedKeyringWhenServerOwnershipChanged() = runTest(dispatcher) {
        val port = ProbeFixture().apply {
            scanFailure = BleFailure.ScanFailed
            bondedCandidates = listOf("bonded")
        }
        val vm = model(port) { ApiResult.Ok(emptyList()) }

        vm.connectOwned(OwnedKeyring(4, "READY", "test", port.serial))
        runCurrent()

        assertFalse(vm.state.value.connected)
        assertEquals(BleFailure.NotOwned, vm.state.value.error)
        assertEquals(BleLinkState.Closed, port.linkState.value)
        assertEquals(listOf("GET_DEVICE_INFO"), port.commands)
    }

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

    /** 가입 완료 계정도 명시적으로 미귀속인 보드를 등록할 수 있고 일반 연결에는 예외를 적용하지 않는다. */
    @Test fun readyAccountCanConnectAnUnownedBoardForRegistration() = runTest(dispatcher) {
        val port = ownerPort().apply {
            extraInfo = extraInfo + mapOf(
                "ownerState" to JsonPrimitive("UNOWNED"),
                "supportedFunctions" to JsonArray(listOf(JsonPrimitive("GET_REGISTRATION_PROOF"))),
            )
        }
        val vm = model(port, TestSession(OnboardingStatus.READY)) { ApiResult.Ok(emptyList()) }

        vm.scan(); runCurrent()
        vm.connect(port.currentId, registrationMode = true); runCurrent()

        assertTrue(vm.state.value.connected)
        vm.leave()
        vm.scan(); runCurrent()
        vm.connect(port.currentId); runCurrent()
        assertEquals(BleFailure.NotOwned, vm.state.value.error)
        assertFalse(vm.state.value.connected)
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
