package com.ssafy.e106.core.push

import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.call
import com.ssafy.e106.core.network.catchingNonCancel
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
private class RegisterPushRequest(val installationId: String, val platform: String, val token: String)

@Serializable
private class PushAcknowledgement(val bindingId: String)

/**
 * 가입을 마친(isReady) 세션이 되면 이 기기를 서버에 등록한다(PUT push-installations). 서버는 등록을 세션(sid)에 묶고 로그아웃 때 끊으므로
 * 로그인마다 다시 보낸다. 같은 (사용자, 토큰)은 한 번만, 실패(403 · 503 · 오프라인)는 다음 기회(세션 변화 · 토큰 갱신 · 앱 시작)에 다시.
 * 준비 전에 바뀐 토큰은 따로 보관하지 않는다 — 준비되는 순간 [PushDevice.token] 으로 최신 토큰을 읽는다.
 * 성공하면 (사용자, bindingId)를 저장해 알림을 거르고(TingleMessagingService), 로그아웃(SignedOut)이면 지운다. 복원 중 · 가입 단계는 지우지 않는다(오프라인 시작에도 알림이 뜨게)
 */
internal class PushRegistrar(
    private val session: SessionStore,
    private val client: HttpClient,
    private val device: PushDevice,
    private val beforeRegister: suspend () -> Unit = {},   // 보낼 때마다 먼저 기다릴 것(이전 계정의 로그아웃 재전송)
) {
    private var registered: Pair<Long, String>? = null   // collectLatest 안에서만 읽고 쓴다(한 번에 하나)

    // 한계: 로그아웃 → 같은 계정 재로그인이 StateFlow 에서 합쳐지면(SignedOut 을 못 보면) 다시 보내지 않는다. 사람 손으로 하는 소셜 로그인이라 사실상 없음
    fun start(scope: CoroutineScope) = scope.launch {
        // (로그아웃인지, 준비된 사용자). 가입 단계 · 복원 중 → 로그아웃도 바뀜으로 보이게 둘을 같이 본다
        val readyUser = session.state.map { (it == Session.SignedOut) to (it as? Session.SignedIn)?.takeIf { s -> s.isReady }?.userId }.distinctUntilChanged()
        combine(readyUser, device.tokenRefreshed.onStart { emit(Unit) }) { state, _ -> state }
            .collectLatest { (signedOut, userId) -> register(signedOut, userId) }
    }

    private suspend fun register(signedOut: Boolean, userId: Long?) {
        if (userId == null) {
            registered = null
            if (signedOut) catchingNonCancel { device.saveBinding(null) }
            return
        }
        val token = catchingNonCancel { device.token() }.getOrNull() ?: return
        if (registered == userId to token) return
        beforeRegister()
        val installationId = catchingNonCancel { device.installationId() }.getOrNull() ?: return   // 디스크 오류로 등록기가 멈추지 않게
        val body = RegisterPushRequest(installationId, device.platform, token)
        val r = client.call<PushAcknowledgement> { put("push-installations") { setBody(body) } }
        // 디스크에 저장된 뒤에만 완료로 본다. 저장이 실패했는데 완료로 두면 다음 기회에도 다시 보내지 않아 알림이 전부 버려진다
        if (r is ApiResult.Ok) catchingNonCancel { device.saveBinding(PushBinding(userId, r.value.bindingId)) }.onSuccess { registered = userId to token }
    }
}
