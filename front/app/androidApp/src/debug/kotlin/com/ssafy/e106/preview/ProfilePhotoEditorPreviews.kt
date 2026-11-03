package com.ssafy.e106.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.profile.photo.*

@Composable private fun PhotoEditorExample(state: PhotoEditorState, existing: Boolean = false) {
    val bitmap = remember {
        Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(0xFFE6EDE6.toInt())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = 0xFFF3C681.toInt(); drawCircle(590f, 140f, 90f, paint)
                paint.color = 0xFF8CAD9B.toInt(); drawOval(-100f, 280f, 650f, 900f, paint)
                paint.color = 0xFF476E60.toInt(); drawOval(380f, 380f, 1000f, 900f, paint)
            }
        }
    }
    val square = remember { Bitmap.createBitmap(bitmap, 100, 0, 600, 600).asImageBitmap() }
    AppTheme { ProfilePhotoEditorScreen(state, if (existing) square else null, bitmap.asImageBitmap(), square,
        {}, {}, {}, {}, {}, {}, {}) }
}
@Preview(name = "01 사진 선택 전", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorEmptyPreview() = PhotoEditorExample(PhotoEditorState())
@Preview(name = "02 기존 사진", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorCurrentPreview() = PhotoEditorExample(PhotoEditorState(consent = true), true)
@Preview(name = "03 구도 맞추기", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorCropPreview() = PhotoEditorExample(PhotoEditorState(PhotoEditorStage.Crop, consent = true))
@Preview(name = "04 잘린 사진", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorReviewPreview() = PhotoEditorExample(PhotoEditorState(PhotoEditorStage.Review, consent = true))
@Preview(name = "05 읽는 중", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorReadingPreview() = PhotoEditorExample(PhotoEditorState(PhotoEditorStage.Reading))
@Preview(name = "06 변환 중", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorProcessingPreview() = PhotoEditorExample(PhotoEditorState(PhotoEditorStage.Processing))
@Preview(name = "07 용량 초과", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorTooLargePreview() = PhotoEditorExample(PhotoEditorState(error = PhotoEditorError.TooLarge), true)
@Preview(name = "08 읽기 실패", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorFailedPreview() = PhotoEditorExample(PhotoEditorState(error = PhotoEditorError.ReadFailed), true)
@Preview(name = "09 편집 취소", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorDiscardPreview() = PhotoEditorExample(PhotoEditorState(PhotoEditorStage.Crop, consent = true, confirmExit = true), true)
@Preview(name = "10 삭제 확인", widthDp = 390, heightDp = 844, locale = "ko")
@Composable fun PhotoEditorDeletePreview() = PhotoEditorExample(PhotoEditorState(consent = true, confirmDelete = true), true)
@Preview(name = "11 작은 화면·큰 글자", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ko")
@Composable fun PhotoEditorCompactPreview() = PhotoEditorExample(PhotoEditorState(PhotoEditorStage.Crop, consent = true))
