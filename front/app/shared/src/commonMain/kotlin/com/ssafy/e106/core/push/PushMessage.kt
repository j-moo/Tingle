package com.ssafy.e106.core.push

import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

/** 이 기기 등록이 묶인 계정과 서버 bindingId(PUT push-installations 응답). 같은 사용자 · 같은 로그인 세션이면 서버가 같은 값을 준다 */
internal class PushBinding(val userId: Long, val id: String)

/** 기기 쪽 푸시 정보. Android 는 FCM(AndroidPushDevice), iOS 는 S15P31E106-197 */
internal interface PushDevice {
    /** 서버 RegisterPushRequest.platform 값: ANDROID · IOS */
    val platform: String

    /** 설치당 UUID 하나. 계정과 무관하게 유지(앱 데이터 삭제 · 재설치면 새로) */
    suspend fun installationId(): String

    /** 지금 푸시 토큰. 못 받으면(오프라인 · Play 서비스 없음) null */
    suspend fun token(): String?

    /** 토큰이 바뀌었을 때(onNewToken) */
    val tokenRefreshed: Flow<Unit>

    /** 마지막으로 성공한 등록. 앱이 꺼져 있을 때 받은 알림을 거르는 데 쓴다(디스크) */
    suspend fun binding(): PushBinding?
    suspend fun saveBinding(binding: PushBinding?)
}

/** 서버가 보내는 data 전용 메시지 종류. 문구는 앱이 정한다(서버 payload 에 제목 · 본문이 없다) */
internal enum class PushKind { CHAT_MESSAGE, ONE_HOUR_BEFORE_END, NEW_PROPOSAL, OTHER }

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
}

/**
 * 서버 data 맵 → [PushMessage]. 채팅 메시지의 chatRoomId 는 방 long id, 종료 안내(ONE_HOUR_BEFORE_END)의 chatRoomId 는 방 publicId(UUID)라
 * 채팅 화면으로 갈 수 없어 홈으로 연다. 모르는 type · 깨진 id 도 홈
 */
internal fun pushMessageOf(data: Map<String, String>): PushMessage {
    val kind = PushKind.entries.firstOrNull { it.name == data["type"] && it != PushKind.OTHER } ?: PushKind.OTHER
    val target = when (kind) {
        PushKind.CHAT_MESSAGE -> data["chatRoomId"]?.toLongOrNull()?.let { PushTarget.Chat(it) } ?: PushTarget.Home
        PushKind.NEW_PROPOSAL -> data["proposalId"]?.toLongOrNull()?.takeIf { it > 0 }
            ?.takeIf { data["noticeId"] == "proposal-$it" }?.let { PushTarget.Proposal(it) } ?: PushTarget.Home
        else -> PushTarget.Home
    }
    val expiresAt = data["expiresAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return PushMessage(kind, target, data["recipientUserId"]?.toLongOrNull(), data["bindingId"],
        data["installationId"]?.takeIf { it.isNotBlank() }, expiresAt,
        data["title"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80), data["body"]?.trim()?.takeIf { it.isNotEmpty() }?.take(300))
}
