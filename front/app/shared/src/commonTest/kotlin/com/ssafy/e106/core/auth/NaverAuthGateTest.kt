package com.ssafy.e106.core.auth

import com.ssafy.e106.core.auth.NaverAuthGate.Action
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val REDIRECT = "https://k15e106.p.ssafy.io/auth/naver/callback"
private const val URL_A = "https://nid.naver.com/oauth2/authorize?A"
private const val URL_B = "https://nid.naver.com/oauth2/authorize?B"
private fun callback(state: String) = "$REDIRECT?code=c&state=$state"

/**
 * NaverAuthActivity 가 넘기는 이벤트를 실제 Android 호출 순서대로 흉내 낸다(화면 = [Screen]).
 * Activity 는 이 동작만 하므로, 순서를 잘못 부르는 실수도 여기서 드러난다
 */
@OptIn(ExperimentalCoroutinesApi::class)   // getCompleted
class NaverAuthGateTest {
    private val gate = NaverAuthGate()

    /** NaverAuthActivity 한 인스턴스 */
    private inner class Screen(attempt: Long?, data: String? = null) {
        private var served = attempt
        var finishing = false
        val opened = mutableListOf<String>()

        init { act(gate.onCreate(data, attempt)) }

        fun newIntent(data: String? = null, attempt: Long? = null) {
            attempt?.let { served = it }
            act(gate.onNewIntent(data))
        }
        fun resume() = act(gate.onResume(finishing))
        fun destroy() = gate.onDestroy(finishing, served)

        private fun act(a: Action) {
            when (a) {
                is Action.Open -> opened += a.url
                Action.Finish -> finishing = true
                Action.None -> Unit
            }
        }
    }

    @Test
    fun normalLoginOpensOnceAndDeliversCallback() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        val s = Screen(a.id)
        s.resume()
        assertEquals(listOf(URL_A), s.opened)
        s.newIntent(callback("sA"))                      // App Link → onNewIntent → onResume
        s.resume()
        assertEquals(NaverOidc.BrowserResult.Callback(callback("sA")), a.result.getCompleted())
        assertEquals(true, s.finishing)
        assertEquals(listOf(URL_A), s.opened, "탭을 다시 열지 않는다")
    }

    @Test
    fun closingTabCancels() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        val s = Screen(a.id)
        s.resume()
        s.resume()                                       // 콜백 없이 돌아왔다
        assertEquals(NaverOidc.BrowserResult.Cancelled, a.result.getCompleted())
        assertEquals(true, s.finishing)
    }

    @Test
    fun systemDestroyWhileTabOpenStillGetsCallback() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        val s = Screen(a.id)
        s.resume()
        s.destroy()                                      // 시스템이 뒤 화면을 없앴다(finishing 아님)
        assertFalse(a.result.isCompleted, "취소하지 않는다")
        Screen(attempt = null, data = callback("sA"))    // 콜백이 새 인스턴스로 온다
        assertEquals(NaverOidc.BrowserResult.Callback(callback("sA")), a.result.getCompleted())
    }

    @Test
    fun newAttemptOnLeftoverScreenOpensNewTab() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        val s = Screen(a.id)
        s.resume()                                       // A 의 탭이 열려 있다
        val b = gate.start(URL_B, REDIRECT, "sB")
        s.newIntent(attempt = b.id)                      // 같은 화면이 B 를 받는다
        s.resume()
        assertEquals(NaverOidc.BrowserResult.Cancelled, a.result.getCompleted())
        assertEquals(listOf(URL_A, URL_B), s.opened, "B 는 바로 취소되지 않고 새 탭을 연다")
        s.finishing = true
        s.destroy()                                      // 바깥에서 화면이 닫혔다
        assertEquals(NaverOidc.BrowserResult.Cancelled, b.result.getCompleted(), "B 가 멈춘 채 남지 않는다")
    }

    @Test
    fun noBrowserFails() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        Screen(a.id)
        assertEquals(Action.Finish, gate.onOpenFailed())
        assertEquals(NaverOidc.BrowserResult.Failed, a.result.getCompleted())
    }

    @Test
    fun foreignIntentDoesNotEndLoginAndReopensTab() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        val s = Screen(a.id)
        s.resume()
        s.newIntent("https://evil.example/x?code=c&state=sA")   // 다른 앱이 보낸 데이터 — singleTask 가 탭을 닫는다
        s.resume()
        s.newIntent(callback("forged"))                  // 우리 주소지만 state 가 다르다
        s.resume()
        s.newIntent()                                    // 데이터 없는 Intent
        s.resume()
        assertFalse(a.result.isCompleted, "진행 중인 로그인이 끝나지 않는다")
        assertEquals(listOf(URL_A, URL_A, URL_A, URL_A), s.opened, "닫힌 탭을 같은 주소로 다시 연다")
        s.newIntent(callback("sA"))
        assertEquals(NaverOidc.BrowserResult.Callback(callback("sA")), a.result.getCompleted())
    }

    @Test
    fun deniedConsentCallbackIsDelivered() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        val s = Screen(a.id)
        s.resume()
        s.newIntent("$REDIRECT?state=sA&error=access_denied")
        assertEquals(NaverOidc.BrowserResult.Callback("$REDIRECT?state=sA&error=access_denied"), a.result.getCompleted(), "취소 판단은 parseCallback 이")
    }

    @Test
    fun screenWithoutWaitingLoginJustCloses() {
        val stray = Screen(attempt = null, data = callback("x"))   // 누가 콜백 주소를 직접 열었다
        assertEquals(true, stray.finishing)
        val forged = Screen(attempt = 99)                          // 없는 시도 번호
        assertEquals(true, forged.finishing)
        assertEquals(emptyList(), forged.opened)
    }

    @Test
    fun abandonedLoginIgnoresLateCallbackAndScreenCloses() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        val s = Screen(a.id)
        s.resume()
        gate.abandon(a)                                  // 로그인 코루틴이 취소됐다
        s.newIntent(callback("sA"))
        s.resume()
        assertFalse(a.result.isCompleted)
        assertEquals(true, s.finishing)
        val b = gate.start(URL_B, REDIRECT, "sB")         // 다음 시도는 정상
        val s2 = Screen(b.id)
        s2.resume()
        assertEquals(listOf(URL_B), s2.opened)
    }

    @Test
    fun oldScreenFinishingLateDoesNotCancelNewAttempt() {
        val a = gate.start(URL_A, REDIRECT, "sA")
        val s = Screen(a.id)
        s.resume()
        s.newIntent(callback("sA"))                      // A 끝, 화면 닫히는 중
        val b = gate.start(URL_B, REDIRECT, "sB")
        Screen(b.id)
        s.destroy()                                      // A 화면이 늦게 없어진다
        assertFalse(b.result.isCompleted)
    }
}
