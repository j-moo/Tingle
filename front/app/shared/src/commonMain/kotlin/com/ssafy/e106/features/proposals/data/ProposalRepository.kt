package com.ssafy.e106.features.proposals.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

// 상대의 선택은 공개 계약에 없으므로 DTO에도 추가하지 않는다.
@Serializable data class ProposalRecord(val proposalId: Long, val status: String, val myDecision: String,
    val createdAt: String, val expiresAt: String, val chatAvailable: Boolean, val chatRoomId: Long? = null) {
    val active get() = status == "PENDING" && myDecision == "NONE" || status == "WAITING_FOR_OTHER" && myDecision == "ACCEPT"
}
@Serializable data class ProposalPage(val items: List<ProposalRecord>, val nextCursor: String?, val serverTime: String)
@Serializable data class PartnerProfile(val nickname: String, val age: Int, val gender: String?,
    val introduction: String?, val smokingStatus: String?, val drinkingFrequency: String?,
    val religionDisplay: String?, val hasProfilePhoto: Boolean, val profileVersion: String)
@Serializable data class AcceptRequest(val profileVersion: String)

/** 재시도에 필요한 최소 정보만 보관한다. 상대 프로필/사진이나 인증 토큰은 저장하지 않는다. */
@Serializable data class PendingDecision(val owner: Long, val proposalId: Long, val choice: String,
    val key: String, val profileVersion: String?)

interface ProposalRepository {
    suspend fun list(cursor: String? = null): ApiResult<ProposalPage>
    suspend fun get(id: Long): ApiResult<ProposalRecord>
    suspend fun profile(id: Long): ApiResult<PartnerProfile>
    suspend fun photo(id: Long): ApiResult<ByteArray>
    suspend fun decide(request: PendingDecision): ApiResult<ProposalRecord>
}

class HttpProposalRepository(private val client: HttpClient) : ProposalRepository {
    override suspend fun list(cursor: String?) = client.call<ProposalPage> {
        get("match-proposals") { parameter("limit", 20); cursor?.let { parameter("cursor", it) }; header(HttpHeaders.CacheControl, "no-store") }
    }
    override suspend fun get(id: Long) = client.call<ProposalRecord> { get("match-proposals/$id") { header(HttpHeaders.CacheControl, "no-store") } }
    override suspend fun profile(id: Long) = client.call<PartnerProfile> { get("match-proposals/$id/partner-profile") { header(HttpHeaders.CacheControl, "no-store") } }
    override suspend fun photo(id: Long): ApiResult<ByteArray> = client.call<ByteArray> {
        get("match-proposals/$id/partner-photo") { accept(ContentType.Image.JPEG); header(HttpHeaders.CacheControl, "no-store") }
            .also { if (it.status.isSuccess()) require(it.contentType()?.withoutParameters() == ContentType.Image.JPEG) }
    }.let { result ->
        if (result is ApiResult.Ok && (result.value.isEmpty() || result.value.size > 10 * 1024 * 1024))
            ApiResult.Fail(AppError.Unknown(IllegalStateException("Invalid photo response"))) else result
    }
    override suspend fun decide(request: PendingDecision) = client.call<ProposalRecord> {
        require(request.choice in setOf("ACCEPT", "DECLINE"))
        post("match-proposals/${request.proposalId}/${if (request.choice == "ACCEPT") "accept" else "decline"}") {
            header("Idempotency-Key", request.key)
            if (request.choice == "ACCEPT") { contentType(ContentType.Application.Json); setBody(AcceptRequest(requireNotNull(request.profileVersion))) }
        }
    }
}
