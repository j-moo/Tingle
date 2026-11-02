package com.ssafy.e106.features.onboarding.data

import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.call
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class AgreementItem(
    val type: String, val version: String, val title: String, val required: Boolean,
    val contentUrl: String? = null,
) {
    // ADULT is a declaration, not an external document. Never pass placeholder terms off as a document.
    val canReview get() = type == "ADULT" || reviewUrl != null
    val reviewUrl get() = contentUrl?.takeIf(::isPublishedHttpsUrl)
}
internal fun isPublishedHttpsUrl(value: String): Boolean = runCatching {
    val url = Url(value)
    url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() &&
        listOf("example.com", "example.org", "example.net").none { url.host == it || url.host.endsWith(".$it") }
}.getOrDefault(false)

@Serializable data class AgreementAnswer(val type: String, val version: String, val agreed: Boolean)
@Serializable data class AgreementsResponse(val agreements: List<AgreementItem>)
@Serializable data class AgreeRequest(val agreements: List<AgreementAnswer>)
@Serializable data class PurchaseInfo(val purchaseUrl: String?, val registrationRequired: Boolean)

interface OnboardingRepository {
    suspend fun agreements(): ApiResult<AgreementsResponse>
    suspend fun agree(userId: Long, answers: List<AgreementAnswer>): ApiResult<Unit>
    suspend fun purchaseInfo(): ApiResult<PurchaseInfo>
}

class HttpOnboardingRepository(private val client: HttpClient) : OnboardingRepository {
    override suspend fun agreements() = client.call<AgreementsResponse> { get("agreements") }
    override suspend fun agree(userId: Long, answers: List<AgreementAnswer>) = client.call<Unit> {
        post("users/$userId/agreements") { contentType(ContentType.Application.Json); setBody(AgreeRequest(answers)) }
    }
    override suspend fun purchaseInfo() = client.call<PurchaseInfo> { get("keyrings/purchase-info") }
}
