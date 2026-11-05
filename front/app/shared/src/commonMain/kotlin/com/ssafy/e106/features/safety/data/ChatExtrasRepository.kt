package com.ssafy.e106.features.safety.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import kotlinx.serialization.Serializable

@Serializable data class IcebreakerTask(val chatRoomId: Long, val kind: String, val status: String,
    val statusUrl: String, val errorCode: String?, val retryable: Boolean, val pollAfterSeconds: Int)
@Serializable data class IcebreakerResult(val status: String, val questions: List<String>, val source: String?, val promptVersion: String?)
@Serializable data class ChatNoticeRecord(val noticeId: String, val type: String, val scheduledAt: String,
    val expiresAt: String, val text: String, val acknowledgedAt: String?)
@Serializable data class ChatNotices(val items: List<ChatNoticeRecord>)
@Serializable data class ChatNoticeAck(val serverTime: String)
@Serializable data class RiskConsentRecord(val enabled: Boolean, val noticeVersion: String, val grantedAt: String?)
@Serializable data class RiskConsentBody(val enabled: Boolean, val noticeVersion: String)

interface ChatExtrasRepository {
    suspend fun requestQuestions(room: Long, key: String): ApiResult<IcebreakerTask>
    suspend fun questions(room: Long): ApiResult<IcebreakerResult>
    suspend fun notices(room: Long): ApiResult<ChatNotices>
    suspend fun acknowledge(room: Long, id: String, key: String): ApiResult<ChatNoticeAck>
    suspend fun consent(user: Long): ApiResult<RiskConsentRecord>
    suspend fun consent(user: Long, body: RiskConsentBody): ApiResult<RiskConsentRecord>
}

/** 공개 BE API만 사용한다. 내부 AI/GMS 호출·위험 분석 활성 설정은 FE에서 다루지 않는다. */
class HttpChatExtrasRepository(private val client: HttpClient) : ChatExtrasRepository {
    override suspend fun requestQuestions(room: Long, key: String) = client.call<IcebreakerTask> {
        post("chat-rooms/$room/icebreakers") { header("Idempotency-Key", key) }
    }
    override suspend fun questions(room: Long) = client.call<IcebreakerResult> { get("chat-rooms/$room/icebreakers") }
    override suspend fun notices(room: Long) = client.call<ChatNotices> { get("chat-rooms/$room/notices") }
    override suspend fun acknowledge(room: Long, id: String, key: String) = client.call<ChatNoticeAck> {
        put("chat-rooms/$room/notices/$id/ack") { header("Idempotency-Key", key) }
    }
    override suspend fun consent(user: Long) = client.call<RiskConsentRecord> { get("users/$user/risk-analysis-consent") }
    override suspend fun consent(user: Long, body: RiskConsentBody) = client.call<RiskConsentRecord> {
        put("users/$user/risk-analysis-consent") { setBody(body) }
    }
}
