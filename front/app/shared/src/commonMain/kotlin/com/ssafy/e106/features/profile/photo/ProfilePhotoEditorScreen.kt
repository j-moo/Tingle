package com.ssafy.e106.features.profile.photo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable fun ProfilePhotoImage(image: ImageBitmap) {
    Image(image, stringResource(Res.string.photo_editor_current),
        Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
}

@Composable
fun ProfilePhotoEditorScreen(
    state: PhotoEditorState, current: ImageBitmap?, candidate: ImageBitmap?, prepared: ImageBitmap?,
    onChange: (PhotoEditorState) -> Unit, onPick: () -> Unit, onPreview: () -> Unit,
    onApply: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit, onDiscard: () -> Unit,
) {
    val title = stringResource(when (state.stage) {
        PhotoEditorStage.Crop -> Res.string.photo_editor_crop
        PhotoEditorStage.Review -> Res.string.photo_editor_review
        else -> Res.string.photo_editor_title
    })
    Surface(color = AppColors.Screen) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(title, onBack = onBack)
            Column(Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = 560.dp)
                .fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                when (state.stage) {
                    PhotoEditorStage.Manage -> {
                        if (current != null) Image(current, stringResource(Res.string.photo_editor_current),
                            Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
                        else ProfilePlaceholder(stringResource(Res.string.profile_photo_empty))
                        Text(stringResource(Res.string.photo_editor_rule), style = MaterialTheme.typography.bodySmall)
                        AppCheckbox(state.consent, { onChange(state.copy(consent = it)) },
                            stringResource(Res.string.profile_photo_consent))
                        AppButton(stringResource(if (current == null) Res.string.profile_photo_add else Res.string.profile_photo_replace),
                            Modifier.fillMaxWidth(), enabled = state.consent, onClick = onPick)
                        if (current != null) TextButton(onClick = { onChange(state.copy(confirmDelete = true)) }) {
                            Text(stringResource(Res.string.profile_photo_delete))
                        }
                    }
                    PhotoEditorStage.Crop -> if (candidate != null) {
                        Text(stringResource(Res.string.photo_editor_help))
                        CropCanvas(candidate, state.crop) { onChange(state.copy(crop = it)) }
                        TextButton(onClick = { onChange(state.copy(crop = SquareCrop())) }) { Text(stringResource(Res.string.photo_editor_reset)) }
                        TextButton(onClick = onPick) { Text(stringResource(Res.string.photo_editor_pick_again)) }
                    }
                    PhotoEditorStage.Review -> {
                        if (prepared != null) Image(prepared, stringResource(Res.string.photo_editor_candidate),
                            Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
                        AppButton(stringResource(Res.string.photo_editor_recrop), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                            onClick = { onChange(state.copy(stage = PhotoEditorStage.Crop)) })
                    }
                    // 읽는 중 · 자르는 중: 사진이 들어갈 자리를 회색으로 둔다. 화면 리더에는 무엇을 하는 중인지 한 문장으로 읽힌다
                    else -> SkeletonGroup(label = stringResource(if (state.stage == PhotoEditorStage.Reading) Res.string.photo_editor_reading else Res.string.photo_editor_processing)) {
                        SkeletonBone(Modifier.aspectRatio(1f), shape = AppShapes.Card)
                    }
                }
            }
            // 사진을 못 읽었거나 못 잘랐다: 고른 내용은 그대로 두고 버튼 바로 위에 한 줄로 알린다
            state.error?.let {
                    InlineError(stringResource(when (it) {
                        PhotoEditorError.TooLarge -> Res.string.photo_editor_too_large
                        PhotoEditorError.Unsupported -> Res.string.photo_editor_unsupported
                        PhotoEditorError.ReadFailed -> Res.string.photo_editor_read_failed
                        PhotoEditorError.ProcessFailed -> Res.string.photo_editor_process_failed
                    }), Modifier.align(Alignment.CenterHorizontally).widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = AppSpacing.Medium))
            }
            AppButton(stringResource(when (state.stage) {
                PhotoEditorStage.Crop -> Res.string.photo_editor_preview
                PhotoEditorStage.Review -> Res.string.photo_editor_apply
                PhotoEditorStage.Manage -> Res.string.photo_editor_done
                else -> Res.string.photo_editor_cancel
            }), Modifier.align(Alignment.CenterHorizontally).widthIn(max = 560.dp).fillMaxWidth().padding(AppSpacing.Medium),
                enabled = when (state.stage) {
                    PhotoEditorStage.Crop -> candidate != null
                    PhotoEditorStage.Review -> prepared != null && state.consent
                    else -> true
                }, onClick = { when (state.stage) {
                    PhotoEditorStage.Crop -> onPreview()
                    PhotoEditorStage.Review -> onApply()
                    else -> onBack()
                } })
        }
    }
    if (state.confirmExit) AlertDialog(onDismissRequest = { onChange(state.copy(confirmExit = false)) },
        title = { Text(stringResource(Res.string.photo_editor_exit_title)) },
        text = { Text(stringResource(Res.string.photo_editor_exit_body)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(Res.string.photo_editor_exit)) } },
        dismissButton = { TextButton(onClick = { onChange(state.copy(confirmExit = false)) }) { Text(stringResource(Res.string.photo_editor_keep)) } })
    if (state.confirmDelete) AlertDialog(onDismissRequest = { onChange(state.copy(confirmDelete = false)) },
        title = { Text(stringResource(Res.string.photo_editor_delete_title)) },
        text = { Text(stringResource(Res.string.photo_editor_delete_body)) },
        confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(Res.string.profile_photo_delete)) } },
        dismissButton = { TextButton(onClick = { onChange(state.copy(confirmDelete = false)) }) { Text(stringResource(Res.string.photo_editor_cancel)) } })
}

@Composable private fun CropCanvas(image: ImageBitmap, crop: SquareCrop, onChange: (SquareCrop) -> Unit) {
    val latest by rememberUpdatedState(crop)
    val change by rememberUpdatedState(onChange)
    val description = stringResource(Res.string.photo_editor_candidate)
    val grid = AppColors.Background.copy(alpha = .65f)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = description }
        .pointerInput(image.width, image.height) {
            detectTransformGestures { _, pan, zoom, _ ->
                val scaled = latest.copy(zoom = (latest.zoom * zoom).coerceIn(1f, 4f)).constrained(image.width, image.height)
                change(scaled.move(pan.x, pan.y, size.width.toFloat(), image.width, image.height))
            }
        }) {
        val f = crop.frame(image.width, image.height)
        drawImage(image, srcOffset = IntOffset(f.x, f.y), srcSize = IntSize(f.side, f.side),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        for (fraction in listOf(1f / 3f, 2f / 3f)) {
            drawLine(grid, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), 1.dp.toPx())
            drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), 1.dp.toPx())
        }
    }
}
