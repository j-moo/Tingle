package com.ssafy.e106.features.proposals.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

internal actual suspend fun decodeProposalImage(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    try { Image.makeFromEncoded(bytes).toComposeImageBitmap() } catch (_: Exception) { null }
}
