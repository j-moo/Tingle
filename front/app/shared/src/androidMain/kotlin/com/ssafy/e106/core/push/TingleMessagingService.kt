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
import com.ssafy.e106.resources.push_other
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.android.ext.android.inject

/**
 * 서버는 data 전용 메시지를 보낸다(제목 · 본문 없음) → 여기서 알림을 만든다. 앱이 꺼져 있어도 FCM 이 이 서비스를 깨운다.
 * 세션 복원(네트워크)은 하지 않고 저장 토큰의 userId · 마지막 등록(bindingId)만 읽는다: 로그아웃 상태 · 다른 계정 · 지난 등록으로 온 알림은 띄우지 않는다([PushMessage.showTo])
 */
class TingleMessagingService : FirebaseMessagingService() {
    private val device: PushDevice by inject()
    private val tokens: TokenStore by inject()

    override fun onNewToken(token: String) { (device as? AndroidPushDevice)?.onNewToken() }

    // FCM 작업 스레드에서 불린다(메인 아님). 몇 초 안에 끝내야 해 디스크 읽기만 한다
    override fun onMessageReceived(message: RemoteMessage) {
        val msg = pushMessageOf(message.data)
        val (userId, text) = runBlocking {
            val userId = tokens.load()?.userId
            if (userId == null || !msg.showTo(userId, device.binding())) return@runBlocking null
            val res = when (msg.kind) {
                PushKind.CHAT_MESSAGE -> Res.string.push_chat_message
                PushKind.ONE_HOUR_BEFORE_END -> Res.string.push_chat_ending
                PushKind.OTHER -> Res.string.push_other
            }
            userId to getString(res)
        } ?: return
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        // NotificationChannel 은 API 26+ 라 Compat 으로(minSdk 24)
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(runBlocking { getString(Res.string.push_channel_name) }).build())
        val key = message.data["chatRoomId"] ?: message.data["noticeId"] ?: ""
        val id = key.hashCode()   // 같은 방 알림은 덮어쓴다
        // data 로 알림마다 다른 Intent 를 만든다. requestCode(해시)만으로 구분하면 해시가 겹친 다른 알림의 extras 를 덮어쓴다
        val tap = PendingIntent.getActivity(this, id, PushTapActivity.intent(this, msg.target, userId).setData(Uri.fromParts("tingle-push", "${msg.kind}/$key", null)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)   // 앱 알림 아이콘이 생기면 바꾼다(디자인)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)   // 잠금화면에는 "내용 숨김"
            .setAutoCancel(true)
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
        private const val USER = "forUser"

        internal fun intent(context: Context, target: PushTarget, forUser: Long) = Intent(context, PushTapActivity::class.java)
            .putExtra(USER, forUser)
            .apply { if (target is PushTarget.Chat) putExtra(ROOM, target.roomId) }

        private fun targetOf(intent: Intent): Pair<PushTarget, Long>? {
            if (!intent.hasExtra(USER)) return null
            val target = if (intent.hasExtra(ROOM)) PushTarget.Chat(intent.getLongExtra(ROOM, 0)) else PushTarget.Home
            return target to intent.getLongExtra(USER, 0)
        }
    }
}
