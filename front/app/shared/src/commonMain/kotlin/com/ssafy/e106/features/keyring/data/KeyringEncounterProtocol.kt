package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.network.ApiJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
internal data class KeyringEncounterHeader(
    val recordCursorEpoch: String, val sequence: Long, val kind: String, val eventId: String,
    val submissionId: String, val deviceId: Long, val ownerGeneration: String, val ownershipEpoch: Long,
    val occurredAtEpochSeconds: Long, val expiresAtEpochSeconds: Long, val policyVersion: String,
    val matchingRuleVersion: String, val settingsSnapshotId: String, val peerCredential: String,
    val evidenceProtocolVersion: String, val totalBytes: Int, val envelopeHash: String,
) {
    override fun toString() = "KeyringEncounterHeader(REDACTED)"
}

@Serializable
internal data class KeyringEncounterUpload(val deviceId: Long, val submissions: List<Submission>) {
    @Serializable
    data class Submission(
        val submissionId: String, val eventId: String, val occurredAt: String, val policyVersion: String,
        val matchingRuleVersion: String, val settingsSnapshotId: String, val peerCredential: String,
        val evidence: Evidence,
    ) { override fun toString() = "KeyringEncounterSubmission(REDACTED)" }

    @Serializable
    data class Evidence(val protocolVersion: String, val payload: String) {
        override fun toString() = "KeyringEncounterEvidence(REDACTED)"
    }
}

@Serializable
internal data class KeyringEncounterResponse(val results: List<Receipt>) {
    @Serializable
    data class Receipt(
        val submissionId: String, val eventId: String, val receiptStatus: String,
        val receiptId: String? = null, val encounterStatus: String? = null, val errorCode: String? = null,
        val retryable: Boolean, val serverProof: KeyringEncounterProof? = null,
    )
}

@Serializable
internal data class KeyringEncounterProof(
    val proofProtocolVersion: String, val serverKeyId: String, val deviceId: Long, val ownerGeneration: String,
    val ownershipEpoch: Long, val eventId: String, val submissionId: String, val envelopeHash: String,
    val receiptId: String, val receivedAtEpochSeconds: Long, val receiptStatus: String, val deletePermission: String,
    val serverSignature: String,
) {
    override fun toString() = "KeyringEncounterProof(REDACTED)"
}

/** 보드 원본을 그대로 전달한다. 기기·서버 서명 판정은 각각 수신 측이 맡는다. */
@OptIn(ExperimentalUuidApi::class)
internal object KeyringEncounterProtocol {
    private const val CHUNK_BYTES = 512
    private const val MAX_RECORDS = 32
    private const val MIN_ENVELOPE_BYTES = 1165
    private const val MAX_ENVELOPE_BYTES = 1565
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val cursorPattern = Regex("[0-9a-f]{32}")

    data class Scan(val headers: List<KeyringEncounterHeader>, val legacyCount: Int)

    /** READ 세션에서 끝까지 순번을 읽는다. 본문은 전송 직전에 읽어 한 번에 한 건만 메모리에 둔다. */
    suspend fun scanSendable(exchange: suspend (ByteArray) -> ByteArray): Scan {
        try {
            return scan(exchange)
        } catch (failure: BleCommandException) {
            if (failure.code != "CURSOR_EPOCH_MISMATCH") throw failure
        }
        // 검색 도중 저장소 epoch가 바뀌면 처음부터 한 번만 확인한다. 계속 바뀌면 재시도를 요청한다.
        return scan(exchange)
    }

