package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.storage.ProtectedRead
import com.ssafy.e106.core.storage.ProtectedRecordStore
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** 합성 서버/키링 포트만 사용한다. 실제 서명 검증은 키링·백엔드 테스트가 맡는다. */
class KeyringRecordReleaseTest {
    private val generation = "33333333-3333-4333-8333-333333333333"
    private val receipt = "55555555-5555-4555-8555-555555555555"
    private val identity = SyncIdentity("11111111-1111-4111-8111-111111111111", 4, generation, 1, "a".repeat(64),
        "n", "key", "TSV3", ownershipEpoch = 2, proofProtocolVersion = KeyringRegistrationProtocol.VERSION_2)
    private val proof = KeyringEncounterProof("tingle-record-receipt-v2", "key-1", 4, generation, 2,
        "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "b".repeat(64), receipt,
        1793934001, "STORED", "DELETE_ORIGINAL", "A".repeat(86))
    private val command = KeyringReleaseCommand(KeyringReleaseProtocol.VERSION, "key-1", 4, generation, 2,
        proof.eventId, receipt, "0123456789abcdef", 1793934001, KeyringReleaseProtocol.RESULT, "A".repeat(86))

    private class Store : ProtectedRecordStore {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]?.let { ProtectedRead.Found(it) } ?: ProtectedRead.Missing
        override suspend fun write(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String, expectedValue: String) = values.remove(key) == expectedValue
    }

    private class Server(var create: ApiResult<KeyringReleaseCommand>, var get: ApiResult<KeyringReleaseCommand>) :
        KeyringReleaseRepository {
        val calls = mutableListOf<String>()
        override suspend fun create(deviceId: Long, key: String, body: KeyringReleaseRequest): ApiResult<KeyringReleaseCommand> {
            assertEquals(KeyringReleaseProtocol.RESULT, body.command)
            calls += "POST $key"; return create
        }
        override suspend fun get(deviceId: Long, key: String): ApiResult<KeyringReleaseCommand> {
            calls += "GET $key"; return get
        }
    }

    private var clock = 1_000L
    private val store = Store()
    private val journal = KeyringReleaseJournal(store)
    private fun release(server: Server) = KeyringRecordRelease(server, journal) { clock }
    private fun policy() = ApiResult.Fail(AppError.Server(422, "POLICY_UNRESOLVED", "x"))

    @Test fun releasedRemovesPendingAndRepeatedEnqueueKeepsSameKey() = runTest {
        val server = Server(ApiResult.Ok(command), policy())
        val release = release(server)
        release.enqueue(1, proof)
        release.enqueue(1, proof)
        val key = journal.read(1, 4).single().requestKey
        val sent = mutableListOf<KeyringReleaseCommand>()
        assertEquals(mapOf(receipt to KeyringRecordRelease.Outcome.Released), release.releaseDue(1, identity) { sent += it })
        assertEquals(listOf(command), sent)
        assertEquals(listOf("POST $key"), server.calls)
        assertTrue(journal.read(1, 4).isEmpty())
    }

    @Test fun departureNotConfirmedStaysPendingWithSameKeyForNextSync() = runTest {
        val server = Server(ApiResult.Ok(command), policy())
        val release = release(server)
        release.enqueue(1, proof)
        val before = journal.read(1, 4).single()
        repeat(2) {
            assertEquals(mapOf(receipt to KeyringRecordRelease.Outcome.DepartureNotConfirmed),
                release.releaseDue(1, identity) { throw BleCommandException("DEPARTURE_NOT_CONFIRMED") })
        }
        // 백오프 없이 다음 동기화에서 같은 키로 바로 재시도한다.
        assertEquals(listOf(before), journal.read(1, 4))
        assertEquals(listOf("POST ${before.requestKey}", "POST ${before.requestKey}"), server.calls)
    }

    @Test fun policyUnresolvedAndOtherFailuresBackOffWithoutBlockingOthers() = runTest {
        val server = Server(policy(), policy())
        val release = release(server)
        release.enqueue(1, proof)
        assertEquals(mapOf(receipt to KeyringRecordRelease.Outcome.PolicyUnresolved),
            release.releaseDue(1, identity) { fail("Must not reach keyring") })
        val waiting = journal.read(1, 4).single()
        assertEquals(1, waiting.failures)
        assertEquals(clock + 60, waiting.notBefore)
        // 대기 시간 안에는 서버를 다시 부르지 않는다.
        assertTrue(release.releaseDue(1, identity) { fail() }.isEmpty())
        clock += 60
        server.create = ApiResult.Ok(command)
        assertEquals(mapOf(receipt to KeyringRecordRelease.Outcome.Failed),
            release.releaseDue(1, identity) { throw BleCommandException("RELEASE_CONFLICT") })
        assertEquals(clock + 120, journal.read(1, 4).single().notBefore)
    }

    @Test fun lostResponseIsReplayedWithSameRequestKey() = runTest {
        val server = Server(ApiResult.Fail(AppError.Offline), ApiResult.Ok(command))
        val release = release(server)
        release.enqueue(1, proof)
        val key = journal.read(1, 4).single().requestKey
        assertEquals(mapOf(receipt to KeyringRecordRelease.Outcome.Released), release.releaseDue(1, identity) {})
        assertEquals(listOf("POST $key", "GET $key"), server.calls)

        // 조회 결과가 없으면 같은 키로 다시 발급을 요청한다.
        server.calls.clear(); server.get = ApiResult.Fail(AppError.Server(404, "RESOURCE_NOT_FOUND", "x"))
        release.enqueue(1, proof)
        val next = journal.read(1, 4).single().requestKey
        release.releaseDue(1, identity) {}
        assertEquals(listOf("POST $next", "GET $next", "POST $next"), server.calls)
    }

    @Test fun mismatchedCommandOrOldGenerationIsNeverSent() = runTest {
        val server = Server(ApiResult.Ok(command.copy(receiptId = "66666666-6666-4666-8666-666666666666")), policy())
        val release = release(server)
        release.enqueue(1, proof)
        assertEquals(KeyringRecordRelease.Outcome.Failed, release.releaseDue(1, identity) { fail() }[receipt])
        assertTrue(release.releaseDue(1, identity.copy(ownerGeneration = "77777777-7777-4777-8777-777777777777")) {
            fail()
        }.isEmpty())
        assertTrue(journal.read(1, 4).isEmpty())
    }

    @Test fun releaseRecordFrameCarriesSignedFieldsUnchanged() = runTest {
        var frame: JsonObject? = null
        KeyringReleaseProtocol.release(command) { bytes ->
            assertEquals('\n', bytes.decodeToString().last())
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.also { frame = it }
            buildJsonObject {
                put("protocolVersion", 1); put("type", "RELEASE_RESULT")
                put("requestId", request.getValue("requestId")); put("result", "RELEASED")
            }.toString().encodeToByteArray()
        }
        val sent = requireNotNull(frame)
        assertEquals("RELEASE_RECORD", sent.getValue("type").jsonPrimitive.content)
        assertEquals(1, sent.getValue("protocolVersion").jsonPrimitive.int)
        assertEquals("tingle-record-release-v2", sent.getValue("proofProtocolVersion").jsonPrimitive.content)
        // uint64 peerRef는 숫자가 아닌 16자리 hex 문자열로 보낸다.
        assertEquals(JsonPrimitive("0123456789abcdef"), sent["peerDeviceRefHex"])
        assertEquals(JsonPrimitive(1793934001L), sent["receivedAtEpochSeconds"])
        assertEquals(JsonPrimitive("RELEASE_AFTER_DEPARTURE"), sent["result"])
        listOf("serverKeyId", "deviceId", "ownerGeneration", "ownershipEpoch", "eventId", "receiptId", "serverSignature")
            .forEach { assertTrue(it in sent, it) }

        assertFailsWith<BleCommandException> {
            KeyringReleaseProtocol.release(command) { bytes ->
                val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                buildJsonObject {
                    put("protocolVersion", 1); put("type", "ERROR")
                    put("requestId", request.getValue("requestId")); put("code", "DEPARTURE_NOT_CONFIRMED")
                }.toString().encodeToByteArray()
            }
        }
    }
}
