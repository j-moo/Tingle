package com.ssafy.e106.core.push

import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.storage.PrefKey
import com.ssafy.e106.core.storage.PrefRead
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** 실제 저장소처럼 계정별로 값을 두고, 읽기 실패를 흉내 낼 수 있다 */
private class InboxPrefs : AppPrefs {
    val values = mutableMapOf<String, Any?>()
    var failRead: PrefRead<Nothing>? = null
    override fun <T> flow(key: PrefKey<T>): Flow<T?> = flowOf(null)
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) { values["$forUser:${key.name}"] = value }
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> = failRead
        ?: (values["$forUser:${key.name}"] as T?)?.let { PrefRead.Found(it) } ?: PrefRead.Missing
}

class NotificationInboxTest {
    private fun item(id: String, room: Long? = null) = InboxItem(id, "초록", "안녕하세요", 1_000, roomId = room)
    private fun ids(prefs: InboxPrefs) = Regex("id=([a-z0-9]+)").findAll(prefs.values.values.single().toString()).map { it.groupValues[1] }.toList()

    @Test fun keepsNewestFirstUpToMaxAndMarksRead() = runTest {
        val prefs = InboxPrefs(); val inbox = NotificationInbox(prefs)
        repeat(NotificationInbox.MAX + 3) { inbox.add(1, item("m$it")) }
        val list = ids(prefs)
        assertEquals("m52", list.first()); assertEquals(NotificationInbox.MAX, list.size)
        assertFalse("m0" in list)   // 가장 오래된 것은 밀려남
        inbox.markAllRead(1)
        assertFalse(prefs.values.values.single().toString().contains("read=false"))
        // 같은 알림이 다시 와도 읽은 항목을 되살리거나 맨 위로 올리지 않는다
        inbox.add(1, item("m10"))
        assertEquals("m52", ids(prefs).first()); assertFalse(prefs.values.values.single().toString().contains("read=false"))
    }

    @Test fun unreadableStoreIsNotOverwritten() = runTest {
        val prefs = InboxPrefs(); val inbox = NotificationInbox(prefs)
        inbox.add(1, item("a")); prefs.failRead = PrefRead.Unreadable
        inbox.add(1, item("b"))
        assertEquals(listOf("a"), ids(prefs))
    }

    @Test fun targetFollowsRoomOrProposal() {
        assertEquals(PushTarget.Chat(8), item("x", room = 8).target)
        assertEquals(PushTarget.Proposal(3), InboxItem("p", null, "제안", 1, proposalId = 3).target)
        assertEquals(PushTarget.Home, InboxItem("h", null, "알림", 1).target)
    }
}
