package com.ssafy.e106.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class WebDocumentLoad { Loading, Loaded, Failed }

/**
 * 우리 서버의 정적 문서(약관 · 개인정보 처리방침 등)를 앱 화면 안에 그린다. 스크립트는 끈다(정적 페이지).
 * 같은 호스트(https) 안의 링크만 안에서 열고, 다른 주소는 [onExternal] 로 넘긴다. 소셜 로그인 창에는 쓰지 않는다(제공자 정책상 브라우저).
 */
@Composable
expect fun WebDocument(url: String, onLoad: (WebDocumentLoad) -> Unit, onExternal: (String) -> Unit, modifier: Modifier = Modifier)
