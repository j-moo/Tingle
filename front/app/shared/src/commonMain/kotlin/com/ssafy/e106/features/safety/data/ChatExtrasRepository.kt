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
/** 설문 Q040(관심 주제)을 대화 주제 추천에 쓰는 별도 동의. 두 사람이 모두 켜야 공통 주제 질문이 나온다. */
@Serializable data class TopicConsentRecord(val enabled: Boolean, val noticeVersion: String, val grantedAt: String?)
@Serializable data class TopicConsentBody(val enabled: Boolean, val noticeVersion: String)
/** 나에게만 보이는 AI 위험 안내. 문구는 앱이 유형(type)·대상(audience: SENDER · RECIPIENT · null)으로 고른다. */
@Serializable data class SafetyNoticeItem(val noticeId: Long, val type: String, val audience: String?, val createdAt: String)
@Serializable data class SafetyNoticeList(val items: List<SafetyNoticeItem>)
@Serializable data class SafetyNoticeShown(val noticeId: Long, val displayedAt: String)

interface ChatExtrasRepository {
    suspend fun requestQuestions(room: Long, key: String): ApiResult<IcebreakerTask>
    suspend fun questions(room: Long): ApiResult<IcebreakerResult>
    suspend fun notices(room: Long): ApiResult<ChatNotices>
    suspend fun acknowledge(room: Long, id: String, key: String): ApiResult<ChatNoticeAck>
    suspend fun consent(user: Long): ApiResult<RiskConsentRecord>
    suspend fun consent(user: Long, body: RiskConsentBody): ApiResult<RiskConsentRecord>
    suspend fun topicConsent(user: Long): ApiResult<TopicConsentRecord>
    suspend fun topicConsent(user: Long, body: TopicConsentBody): ApiResult<TopicConsentRecord>
    suspend fun safetyNotices(room: Long): ApiResult<SafetyNoticeList>
    suspend fun safetyShown(room: Long, id: Long): ApiResult<SafetyNoticeShown>
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
    override suspend fun topicConsent(user: Long) = client.call<TopicConsentRecord> { get("users/$user/icebreaker-topic-consent") }
    override suspend fun safetyNotices(room: Long) = client.call<SafetyNoticeList> { get("chat-rooms/$room/safety-notices") }
    override suspend fun safetyShown(room: Long, id: Long) = client.call<SafetyNoticeShown> {
        put("chat-rooms/$room/safety-notices/$id/displayed")
    }
    override suspend fun topicConsent(user: Long, body: TopicConsentBody) = client.call<TopicConsentRecord> {
        put("users/$user/icebreaker-topic-consent") { setBody(body) }
    }
}
