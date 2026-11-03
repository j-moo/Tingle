package com.ssafy.e106.features.profile.photo

import kotlin.math.min
import kotlin.math.roundToInt

data class SquareCrop(val zoom: Float = 1f, val centerX: Float = .5f, val centerY: Float = .5f) {
    fun frame(width: Int, height: Int): CropFrame {
        require(width > 0 && height > 0)
        val scale = if (zoom.isFinite()) zoom.coerceIn(1f, 4f) else 1f
        val side = (min(width, height) / scale).roundToInt().coerceIn(1, min(width, height))
        fun origin(center: Float, length: Int) =
            (((if (center.isFinite()) center else .5f) * length) - side / 2f).roundToInt().coerceIn(0, length - side)
        return CropFrame(origin(centerX, width), origin(centerY, height), side)
    }
    fun constrained(width: Int, height: Int): SquareCrop {
        val f = frame(width, height)
        return SquareCrop(if (zoom.isFinite()) zoom.coerceIn(1f, 4f) else 1f,
            (f.x + f.side / 2f) / width, (f.y + f.side / 2f) / height)
    }
    fun move(dx: Float, dy: Float, viewport: Float, width: Int, height: Int): SquareCrop {
        if (viewport <= 0f || !viewport.isFinite()) return this
        val current = constrained(width, height)
        val side = current.frame(width, height).side
        return current.copy(centerX = current.centerX - dx * side / viewport / width,
            centerY = current.centerY - dy * side / viewport / height).constrained(width, height)
    }
}
data class CropFrame(val x: Int, val y: Int, val side: Int)
enum class PhotoEditorStage { Manage, Reading, Crop, Processing, Review }
enum class PhotoEditorError { TooLarge, Unsupported, ReadFailed, ProcessFailed }
data class PhotoEditorState(
    val stage: PhotoEditorStage = PhotoEditorStage.Manage,
    val crop: SquareCrop = SquareCrop(),
    val consent: Boolean = false,
    val error: PhotoEditorError? = null,
    val confirmExit: Boolean = false,
    val confirmDelete: Boolean = false,
) {
    val busy get() = stage == PhotoEditorStage.Reading || stage == PhotoEditorStage.Processing
}
