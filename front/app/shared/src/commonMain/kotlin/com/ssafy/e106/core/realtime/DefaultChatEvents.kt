package com.ssafy.e106.core.realtime

import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.SERVER_FALLBACK_MESSAGE
import com.ssafy.e106.core.network.call
import com.ssafy.e106.core.network.catchingNonCancel
import com.ssafy.e106.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 한 번 연결해 끊길 때까지 서버의 문자열 프레임을 [onText] 로 넘긴다. 서버가 닫으면 돌아오고, 연결 실패 · 끊김은 예외 */
internal fun interface ChatSocket {
    suspend fun open(roomId: Long, afterSequence: Long, accessToken: String, onText: suspend (String) -> Unit)
}

/** 끊긴 뒤 원인을 묻는 HTTP 조회(GET chat-rooms/{id}). Ok 값은 그 방을 아직 읽을 수 있는지 */
internal fun interface ChatRoomProbe {
    suspend fun canRead(roomId: Long): ApiResult<Boolean>
}

/** 연속으로 구독에 실패할 때 다음 연결까지 기다리는 시간. 구독되면 처음으로 돌아간다 */
internal val RECONNECT_DELAYS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

/** 이 시간 넘게 백그라운드면 끊는다. 화면 회전처럼 잠깐 모든 Activity 가 멈추는 때는 그대로 둔다 */
internal const val BACKGROUND_GRACE_MILLIS = 2_000L

/** 방은 읽을 수 있다는데 구독이 이만큼 연속으로 안 되면 끝낸다(잘못된 afterSequence 등 — 계속 두드리지 않게) */
internal const val MAX_REJECTED = 5

internal const val CHAT_CLOSED_MESSAGE = "대화가 끝난 방이에요"

/**
 * 서버 계약(B/chat/realtime): `wss://…/chat-rooms/{id}/events?afterSequence=N`, 연결 요청에 Bearer. 서버 → 앱 한 방향
 * (CHAT_SUBSCRIBED · CHAT_MESSAGE · CHAT_ERROR 뒤 종료). 서버는 연결 중에도 매번 토큰 · 방 권한을 다시 확인해, 액세스 토큰이 만료되면 끊는다.
 * 끊기거나 거절되면 이유를 소켓에서 읽지 않고 [probe] 로 HTTP 에 다시 묻는다 — 재발급 · 세션 만료 · 가입 단계 처리를 기존 HTTP 경로가 그대로 한다
 * (연결 단계 거절은 Ktor 가 상태 코드 · 본문을 주지 않는다). 방을 읽을 수 있으면 새 토큰으로 다시 연결한다.
 */
