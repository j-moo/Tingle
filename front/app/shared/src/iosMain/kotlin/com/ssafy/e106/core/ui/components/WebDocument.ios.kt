package com.ssafy.e106.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

// ponytail: iOS 는 페이지만 연다(로딩 · 실패 표시, 다른 주소 차단 없음). 필요해지면 WKNavigationDelegate 로 Android 와 맞춘다
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun WebDocument(url: String, onLoad: (WebDocumentLoad) -> Unit, onExternal: (String) -> Unit, modifier: Modifier) {
    LaunchedEffect(url) { onLoad(WebDocumentLoad.Loaded) }
    UIKitView(
        factory = {
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = WKWebViewConfiguration()).apply {
                NSURL.URLWithString(url)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
            }
        },
        modifier = modifier,
    )
}
