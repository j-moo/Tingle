package com.ssafy.e106.core.push

import androidx.compose.runtime.Composable

/**
 * 휴대폰 설정의 이 앱 알림 화면을 여는 동작. 한 번 거절된 알림은 앱이 다시 켤 수 없어 설정으로 보낸다.
 * 메뉴의 "알림 설정"이 쓴다(권한 요청은 로그인 직후 권한 시트가 한다).
 */
@Composable
expect fun rememberOpenNotificationSettings(): () -> Unit
