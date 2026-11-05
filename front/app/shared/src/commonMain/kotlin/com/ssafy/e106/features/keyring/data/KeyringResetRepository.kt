package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable

/** BE의 공개 DTO만 보존한다. challenge·서명·명령은 일반 로그에 노출하지 않는다. */
@Serializable data class ResetDeviceChallenge(val resetProtocolVersion: String, val keyId: String,
    val deviceSerial: String, val ownershipEpoch: Long, val localOwnerGeneration: String,
    val challengeId: String, val deviceNonce: String, val expiresInSeconds: Int, val signature: String) {
    override fun toString() = "ResetDeviceChallenge(REDACTED)"
}
@Serializable data class ResetStartBody(val discardUnsentConfirmed: Boolean, val deviceChallenge: ResetDeviceChallenge) {
    override fun toString() = "ResetStartBody(REDACTED)"
}
@Serializable data class ResetCommandBody(val deviceChallenge: ResetDeviceChallenge) {
    override fun toString() = "ResetCommandBody(REDACTED)"
}
@Serializable data class ResetAckBody(val proof: String) {
    override fun toString() = "ResetAckBody(REDACTED)"
}
@Serializable data class KeyringResetRun(val resetRunId: String, val deviceId: Long, val ownerGeneration: String,
    val state: String, val discardUnsentConfirmed: Boolean, val createdAt: String, val commandExpiresAt: String,
    val completedAt: String? = null, val deviceCommand: String? = null, val fromOwnershipEpoch: Long,
    val toOwnershipEpoch: Long, val resetNonce: String) {
    override fun toString() = "KeyringResetRun(REDACTED)"
    // 명령은 challenge 재발급 때 달라진다. 실행의 소유·삭제 범위는 재발급에도 고정돼야 한다.
    fun identity() = ResetIdentity(resetRunId, deviceId, ownerGeneration, discardUnsentConfirmed,
        fromOwnershipEpoch, toOwnershipEpoch, resetNonce, createdAt)
}
@Serializable data class ResetIdentity(val resetRunId: String, val deviceId: Long, val ownerGeneration: String,
    val discardUnsentConfirmed: Boolean, val fromOwnershipEpoch: Long, val toOwnershipEpoch: Long,
    val resetNonce: String, val createdAt: String) {
    override fun toString() = "ResetIdentity(REDACTED)"
}

interface KeyringResetRepository {
    suspend fun start(deviceId: Long, key: String, body: ResetStartBody): ApiResult<KeyringResetRun>
    suspend fun get(deviceId: Long, runId: String): ApiResult<KeyringResetRun>
    suspend fun command(deviceId: Long, runId: String, body: ResetCommandBody): ApiResult<KeyringResetRun>
    suspend fun ack(deviceId: Long, runId: String, body: ResetAckBody): ApiResult<KeyringResetRun>
}

/** 시작만 멱등키를 요구한다. command/ack는 동일 실행·challenge/증명으로 중복을 처리하는 BE 계약을 따른다. */
class HttpKeyringResetRepository(private val client: HttpClient) : KeyringResetRepository {
    override suspend fun start(deviceId: Long, key: String, body: ResetStartBody) = client.call<KeyringResetRun> {
        post("keyrings/$deviceId/reset-runs") { header("Idempotency-Key", key); setBody(body) }
    }
    override suspend fun get(deviceId: Long, runId: String) = client.call<KeyringResetRun> {
        get("keyrings/$deviceId/reset-runs/$runId") { header(HttpHeaders.CacheControl, "no-store") }
    }
    override suspend fun command(deviceId: Long, runId: String, body: ResetCommandBody) = client.call<KeyringResetRun> {
        post("keyrings/$deviceId/reset-runs/$runId/command") { setBody(body) }
    }
    override suspend fun ack(deviceId: Long, runId: String, body: ResetAckBody) = client.call<KeyringResetRun> {
        post("keyrings/$deviceId/reset-runs/$runId/ack") { setBody(body) }
    }
}
