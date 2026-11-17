package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.ble.BleException
import com.ssafy.e106.core.ble.BleFailure
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.storage.ProtectedRead
import com.ssafy.e106.core.storage.ProtectedRecordStore
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.data.KeyringRecordRelease.Outcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** 합성 서버/키링 포트만 사용한다. 실제 서명 검증은 키링·백엔드 테스트가 맡는다. */
class KeyringRecordReleaseTest {
    private val generation = "33333333-3333-4333-8333-333333333333"
    private val receipt = "55555555-5555-4555-8555-555555555555"
    private val otherReceipt = "66666666-6666-4666-8666-666666666666"
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

    private inner class Server(var response: ApiResult<KeyringReleaseCommand>) : KeyringReleaseRepository {
        val keys = mutableListOf<String>()
        override suspend fun create(deviceId: Long, key: String, body: KeyringReleaseRequest): ApiResult<KeyringReleaseCommand> {
            assertEquals(KeyringReleaseProtocol.RESULT, body.command)
            keys += key
            return (response as? ApiResult.Ok)?.let { ApiResult.Ok(it.value.copy(receiptId = body.receiptId)) } ?: response
        }
    }

    private var clock = 1_000L
    private val journal = KeyringReleaseJournal(Store())
    private fun release(server: Server) = KeyringRecordRelease(server, journal) { clock }
    private fun serverError(status: Int, code: String) = ApiResult.Fail(AppError.Server(status, code, "x"))

    @Test fun releasedRemovesPendingAndRepeatedEnqueueKeepsSameKey() = runTest {
        val server = Server(ApiResult.Ok(command))
        val release = release(server)
        release.enqueue(1, proof)
        release.enqueue(1, proof)
        val key = journal.read(1, 4).single().requestKey
        val sent = mutableListOf<KeyringReleaseCommand>()
        assertEquals(mapOf(receipt to Outcome.Released), release.releaseDue(1, identity) { sent += it })
        assertEquals(listOf(command), sent)
        assertEquals(listOf(key), server.keys)
        assertTrue(journal.read(1, 4).isEmpty())
    }

    @Test fun departureNotConfirmedWaitsShortIntervalWithoutCountingFailure() = runTest {
        val server = Server(ApiResult.Ok(command))
        val release = release(server)
        release.enqueue(1, proof)
        val notDeparted: suspend (KeyringReleaseCommand) -> Unit = { throw BleCommandException("DEPARTURE_NOT_CONFIRMED") }
        assertEquals(mapOf(receipt to Outcome.DepartureNotConfirmed), release.releaseDue(1, identity, notDeparted))
        val waiting = journal.read(1, 4).single()
        assertEquals(0, waiting.failures)
        assertEquals(clock + KeyringRecordRelease.DEPARTURE_RETRY_SECONDS, waiting.notBefore)
        // 간격 안의 동기화는 서버·키링을 부르지 않는다.
        assertTrue(release.releaseDue(1, identity) { fail() }.isEmpty())
        clock += KeyringRecordRelease.DEPARTURE_RETRY_SECONDS
        assertEquals(mapOf(receipt to Outcome.Released), release.releaseDue(1, identity) {})
        assertEquals(List(2) { waiting.requestKey }, server.keys)
    }

    @Test fun policyUnresolvedAndTransientFailuresBackOff() = runTest {
        val server = Server(serverError(422, "POLICY_UNRESOLVED"))
        val release = release(server)
        release.enqueue(1, proof)
        assertEquals(mapOf(receipt to Outcome.PolicyUnresolved), release.releaseDue(1, identity) { fail() })
        assertEquals(clock + 60, journal.read(1, 4).single().notBefore)
        clock += 60
        server.response = serverError(429, "TOO_MANY_REQUESTS")
        assertEquals(mapOf(receipt to Outcome.Failed), release.releaseDue(1, identity) { fail() })
        clock += 120
        server.response = ApiResult.Ok(command)
        assertEquals(mapOf(receipt to Outcome.Failed),
            release.releaseDue(1, identity) { throw BleCommandException("OWNER_SESSION_REQUIRED") })
        assertEquals(3, journal.read(1, 4).single().failures)
        assertEquals(clock + 240, journal.read(1, 4).single().notBefore)
    }

