package com.ssafy.e106.core.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent

/**
 * 네이버 로그인(OIDC) 창. Custom Tab 으로 인가 주소를 열고, 네이버가 콜백 주소(App Link)로 돌려보내면 그 주소를 넘긴다.
 * 판단은 전부 [NaverAuthGate] 가 하고 여기는 생명주기 이벤트를 넘겨 돌려받은 동작만 한다. 화면은 투명해서 사용자에게는 브라우저만 보인다.
 * singleTask 라 콜백 App Link 는 새 화면이 아니라 이 화면의 onNewIntent 로 온다.
 */
internal class NaverAuthActivity : Activity() {
    private var served: Long? = null                     // 이 화면이 맡은 시도 번호

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        served = intent.attempt()
        act(gate.onCreate(intent.data?.toString(), served))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.attempt()?.let { served = it }
        act(gate.onNewIntent(intent.data?.toString()))
    }

    override fun onResume() {
        super.onResume()
        act(gate.onResume(isFinishing))
    }

    override fun onDestroy() {
        gate.onDestroy(isFinishing, served)
        super.onDestroy()
    }

    private fun act(action: NaverAuthGate.Action) {
        when (action) {
            is NaverAuthGate.Action.Open -> runCatching { CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(action.url)) }
                .onFailure { act(gate.onOpenFailed()) }  // 브라우저가 없다
            NaverAuthGate.Action.Finish -> finish()
            NaverAuthGate.Action.None -> Unit
        }
    }

    private fun Intent.attempt(): Long? = if (hasExtra(ATTEMPT)) getLongExtra(ATTEMPT, 0) else null

    companion object {
        private const val ATTEMPT = "attempt"
        private val gate = NaverAuthGate()

        /** 인가 주소를 열고 결과를 기다린다. 앞의 시도가 남아 있으면 취소로 끝낸다. 메인 스레드에서 부른다 */
        suspend fun authorize(from: Context, authorizeUrl: String, redirectUri: String, state: String): NaverOidc.BrowserResult {
            val attempt = gate.start(authorizeUrl, redirectUri, state)
            from.startActivity(Intent(from, NaverAuthActivity::class.java).putExtra(ATTEMPT, attempt.id))
            return try {
                attempt.result.await()
            } finally {
                gate.abandon(attempt)
            }
        }
    }
}
