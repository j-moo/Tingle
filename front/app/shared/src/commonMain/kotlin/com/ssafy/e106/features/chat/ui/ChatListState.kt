package com.ssafy.e106.features.chat.ui

import androidx.compose.ui.graphics.ImageBitmap

enum class ChatListLoad { Loading, Ready, Failed }
enum class ChatListPage { More, Loading, Failed, End }
enum class ChatRoomAccess { Allowed, Unknown, Closed }
enum class LastMessageKind { Text, None, Unknown }

/** 본문은 서버가 주소 마스킹·공개 권한 검사를 마친 값만 받는다. 상대 읽음 상태는 받지 않는다. */
data class ChatListItem(
    val chatRoomId: Long,
    val nickname: String? = null,
    val access: ChatRoomAccess = ChatRoomAccess.Unknown,
    val lastMessageKind: LastMessageKind = LastMessageKind.Unknown,
    val lastMessage: String? = null,
    val hasNewMessages: Boolean? = null,
    val lastMessageTimeLabel: String? = null,
    val closesAtLabel: String? = null,
    /** 읽지 않은 상대 메시지 수. null 이면 서버가 주지 않은 것(옛 서버)이라 [hasNewMessages] 점으로 표시한다 */
    val unreadCount: Int? = null,
    /** 상대 프로필 사진. 화면에 보이는 동안 메모리에만 둔다(없거나 받는 중이면 null → 닉네임 첫 글자) */
    val photo: ImageBitmap? = null,
)

/**
 * 목록 오른쪽 배지. null = 표시 없음, "" = 초록 점(개수를 주지 않는 옛 서버), 그 밖 = 숫자.
 * 서버는 100 에서 세기를 멈추므로 99 를 넘으면 "99+".
 */
internal fun unreadBadge(unread: Int?, hasNew: Boolean?): String? = when {
    unread == null -> "".takeIf { hasNew == true }
    unread <= 0 -> null
    unread > 99 -> "99+"
    else -> unread.toString()
}

data class ChatListState(
    val load: ChatListLoad = ChatListLoad.Loading,
    val rooms: List<ChatListItem> = emptyList(),
    val page: ChatListPage = ChatListPage.End,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
)

/** 같은 스냅샷 안의 종료/권한 미확인 값이 오래된 허용 값을 가리지 않도록 보수적으로 병합한다.
 * 실제 서버 시각·계정·버전·늦은 응답의 정합성은 연결부에서 확인해야 한다.
 */
internal fun visibleChatRooms(rooms: List<ChatListItem>): List<ChatListItem> =
    rooms.groupBy { it.chatRoomId }.values.mapNotNull { versions ->
        if (versions.any { it.access == ChatRoomAccess.Closed }) null
        else versions.firstOrNull { it.access == ChatRoomAccess.Unknown } ?: versions.first()
    }

enum class ChatListAction { Refresh, LoadMore }
