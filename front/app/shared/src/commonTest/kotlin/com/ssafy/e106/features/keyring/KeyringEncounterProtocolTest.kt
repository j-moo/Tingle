package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.storage.ProtectedRead
import com.ssafy.e106.core.storage.ProtectedRecordStore
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.test.*

class KeyringEncounterProtocolTest {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    // 조각 운반과 원문 보존을 확인하는 합성 데이터다. 기기 서명 검증은 백엔드 테스트에서 수행한다.
    private val raw = "TER2".encodeToByteArray() + ByteArray(1161) { (it % 251).toByte() }
    private val header = KeyringEncounterHeader(
        "a".repeat(32), 3, "REVIEW", "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
        4, "33333333-3333-4333-8333-333333333333", 2, 1793934000, 1793937600, "tingle-policy-v2",
        "r2-c-20261106", "44444444-4444-4444-8444-444444444444", "", "tingle-encounter-v2", raw.size,
        raw.toByteString().sha256().hex(),
    )
    private val proof = KeyringEncounterProof("tingle-record-receipt-v2", "key-1", header.deviceId,
        header.ownerGeneration, header.ownershipEpoch, header.eventId, header.submissionId, header.envelopeHash,
        "55555555-5555-4555-8555-555555555555", 1793934001, "STORED", "DELETE_ORIGINAL", "A".repeat(86))

    private fun response(request: JsonObject, type: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("protocolVersion", 1)
        put("requestId", request.getValue("requestId"))
        put("type", type)
        fields()
    }.toString().encodeToByteArray()

    @Test fun reviewPreservesOriginalBytesAndRejectsCorruptChunk() = runTest {
        val observedOffsets = mutableListOf<Int>()
        suspend fun body(corrupt: Boolean) = KeyringEncounterProtocol.uploadBody(header) { bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val offset = request.getValue("offset").jsonPrimitive.int
            val length = request.getValue("length").jsonPrimitive.int
            assertTrue(length in 1..512)
            observedOffsets += offset
            response(request, "RECORD_CHUNK") {
                put("sequence", header.sequence)
                put("offset", offset)
                put("totalBytes", raw.size)
                put("envelopeHash", header.envelopeHash)
                val chunk = raw.copyOfRange(offset, offset + length)
                if (corrupt && offset == 512) chunk[0] = (chunk[0] + 1).toByte()
                put("data", base64.encode(chunk))
            }
        }
        val submission = body(false).submissions.single()
        assertEquals(listOf(0, 512, 1024), observedOffsets)
        assertEquals(header.submissionId, submission.submissionId)
        assertEquals("2026-11-06T03:00:00Z", submission.occurredAt)
        assertEquals("", submission.peerCredential)
        assertEquals(base64.encode(raw), submission.evidence.payload)
        assertFailsWith<IllegalArgumentException> { body(true) }
    }

    @Test fun oldCursorCannotReadNewEpochAndEmptyRecordEndIsExplicit() = runTest {
        val next = KeyringEncounterProtocol.next({ bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(JsonPrimitive(0), request["afterSequence"])
            response(request, "RECORD") {
                ApiJson.encodeToJsonElement(KeyringEncounterHeader.serializer(), header).jsonObject.forEach { (k, v) -> put(k, v) }
            }
        })
        assertEquals(header, next)
        val end: suspend (ByteArray) -> ByteArray = { bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            response(request, "RECORD_END") { put("recordCursorEpoch", "b".repeat(32)) }
        }
        assertFailsWith<IllegalArgumentException> { KeyringEncounterProtocol.next(end, 3, header.recordCursorEpoch) }
        assertNull(KeyringEncounterProtocol.next(end))
    }

    /** 구형 항목의 위치만 건너뛰며 원본 읽기와 ACK를 시도하지 않는다. */
    @Test fun legacyRecordIsRetainedWhileNextRecordIsFound() = runTest {
        val commands = mutableListOf<String>()
        val scan = KeyringEncounterProtocol.findSendable { bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val type = request.getValue("type").jsonPrimitive.content
            commands += type
            when (type) {
                "GET_STATUS" -> response(request, "STATUS") {
                    put("ownerAuthorized", true)
                    put("recordCursorEpoch", header.recordCursorEpoch)
                }
                "GET_RECORD" -> {
                    assertEquals(JsonPrimitive(header.recordCursorEpoch), request["recordCursorEpoch"])
                    if (request["afterSequence"] == JsonPrimitive(0)) response(request, "ERROR") {
                        put("code", "LEGACY_EVIDENCE_UNAVAILABLE")
                        put("sequence", 2)
                    } else if (request["afterSequence"] == JsonPrimitive(2)) {
                        response(request, "RECORD") {
                            ApiJson.encodeToJsonElement(KeyringEncounterHeader.serializer(), header).jsonObject.forEach { (k, v) -> put(k, v) }
                        }
                    } else {
                        assertEquals(JsonPrimitive(3), request["afterSequence"])
                        response(request, "RECORD_END") { put("recordCursorEpoch", header.recordCursorEpoch) }
                    }
                }
                else -> error("Unexpected command")
            }
        }
        assertEquals(KeyringEncounterProtocol.Scan(header, 1), scan)
        assertEquals(listOf("GET_STATUS", "GET_RECORD", "GET_RECORD", "GET_RECORD"), commands)
    }

