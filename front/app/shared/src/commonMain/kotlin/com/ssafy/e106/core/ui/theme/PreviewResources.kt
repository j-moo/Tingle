package com.ssafy.e106.core.ui.theme

import androidx.compose.runtime.Composable

/**
 * Android Studio Preview 에서만 Compose 리소스(문구 · 아이콘 · 폰트)를 디스크에서 바로 읽게 한다.
 * 기본 리더는 Preview 에서 IDE 파일 시스템(VFS)을 거쳐 문구 하나마다 파일을 다시 여는데, IDE가 파일 갱신 · 인덱싱 중이면
 * 그 읽기가 막혀 Preview 가 60초 시간 초과로 끝난다. 실제 앱(Preview 아님)에는 아무 영향이 없다.
 */
@Composable
internal expect fun ProvidePreviewResources(content: @Composable () -> Unit)
