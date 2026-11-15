package com.ssafy.e106.features.profile.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.ssafy.e106.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

// Bitmap은 Bundle에 넣지 않는다. 작은 편집 값만 복원하고 URI에서 이미지를 다시 읽는다.
internal val PhotoEditorSaver = listSaver<PhotoEditorState, Any>(
    save = { listOf(it.crop.zoom, it.crop.centerX, it.crop.centerY,
        it.stage == PhotoEditorStage.Review || it.stage == PhotoEditorStage.Processing, it.confirmExit) },
    restore = { PhotoEditorState(crop = SquareCrop(it[0] as Float, it[1] as Float, it[2] as Float),
        stage = if (it[3] as Boolean) PhotoEditorStage.Review else PhotoEditorStage.Crop,
        consent = true, confirmExit = it[4] as Boolean) },
)

@Composable actual fun ProfilePhotoPreparation(onReady: (ByteArray) -> Unit, onClose: () -> Unit, canApply: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by rememberSaveable(stateSaver = PhotoEditorSaver) {
        mutableStateOf(PhotoEditorState(stage = PhotoEditorStage.Crop, consent = true))
    }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerStarted by rememberSaveable { mutableStateOf(false) }
    var selectionVersion by rememberSaveable { mutableIntStateOf(0) }
    var candidate by remember { mutableStateOf<Bitmap?>(null) }
    var prepared by remember { mutableStateOf<PreparedProfilePhoto?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val currentClose by rememberUpdatedState(onClose)
    // 편집 화면 자체도 rememberSaveable로 복원되므로 회전 후에도 같은 결과 실행기가 등록된다.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) { if (selectedUri == null) currentClose() }
        else {
            generation++; work?.cancel()
            candidate = null; prepared = null
            state = PhotoEditorState(stage = PhotoEditorStage.Reading, consent = true)
            selectedUri = uri.toString()
            // 같은 사진을 다시 선택해도 읽기 실패 후 재시도할 수 있다.
            selectionVersion++
        }
    }
    val pick: () -> Unit = {
        try {
            pickerStarted = true
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Exception) { state = state.copy(error = PhotoEditorError.ReadFailed) }
    }
    // 이미 열린 시스템 선택기는 회전 후 중복 실행하지 않고 기존 결과를 기다린다.
    LaunchedEffect(Unit) { if (!pickerStarted) pick() }
    LaunchedEffect(selectedUri, selectionVersion) {
        val uri = selectedUri ?: return@LaunchedEffect
        val restoreReview = state.stage == PhotoEditorStage.Review
        val stamp = ++generation
        state = state.copy(stage = PhotoEditorStage.Reading, error = null)
        try {
            val image = AndroidProfilePhoto.read(context.contentResolver, Uri.parse(uri))
            if (stamp != generation) return@LaunchedEffect
            candidate = image
            // 확인 화면에서 회전한 경우에도 저장된 자르기 값으로 결과를 다시 만든다.
            val result = if (restoreReview) AndroidProfilePhoto.crop(image, state.crop) else null
            if (stamp == generation) {
                prepared = result
                state = state.copy(stage = if (result == null) PhotoEditorStage.Crop else PhotoEditorStage.Review)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (stamp == generation) state = state.copy(stage = PhotoEditorStage.Crop,
                error = (e as? PhotoProcessingException)?.reason ?: PhotoEditorError.ReadFailed)
        } catch (_: OutOfMemoryError) {
            if (stamp == generation) state = state.copy(stage = PhotoEditorStage.Crop, error = PhotoEditorError.ReadFailed)
        }
    }
    val back: () -> Unit = {
        if (selectedUri == null && !state.busy) currentClose()
        else state = state.copy(confirmExit = true)
    }
    // 이 작업은 복원 가능한 로컬 이미지 처리다. 서버 저장 요청과 달리 화면 교체 시 취소해도 된다.
    DisposableEffect(Unit) { onDispose { generation++; work?.cancel() } }
    BackHandler(onBack = back)
    Column {
        if (candidate == null && !state.busy) TextButton(onClick = pick) { Text(stringResource(Res.string.photo_editor_pick_again)) }
        ProfilePhotoEditorScreen(state.copy(consent = canApply), null, candidate?.asImageBitmap(), prepared?.bitmap?.asImageBitmap(),
            onChange = { state = it.copy(consent = true) }, onPick = pick,
            onPreview = {
                val image = candidate
                if (image != null && !state.busy) {
                    val crop = state.crop; val stamp = ++generation
                    state = state.copy(stage = PhotoEditorStage.Processing, error = null)
                    work = scope.launch {
                        try {
                            val result = AndroidProfilePhoto.crop(image, crop)
                            if (stamp == generation) { prepared = result; state = state.copy(stage = PhotoEditorStage.Review) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            if (stamp == generation) state = state.copy(stage = PhotoEditorStage.Crop,
                                error = (e as? PhotoProcessingException)?.reason ?: PhotoEditorError.ProcessFailed)
                        } catch (_: OutOfMemoryError) {
                            if (stamp == generation) state = state.copy(stage = PhotoEditorStage.Crop, error = PhotoEditorError.ProcessFailed)
                        }
                    }
                }
            },
            onApply = { if (canApply) prepared?.jpeg?.let(onReady) }, onDelete = {},
            onBack = back, onDiscard = { generation++; work?.cancel(); currentClose() })
    }
}

@Composable actual fun ProfilePhotoBytes(bytes: ByteArray) {
    // 디코딩은 화면 그리기를 막지 않도록 백그라운드에서 수행한다.
    // 키가 바뀌면 상태도 분리하여 이전 사용자의 이미지가 새 입력에 잠시 표시되지 않게 한다.
    key(bytes) {
        // 디코딩 중(아직 결과 없음)과 실패를 구분한다. 디코딩하는 동안 실패 문구를 잠깐 띄우지 않는다
        val decoded by produceState<Result<Bitmap?>?>(null) {
            value = Result.success(withContext(Dispatchers.Default) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2   // 사진 칸이 화면 폭만큼 커서 512로는 흐리다
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            })
        }
        decoded?.let { result ->
            result.getOrNull()?.let { ProfilePhotoImage(it.asImageBitmap()) }
                ?: Text(stringResource(Res.string.optional_photo_unavailable))
        }
    }
}