    @Test fun confirmedRecordTakesPriorityOverEarlierReviews() = runTest {
        val confirmed = header.copy(sequence = 5, kind = "CONFIRMED", peerCredential = base64.encode(ByteArray(357)))
        val (scan, cursors) = scanRecords(listOf(header, header.copy(sequence = 4), confirmed, header.copy(sequence = 6)))

        assertEquals(confirmed, scan.header)
        assertEquals(listOf(0L, 3L, 4L), cursors)
    }

    @Test fun earliestReviewRemainsSendableWhenNoConfirmedRecordExists() = runTest {
        val (scan, cursors) = scanRecords(listOf(header, header.copy(sequence = 4)))

        assertEquals(header, scan.header)
        assertEquals(listOf(0L, 3L, 4L), cursors)
    }

    private suspend fun scanRecords(records: List<KeyringEncounterHeader>): Pair<KeyringEncounterProtocol.Scan, List<Long>> {
        val cursors = mutableListOf<Long>()
        val scan = KeyringEncounterProtocol.findSendable { bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            when (request.getValue("type").jsonPrimitive.content) {
                "GET_STATUS" -> response(request, "STATUS") {
                    put("ownerAuthorized", true)
                    put("recordCursorEpoch", header.recordCursorEpoch)
                }
                "GET_RECORD" -> {
                    val after = request.getValue("afterSequence").jsonPrimitive.long
                    cursors += after
                    val record = records.firstOrNull { it.sequence > after }
                    if (record == null) response(request, "RECORD_END") {
                        put("recordCursorEpoch", header.recordCursorEpoch)
                    } else response(request, "RECORD") {
                        ApiJson.encodeToJsonElement(KeyringEncounterHeader.serializer(), record).jsonObject.forEach { (key, value) ->
                            put(key, value)
                        }
                    }
                }
                else -> error("Unexpected command")
            }
        }
        return scan to cursors
    }

    /** epoch가 바뀌면 옛 위치를 버린다. 현재 epoch에 구형 기록만 있으면 빈 키링과 구분한다. */
    @Test fun cursorEpochMismatchRestartsOnceAndReportsLegacyOnly() = runTest {
        var statusReads = 0
        val scan = KeyringEncounterProtocol.findSendable { bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (request["type"] == JsonPrimitive("GET_STATUS")) {
                statusReads++
                response(request, "STATUS") {
                    put("ownerAuthorized", true)
                    put("recordCursorEpoch", (if (statusReads == 1) "a" else "b").repeat(32))
                }
            } else if (statusReads == 1) {
                response(request, "ERROR") { put("code", "CURSOR_EPOCH_MISMATCH") }
            } else {
                assertEquals(JsonPrimitive("b".repeat(32)), request["recordCursorEpoch"])
                when (request["afterSequence"]) {
                    JsonPrimitive(0) -> response(request, "ERROR") {
                        put("code", "LEGACY_EVIDENCE_UNAVAILABLE")
                        put("sequence", 1)
                    }
                    JsonPrimitive(1) -> response(request, "RECORD_END") { put("recordCursorEpoch", "b".repeat(32)) }
                    else -> error("Unexpected cursor")
                }
            }
        }
        assertEquals(2, statusReads)
        assertEquals(KeyringEncounterProtocol.Scan(null, 1), scan)
    }

    @Test fun onlyMatchingProofMayAcknowledgeAndJournalSurvivesRecreation() = runTest {
        val result = KeyringEncounterResponse.Receipt(header.submissionId, header.eventId, "STORED", proof.receiptId, retryable = false)
        assertNull(KeyringEncounterProtocol.proofFor(header, KeyringEncounterResponse(listOf(result))))
        assertFailsWith<IllegalArgumentException> {
            KeyringEncounterProtocol.proofFor(header, KeyringEncounterResponse(listOf(result.copy(serverProof = proof.copy(ownershipEpoch = 3)))))
        }
        val received = requireNotNull(KeyringEncounterProtocol.proofFor(header,
            KeyringEncounterResponse(listOf(result.copy(serverProof = proof)))))
        val store = object : ProtectedRecordStore {
            val values = mutableMapOf<String, String>()
            override suspend fun read(key: String) = values[key]?.let { ProtectedRead.Found(it) } ?: ProtectedRead.Missing
            override suspend fun write(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String, expectedValue: String): Boolean {
                if (values[key] != expectedValue) return false
                values.remove(key)
                return true
            }
        }
        val journal = KeyringEncounterJournal(store)
        journal.save(1, received)
        // 기기가 정리했더라도 BLE 응답을 잃을 수 있다. 성공 확인 전까지 같은 접수증을 보존한다.
        assertFailsWith<IllegalStateException> {
            KeyringEncounterProtocol.acknowledge(received) { error("BLE response lost") }
        }
        val restored = KeyringEncounterJournal(store)
        assertNull(restored.read(2, 4))
        assertNull(restored.read(1, 5))
        val retry = requireNotNull(restored.read(1, 4))
        assertEquals(proof, retry)
        KeyringEncounterProtocol.acknowledge(retry) { bytes ->
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(JsonPrimitive(proof.serverSignature), request["serverSignature"])
            response(request, "RECORD_ACK") { put("result", "REMOVED_OR_ALREADY_REMOVED") }
        }
        restored.remove(1, retry)
        assertNull(restored.read(1, 4))
    }
}