    @Test fun permanentFailuresAreDroppedImmediately() = runTest {
        val permanent = listOf<Pair<ApiResult<KeyringReleaseCommand>, suspend (KeyringReleaseCommand) -> Unit>>(
            serverError(409, "OWNER_MISMATCH") to { fail() },
            serverError(404, "RESOURCE_NOT_FOUND") to { fail() },
            serverError(400, "INVALID_REQUEST") to { fail() },
            ApiResult.Ok(command) to { throw BleCommandException("INVALID_RELEASE") },
            ApiResult.Ok(command) to { throw BleCommandException("RELEASE_CONFLICT") },
            ApiResult.Ok(command) to { throw BleCommandException("INVALID_SERVER_PROOF") },
            // 상위 비트가 켜진 오류 fingerprint는 이탈 해제 명령으로 보내지 않는다.
            ApiResult.Ok(command.copy(peerDeviceRefHex = "8123456789abcdef")) to { fail() },
            ApiResult.Ok(command.copy(ownershipEpoch = 3)) to { fail() },
        )
        for ((response, send) in permanent) {
            val release = release(Server(response))
            release.enqueue(1, proof)
            assertEquals(mapOf(receipt to Outcome.Dropped), release.releaseDue(1, identity, send))
            assertTrue(journal.read(1, 4).isEmpty())
        }
    }

    @Test fun lostResponseRetriesSamePostKey() = runTest {
        val server = Server(ApiResult.Fail(AppError.Offline))
        val release = release(server)
        release.enqueue(1, proof)
        val key = journal.read(1, 4).single().requestKey
        assertEquals(mapOf(receipt to Outcome.Failed), release.releaseDue(1, identity) { fail() })
        clock += 60
        server.response = ApiResult.Ok(command)
        assertEquals(mapOf(receipt to Outcome.Released), release.releaseDue(1, identity) {})
        assertEquals(listOf(key, key), server.keys)
    }

    @Test fun bleDisconnectStopsLoopWithoutCountingFailure() = runTest {
        val server = Server(ApiResult.Ok(command))
        val release = release(server)
        release.enqueue(1, proof)
        release.enqueue(1, proof.copy(receiptId = otherReceipt))
        val before = journal.read(1, 4)
        assertEquals(mapOf(receipt to Outcome.Interrupted),
            release.releaseDue(1, identity) { throw BleException(BleFailure.Disconnected) })
        assertEquals(1, server.keys.size)
        assertEquals(before, journal.read(1, 4))
    }

    @Test fun releaseNotReadyWaitsLikeDepartureWithoutCountingFailure() = runTest {
        val release = release(Server(ApiResult.Ok(command)))
        release.enqueue(1, proof)
        assertEquals(mapOf(receipt to Outcome.DepartureNotConfirmed),
            release.releaseDue(1, identity) { throw BleCommandException("RELEASE_NOT_READY") })
        val waiting = journal.read(1, 4).single()
        assertEquals(0, waiting.failures)
        assertEquals(clock + KeyringRecordRelease.DEPARTURE_RETRY_SECONDS, waiting.notBefore)
    }

    @Test fun nonLinkBleErrorBacksOffOnlyThatItem() = runTest {
        val server = Server(ApiResult.Ok(command))
        val release = release(server)
        release.enqueue(1, proof)
        release.enqueue(1, proof.copy(receiptId = otherReceipt))
        val outcomes = release.releaseDue(1, identity) { throw BleException(BleFailure.InvalidFrame) }
        // 연결 단절이 아니므로 다음 항목도 시도하고, 각 항목은 백오프한다.
        assertEquals(mapOf(receipt to Outcome.Failed, otherReceipt to Outcome.Failed), outcomes)
        assertTrue(journal.read(1, 4).all { it.failures == 1 && it.notBefore == clock + 60 })
        assertTrue(release.releaseDue(1, identity) { fail() }.isEmpty())
    }

    @Test fun changedOwnerGenerationOrEpochDropsWithoutRequest() = runTest {
        val server = Server(ApiResult.Ok(command))
        val release = release(server)
        release.enqueue(1, proof)
        assertTrue(release.releaseDue(1, identity.copy(ownershipEpoch = 3)) { fail() }.isEmpty())
        release.enqueue(1, proof)
        assertTrue(release.releaseDue(1, identity.copy(ownerGeneration = "77777777-7777-4777-8777-777777777777")) {
            fail()
        }.isEmpty())
        assertTrue(server.keys.isEmpty())
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
