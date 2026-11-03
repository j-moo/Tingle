package com.ssafy.e106.features.profile.data

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.data.AgreementsResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable enum class OptionalConsentType { PROFILE_PHOTO, PROFILE_INTRODUCTION, PROFILE_RELIGION }
@Serializable data class OptionalConsentRecord(
    val type: OptionalConsentType, val version: String, val agreed: Boolean,
    val agreedVersion: String? = null, val recordedAt: String? = null,
)
@Serializable data class OptionalConsentsResponse(val consents: List<OptionalConsentRecord>)
@Serializable data class OptionalConsentRequest(val agreed: Boolean, val version: String)

interface OptionalProfileRepository {
    suspend fun documents(): ApiResult<AgreementsResponse>
    suspend fun consents(userId: Long): ApiResult<OptionalConsentsResponse>
    suspend fun consent(userId: Long, type: OptionalConsentType, request: OptionalConsentRequest): ApiResult<OptionalConsentRecord>
    suspend fun photo(userId: Long): ApiResult<ByteArray>
    suspend fun upload(userId: Long, jpeg: ByteArray): ApiResult<ProfileResponse>
    suspend fun deletePhoto(userId: Long): ApiResult<Unit>
}

class HttpOptionalProfileRepository(private val client: HttpClient) : OptionalProfileRepository {
    override suspend fun documents() = client.call<AgreementsResponse> { get("agreements") }
    override suspend fun consents(userId: Long) = client.call<OptionalConsentsResponse> { get("users/$userId/optional-consents") }
    override suspend fun consent(userId: Long, type: OptionalConsentType, request: OptionalConsentRequest) = client.call<OptionalConsentRecord> {
        put("users/$userId/optional-consents/${type.name}") { contentType(ContentType.Application.Json); setBody(request) }
    }
    override suspend fun photo(userId: Long) = client.call<ByteArray> { get("users/$userId/profile/photo") }
    override suspend fun upload(userId: Long, jpeg: ByteArray) = client.call<ProfileResponse> {
        post("users/$userId/profile/photos") {
            val form = MultiPartFormDataContent(formData {
                append("file", jpeg, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"profile.jpg\"")
                })
            })
            contentType(form.contentType)
            setBody(form)
        }
    }
    override suspend fun deletePhoto(userId: Long) = client.call<Unit> { delete("users/$userId/profile/photo") }
}
