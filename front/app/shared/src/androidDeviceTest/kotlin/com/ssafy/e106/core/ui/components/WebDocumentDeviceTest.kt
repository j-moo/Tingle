package com.ssafy.e106.core.ui.components

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** 실제 WebView 로 약관 정적 페이지를 열어 로딩 완료 · 실패(404) 판정을 확인한다. 개발 서버 정적 페이지와 네트워크가 필요하다 */
class WebDocumentDeviceTest {
    @Test fun publishedTermsPageLoads() = assertEquals(WebDocumentLoad.Loaded, open("https://k15e106.p.ssafy.io/dev/terms/"))

    @Test fun missingPageIsFailed() = assertEquals(WebDocumentLoad.Failed, open("https://k15e106.p.ssafy.io/dev/terms-missing-page-for-test/"))

    private fun open(url: String): WebDocumentLoad {
        WebDocumentTestActivity.states.clear()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<WebDocumentTestActivity>(Intent(context, WebDocumentTestActivity::class.java)
            .putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                WebDocumentTestActivity.states.lastOrNull()?.takeIf { it != WebDocumentLoad.Loading }?.let { Thread.sleep(1_000); return WebDocumentTestActivity.states.last() }   // 늦게 오는 콜백까지 본 마지막 상태
                Thread.sleep(100)
            }
        }
        return WebDocumentLoad.Loading
    }
}

class WebDocumentTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = requireNotNull(intent.getStringExtra("url"))
        setContent { WebDocument(url, onLoad = { states += it }, onExternal = {}, modifier = Modifier.fillMaxSize()) }
    }

    companion object { val states = CopyOnWriteArrayList<WebDocumentLoad>() }
}
