package com.ssafy.e106.core.auth

import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred

/**
 * 네이버 로그인 창(NaverAuthActivity)의 판단 전부 — Android 없이 테스트한다. 화면은 생명주기 이벤트를 넘기고 돌려받은 [Action] 만 한다.
 * - 진행 중인 로그인은 하나. 새로 시작하면 앞의 것은 취소로 끝낸다. 시도마다 번호를 붙여 우리가 띄운 화면인지 가린다
 * - 인가 주소는 Intent 가 아니라 여기(메모리)에 둔다 — 화면이 exported 라 다른 앱이 주소를 바꿔 넣지 못하게
 * - 결과는 한 번만. 탭을 연 뒤 콜백 없이 화면이 다시 보이면 취소(사용자가 탭을 닫았다)
 * - 우리 콜백 주소 + 이번 시도의 state 가 아닌 데이터 · 다른 앱이 보낸 Intent 로는 끝나지 않는다. 그 Intent 가 탭을 닫았으면(singleTask) 같은 주소로 다시 연다
 */
internal class NaverAuthGate {
    sealed interface Action {
        data class Open(val url: String) : Action
        data object Finish : Action
        data object None : Action
    }

    class Attempt(val id: Long, val result: CompletableDeferred<NaverOidc.BrowserResult>)

    private var pending: Attempt? = null
    private var lastId = 0L
    private var authorizeUrl: String? = null
    private var redirectUri: String? = null
    private var state: String? = null
    private var opened = false                           // 이번 시도의 탭을 열었나(열린 탭이 닫혔으면 다시 false)

    fun start(authorizeUrl: String, redirectUri: String, state: String): Attempt {
        finish(NaverOidc.BrowserResult.Cancelled)
        this.authorizeUrl = authorizeUrl
        this.redirectUri = redirectUri
        this.state = state
        opened = false
        return Attempt(++lastId, CompletableDeferred()).also { pending = it }
    }

    /** 화면이 새로 만들어졌다. [data] 는 콜백(App Link) 주소, [attempt] 는 우리가 띄울 때 붙인 시도 번호 */
    fun onCreate(data: String?, attempt: Long?): Action = when {
        data != null -> { deliverIfOurs(data); Action.Finish }   // 콜백으로 새로 열린 화면은 시도를 맡지 않는다
        pending != null && attempt == pending?.id -> Action.None // 우리가 띄웠다 — 보이면 탭을 연다
        else -> Action.Finish                                    // 기다리는 로그인이 없다(프로세스가 죽었다 살아남 · 다른 앱)
    }

    /** 살아 있는 화면으로 Intent 가 왔다(singleTask) */
    fun onNewIntent(data: String?): Action {
        if (data != null && deliverIfOurs(data)) return Action.Finish
        // 새 시도이거나(이미 opened = false) 다른 앱이 보낸 Intent 가 탭을 닫았다 — 다음에 보일 때 같은 주소로 (다시) 연다
        opened = false
        return Action.None
    }

    /** 화면이 보인다 */
    fun onResume(finishing: Boolean): Action {
        if (finishing) return Action.None
        val url = authorizeUrl
        if (pending == null || url == null) return Action.Finish
        if (!opened) {
            opened = true
            return Action.Open(url)
        }
        finish(NaverOidc.BrowserResult.Cancelled)        // 콜백 없이 돌아왔다 = 사용자가 탭을 닫았다
        return Action.Finish
    }

    /** 탭을 열지 못했다(브라우저 없음 등) */
    fun onOpenFailed(): Action {
        finish(NaverOidc.BrowserResult.Failed)
        return Action.Finish
    }

    /** 화면이 없어진다. 결과 없이 끝나는 거면(task 정리 등) 그 화면이 맡았던 시도만 취소 — 시스템이 잠시 없앤 거면(finishing 아님) 콜백을 기다린다 */
    fun onDestroy(finishing: Boolean, served: Long?) {
        if (finishing && served != null && served == pending?.id) finish(NaverOidc.BrowserResult.Cancelled)
    }

    /** 기다리던 쪽이 그만뒀다(코루틴 취소). 그 시도만 정리한다 */
    fun abandon(attempt: Attempt) {
        if (pending === attempt) clear()
    }

    private fun deliverIfOurs(uri: String): Boolean {
        val expected = redirectUri ?: return false
        if (pending == null || !NaverOidc.isCallback(uri, expected) || Url(uri).parameters["state"] != state) return false
        finish(NaverOidc.BrowserResult.Callback(uri))
        return true
    }

    private fun finish(result: NaverOidc.BrowserResult) {
        pending?.result?.complete(result)
        clear()
    }

    private fun clear() {
        pending = null
        authorizeUrl = null
        redirectUri = null
        state = null
        opened = false
    }
}
