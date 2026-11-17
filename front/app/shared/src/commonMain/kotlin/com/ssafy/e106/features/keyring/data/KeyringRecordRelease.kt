package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.call
import com.ssafy.e106.core.storage.ProtectedRead
import com.ssafy.e106.core.storage.ProtectedRecordStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 서버가 현재 관계를 검사한 뒤 서명한 반복 억제 해제 명령. 앱은 내용을 바꾸지 않고 키링에 중계한다. */
@Serializable
internal data class KeyringReleaseCommand(
    val proofProtocolVersion: String, val serverKeyId: String, val deviceId: Long, val ownerGeneration: String,
    val ownershipEpoch: Long, val eventId: String, val receiptId: String, val peerDeviceRefHex: String,
    val receivedAtEpochSeconds: Long, val result: String, val serverSignature: String,
) {
    override fun toString() = "KeyringReleaseCommand(REDACTED)"
}

@Serializable
internal data class KeyringReleaseRequest(val receiptId: String, val command: String)

internal interface KeyringReleaseRepository {
    suspend fun create(deviceId: Long, key: String, body: KeyringReleaseRequest): ApiResult<KeyringReleaseCommand>
    suspend fun get(deviceId: Long, key: String): ApiResult<KeyringReleaseCommand>
}

internal class HttpKeyringReleaseRepository(private val client: HttpClient) : KeyringReleaseRepository {
    override suspend fun create(deviceId: Long, key: String, body: KeyringReleaseRequest) =
        client.call<KeyringReleaseCommand> {
            post("keyrings/$deviceId/record-commands") {
                header("Idempotency-Key", key)
                setBody(body)
            }
        }

    override suspend fun get(deviceId: Long, key: String) = client.call<KeyringReleaseCommand> {
        get("keyrings/$deviceId/record-commands/$key") { header(HttpHeaders.CacheControl, "no-store") }
    }
}

/** 접수증마다 처음 만든 requestKey를 끝까지 재사용한다. 응답이 유실돼도 서버는 같은 명령을 돌려준다. */
@Serializable
internal data class PendingRelease(
    val receiptId: String, val requestKey: String, val ownerGeneration: String, val since: Long,
    val failures: Int = 0, val notBefore: Long = 0,
) {
    override fun toString() = "PendingRelease(REDACTED)"
}

internal object KeyringReleaseProtocol {
    const val VERSION = "tingle-record-release-v2"
    const val RESULT = "RELEASE_AFTER_DEPARTURE"
    private val peerRef = Regex("[0-9a-f]{16}")

    /** 다른 접수증·소유 세대의 명령을 이 기록의 해제로 보내지 않는다. 서명 판정은 키링이 한다. */
    fun matches(command: KeyringReleaseCommand, pending: PendingRelease, identity: SyncIdentity) =
        command.proofProtocolVersion == VERSION && command.result == RESULT &&
            command.receiptId == pending.receiptId && command.deviceId == identity.deviceId &&
            command.ownerGeneration == identity.ownerGeneration && command.ownershipEpoch == identity.ownershipEpoch &&
            peerRef.matches(command.peerDeviceRefHex) && command.receivedAtEpochSeconds > 0 &&
            command.serverKeyId.matches(Regex("[A-Za-z0-9._-]{1,64}")) && command.serverSignature.length == 86 &&
            KeyringRegistrationProtocol.uuid(command.eventId)

    @OptIn(ExperimentalUuidApi::class)
    fun frame(command: KeyringReleaseCommand, id: String = Uuid.random().toString()) = (buildJsonObject {
        put("protocolVersion", 1)
        put("type", "RELEASE_RECORD")
        put("requestId", id)
        ApiJson.encodeToJsonElement(KeyringReleaseCommand.serializer(), command).jsonObject.forEach { (name, value) -> put(name, value) }
    }.toString() + "\n").encodeToByteArray()

    @OptIn(ExperimentalUuidApi::class)
    suspend fun release(command: KeyringReleaseCommand, exchange: suspend (ByteArray) -> ByteArray) {
        val id = Uuid.random().toString()
        val result = BleProbeProtocol.response(exchange(frame(command, id)), id, "RELEASE_RESULT")
        require(result["result"] == JsonPrimitive("RELEASED"))
    }
}

/** 해제 대기 목록. 앱 재시작·BLE 단절 후에도 같은 requestKey로 이어간다. */
internal class KeyringReleaseJournal(private val store: ProtectedRecordStore) {
    private val lock = Mutex()
    private val serializer = ListSerializer(PendingRelease.serializer())

    suspend fun read(owner: Long, deviceId: Long): List<PendingRelease> = lock.withLock { load(key(owner, deviceId)) }

    suspend fun update(owner: Long, deviceId: Long, change: (List<PendingRelease>) -> List<PendingRelease>) =
        lock.withLock {
            val key = key(owner, deviceId)
            val old = load(key)
            val next = change(old)
            if (next != old) store.write(key, ApiJson.encodeToString(serializer, next))
        }

