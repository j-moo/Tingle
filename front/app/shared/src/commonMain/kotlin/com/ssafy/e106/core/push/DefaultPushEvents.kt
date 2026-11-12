package com.ssafy.e106.core.push

import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 알림 탭 → [pending]. 탭은 우리 PendingIntent 로만 들어온다(PushTapActivity, exported=false).
 * 로그아웃 상태에서 눌린 알림은 담지 않고, 복원 중(콜드 스타트)이면 담았다가 복원 결과가 로그아웃 · 다른 계정이면 버린다. 로그아웃 · 만료 때 비운다.
 * [tap] 과 [pending] 을 함께 바꾸므로 메인 스레드에서만 쓴다: open · consume 은 화면이, 세션 구독은 [scope](Dispatchers.Main)가 부른다
 */
internal class DefaultPushEvents(private val session: SessionStore, scope: CoroutineScope) : PushEvents {
    private class Tap(val target: PushTarget, val forUser: Long)

    private var tap: Tap? = null
    private val _pending = MutableStateFlow<PushTarget?>(null)
    override val pending: StateFlow<PushTarget?> = _pending.asStateFlow()
    // 목록 전체를 다시 받으므로 짧은 시간에 몰린 알림은 최신 갱신 신호 하나면 충분하다.
    private val arrivals = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val proposalArrivals = arrivals.asSharedFlow()
    private val recentProposals = LinkedHashSet<Triple<Long, String, Long>>()

    /** 메인 스레드에서 중복 수신을 거른다. 알림 ID도 제안별로 고정해 재시작 후 같은 알림이 쌓이지 않게 한다. */
    fun proposalArrived(userId: Long, bindingId: String, proposalId: Long): Boolean {
        if (!recentProposals.add(Triple(userId, bindingId, proposalId))) return false
        if (recentProposals.size > 64) recentProposals.remove(recentProposals.first())
        if ((session.state.value as? Session.SignedIn)?.userId == userId) arrivals.tryEmit(userId)
        return true
    }

    init {
        scope.launch { session.state.collect { s -> tap?.let { if (stale(it, s)) set(null) } } }
    }

    fun open(target: PushTarget, forUser: Long) {
        val t = Tap(target, forUser)
        if (!stale(t, session.state.value)) set(t)
    }

    override fun consume(target: PushTarget) {
        if (_pending.value == target) set(null)
    }

    private fun set(t: Tap?) {
        tap = t
        _pending.value = t?.target
    }

    private fun stale(t: Tap, s: Session) = when (s) {
        Session.Restoring -> false
        Session.SignedOut -> true
        is Session.SignedIn -> t.forUser != s.userId
    }
}
