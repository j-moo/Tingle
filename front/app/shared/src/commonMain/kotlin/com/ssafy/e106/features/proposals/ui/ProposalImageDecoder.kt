package com.ssafy.e106.features.proposals.ui

import androidx.compose.ui.graphics.ImageBitmap

/** 디스크 캐시 없이 현재 화면에서만 사용한다. 디코딩은 UI 스레드를 막지 않아야 한다. */
internal expect suspend fun decodeProposalImage(bytes: ByteArray): ImageBitmap?