    private suspend fun load(key: String) = when (val result = store.read(key)) {
        ProtectedRead.Missing -> emptyList()
        is ProtectedRead.Found -> ApiJson.decodeFromString(serializer, result.value)
        ProtectedRead.Unavailable -> error("Protected release journal unavailable")
    }

    private fun key(owner: Long, deviceId: Long) = "keyring.release.v1.$owner.$deviceId"
}

/**
 * 원본 정리(ACK_RECORD) 뒤 동기화 시도마다 대기 중인 접수증의 반복 억제 해제를 시도한다.
 * 실패해도 예외를 밖으로 내보내지 않아 나머지 동기화를 막지 않는다.
 */
internal class KeyringRecordRelease(
    private val api: KeyringReleaseRepository,
    private val journal: KeyringReleaseJournal,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    enum class Outcome { Released, DepartureNotConfirmed, PolicyUnresolved, Failed }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun enqueue(owner: Long, proof: KeyringEncounterProof) = journal.update(owner, proof.deviceId) { list ->
        if (list.any { it.receiptId == proof.receiptId }) list
        else list + PendingRelease(proof.receiptId, Uuid.random().toString(), proof.ownerGeneration, now())
    }

    /** [send]는 같은 연결의 RELEASE 권한으로 명령을 보낸다. 반환값은 이번에 시도한 항목의 결과다. */
    suspend fun releaseDue(owner: Long, identity: SyncIdentity,
        send: suspend (KeyringReleaseCommand) -> Unit): Map<String, Outcome> {
        val time = now()
        val outcomes = linkedMapOf<String, Outcome>()
        for (pending in journal.read(owner, identity.deviceId)) {
            // 소유 세대가 바뀌었거나 원본 보관 기한이 지난 접수증은 더 이상 서명받을 수 없다.
            if (pending.ownerGeneration != identity.ownerGeneration || time - pending.since > MAX_AGE_SECONDS) {
                journal.update(owner, identity.deviceId) { list -> list.filterNot { it.receiptId == pending.receiptId } }
                continue
            }
            if (pending.notBefore > time) continue
            val outcome = attempt(identity, pending, send)
            outcomes[pending.receiptId] = outcome
            journal.update(owner, identity.deviceId) { list ->
                list.mapNotNull { item ->
                    when {
                        item.receiptId != pending.receiptId -> item
                        outcome == Outcome.Released -> null
                        // 이탈 관측 전이다. 다음 동기화에서 바로 다시 시도한다.
                        outcome == Outcome.DepartureNotConfirmed -> item
                        else -> item.copy(failures = item.failures + 1, notBefore = time + backoff(item.failures))
                    }
                }
            }
        }
        return outcomes
    }

    private suspend fun attempt(identity: SyncIdentity, pending: PendingRelease,
        send: suspend (KeyringReleaseCommand) -> Unit): Outcome {
        val body = KeyringReleaseRequest(pending.receiptId, KeyringReleaseProtocol.RESULT)
        var response = api.create(identity.deviceId, pending.requestKey, body)
        if (response is ApiResult.Fail && response.error.mayHaveIssued()) {
            // 서명 응답만 사라졌을 수 있다. 같은 requestKey로 기존 명령을 찾고, 없으면 같은 키로 다시 요청한다.
            response = api.get(identity.deviceId, pending.requestKey)
            if (((response as? ApiResult.Fail)?.error as? AppError.Server)?.status == 404) {
                response = api.create(identity.deviceId, pending.requestKey, body)
            }
        }
        val command = when (response) {
            is ApiResult.Ok -> response.value
            is ApiResult.Fail -> {
                val error = response.error as? AppError.Server
                return if (error?.status == 422 && error.code == "POLICY_UNRESOLVED") Outcome.PolicyUnresolved
                else Outcome.Failed
            }
        }
        if (!KeyringReleaseProtocol.matches(command, pending, identity)) return Outcome.Failed
        return try {
            send(command)
            Outcome.Released
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BleCommandException) {
            if (failure.code == "DEPARTURE_NOT_CONFIRMED") Outcome.DepartureNotConfirmed else Outcome.Failed
        } catch (_: Exception) {
            Outcome.Failed
        }
    }

    private fun AppError.mayHaveIssued() = this == AppError.Offline ||
        this is AppError.Unknown || this is AppError.Server && status in 500..599

    // ponytail: 1분부터 두 배, 최대 6시간. 서버 상태 알림이 생기면 그 신호로 바꾼다.
    private fun backoff(failures: Int) = minOf(60L shl minOf(failures, 9), 6 * 3600L)

    companion object {
        // 만남 기한(최대 7일)과 서버 원본 보관(종료 후 14일)이 지나면 접수증으로 명령을 받을 수 없다.
        const val MAX_AGE_SECONDS = 21 * 24 * 3600L
    }
}
