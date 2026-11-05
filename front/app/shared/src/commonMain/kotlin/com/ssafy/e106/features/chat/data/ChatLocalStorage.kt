package com.ssafy.e106.features.chat.data

import com.ssafy.e106.core.storage.AppPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 목록과 대화 화면이 같은 잠금을 사용해 종료 정리와 늦은 초안 저장이 서로 뒤집히지 않게 한다. */
class ChatLocalStorage(internal val prefs: AppPrefs) {
    internal val mutex = Mutex()
    private val closed = mutableSetOf<Pair<Long, Long>>()
    internal fun closedLocked(user: Long, room: Long) = user to room in closed
    // 일시 권한 제한 후 재동의처럼 서버가 접근을 다시 허용한 경우에만 새 입력을 허용한다.
    internal suspend fun allowRoom(user: Long, room: Long) = mutex.withLock { closed.remove(user to room) }

    /** 작성 원문을 저장하기 전에 방 인덱스를 확정한다. 목록에 없다는 이유만으로 삭제하지 않는다. */
    internal suspend fun trackLocked(user: Long, room: Long) {
        val old = prefs.flow(chatStoredRoomsKey).first()?.takeIf { it.owner == user }
        if (old?.rooms?.contains(room) == true) return
        val next = ChatStoredRooms(user, old?.rooms.orEmpty() + room)
        prefs.set(chatStoredRoomsKey, next, user)
        check(prefs.flow(chatStoredRoomsKey).first() == next)
    }
    internal suspend fun storedRooms(user: Long): Set<Long> = mutex.withLock {
        prefs.flow(chatStoredRoomsKey).first()?.takeIf { it.owner == user }?.rooms.orEmpty() +
            listOfNotNull(prefs.flow(pendingChatKey).first()?.takeIf { it.owner == user }?.room,
                prefs.flow(chatDraftKey).first()?.takeIf { it.owner == user }?.room)
    }

    internal suspend fun draft(user: Long, room: Long): String = mutex.withLock {
        if (closedLocked(user, room)) return@withLock ""
        val saved = prefs.flow(chatDraftKey(room)).first()?.takeIf { it.owner == user && it.room == room }
        val legacy = prefs.flow(chatDraftKey).first()?.takeIf { it.owner == user && it.room == room }
        if (saved != null || legacy != null) trackLocked(user, room)
        // 구버전의 단일 초안은 해당 방을 처음 열 때만 이관한다. 다른 방의 초안은 건드리지 않는다.
        if (legacy != null) {
            if (saved == null) prefs.set(chatDraftKey(room), legacy, user)
            prefs.set(chatDraftKey, null, user)
        }
        saved?.text ?: legacy?.text.orEmpty()
    }

    internal suspend fun forgetRoom(user: Long, room: Long) = mutex.withLock {
        closed += user to room
        prefs.set(chatDraftKey(room), null, user)
        if (prefs.flow(chatDraftKey).first()?.let { it.owner == user && it.room == room } == true)
            prefs.set(chatDraftKey, null, user)
        if (prefs.flow(pendingChatKey).first()?.let { it.owner == user && it.room == room } == true)
            prefs.set(pendingChatKey, null, user)
        // 삭제가 확인된 방만 인덱스에서 제거한다. 실패한 방은 다음 목록 진입에서 다시 정리한다.
        if (prefs.flow(chatDraftKey(room)).first() == null &&
            prefs.flow(pendingChatKey).first()?.room != room && prefs.flow(chatDraftKey).first()?.room != room) {
            prefs.flow(chatStoredRoomsKey).first()?.takeIf { it.owner == user }?.let {
                prefs.set(chatStoredRoomsKey, it.copy(rooms = it.rooms - room), user)
            }
        }
    }
}
