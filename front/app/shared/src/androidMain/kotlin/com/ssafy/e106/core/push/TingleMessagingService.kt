package com.ssafy.e106.core.push

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ssafy.e106.core.storage.TokenStore
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.push_channel_name
import com.ssafy.e106.resources.push_chat_message
import com.ssafy.e106.resources.push_chat_ending
import com.ssafy.e106.resources.push_matched
import com.ssafy.e106.resources.push_other
import com.ssafy.e106.resources.push_new_proposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import org.koin.android.ext.android.inject

/**
 * 서버는 data 전용 메시지를 보낸다(제목 · 본문 없음) → 여기서 알림을 만든다. 앱이 꺼져 있어도 FCM 이 이 서비스를 깨운다.
 * 세션 복원(네트워크)은 하지 않고 저장 토큰의 userId · 마지막 등록(bindingId)만 읽는다: 로그아웃 상태 · 다른 계정 · 지난 등록으로 온 알림은 띄우지 않는다([PushMessage.showTo])
 */
class TingleMessagingService : FirebaseMessagingService() {
    private val device: PushDevice by inject()
    private val tokens: TokenStore by inject()
    private val events: DefaultPushEvents by inject()
    private val inbox: NotificationInbox by inject()
    private val sessions: com.ssafy.e106.core.auth.SessionStore by inject()

    override fun onNewToken(token: String) { (device as? AndroidPushDevice)?.onNewToken() }

    // FCM 작업 스레드에서 불린다(메인 아님). 몇 초 안에 끝내야 해 디스크 읽기만 한다
    override fun onMessageReceived(message: RemoteMessage) {
        val msg = pushMessageOf(message.data)
        val (userId, text) = runBlocking {
            val userId = tokens.load()?.userId ?: return@runBlocking null
            val binding = device.binding() ?: return@runBlocking null
            val installation = if (msg.kind == PushKind.NEW_PROPOSAL) device.installationId() else null
            if (!msg.showTo(userId, binding, installation)) return@runBlocking null
            // 지금 보고 있는 대화방의 새 메시지는 화면에 바로 뜨므로 알림 · 알림 모음에 남기지 않는다
            if (msg.seenIn(events.viewingRoom)) return@runBlocking null
            // 알림 권한이 꺼져 있어도 열린 제안 목록은 새로 조회할 수 있다.
            if (msg.target is PushTarget.Proposal && !withContext(Dispatchers.Main.immediate) {
                    events.proposalArrived(userId, checkNotNull(msg.bindingId), msg.target.proposalId)
                }) return@runBlocking null
            val res = when (msg.kind) {
                PushKind.CHAT_MESSAGE -> Res.string.push_chat_message
                PushKind.CHAT_ROOM_CREATED -> Res.string.push_matched
                PushKind.ONE_HOUR_BEFORE_END -> Res.string.push_chat_ending
                PushKind.NEW_PROPOSAL -> Res.string.push_new_proposal
                PushKind.OTHER -> Res.string.push_other
            }
            val text = getString(res)
            // 홈 종 모양 알림 모음에 남긴다(시스템 알림이 꺼져 있어도). FCM이 앱을 막 깨운 경우 로그인 복원을 잠깐 기다린다
            // ponytail: 5초 안에 복원되지 않으면 모음에는 빠진다(시스템 알림은 그대로 뜬다)
            val restored = withTimeoutOrNull(5_000) { sessions.state.first { it !is com.ssafy.e106.core.auth.Session.Restoring } }
            if ((restored as? com.ssafy.e106.core.auth.Session.SignedIn)?.userId == userId) runCatching {
                inbox.add(userId, InboxItem(id = message.messageId ?: "${msg.kind}-${System.currentTimeMillis()}",
                    title = msg.title, body = msg.body ?: text, atMillis = System.currentTimeMillis(),
                    roomId = (msg.target as? PushTarget.Chat)?.roomId, proposalId = (msg.target as? PushTarget.Proposal)?.proposalId))
            }
            userId to text
        } ?: return
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        // NotificationChannel 은 API 26+ 라 Compat 으로(minSdk 24)
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(runBlocking { getString(Res.string.push_channel_name) }).build())
        val key = if (msg.target is PushTarget.Proposal) "proposal-${msg.target.proposalId}"
            else message.data["chatRoomId"] ?: message.data["noticeId"] ?: ""
        val id = key.hashCode()   // 같은 방 알림은 덮어쓴다
        // data 로 알림마다 다른 Intent 를 만든다. requestCode(해시)만으로 구분하면 해시가 겹친 다른 알림의 extras 를 덮어쓴다
        val tap = PendingIntent.getActivity(this, id, PushTapActivity.intent(this, msg.target, userId).setData(Uri.fromParts("tingle-push", "${msg.kind}/$key", null)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val appName = applicationInfo.loadLabel(packageManager)
        // 잠금화면에는 앱 이름 + 기본 문구만(보낸 사람 · 내용 숨김)
        val public = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(appName).setContentText(text).build()
        // 서버가 제목 · 본문을 주면 그대로(채팅: 보낸 사람 닉네임 · 마지막 메시지). 같은 방 알림은 덮어써 마지막 메시지만 남는다
        val body = msg.body ?: text
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)   // 앱 알림 아이콘이 생기면 바꾼다(디자인)
            .setContentTitle(msg.title ?: appName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setOnlyAlertOnce(msg.kind == PushKind.NEW_PROPOSAL)
            .setContentIntent(tap)
            .build()
        try {
            manager.notify(msg.kind.name, id, notification)
        } catch (_: SecurityException) {
            // 확인과 표시 사이에 권한이 꺼졌다
        }
    }

    private companion object {
        const val CHANNEL = "chat"
    }
}

/** 알림 탭만 받는 화면(exported=false — 다른 앱이 띄우거나 extras 를 위조할 수 없다). 대상을 [DefaultPushEvents] 에 담고 앱을 앞으로 가져온 뒤 닫힌다 */
class PushTapActivity : Activity() {
    private val events: DefaultPushEvents by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) targetOf(intent)?.let { (target, forUser) -> events.open(target, forUser) }
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
        finish()
    }

    companion object {
        private const val ROOM = "roomId"
        private const val PROPOSAL = "proposalId"
        private const val USER = "forUser"

        internal fun intent(context: Context, target: PushTarget, forUser: Long) = Intent(context, PushTapActivity::class.java)
            .putExtra(USER, forUser)
            .apply {
                when (target) {
                    is PushTarget.Chat -> putExtra(ROOM, target.roomId)
                    is PushTarget.Proposal -> putExtra(PROPOSAL, target.proposalId)
                    PushTarget.Home -> Unit
                }
            }

        internal fun targetOf(intent: Intent): Pair<PushTarget, Long>? {
            if (!intent.hasExtra(USER)) return null
            val target = when {
                intent.hasExtra(ROOM) -> PushTarget.Chat(intent.getLongExtra(ROOM, 0))
                intent.getLongExtra(PROPOSAL, 0) > 0 -> PushTarget.Proposal(intent.getLongExtra(PROPOSAL, 0))
                else -> PushTarget.Home
            }
            return target to intent.getLongExtra(USER, 0)
        }
    }
}
