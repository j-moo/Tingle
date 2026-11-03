package com.ssafy.e106.features.profile.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class ProfileResponse(
    val userId: Long,
    val nickname: String? = null, val birthDate: String? = null, val gender: String? = null,
    val smokingStatus: String? = null, val drinkingFrequency: String? = null,
    val religion: String? = null, val introduction: String? = null,
    val hasProfilePhoto: Boolean = false, val completed: Boolean = false,
)
@Serializable data class NicknameAvailability(val nickname: String, val available: Boolean, val reason: String? = null)

interface ProfileRepository {
    suspend fun get(userId: Long): ApiResult<ProfileResponse>
    suspend fun nickname(value: String): ApiResult<NicknameAvailability>
    suspend fun save(userId: Long, fields: JsonObject): ApiResult<ProfileResponse>
    suspend fun complete(userId: Long): ApiResult<Unit>
}
class HttpProfileRepository(private val client: HttpClient) : ProfileRepository {
    override suspend fun get(userId: Long) = client.call<ProfileResponse> { get("users/$userId/profile") }
    override suspend fun nickname(value: String) = client.call<NicknameAvailability> {
        get("users/nickname-availability") { parameter("nickname", value) }
    }
    override suspend fun save(userId: Long, fields: JsonObject) = client.call<ProfileResponse> {
        patch("users/$userId/profile") { contentType(ContentType.Application.Json); setBody(fields) }
    }
    override suspend fun complete(userId: Long) = client.call<Unit> { post("users/$userId/profile/complete") }
}
