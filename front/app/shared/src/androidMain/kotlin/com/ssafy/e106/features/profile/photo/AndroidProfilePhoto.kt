package com.ssafy.e106.features.profile.photo

import android.content.ContentResolver
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PhotoProcessingException(val reason: PhotoEditorError) : Exception()
data class PreparedProfilePhoto(val bitmap: Bitmap, val jpeg: ByteArray)

/** 선택 URI만 읽는다. 원본 파일을 수정하거나 복제 파일/EXIF를 저장하지 않는다. */
object AndroidProfilePhoto {
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val PREVIEW_EDGE = 2048

    suspend fun read(resolver: ContentResolver, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw PhotoProcessingException(PhotoEditorError.TooLarge)
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        } ?: throw PhotoProcessingException(PhotoEditorError.ReadFailed)
        if (bytes.isEmpty()) throw PhotoProcessingException(PhotoEditorError.ReadFailed)
        if (Build.VERSION.SDK_INT >= 28) decodeModern(bytes) else decodeLegacy(bytes)
    }

    @android.annotation.TargetApi(28)
    private fun decodeModern(bytes: ByteArray): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        if (info.mimeType.lowercase() !in setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif") || info.isAnimated)
            throw PhotoProcessingException(PhotoEditorError.Unsupported)
        val w = info.size.width; val h = info.size.height
        if (w <= 0 || h <= 0) throw PhotoProcessingException(PhotoEditorError.ReadFailed)
        val scale = min(1.0, PREVIEW_EDGE.toDouble() / max(w, h))
        decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    private fun decodeLegacy(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType?.lowercase() !in setOf("image/jpeg", "image/png", "image/webp"))
            throw PhotoProcessingException(PhotoEditorError.Unsupported)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoProcessingException(PhotoEditorError.ReadFailed)
        var sample = 1
        while (bounds.outWidth / sample > PREVIEW_EDGE || bounds.outHeight / sample > PREVIEW_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw PhotoProcessingException(PhotoEditorError.ReadFailed)
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    suspend fun crop(source: Bitmap, crop: SquareCrop): PreparedProfilePhoto = withContext(Dispatchers.Default) {
        val frame = crop.frame(source.width, source.height)
        val edge = min(frame.side, 1080)
        val result = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, Rect(frame.x, frame.y, frame.x + frame.side, frame.y + frame.side),
                Rect(0, 0, edge, edge), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val bytes = ByteArrayOutputStream().use { output ->
            if (!result.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw PhotoProcessingException(PhotoEditorError.ProcessFailed)
            output.toByteArray()
        }
        if (bytes.size > MAX_BYTES) throw PhotoProcessingException(PhotoEditorError.TooLarge)
        PreparedProfilePhoto(result, bytes)
    }
}
