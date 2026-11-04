package com.ssafy.e106.features.settings.data

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class SettingsKind(val path: String) { AGE("preferences"), MATCHING("settings") }

/** 저장용 ETag는 응답 헤더 그대로 보존한다. settingsVersion으로 대신 만들지 않는다. */
data class SettingsDocument(val values: ConflictValues, val etag: String?,
    val desiredGenders: List<String> = emptyList(), val settingsVersion: Long? = null)

@Serializable internal data class AgeResponse(val desiredGenders: List<String>, val desiredAgeMode: String?,
    val desiredAgeMin: Int?, val desiredAgeMax: Int?)
@Serializable internal data class MatchingResponse(val matchingEnabled: Boolean,
    val requirePartnerPhoto: Boolean, val settingsVersion: Long)

interface MatchingSettingsRepository {
    suspend fun get(userId: Long, kind: SettingsKind): ApiResult<SettingsDocument>
    suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String): ApiResult<SettingsDocument>
}

class HttpMatchingSettingsRepository(private val client: HttpClient) : MatchingSettingsRepository {
    override suspend fun get(userId: Long, kind: SettingsKind) = request(userId, kind)

    override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String): ApiResult<SettingsDocument> {
        // 잘못된 입력/약한 ETag는 요청 전에 거부한다. 읽기 전용 성별은 전송하지 않는다.
        require(values.valid && validSettingsEtag(etag))
        val body = when (kind) {
            SettingsKind.AGE -> (values as ConflictValues.Age).let { age -> buildJsonObject {
                put("desiredAgeMode", if (age.any) "ANY" else "RANGE")
                put("desiredAgeMin", age.minimum?.let(::JsonPrimitive) ?: JsonNull)
                put("desiredAgeMax", age.maximum?.let(::JsonPrimitive) ?: JsonNull)
            } }
            SettingsKind.MATCHING -> (values as ConflictValues.Matching).let { matching -> buildJsonObject {
                put("matchingEnabled", matching.enabled); put("requirePartnerPhoto", matching.photoRequired)
            } }
        }
        return request(userId, kind, body, etag)
    }

    private suspend fun request(userId: Long, kind: SettingsKind, body: JsonObject? = null, etag: String? = null): ApiResult<SettingsDocument> {
        var responseTag: String? = null
        // 공통 인증·오류 변환을 그대로 이용하고, 이 기능에 필요한 헤더만 함께 수집한다.
        suspend fun HttpClient.send() = if (body == null) get("users/$userId/${kind.path}") else
            put("users/$userId/${kind.path}") {
                contentType(ContentType.Application.Json); header(HttpHeaders.IfMatch, etag); setBody(body)
            }
        return when (kind) {
            SettingsKind.AGE -> client.call<AgeResponse> { send().also { responseTag = it.headers[HttpHeaders.ETag] } }.map {
                require(it.desiredGenders.size == 1 && it.desiredGenders.single() in setOf("MALE", "FEMALE"))
                require(it.desiredAgeMode == null || it.desiredAgeMode in setOf("ANY", "RANGE"))
                val values = ConflictValues.Age(it.desiredAgeMin, it.desiredAgeMax,
                    any = it.desiredAgeMode == "ANY", selected = it.desiredAgeMode != null)
                require(values.valid || values.unselected)
                SettingsDocument(values, responseTag, it.desiredGenders)
            }
            SettingsKind.MATCHING -> client.call<MatchingResponse> { send().also { responseTag = it.headers[HttpHeaders.ETag] } }.map {
                require(it.settingsVersion >= 0)
                SettingsDocument(ConflictValues.Matching(it.matchingEnabled, it.requirePartnerPhoto), responseTag,
                    settingsVersion = it.settingsVersion)
            }
        }
    }
}

internal fun validSettingsEtag(value: String?) = value != null && value.length > 2 &&
    value.first() == '"' && value.last() == '"' &&
    value.substring(1, value.lastIndex).all { it != '"' && it.code in 0x21..0x7e }
