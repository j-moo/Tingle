package com.ssafy.e106.core.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun WebDocument(url: String, onLoad: (WebDocumentLoad) -> Unit, onExternal: (String) -> Unit, modifier: Modifier) {
    val load by rememberUpdatedState(onLoad)
    val external by rememberUpdatedState(onExternal)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val host = Uri.parse(url).host
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    // 오류(404 등)가 onPageStarted 보다 먼저 올 수 있어(실기기 확인) 깃발 대신 실패한 주소를 기억한다
                    private var failedUrl: String? = null
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url
                        if (target.scheme == "https" && target.host == host) return false   // 약관끼리 이동(../privacy/ 등)은 안에서
                        external(target.toString())
                        return true
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { if (url != failedUrl) load(WebDocumentLoad.Loading) }
                    override fun onPageFinished(view: WebView, url: String?) { if (url != failedUrl) load(WebDocumentLoad.Loaded) }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) = fail(request)
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) = fail(request)
                    private fun fail(request: WebResourceRequest) {
                        if (!request.isForMainFrame) return
                        failedUrl = request.url.toString()
                        load(WebDocumentLoad.Failed)
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}
