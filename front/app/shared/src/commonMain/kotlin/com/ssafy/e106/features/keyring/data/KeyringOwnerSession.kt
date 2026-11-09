package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.ble.BleConnection
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.call
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 기록 읽기와 접수증 전달은 각 작업에 필요한 권한만 요청한다. */
internal enum class KeyringOwnerPermission(val bit: Int) { Read(1), Ack(2) }

@Serializable
internal data class KeyringOwnerGrantRequest(
    val ownerGeneration: String,
    val ownershipEpoch: Long,
    val deviceNonce: String,
    val permissions: Int,
    val lifetimeSeconds: Int,
) {
    override fun toString() = "KeyringOwnerGrantRequest(REDACTED)"
}

@Serializable
internal data class KeyringOwnerGrant(
    val proofProtocolVersion: String,
    val serverKeyId: String,
    val deviceId: Long,
    val ownerGeneration: String,
    val ownershipEpoch: Long,
    val deviceNonce: String,
    val permissions: Int,
    val serverTime: String,
    val expiresAtEpochSeconds: Long,
    val serverSignature: String,
) {
    override fun toString() = "KeyringOwnerGrant(REDACTED)"
}

internal interface KeyringOwnerSessionRepository {
    suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest): ApiResult<KeyringOwnerGrant>
    suspend fun get(deviceId: Long, key: String): ApiResult<KeyringOwnerGrant>
}

internal class HttpKeyringOwnerSessionRepository(private val client: HttpClient) : KeyringOwnerSessionRepository {
    override suspend fun create(deviceId: Long, key: String, body: KeyringOwnerGrantRequest) = client.call<KeyringOwnerGrant> {
        post("keyrings/$deviceId/owner-session-grants") {
            header("Idempotency-Key", key)
            setBody(body)
        }
    }

    override suspend fun get(deviceId: Long, key: String) = client.call<KeyringOwnerGrant> {
        get("keyrings/$deviceId/owner-session-grants/$key") { header(HttpHeaders.CacheControl, "no-store") }
    }
}

internal class KeyringOwnerSessionFailure(val error: AppError) : Exception("Keyring owner session failed")

/** 허가는 연결에 묶인다. 재연결·앱 재시작에서 재사용할 저장 상태를 만들지 않는다. */
internal class KeyringOwnerAuthorizer(
    private val repository: KeyringOwnerSessionRepository,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun authorize(
        channel: BleConnection,
        identity: SyncIdentity,
        permission: KeyringOwnerPermission,
        ensureCurrent: () -> Unit,
    ) {
        val epoch = requireNotNull(identity.ownershipEpoch)
        require(identity.deviceId > 0 && identity.proofProtocolVersion == KeyringRegistrationProtocol.VERSION_2 &&
            epoch >= 0 && KeyringRegistrationProtocol.uuid(identity.ownerGeneration))

        // 페어링 대기는 키링의 60초 challenge가 생기기 전에 끝낸다.
        channel.prepareProtectedTransport()
        ensureCurrent()
        val started = time.markNow()
        val challengeId = Uuid.random().toString()
        val challenge = BleProbeProtocol.response(
            channel.exchangeProtected(frame("GET_OWNER_CHALLENGE", challengeId)),
            challengeId, "OWNER_CHALLENGE",
        )
        ensureCurrent()
        val nonce = (challenge["deviceNonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ttl = (challenge["expiresInSeconds"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(nonce != null && KeyringSyncProtocol.validDeviceNonce(nonce) && ttl != null && ttl in 1..60)
        fun checkChallenge() {
            ensureCurrent()
            if (started.elapsedNow() >= ttl.seconds) throw BleCommandException("OWNER_CHALLENGE_REQUIRED")
        }

        val key = Uuid.random().toString()
        val body = KeyringOwnerGrantRequest(identity.ownerGeneration, epoch, nonce, permission.bit, lifetimeSeconds = 60)
        checkChallenge()
        var response = repository.create(identity.deviceId, key, body)
        checkChallenge()
        if (response is ApiResult.Fail && response.error.mayHaveIssuedGrant()) {
            // 성공 응답만 사라졌을 수 있다. 같은 연결과 challenge에서 원래 요청 키로 결과를 찾는다.
            response = repository.get(identity.deviceId, key)
            checkChallenge()
            val missing = (response as? ApiResult.Fail)?.error as? AppError.Server
            if (missing?.status == 404) {
                response = repository.create(identity.deviceId, key, body)
                checkChallenge()
            }
        }
        val grant = when (response) {
            is ApiResult.Ok -> response.value
            is ApiResult.Fail -> throw KeyringOwnerSessionFailure(response.error)
        }
        val issuedAt = Instant.parse(grant.serverTime).epochSeconds
        require(grant.deviceId == identity.deviceId && grant.ownerGeneration == body.ownerGeneration &&
            grant.ownershipEpoch == epoch && grant.deviceNonce == nonce && grant.permissions == permission.bit &&
            grant.proofProtocolVersion == "tingle-owner-session-v2" &&
            grant.serverKeyId.matches(Regex("[A-Za-z0-9._-]{1,64}")) && validSignature(grant.serverSignature) &&
            issuedAt > 0 && grant.expiresAtEpochSeconds > issuedAt &&
            grant.expiresAtEpochSeconds - issuedAt <= body.lifetimeSeconds)

        val authorizationId = Uuid.random().toString()
        val authorization = frame("AUTHORIZE_OWNER", authorizationId) {
            // 서버 시각·서명을 포함한 검증 원문을 바꾸지 않고 같은 키링에 전달한다.
            put("proofProtocolVersion", grant.proofProtocolVersion)
            put("serverKeyId", grant.serverKeyId)
            put("deviceId", grant.deviceId)
            put("ownerGeneration", grant.ownerGeneration)
            put("ownershipEpoch", grant.ownershipEpoch)
            put("deviceNonce", grant.deviceNonce)
            put("permissions", grant.permissions)
            put("serverTime", grant.serverTime)
            put("expiresAtEpochSeconds", grant.expiresAtEpochSeconds)
            put("serverSignature", grant.serverSignature)
        }
        checkChallenge()
        val result = BleProbeProtocol.response(channel.exchangeProtected(authorization), authorizationId, "OWNER_RESULT")
        ensureCurrent()
        require(result["authorized"] == JsonPrimitive(true) && result["permissions"] == JsonPrimitive(permission.bit))
    }

    private fun AppError.mayHaveIssuedGrant() = this == AppError.Offline ||
        this is AppError.Unknown || this is AppError.Server && status in 500..599

    private fun validSignature(value: String): Boolean {
        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return value.length == 86 && runCatching {
            val decoded = base64.decode(value)
            decoded.size == 64 && base64.encode(decoded) == value
        }.getOrDefault(false)
    }

    private fun frame(type: String, id: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        (buildJsonObject {
            put("protocolVersion", 1)
            put("type", type)
            put("requestId", id)
            fields()
        }.toString() + "\n").encodeToByteArray()
}
