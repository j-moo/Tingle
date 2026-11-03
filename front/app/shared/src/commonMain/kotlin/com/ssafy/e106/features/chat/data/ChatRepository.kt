package com.ssafy.e106.features.chat.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

// 공개 계약의 필드만 받는다. 상대 읽음/회원 번호/마지막 메시지를 추정해 만들지 않는다.
@Serializable data class ChatRoomRecord(val chatRoomId: Long, val status: String, val createdAt: String,
    val expiresAt: String, val closedAt: String?, val deleteAt: String?, val serverTime: String,
    val canRead: Boolean, val canSend: Boolean, val hasNewMessage: Boolean)
@Serializable data class ChatRoomsPage(val items: List<ChatRoomRecord>, val nextCursor: String?, val serverTime: String)
@Serializable data class ChatMessageRecord(val messageId: Long, val clientMessageId: String, val sender: String,
    val sequence: Long, val text: String, val createdAt: String)
@Serializable data class ChatMessagesPage(val items: List<ChatMessageRecord>, val nextCursor: String?, val serverTime: String)
@Serializable data class ChatSendBody(val clientMessageId: String, val text: String)
@Serializable data class ChatReadsBody(val messageIds: List<Long>)
@Serializable data class ChatReadsResult(val messageIds: List<Long>, val serverTime: String)
@Serializable data class ChatPartnerRecord(val nickname: String, val age: Int, val gender: String,
    val smokingStatus: String, val drinkingFrequency: String, val religion: String?,
    val introduction: String?, val hasProfilePhoto: Boolean)

interface ChatRepository {
    suspend fun list(cursor: String? = null): ApiResult<ChatRoomsPage>
    suspend fun room(id: Long): ApiResult<ChatRoomRecord>
    suspend fun messages(id: Long, cursor: String? = null, after: Long? = null): ApiResult<ChatMessagesPage>
    suspend fun send(id: Long, key: String, body: ChatSendBody): ApiResult<ChatMessageRecord>
    suspend fun read(id: Long, ids: List<Long>): ApiResult<ChatReadsResult>
    suspend fun profile(id: Long): ApiResult<ChatPartnerRecord>
    suspend fun photo(id: Long): ApiResult<ByteArray>
}

/** 순철의 인증/재발급/언어/환경 주소를 가진 공통 HttpClient를 그대로 사용한다. */
class HttpChatRepository(private val client: HttpClient) : ChatRepository {
    override suspend fun list(cursor: String?) = client.call<ChatRoomsPage> {
        get("chat-rooms") { parameter("limit", 20); cursor?.let { parameter("cursor", it) }; noStore() }
    }
    override suspend fun room(id: Long) = client.call<ChatRoomRecord> { get("chat-rooms/$id") { noStore() } }
    override suspend fun messages(id: Long, cursor: String?, after: Long?) = client.call<ChatMessagesPage> {
        require(cursor == null || after == null)
        get("chat-rooms/$id/messages") {
            parameter("limit", 100); cursor?.let { parameter("cursor", it) }; after?.let { parameter("afterSequence", it) }; noStore()
        }
    }
    override suspend fun send(id: Long, key: String, body: ChatSendBody) = client.call<ChatMessageRecord> {
        post("chat-rooms/$id/messages") { header("Idempotency-Key", key); setBody(body); noStore() }
    }
    override suspend fun read(id: Long, ids: List<Long>) = client.call<ChatReadsResult> {
        post("chat-rooms/$id/reads") { setBody(ChatReadsBody(ids)); noStore() }
    }
    override suspend fun profile(id: Long) = client.call<ChatPartnerRecord> { get("chat-rooms/$id/partner-profile") { noStore() } }
    override suspend fun photo(id: Long): ApiResult<ByteArray> = client.call<ByteArray> {
        get("chat-rooms/$id/partner-photo") { accept(ContentType.Image.JPEG); noStore() }
            .also { if (it.status.isSuccess()) require(it.contentType()?.withoutParameters() == ContentType.Image.JPEG) }
    }.let { result ->
        if (result is ApiResult.Ok && (result.value.isEmpty() || result.value.size > 10 * 1024 * 1024))
            ApiResult.Fail(chatInvalid()) else result
    }
    private fun HttpRequestBuilder.noStore() { header(HttpHeaders.CacheControl, "no-store") }
}

internal fun chatInvalid() = AppError.Unknown(IllegalStateException("Invalid chat response"))
internal fun AppError.chatCode() = when (this) {
    is AppError.Server -> code
    is AppError.OnboardingRequired -> "ONBOARDING_REQUIRED"
    AppError.SessionExpired -> "SESSION_REVOKED"
    else -> "UNAVAILABLE"
}
internal fun AppError.revokesChat() = this is AppError.OnboardingRequired || this == AppError.SessionExpired ||
    this is AppError.Server && (status in listOf(401, 403, 404, 410))
