package com.ssafy.e106.features.chat.ui

import androidx.compose.ui.graphics.ImageBitmap

/** 현재 화면용으로만 디코딩한다. 상대 사진을 파일 캐시로 저장하지 않는다. */
internal expect suspend fun decodeChatImage(bytes: ByteArray, maxSide: Int = 1024): ImageBitmap?

/** 채팅 목록 동그란 사진(48dp)용 디코딩 크기. 목록에 방이 많이 쌓여도 메모리를 적게 쓴다 */
internal const val LIST_PHOTO_SIDE = 192
