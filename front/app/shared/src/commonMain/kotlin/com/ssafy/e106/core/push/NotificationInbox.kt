package com.ssafy.e106.core.push

import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.storage.PrefKey
import com.ssafy.e106.core.storage.PrefRead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** 홈 종 모양에서 보는 받은 알림 한 건. 대상은 채팅방 · 제안 중 하나(없으면 홈) */
@Serializable data class InboxItem(val id: String, val title: String? = null, val body: String, val atMillis: Long,
    val roomId: Long? = null, val proposalId: Long? = null, val read: Boolean = false) {
    val target: PushTarget get() = when {
        roomId != null && roomId > 0 -> PushTarget.Chat(roomId)
        proposalId != null && proposalId > 0 -> PushTarget.Proposal(proposalId)
        else -> PushTarget.Home
    }
}
@Serializable private data class Inbox(val items: List<InboxItem>)

/**
 * 받은 알림 모아 보기. 서버에 알림 목록 API가 없어 이 폰에서 받은 푸시만 계정별로 남긴다(최근 [MAX]건).
 * 계정 데이터라 로그아웃 · 계정 전환 때 [AppPrefs] 기반이 지운다.
 * ponytail: 이 폰에서 받은 것만 — 다른 기기 · 앱 삭제 전 알림은 없다. 서버에 알림 목록이 생기면 그걸로 바꾼다
 */
class NotificationInbox(private val prefs: AppPrefs) {
    // 메시지 미리보기가 들어가므로 오래 남는 durable 파일이 아니라 캐시에 둔다(로그아웃 · 계정 전환 때는 둘 다 지워진다)
    private val key = PrefKey("push.inbox.v1", Inbox.serializer())
    private val lock = Mutex()

    /** 지금 로그인한 계정의 알림(최신순). 로그아웃이면 빈 목록 */
    val items: Flow<List<InboxItem>> = prefs.flow(key).map { it?.items.orEmpty() }

    suspend fun add(user: Long, item: InboxItem) = lock.withLock {
        val list = read(user) ?: return@withLock
        // FCM이 같은 메시지를 다시 보내도 이미 있는(읽었을 수 있는) 항목을 되살리지 않는다
        if (list.any { it.id == item.id }) return@withLock
        prefs.set(key, Inbox((listOf(item) + list).take(MAX)), user)
    }

    suspend fun markAllRead(user: Long) = lock.withLock {
        val list = read(user) ?: return@withLock
        if (list.any { !it.read }) prefs.set(key, Inbox(list.map { it.copy(read = true) }), user)
    }

    // 잠시 못 읽은 것(다른 계정 · 읽기 실패)은 빈 목록으로 덮어쓰지 않는다. 깨진 기록은 알림 모음일 뿐이라 새로 시작한다
    private suspend fun read(user: Long): List<InboxItem>? = when (val r = prefs.read(key, user)) {
        is PrefRead.Found -> r.value.items
        PrefRead.Missing, PrefRead.Corrupt -> emptyList()
        else -> null
    }

    companion object { const val MAX = 50 }
}
