package com.ssafy.e106.features.safety.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import kotlinx.serialization.Serializable

@Serializable data class BlockBody(val chatRoomId: Long)
@Serializable data class BlockReceipt(val proposalId: Long, val blockedAt: String)
@Serializable data class LeaveReceipt(val chatRoomId: Long, val leftAt: String, val rematchingRestricted: Boolean)
@Serializable data class PendingSafety(val owner: Long, val room: Long, val operation: String, val key: String)

interface SafetyRepository { suspend fun execute(request: PendingSafety): ApiResult<Unit> }

/** 방 CLOSED만으로 성공을 추정하지 않고, 명령의 성공 영수증을 검사한다. */
class HttpSafetyRepository(private val client: HttpClient) : SafetyRepository {
    override suspend fun execute(request: PendingSafety): ApiResult<Unit> = when (request.operation) {
        "BLOCK" -> client.call<BlockReceipt> {
            post("blocks") { header("Idempotency-Key", request.key); setBody(BlockBody(request.room)) }
        }.let { r -> when (r) {
            is ApiResult.Fail -> r
            is ApiResult.Ok -> if (r.value.proposalId > 0 && r.value.blockedAt.isNotBlank()) ApiResult.Ok(Unit) else invalid()
        } }
        "LEAVE" -> client.call<LeaveReceipt> {
            // leave는 빈 본문 계약이다. 빈 JSON 객체도 보내지 않는다.
            post("chat-rooms/${request.room}/leave") { header("Idempotency-Key", request.key) }
        }.let { r -> when(r) {
            is ApiResult.Fail -> r
            is ApiResult.Ok -> if (r.value.chatRoomId == request.room && r.value.leftAt.isNotBlank()) ApiResult.Ok(Unit) else invalid()
        } }
        else -> invalid()
    }
    private fun invalid() = ApiResult.Fail(AppError.Unknown(IllegalStateException("Invalid safety receipt")))
}