internal class DefaultChatEvents(
    private val session: SessionStore,
    private val accessToken: suspend () -> String?,
    private val foreground: StateFlow<Boolean>,
    private val socket: ChatSocket,
    private val probe: ChatRoomProbe,
    private val delays: List<Long> = RECONNECT_DELAYS,
) : ChatEvents {
    override fun <M> connect(roomId: Long, afterSequence: Long, message: KSerializer<M>, sequenceOf: (M) -> Long): Flow<ChatEvent<M>> = channelFlow {
        val start = session.state.value
        if (start !is Session.SignedIn || !start.isReady) {
            send(ChatEvent.Ended(start.endReason(userId = (start as? Session.SignedIn)?.userId)))
            return@channelFlow
        }
        val loop = launch { receive(roomId, afterSequence, message, sequenceOf) }
        // 로그아웃 · 만료 · 다른 계정, 같은 계정이라도 가입 단계로 돌아감(약관 재동의 등 — 서버가 채팅을 막는다)
        val leftReady = async { session.state.first { it !is Session.SignedIn || it.userId != start.userId || !it.isReady } }
        select {
            loop.onJoin { }
            leftReady.onAwait { s ->
                loop.cancelAndJoin()
                send(ChatEvent.Ended(s.endReason(start.userId)))
            }
        }
        leftReady.cancel()
    }

    private suspend fun <M> ProducerScope<ChatEvent<M>>.receive(roomId: Long, start: Long, serializer: KSerializer<M>, sequenceOf: (M) -> Long) {
        var after = start
        var failures = 0
        var rejected = 0
        var malformed = 0
        while (true) {
            foreground.first { it }
            // 토큰 저장소 오류(Keystore 등)도 이벤트로 끝낸다. 취소는 그대로 올린다
            val token = catchingNonCancel { accessToken() }.getOrElse { return send(ChatEvent.Ended(AppError.Unknown(it))) }
                ?: return send(ChatEvent.Ended(AppError.SessionExpired))
            var subscribed = false
            val outcome = connectWhileForeground(roomId, after, token) { text ->
                val frame = ApiJson.decodeFromString(ServerFrame.serializer(), text)   // 깨진 프레임은 예외 → 이 연결을 끝내고 다시
                when (frame.type) {
                    "CHAT_SUBSCRIBED" -> {
                        subscribed = true
                        send(ChatEvent.Connected(frame.afterSequence ?: after))
                    }
                    "CHAT_MESSAGE" -> {
                        val m = ApiJson.decodeFromJsonElement(serializer, checkNotNull(frame.message) { "message 없음" })
                        val sequence = sequenceOf(m)
                        send(ChatEvent.Message(frame.eventId, m))
                        after = maxOf(after, sequence)       // 화면에 넘긴 뒤에만. 넘기다 취소되면(백그라운드 등) 다음 연결이 이 메시지부터 다시 받는다
                        malformed = 0
                    }
                    // CHAT_ERROR 뒤에는 서버가 닫는다. 원인은 아래에서 HTTP 로 다시 묻는다. 모르는 type 은 넘긴다
                }
            }
            if (outcome == Outcome.Backgrounded) continue
            // 해석할 수 없는 프레임(형식 오류)으로 끊김: 같은 위치에서 다시 받으면 같은 프레임이 온다. 구독돼도 초기화하지 않고 세다가 끝낸다
            val broken = outcome is Outcome.Failed && outcome.cause !is IOException
            if (broken && ++malformed >= MAX_REJECTED) return send(ChatEvent.Ended(AppError.Server(0, "CHAT_PROTOCOL_ERROR", SERVER_FALLBACK_MESSAGE)))
            if (subscribed && !broken) {                  // 정상 구독 뒤 끊김(토큰 만료 · 배포 등)만 대기 시간 · 거절 횟수를 처음으로
                failures = 0
                rejected = 0
            }
            val shown: AppError? = when (val r = probe.canRead(roomId)) {
                is ApiResult.Ok -> when {
                    !r.value -> return send(ChatEvent.Ended(AppError.Server(0, "CHAT_CLOSED", CHAT_CLOSED_MESSAGE)))
                    !subscribed && ++rejected >= MAX_REJECTED -> return send(ChatEvent.Ended(AppError.Server(0, "CHAT_CONNECT_FAILED", SERVER_FALLBACK_MESSAGE)))
                    // 구독 뒤의 연결 오류만 오프라인으로 보인다. 구독 전 실패는 대개 서버의 연결 거절(OkHttp 는 401 밖의 거절을 IOException 으로 준다)
                    subscribed && outcome is Outcome.Failed && outcome.cause is IOException -> AppError.Offline
                    // 서버가 닫았고 방은 그대로(액세스 토큰 만료 → 위 조회가 재발급함, 배포, 느린 수신 등). 화면에 알릴 일 없이 다시 연결한다
                    else -> null
                }
                is ApiResult.Fail -> if (r.error.retryable()) r.error else return send(ChatEvent.Ended(r.error))
            }
            if (shown != null) send(ChatEvent.Reconnecting(shown))
            delay(delays[minOf(failures++, delays.lastIndex)])
        }
    }

    private sealed interface Outcome {
        data object Closed : Outcome
        data object Backgrounded : Outcome
        class Failed(val cause: Exception) : Outcome
    }

    /** 연결이 끝나거나, 백그라운드가 [BACKGROUND_GRACE_MILLIS] 넘게 이어지면(그때는 끊는다) 돌아온다 */
    private suspend fun connectWhileForeground(roomId: Long, after: Long, token: String, onText: suspend (String) -> Unit): Outcome = coroutineScope {
        val connection = async {
            try {
                socket.open(roomId, after, token, onText)
                Outcome.Closed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e)
            }
        }
        val background = async {
            while (true) {
                foreground.first { !it }
                if (withTimeoutOrNull(BACKGROUND_GRACE_MILLIS) { foreground.first { it } } == null) break
            }
        }
        select {
            connection.onAwait { background.cancel(); it }
            background.onAwait { connection.cancel(); Outcome.Backgrounded }
        }
    }
}

/** 채팅을 이어갈 수 없게 된 세션 상태 → 끝 이유. 같은 계정이 가입 단계로 돌아갔을 때만 OnboardingRequired, 나머지(로그아웃 · 다른 계정)는 SessionExpired */
private fun Session.endReason(userId: Long?): AppError =
    if (this is Session.SignedIn && this.userId == userId) AppError.OnboardingRequired("") else AppError.SessionExpired

/** 다시 연결해 볼 만한 실패: 연결 실패 · 서버 5xx · 429 · 응답 해석 실패 */
private fun AppError.retryable() = when (this) {
    AppError.Offline, is AppError.Unknown -> true
    is AppError.Server -> status >= 500 || status == 429
    AppError.SessionExpired, is AppError.OnboardingRequired -> false
}

@Serializable
private class ServerFrame(val type: String = "", val eventId: String = "", val afterSequence: Long? = null, val message: JsonElement? = null)

/** 서버 주소(https://…/api/)를 같은 경로의 wss 로. Ktor HttpClient 의 Bearer · 401 처리는 우리 https API 에만 걸려 이 연결에는 끼지 않는다 */
internal fun chatEventsUrl(baseUrl: String, roomId: Long, afterSequence: Long): String = URLBuilder(baseUrl).apply {
    protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
    appendPathSegments("chat-rooms", roomId.toString(), "events")
    parameters.append("afterSequence", afterSequence.toString())
}.buildString()

internal class KtorChatSocket(private val client: HttpClient, private val baseUrl: String) : ChatSocket {
    override suspend fun open(roomId: Long, afterSequence: Long, accessToken: String, onText: suspend (String) -> Unit) {
        val ws = client.webSocketSession(chatEventsUrl(baseUrl, roomId, afterSequence)) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")   // HttpTimeout 의 15초 요청 제한은 WebSocket 에 걸리지 않는다(Ktor 가 건너뜀)
        }
        try {
            for (frame in ws.incoming) if (frame is Frame.Text) onText(frame.readText())
        } finally {
            ws.cancel()
        }
    }
}

@Serializable
private class RoomAccess(val canRead: Boolean = false)

internal class HttpChatRoomProbe(private val client: HttpClient) : ChatRoomProbe {
    override suspend fun canRead(roomId: Long): ApiResult<Boolean> = client.call<RoomAccess> { get("chat-rooms/$roomId") }.map { it.canRead }
}
