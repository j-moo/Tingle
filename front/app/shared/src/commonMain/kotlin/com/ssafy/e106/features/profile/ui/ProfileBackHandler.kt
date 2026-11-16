package com.ssafy.e106.features.profile.ui

import androidx.compose.runtime.Composable

@Composable internal expect fun ProfileBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * [enabled] 동안 [content] 안의 뒤로 가기 처리를 끈다. 시트에 가려진 뒤 화면이 시트보다 먼저 뒤로 가기를 가져가지 않게 한다.
 * 켜고 끌 때 [content] 는 같은 자리에 남아 상태를 잃지 않는다.
 */
@Composable internal expect fun NoBackHandling(enabled: Boolean = true, content: @Composable () -> Unit)

/** [enabled] 동안(사진이 상태 바 밑에 깔린 동안)만 상태 바 아이콘을 밝게 한다. 꺼지거나 화면을 떠나면 원래대로 되돌린다 */
@Composable internal expect fun LightStatusBarIcons(enabled: Boolean)
