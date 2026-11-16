package com.ssafy.e106.core.push

import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

/** 이 기기 등록이 묶인 계정과 서버 bindingId(PUT push-installations 응답). 같은 사용자 · 같은 로그인 세션이면 서버가 같은 값을 준다 */
internal class PushBinding(val userId: Long, val id: String)

/** 기기 쪽 푸시 정보. Android 는 FCM(AndroidPushDevice), iOS 는 S15P31E106-197 */
/**
 * 같은 방 알림에 새 메시지를 이어 붙인다: (지금까지 온 개수, 펼친 알림에 보일 최근 줄들).
 * 개수는 전부 세고 줄은 최근 [max] 개만 남긴다. 떠 있는 알림이 없으면 (1, [body]).
 */
internal fun mergedLines(shownCount: Int, shown: List<String>, body: String, max: Int): Pair<Int, List<String>> =
    (shownCount.coerceAtLeast(0) + 1) to (shown + body).takeLast(max)

internal interface PushDevice {
    /** 서버 RegisterPushRequest.platform 값: ANDROID · IOS */
    val platform: String

    /** 설치당 UUID 하나. 계정과 무관하게 유지(앱 데이터 삭제 · 재설치면 새로) */
    suspend fun installationId(): String

    /** 지금 푸시 토큰. 못 받으면(오프라인 · Play 서비스 없음) null */
    suspend fun token(): String?

    /** 토큰이 바뀌었을 때(onNewToken) */
    val tokenRefreshed: Flow<Unit>

    /** 그 방을 화면에 띄웠다. 떠 있던 그 방의 메시지 알림(닉네임 · 본문 여러 줄)을 지운다. 알림이 없는 플랫폼은 아무 일도 하지 않는다 */
    fun clearChat(roomId: Long) {}

    /** 마지막으로 성공한 등록. 앱이 꺼져 있을 때 받은 알림을 거르는 데 쓴다(디스크) */
    suspend fun binding(): PushBinding?
    suspend fun saveBinding(binding: PushBinding?)
}

/** 서버가 보내는 data 전용 메시지 종류. 문구는 앱이 정한다(서버 payload 에 제목 · 본문이 없다) */
/** CHAT_ROOM_CREATED: 서로 수락해 매칭이 성사됨(먼저 수락하고 기다리던 사람에게 온다) */
internal enum class PushKind { CHAT_MESSAGE, CHAT_ROOM_CREATED, ONE_HOUR_BEFORE_END, NEW_PROPOSAL, OTHER }

/** 받은 data 메시지를 알림 하나로 변환한다. 제안 알림은 수신 계정과 설치를 모두 확인한다. */
internal class PushMessage(val kind: PushKind, val target: PushTarget, val recipientUserId: Long?, val bindingId: String?,
    private val installationId: String? = null, private val expiresAt: Instant? = null,
    /** 서버가 보낸 알림 제목 · 본문(채팅은 보낸 사람 닉네임 · 메시지). 없으면 앱의 기본 문구를 쓴다 */
    val title: String? = null, val body: String? = null) {
    /**
     * 띄울지. 로그인돼 있고([userId]), 지금 계정으로 성공한 등록([binding])과 메시지의 bindingId 가 같아야 한다.
     * 종료 안내는 수신자 userId 가 없어 bindingId 로만 거른다 — 오프라인 로그아웃으로 서버에 이전 계정 연결이 남아도 그 계정 알림은 bindingId 가 달라 버려진다
     */
    fun showTo(userId: Long?, binding: PushBinding?, currentInstallation: String? = null,
        now: Instant = Clock.System.now()): Boolean {
        if (userId == null || binding == null || binding.userId != userId || bindingId != binding.id) return false
        // 잘못된 새 제안은 일반 알림으로 대신 표시하지 않는다.
        if (kind == PushKind.NEW_PROPOSAL) return target is PushTarget.Proposal && recipientUserId == userId &&
            installationId != null && installationId == currentInstallation && expiresAt != null && expiresAt > now
        return recipientUserId == null || recipientUserId == userId
    }

    /**
     * 사용자가 지금 보고 있는 대화방([room])의 새 메시지인지. 화면에 바로 보이므로 알림 · 알림 모음에 남기지 않는다.
     * ponytail: 방의 실시간 연결이 끊긴 순간에 온 메시지는 알림 없이 다음 조회 때 보인다. 놓치는 일이 잦으면 연결 상태도 함께 본다
     */
    fun seenIn(room: Long?) = kind == PushKind.CHAT_MESSAGE && room != null && (target as? PushTarget.Chat)?.roomId == room
}

/**
 * 서버 data 맵 → [PushMessage]. 채팅 메시지 · 매칭 성사 · 종료 안내(ONE_HOUR_BEFORE_END)의 chatRoomId 는 방 long id 라 그 채팅 화면으로 연다.
 * 모르는 type · 깨진 id 는 홈
 */
internal fun pushMessageOf(data: Map<String, String>): PushMessage {
    val kind = PushKind.entries.firstOrNull { it.name == data["type"] && it != PushKind.OTHER } ?: PushKind.OTHER
    val target = when (kind) {
        PushKind.CHAT_MESSAGE, PushKind.CHAT_ROOM_CREATED, PushKind.ONE_HOUR_BEFORE_END -> data["chatRoomId"]?.toLongOrNull()?.let { PushTarget.Chat(it) } ?: PushTarget.Home
        PushKind.NEW_PROPOSAL -> data["proposalId"]?.toLongOrNull()?.takeIf { it > 0 }
            ?.takeIf { data["noticeId"] == "proposal-$it" }?.let { PushTarget.Proposal(it) } ?: PushTarget.Home
        else -> PushTarget.Home
    }
    val expiresAt = data["expiresAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return PushMessage(kind, target, data["recipientUserId"]?.toLongOrNull(), data["bindingId"],
        data["installationId"]?.takeIf { it.isNotBlank() }, expiresAt,
        data["title"].clip(80), data["body"].clip(300))
}

/** 앞뒤 공백을 빼고 [max] 글자에서 자른다. 이모지(서로게이트 쌍)를 반으로 자르지 않는다 */
internal fun String?.clip(max: Int): String? {
    val text = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.length <= max) return text
    return text.take(if (text[max - 1].isHighSurrogate()) max - 1 else max)
}
