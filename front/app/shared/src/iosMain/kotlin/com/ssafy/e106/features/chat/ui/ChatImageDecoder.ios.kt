package com.ssafy.e106.features.chat.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

// 공통 코드의 플랫폼 구현이다. iOS 빌드/실기기 검증은 별도로 필요하다.
// ponytail: maxSide 축소는 Android만 한다. iOS도 목록 메모리가 문제가 되면 Skia 로 줄여 그린다
internal actual suspend fun decodeChatImage(bytes: ByteArray, maxSide: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) return@withContext null
    try {
        // 전체 픽셀을 만들기 전에 헤더의 크기를 검사한다. 압축 파일 크기만으로 메모리 사용량을 판단하지 않는다.
        val data = Data.makeFromBytes(bytes)
        val allowed = try {
            val codec = Codec.makeFromData(data)
            try { chatImageAllocationAllowed(codec.imageInfo.width, codec.imageInfo.height) }
            finally { codec.close() }
        } finally { data.close() }
        if (allowed) Image.makeFromEncoded(bytes).toComposeImageBitmap() else null
    } catch (_: Exception) { null }
}
