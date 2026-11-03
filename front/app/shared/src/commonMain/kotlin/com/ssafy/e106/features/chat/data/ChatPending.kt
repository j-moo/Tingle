package com.ssafy.e106.features.chat.data

import com.ssafy.e106.core.storage.PrefKey
import kotlinx.serialization.Serializable

/** 수신 대화는 디스크에 캐시하지 않는다. 본인이 작성한 미확인 전송 하나만 재시도용으로 보존한다. */
@Serializable data class PendingChatSend(val owner: Long, val room: Long, val key: String, val body: ChatSendBody)
@Serializable data class ChatDraftRecord(val owner: Long, val room: Long, val text: String)
internal val pendingChatKey = PrefKey("chat.pending-send.v1", PendingChatSend.serializer())
internal val chatDraftKey = PrefKey("chat.current-draft.v1", ChatDraftRecord.serializer())
// 사용자별 공간 안에서도 방 ID를 키에 포함해 다른 방의 빈 초안이 이전 초안을 덮어쓰지 않게 한다.
internal fun chatDraftKey(room: Long) = PrefKey("chat.room-$room.draft.v2", ChatDraftRecord.serializer())
// 목록에서 빠진 방도 개별 권한 조회로 정리할 수 있도록 보관한 방 ID만 별도로 기록한다.
@Serializable internal data class ChatStoredRooms(val owner: Long, val rooms: Set<Long>)
internal val chatStoredRoomsKey = PrefKey("chat.stored-rooms.v1", ChatStoredRooms.serializer())

/** HTTP/실시간/재전송의 동일 메시지를 한 번만 표시하고 서버가 반환한 마스킹된 본문을 우선한다. */
internal fun mergeChatMessages(old: List<ChatMessageRecord>, incoming: List<ChatMessageRecord>): List<ChatMessageRecord> {
    require(incoming.all { it.messageId > 0 && it.sequence > 0 && it.sender in setOf("ME", "PARTNER") })
    return (old + incoming).associateBy { it.messageId }.values.sortedBy { it.sequence }
}
