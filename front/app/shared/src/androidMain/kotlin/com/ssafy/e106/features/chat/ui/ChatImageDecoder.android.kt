package com.ssafy.e106.features.chat.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 사진 디코딩은 UI 스레드 밖에서 하고, 큰 원본의 메모리 사용을 제한한다. 결과의 긴 변은 maxSide 이상 2 × maxSide 미만이다.
 * 서버 사진은 최대 1080px 이라 기본값(1024)에서는 원본 그대로, 목록(192)에서는 270px 로 디코딩된다.
 */
internal actual suspend fun decodeChatImage(bytes: ByteArray, maxSide: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..16000 || bounds.outHeight !in 1..16000) return@withContext null
    var sample = 1
    // 한 번 더 줄여도 maxSide 이상일 때만 줄인다(maxSide 보다 작아져 흐려지지 않게)
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