    private suspend fun scan(exchange: suspend (ByteArray) -> ByteArray): Scan {
        val id = Uuid.random().toString()
        val response = BleProbeProtocol.response(exchange(BleProbeProtocol.request("GET_STATUS", id)), id, "STATUS")
        val status = BleProbeProtocol.status(response).ownerFlow
        require(status.ownerAuthorized == true)
        val cursor = requireNotNull(status.cursorEpoch)
        var after = 0L
        var legacyCount = 0
        val headers = mutableListOf<KeyringEncounterHeader>()
        for (scanned in 0..MAX_RECORDS) {
            val record = try {
                next(exchange, after, cursor)
            } catch (failure: BleCommandException) {
                val position = failure.legacySequence
                if (position == null || position <= after || scanned == MAX_RECORDS) throw failure
                legacyCount++
                after = position
                continue
            }
            if (record == null) return Scan(headers.sortedBy { it.kind != "CONFIRMED" }, legacyCount)
            check(scanned < MAX_RECORDS) { "Encounter record limit exceeded" }

            // 나중에 접수되지 않은 원본도 이번 순회에서는 다시 선택하지 않는다.
            headers += record
            after = record.sequence
        }
        error("Encounter cursor did not advance")
    }

    /** 읽기 권한이 열린 연결에서 가져온다. cursor는 그 기기가 반환한 epoch와 함께 쓴다. */
    suspend fun next(exchange: suspend (ByteArray) -> ByteArray, after: Long = 0, cursor: String? = null): KeyringEncounterHeader? {
        require(after in 0..UInt.MAX_VALUE.toLong() && (after == 0L || cursor != null) &&
            (cursor == null || cursorPattern.matches(cursor)))
        val id = Uuid.random().toString()
        val result = BleProbeProtocol.response(exchange(frame("GET_RECORD", id) {
            put("afterSequence", after)
            cursor?.let { put("recordCursorEpoch", it) }
        }), id, "RECORD", "RECORD_END")
        val epoch = (result["recordCursorEpoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(epoch != null && cursorPattern.matches(epoch) && (cursor == null || cursor == epoch))
        if (result["type"] == JsonPrimitive("RECORD_END")) return null
        return ApiJson.decodeFromJsonElement<KeyringEncounterHeader>(result).also {
            require(it.sequence in (after + 1)..UInt.MAX_VALUE.toLong() && valid(it))
        }
    }

    suspend fun uploadBody(header: KeyringEncounterHeader, exchange: suspend (ByteArray) -> ByteArray): KeyringEncounterUpload {
        require(valid(header))
        val envelope = ByteArray(header.totalBytes)
        for (offset in envelope.indices step CHUNK_BYTES) {
            val length = minOf(CHUNK_BYTES, envelope.size - offset)
            val id = Uuid.random().toString()
            val chunk = BleProbeProtocol.response(exchange(frame("READ_RECORD_CHUNK", id) {
                put("sequence", header.sequence)
                put("offset", offset)
                put("length", length)
                put("envelopeHash", header.envelopeHash)
            }), id, "RECORD_CHUNK")
            require(chunk["sequence"] == JsonPrimitive(header.sequence) && chunk["offset"] == JsonPrimitive(offset) &&
                chunk["totalBytes"] == JsonPrimitive(header.totalBytes) && chunk["envelopeHash"] == JsonPrimitive(header.envelopeHash))
            val data = (chunk["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing encounter chunk")
            require(data.length <= 683)
            val decoded = base64.decode(data)
            require(decoded.size == length && base64.encode(decoded) == data)
            decoded.copyInto(envelope, offset)
        }
        // 전체 서명까지 포함한 원본을 해시한다. 원본이 바뀌면 업로드하거나 접수증을 요청하지 않는다.
        require(envelope.decodeToString(endIndex = 4) == "TER2" && envelope.toByteString().sha256().hex() == header.envelopeHash)
        val submission = KeyringEncounterUpload.Submission(
            header.submissionId, header.eventId, Instant.fromEpochSeconds(header.occurredAtEpochSeconds).toString(),
            header.policyVersion, header.matchingRuleVersion, header.settingsSnapshotId, header.peerCredential,
            KeyringEncounterUpload.Evidence(header.evidenceProtocolVersion, base64.encode(envelope)),
        )
        return KeyringEncounterUpload(header.deviceId, listOf(submission))
    }

    /** 다른 기록의 응답을 처리 결과나 삭제 증명으로 사용하지 않도록 먼저 식별자를 대조한다. */
    fun resultFor(header: KeyringEncounterHeader, response: KeyringEncounterResponse): KeyringEncounterResponse.Receipt =
        response.results.single().also { require(it.submissionId == header.submissionId && it.eventId == header.eventId) }

    /** 서버가 내구 접수한 같은 원본에만 삭제 허가를 사용한다. HTTP 성공과 boolean은 조건에 사용하지 않는다. */
    fun proofFor(header: KeyringEncounterHeader, result: KeyringEncounterResponse.Receipt): KeyringEncounterProof? {
        require(result.submissionId == header.submissionId && result.eventId == header.eventId)
        val proof = result.serverProof ?: return null
        require(result.receiptStatus == "STORED" && result.receiptId == proof.receiptId &&
            proof.deviceId == header.deviceId && proof.ownerGeneration == header.ownerGeneration &&
            proof.ownershipEpoch == header.ownershipEpoch && proof.eventId == header.eventId &&
            proof.submissionId == header.submissionId && proof.envelopeHash == header.envelopeHash && valid(proof))
        return proof
    }

    fun valid(proof: KeyringEncounterProof): Boolean =
        proof.proofProtocolVersion == "tingle-record-receipt-v2" && proof.receiptStatus == "STORED" &&
            proof.deletePermission == "DELETE_ORIGINAL" && proof.deviceId > 0 && proof.ownershipEpoch >= 0 &&
            proof.receivedAtEpochSeconds > 0 && hashPattern.matches(proof.envelopeHash) &&
            proof.serverKeyId.matches(Regex("[A-Za-z0-9._-]{1,64}")) && canonical(proof.serverSignature, 64) &&
            listOf(proof.ownerGeneration, proof.eventId, proof.submissionId, proof.receiptId).all(KeyringRegistrationProtocol::uuid)

    suspend fun acknowledge(proof: KeyringEncounterProof, exchange: suspend (ByteArray) -> ByteArray) {
        require(valid(proof))
        val id = Uuid.random().toString()
        val fields = ApiJson.encodeToJsonElement(KeyringEncounterProof.serializer(), proof).jsonObject
        val result = BleProbeProtocol.response(exchange(frame("ACK_RECORD", id) {
            fields.forEach { (name, value) -> put(name, value) }
        }), id, "RECORD_ACK")
        require(result["result"] == JsonPrimitive("REMOVED_OR_ALREADY_REMOVED"))
    }

    private fun valid(h: KeyringEncounterHeader) = h.deviceId > 0 && h.ownershipEpoch >= 0 &&
        h.occurredAtEpochSeconds > 0 && h.expiresAtEpochSeconds > h.occurredAtEpochSeconds &&
        h.totalBytes in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES && hashPattern.matches(h.envelopeHash) &&
        h.policyVersion == "tingle-policy-v2" && h.matchingRuleVersion == "r2-c-20261106" &&
        h.evidenceProtocolVersion == "tingle-encounter-v2" &&
        listOf(h.eventId, h.submissionId, h.ownerGeneration, h.settingsSnapshotId).all(KeyringRegistrationProtocol::uuid) &&
        when (h.kind) {
            "CONFIRMED" -> canonical(h.peerCredential, 357)
            "REVIEW" -> h.peerCredential.isEmpty() || canonical(h.peerCredential, 357)
            else -> false
        }

    private fun canonical(value: String, size: Int): Boolean {
        if (value.length != (size * 8 + 5) / 6) return false
        return runCatching {
            val bytes = base64.decode(value)
            bytes.size == size && base64.encode(bytes) == value
        }.getOrDefault(false)
    }

    private fun frame(type: String, id: String, fields: JsonObjectBuilder.() -> Unit) =
        (buildJsonObject {
            put("protocolVersion", 1)
            put("type", type)
            put("requestId", id)
            fields()
        }.toString() + "\n").encodeToByteArray()
}
