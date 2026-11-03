package com.ssafy.e106.features.chat.ui

import androidx.compose.ui.graphics.ImageBitmap

/** 현재 화면용으로만 디코딩한다. 상대 사진을 파일 캐시로 저장하지 않는다. */
internal expect suspend fun decodeChatImage(bytes: ByteArray): ImageBitmap?
